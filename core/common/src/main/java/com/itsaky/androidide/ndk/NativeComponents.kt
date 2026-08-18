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

package com.itsaky.androidide.ndk

import android.content.Context
import com.itsaky.androidide.proot.ProotConfig
import java.io.File

/**
 * The NDK and CMake builds a native project needs.
 *
 * Google's own NDK ships host binaries for `linux-x86_64` only, so it cannot run on a device.
 * These come from HomuHomu833's rebuilds, which include a `prebuilt/linux-arm64` toolchain.
 *
 * @author Akash Yadav
 */
object NativeComponents {

  /** aarch64 glibc archives; the rootfs is Ubuntu, so `-linux-gnu` is the right variant. */
  private const val NDK_BASE = "https://github.com/HomuHomu833/android-ndk-custom/releases/download"
  private const val CMAKE_BASE = "https://github.com/HomuHomu833/cmake-custom/releases/download"

  /**
   * An NDK release.
   *
   * @property tag GitHub release tag.
   * @property archiveName File name within the release; the patch suffix does not always match the
   * tag (tag `r28` publishes `r28c`), so it is spelled out instead of derived.
   * @property sizeMb Download size, shown before the download starts.
   */
  enum class Ndk(val tag: String, val archiveName: String, val sizeMb: Int) {

    R26("r26", "android-ndk-r26d-aarch64-linux-gnu.tar.xz", 117),
    R27("r27", "android-ndk-r27d-aarch64-linux-gnu.tar.xz", 138),
    R28("r28", "android-ndk-r28c-aarch64-linux-gnu.tar.xz", 159),
    R29("r29", "android-ndk-r29-aarch64-linux-gnu.tar.xz", 176),
    ;

    /** Release name as users know it, e.g. `r28c`. */
    val release: String
      get() = archiveName.removePrefix("android-ndk-").substringBefore("-aarch64")

    val displayName: String
      get() = "NDK $release"

    val url: String
      get() = "$NDK_BASE/$tag/$archiveName"

    companion object {

      /** Newest stable release. r30 is excluded while it is still a beta. */
      @JvmStatic
      val default = R28

      @JvmStatic
      fun fromDisplayName(displayName: CharSequence) =
        entries.firstOrNull { it.displayName.contentEquals(displayName) } ?: default
    }
  }

  /**
   * A CMake release. Each archive also bundles `ninja`, so no separate download is needed.
   *
   * CMake 4 removed compatibility with `cmake_minimum_required(VERSION < 3.5)`, which breaks older
   * native projects. Only the generated template is guaranteed to work with these.
   */
  enum class CMake(val version: String, val sizeMb: Int) {

    V4_0_2("4.0.2", 9),
    V4_0_3("4.0.3", 9),
    V4_1_0("4.1.0", 9),
    V4_1_1("4.1.1", 9),
    V4_1_2("4.1.2", 9),
    ;

    val displayName: String
      get() = "CMake $version"

    val url: String
      get() = "$CMAKE_BASE/$version/cmake-aarch64-linux-gnu.tar.xz"

    companion object {

      @JvmStatic
      val default = V4_1_2

      @JvmStatic
      fun fromDisplayName(displayName: CharSequence) =
        entries.firstOrNull { it.displayName.contentEquals(displayName) } ?: default
    }
  }

  /**
   * Where AGP looks for NDKs: `$ANDROID_HOME/ndk/<Pkg.Revision>`. The directory name has to be the
   * revision from the archive's `source.properties` (e.g. `28.2.13676358`), not the release name —
   * AGP reads that file back and rejects a mismatch.
   */
  fun ndkRoot(context: Context): File = File(ProotConfig.sdkDir(context), "ndk")

  /** Where AGP looks for CMake: `$ANDROID_HOME/cmake/<version>/bin/cmake`. */
  fun cmakeRoot(context: Context): File = File(ProotConfig.sdkDir(context), "cmake")

  /** Installed NDK revisions, newest first. */
  fun installedNdks(context: Context): List<String> =
    ndkRoot(context).listFiles()
      ?.filter { it.isDirectory && File(it, "source.properties").isFile }
      ?.map { it.name }
      ?.sortedWith(revisionOrder.reversed())
      ?: emptyList()

  /** Installed CMake versions, newest first. */
  fun installedCMakes(context: Context): List<String> =
    cmakeRoot(context).listFiles()
      ?.filter { it.isDirectory && File(it, "bin/cmake").canExecute() }
      ?.map { it.name }
      ?.sortedWith(revisionOrder.reversed())
      ?: emptyList()

  /**
   * The revision a project should build against when it does not pin one: the highest installed.
   *
   * Returning the newest installed rather than a hardcoded version keeps generated projects
   * portable — the same project builds on a device with a different NDK.
   */
  fun preferredNdk(context: Context): String? = installedNdks(context).firstOrNull()

  fun preferredCMake(context: Context): String? = installedCMakes(context).firstOrNull()

  /** Whether at least one NDK is installed. */
  fun hasNdk(context: Context): Boolean = installedNdks(context).isNotEmpty()

  /** Whether at least one CMake is installed. */
  fun hasCMake(context: Context): Boolean = installedCMakes(context).isNotEmpty()

  /** Compares dotted version strings numerically, so `28.2.1` sorts above `9.9.9`. */
  private val revisionOrder = Comparator<String> { left, right ->
    val a = left.split('.', '-').mapNotNull { it.toIntOrNull() }
    val b = right.split('.', '-').mapNotNull { it.toIntOrNull() }
    for (i in 0 until maxOf(a.size, b.size)) {
      val diff = (a.getOrNull(i) ?: 0).compareTo(b.getOrNull(i) ?: 0)
      if (diff != 0) return@Comparator diff
    }
    0
  }
}
