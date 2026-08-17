/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.proot

import android.content.Context
import android.os.Process
import com.itsaky.androidide.utils.Environment
import java.io.File

/**
 * Configuration for running commands inside the bundled Ubuntu rootfs via proot.
 *
 * proot is a ptrace-based userspace path translator, NOT a security boundary. The real
 * boundary is the Android application sandbox (UID isolation + SELinux).
 */
object ProotConfig {

  const val ROOTFS_NAME = "ubuntu"
  const val FAKE_KERNEL_VERSION = "6.2.1-PRoot-Distro"
  const val INSTALL_MARKER = ".installed"
  const val ROOTFS_VERSION = "noble-24.04.4"

  private const val TARBALL_URL_ARM64 =
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
  private const val TARBALL_SHA256_ARM64 =
    "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2"
  private const val TARBALL_URL_ARM =
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-armhf.tar.gz"
  private const val TARBALL_SHA256_ARM =
    "991520b47f6586f38a78505cf016e300b6191bb8ff86a0723481ec23a37ab7f4"

  const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

  /** Guest mount points, declared in [Environment] so `core:common` can use them too. */
  const val GUEST_OPT = Environment.GUEST_OPT
  const val GUEST_SDK_ROOT = Environment.GUEST_SDK_ROOT
  const val GUEST_JAVA_HOME = Environment.GUEST_JAVA_HOME
  const val GUEST_GRADLE_HOME = Environment.GUEST_GRADLE_HOME

  private const val PRIMARY_STORAGE = "/storage/emulated/0"

  private val storageBinds: List<String>
    get() = buildList {
      if (File(PRIMARY_STORAGE).isDirectory) {
        add("$PRIMARY_STORAGE:$PRIMARY_STORAGE")
        add("$PRIMARY_STORAGE:/sdcard")
      }
      if (File("/storage").isDirectory) add("/storage:/storage")
    }

  fun rootfsDir(context: Context): File = File(context.filesDir, ROOTFS_NAME)

  /**
   * Host directory holding the JDK, Android SDK and Gradle. Kept OUTSIDE the rootfs so
   * reinstalling Ubuntu never wipes multi-hundred-megabyte downloads.
   */
  fun toolchainRoot(context: Context): File =
    File(context.filesDir, "toolchains").apply { mkdirs() }

  fun sdkDir(context: Context): File = File(toolchainRoot(context), "android-sdk")

  fun jdkDir(context: Context): File = File(toolchainRoot(context), "jdk")

  fun gradleDir(context: Context): File = File(toolchainRoot(context), "gradle")

  fun isInstalled(context: Context): Boolean {
    val rootfs = rootfsDir(context)
    return File(rootfs, INSTALL_MARKER).readTextOrNull() == ROOTFS_VERSION &&
      File(rootfs, "etc").isDirectory &&
      File(rootfs, "usr/bin/bash").exists()
  }

  private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

  fun prootBinary(context: Context): String =
    File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath

  fun loaderBinary(context: Context): String =
    File(context.applicationInfo.nativeLibraryDir, "libloader.so").absolutePath

  fun isAvailable(context: Context): Boolean = File(prootBinary(context)).exists()

  private fun isArm64(context: Context): Boolean =
    context.applicationInfo.nativeLibraryDir.contains("64")

  fun tarballUrl(context: Context): String =
    if (isArm64(context)) TARBALL_URL_ARM64 else TARBALL_URL_ARM

  fun tarballSha256(context: Context): String =
    if (isArm64(context)) TARBALL_SHA256_ARM64 else TARBALL_SHA256_ARM

  fun tarballFile(context: Context): File = File(context.cacheDir, "ubuntu-rootfs.tar.gz")

  fun tmpDir(context: Context): File = File(context.cacheDir, "proot-tmp").apply {
    mkdirs()
    setReadable(true, false)
    setWritable(true, false)
    setExecutable(true, false)
  }

  fun prepareMounts(context: Context) {
    val rootfs = rootfsDir(context)
    runCatching {
      File(rootfs, "storage/emulated/0").mkdirs()
      File(rootfs, "sdcard").mkdirs()
      File(rootfs, GUEST_OPT.trimStart('/')).mkdirs()
      toolchainRoot(context)
    }
  }

  private fun toolchainBind(context: Context): String {
    val host = toolchainRoot(context)
    return "${host.absolutePath}:$GUEST_OPT"
  }

