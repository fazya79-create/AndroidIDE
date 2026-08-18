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

package com.itsaky.androidide.utils

import android.content.Context
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.ndk.NativeComponents
import com.itsaky.androidide.ndk.NativeInstaller
import com.itsaky.androidide.ndk.NativePhase
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Installs the NDK and CMake on demand when a project turns out to need them, reporting into the
 * build output so the user never has to leave the editor.
 *
 * @author Akash Yadav
 */
object NativeBuildSetup {

  private val log = LoggerFactory.getLogger(NativeBuildSetup::class.java)

  /** What a project needs but does not have. */
  data class Missing(val ndk: NativeComponents.Ndk?, val cmake: NativeComponents.CMake?) {

    val isEmpty: Boolean
      get() = ndk == null && cmake == null

    val totalMb: Int
      get() = (ndk?.sizeMb ?: 0) + (cmake?.sizeMb ?: 0)

    /** One line per component, for the confirmation dialog. */
    fun describe(): String = buildList {
      ndk?.let { add("${it.displayName}  ${it.sizeMb} MB") }
      cmake?.let { add("${it.displayName}  ${it.sizeMb} MB") }
    }.joinToString("\n")
  }

  /**
   * What the project at [projectDir] is missing, or an empty result when it has no native sources
   * at all.
   *
   * Detection is based on the build scripts rather than on `externalNativeBuild` parsing: any
   * `CMakeLists.txt` means CMake is needed, and an `Android.mk` means only the NDK is.
   */
  fun findMissing(context: Context, projectDir: File): Missing {
    val scripts = nativeScripts(projectDir)
    if (scripts.isEmpty()) {
      return Missing(null, null)
    }

    val needsCMake = scripts.any { it.name == "CMakeLists.txt" }

    val ndk = if (NativeComponents.hasNdk(context)) null else NativeComponents.Ndk.default
    val cmake = if (!needsCMake || NativeComponents.hasCMake(context)) {
      null
    } else {
      NativeComponents.CMake.default
    }

    return Missing(ndk, cmake)
  }

  /**
   * Installs [missing], forwarding progress as single-line build output.
   *
   * @param log Receives the lines to show in the build output pane.
   * @return true when everything installed successfully.
   */
  suspend fun install(
    context: Context,
    missing: Missing,
    log: (String) -> Unit
  ): Boolean {
    if (missing.isEmpty) return true

    log(context.getString(R.string.msg_native_setting_up))

    val installer = NativeInstaller(context)
    var lastReported = -1

    val ok = installer.install(missing.ndk, missing.cmake) { phase ->
      when (phase) {
        is NativePhase.Downloading -> {
          // Only whole percents reach the pane; a line per chunk would drown the log.
          if (phase.percent != lastReported) {
            lastReported = phase.percent
            log(
              "  ${phase.component}  ${phase.percent}%  " +
                  "${phase.receivedMb.toInt()}/${phase.totalMb.toInt()} MB"
            )
          }
        }

        is NativePhase.Extracting -> {
          lastReported = -1
          log("  ${phase.component}: extracting")
        }

        is NativePhase.Installed -> log("  installed ${phase.component} (${phase.version})")

        is NativePhase.Failed -> log("  failed: ${phase.message}")
      }
    }

    if (ok) {
      log(context.getString(R.string.msg_native_ready))
    }
    return ok
  }

  /**
   * Native build scripts in the project, searched only a few levels deep.
   *
   * Walking the whole tree would mean descending into `build/` output and every `.git` object on a
   * large project, which is slow on a phone; native scripts live in a module's `src/main/cpp`.
   */
  private fun nativeScripts(projectDir: File): List<File> {
    val modules = projectDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
      ?: return emptyList()

    return modules.flatMap { module ->
      val cpp = File(module, "src/main/cpp")
      if (!cpp.isDirectory) {
        emptyList()
      } else {
        cpp.listFiles()
          ?.filter { it.isFile && (it.name == "CMakeLists.txt" || it.name == "Android.mk") }
          ?: emptyList()
      }
    }
  }
}