  private fun baseArgs(context: Context, guestCwd: String): MutableList<String> {
    val rootfs = rootfsDir(context).absolutePath
    return mutableListOf(
      prootBinary(context),
      "-L",
      "--kernel-release=$FAKE_KERNEL_VERSION",
      "--link2symlink",
      "--sysvipc",
      "--kill-on-exit",
      "--rootfs=$rootfs",
      "--change-id=0:0",
      "--cwd=$guestCwd",
      "--bind=/dev",
      "--bind=/dev/urandom:/dev/random",
      "--bind=/proc",
      "--bind=/proc/self/fd:/dev/fd",
      "--bind=/proc/self/fd/0:/dev/stdin",
      "--bind=/proc/self/fd/1:/dev/stdout",
      "--bind=/proc/self/fd/2:/dev/stderr",
      "--bind=/sys",
      "--bind=$rootfs/proc/.loadavg:/proc/loadavg",
      "--bind=$rootfs/proc/.stat:/proc/stat",
      "--bind=$rootfs/proc/.uptime:/proc/uptime",
      "--bind=$rootfs/proc/.version:/proc/version",
      "--bind=$rootfs/proc/.vmstat:/proc/vmstat",
      "--bind=$rootfs/proc/.sysctl_entry_cap_last_cap:/proc/sys/kernel/cap_last_cap"
    )
  }

  private fun guestEnv(extraPath: List<String>): List<String> {
    val path = (extraPath + listOf("$GUEST_JAVA_HOME/bin", "$GUEST_GRADLE_HOME/bin") +
      GUEST_PATH.split(':')).joinToString(":")
    return listOf(
      "/usr/bin/env",
      "-i",
      "HOME=/root",
      "USER=root",
      "LOGNAME=root",
      "LANG=C.UTF-8",
      "PATH=$path",
      "JAVA_HOME=$GUEST_JAVA_HOME",
      "ANDROID_HOME=$GUEST_SDK_ROOT",
      "ANDROID_SDK_ROOT=$GUEST_SDK_ROOT",
      "GRADLE_USER_HOME=/root/.gradle",
      "TMPDIR=/tmp"
    )
  }

  /**
   * Interactive login shell, optionally running [bootCommand] first.
   *
   * When [keepAlive] is `true` the shell stays open after [bootCommand] finishes. Pass `false`
   * for one-shot sessions such as the toolchain installer, so the session exits and the caller
   * receives the exit status.
   */
  fun shellArgs(
    context: Context,
    guestCwd: String = "/root",
    bootCommand: String? = null,
    keepAlive: Boolean = true
  ): Array<String> {
    val args = baseArgs(context, guestCwd)
    storageBinds.forEach { args += "--bind=$it" }
    args += "--bind=${toolchainBind(context)}"
    args += guestEnv(emptyList())
    args += "TERM=xterm-256color"
    args += when {
      bootCommand == null -> listOf("/usr/bin/bash", "-l")
      keepAlive -> listOf("/usr/bin/bash", "-lc", "$bootCommand; exec /usr/bin/bash -l")
      else -> listOf("/usr/bin/bash", "-lc", bootCommand)
    }
    return args.toTypedArray()
  }

  /**
   * Guest path of the harvested offline Maven repository, exported so the generated init script
   * can register it. `filesDir` is bound at an identical guest path, so the host path resolves
   * unchanged inside the rootfs.
   */
  private fun offlineRepoEnv(context: Context): List<String> {
    val repo = File(context.filesDir, "home/maven/localMvnRepository")
    return if (repo.isDirectory) listOf("ANDROIDIDE_OFFLINE_REPO=${repo.absolutePath}")
    else emptyList()
  }

  /** Run [command] non-interactively; used to launch the tooling server and helpers. */
  fun exec(
    context: Context,
    command: List<String>,
    guestCwd: String = "/root",
    binds: List<String> = emptyList(),
    extraEnv: List<String> = emptyList()
  ): List<String> {
    val args = baseArgs(context, guestCwd)
    storageBinds.forEach { args += "--bind=$it" }
    args += "--bind=${toolchainBind(context)}"
    binds.forEach { args += "--bind=$it" }
    args += guestEnv(emptyList())
    args += "TERM=dumb"
    args += offlineRepoEnv(context)
    args += extraEnv
    args += command
    return args
  }

  /**
   * Wraps a JVM invocation so it runs inside the rootfs. The app's `filesDir` is bound at an
   * identical guest path, so absolute paths to JARs and project caches resolve unchanged.
   */
  fun javaArgs(
    context: Context,
    jvmArgs: List<String>,
    guestCwd: String = "/root",
    binds: List<String> = emptyList()
  ): List<String> {
    val files = context.filesDir.absolutePath
    return exec(
      context = context,
      command = listOf("$GUEST_JAVA_HOME/bin/java") + jvmArgs,
      guestCwd = guestCwd,
      binds = listOf("$files:$files") + binds
    )
  }

  /** Run a bash [script] non-interactively. */
  fun execScript(
    context: Context,
    script: String,
    guestCwd: String = "/root",
    binds: List<String> = emptyList()
  ): List<String> = exec(
    context = context,
    command = listOf("/usr/bin/bash", "-lc", script),
    guestCwd = guestCwd,
    binds = binds
  )

  /**
   * Host-side environment for the proot process. proot has the Termux tmp path compiled in as
   * its default, which this app cannot write to, so PROOT_TMP_DIR and TMPDIR must both be
   * overridden or proot fails with "can't create temporary directory: Permission denied".
   */
  fun prootEnvMap(context: Context): Map<String, String> {
    val tmp = tmpDir(context)
    return mapOf(
      "TERM" to "xterm-256color",
      "HOME" to context.filesDir.absolutePath,
      "TMPDIR" to tmp.absolutePath,
      "PROOT_LOADER" to loaderBinary(context),
      "PROOT_TMP_DIR" to tmp.absolutePath,
      "PROOT_NO_SECCOMP" to "1"
    )
  }

  fun writeShellProfile(context: Context) {
    val rootfs = rootfsDir(context)
    if (!File(rootfs, "etc").isDirectory) return
    runCatching {
      File(rootfs, "etc/profile.d").mkdirs()
      File(rootfs, "etc/profile.d/00-androidide.sh").writeText(
        """
        export JAVA_HOME=$GUEST_JAVA_HOME
        export ANDROID_HOME=$GUEST_SDK_ROOT
        export ANDROID_SDK_ROOT=$GUEST_SDK_ROOT
        export GRADLE_USER_HOME=/root/.gradle
        export PATH=$GUEST_JAVA_HOME/bin:$GUEST_GRADLE_HOME/bin:$GUEST_SDK_ROOT/cmdline-tools/bin:${'$'}PATH
        export LANG=C.UTF-8
        export TMPDIR=/tmp
        export DEBIAN_FRONTEND=noninteractive
        """.trimIndent() + "\n"
      )
      val bashrc = File(rootfs, "root/.bashrc")
      bashrc.parentFile?.mkdirs()
      bashrc.writeText(
        """
        export PS1='\[\e[1;92m\]\u@androidide\[\e[0m\]:\[\e[1;36m\]\w\[\e[0m\]\${'$'} '
        export JAVA_HOME=$GUEST_JAVA_HOME
        export ANDROID_HOME=$GUEST_SDK_ROOT
        export ANDROID_SDK_ROOT=$GUEST_SDK_ROOT
        export GRADLE_USER_HOME=/root/.gradle
        export PATH=$GUEST_JAVA_HOME/bin:$GUEST_GRADLE_HOME/bin:$GUEST_SDK_ROOT/cmdline-tools/bin:$GUEST_PATH
        export LANG=C.UTF-8
        export TMPDIR=/tmp
        export DEBIAN_FRONTEND=noninteractive
        alias ll='ls -alF'
        """.trimIndent() + "\n"
      )
      File(rootfs, "root/.profile").writeText(
        "[ -n \"\$BASH_VERSION\" ] && [ -f ~/.bashrc ] && . ~/.bashrc\n"
      )
    }
  }

  fun registerAndroidIds(context: Context) {
    val rootfs = rootfsDir(context)
    val uid = Process.myUid()
    val userName = "aid_app_$uid"
    val passwd = File(rootfs, "etc/passwd")
    val shadow = File(rootfs, "etc/shadow")
    val group = File(rootfs, "etc/group")
    val gshadow = File(rootfs, "etc/gshadow")
    runCatching {
      if (passwd.exists() && !passwd.readText().contains(userName)) {
        passwd.appendText("$userName:x:$uid:$uid:AndroidIDE:/:/usr/sbin/nologin\n")
      }
      if (shadow.exists() && !shadow.readText().contains(userName)) {
        shadow.appendText("$userName:*:18446:0:99999:7:::\n")
      }
    }
    val existing = runCatching { group.readText() }.getOrDefault("")
    val lines = StringBuilder()
    val shadowLines = StringBuilder()
    for (gid in supplementaryGids()) {
      val name = "aid_$gid"
      if (existing.contains(":$gid:") || existing.contains("$name:")) continue
      lines.append("$name:x:$gid:root,$userName\n")
      shadowLines.append("$name:*::root,$userName\n")
    }
    runCatching {
      if (group.exists() && lines.isNotEmpty()) group.appendText(lines.toString())
      if (gshadow.exists() && shadowLines.isNotEmpty()) {
        gshadow.appendText(shadowLines.toString())
      }
    }
  }

  private fun supplementaryGids(): List<Int> {
    val gids = linkedSetOf(Process.myUid())
    runCatching {
      File("/proc/self/status").forEachLine { line ->
        when {
          line.startsWith("Groups:") -> line.removePrefix("Groups:")
          line.startsWith("Gid:") -> line.removePrefix("Gid:")
          else -> null
        }?.trim()
          ?.split(Regex("\\s+"))
          ?.mapNotNull { it.toIntOrNull() }
          ?.forEach { gids.add(it) }
      }
    }
    return gids.toList()
  }
}
