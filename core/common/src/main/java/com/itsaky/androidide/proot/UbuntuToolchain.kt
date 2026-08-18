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
import java.io.File

/**
 * Versions of the build toolchain that ships as prebuilt release assets.
 *
 * There is no installer here any more. `BundleInstaller` downloads `jdk.zip.br`,
 * `android-sdk.zip.br` and `gradle.zip.br` from the `toolchain-*` release tag and extracts them
 * into `<filesDir>/toolchains`, which `ProotConfig` binds at `/opt` inside the rootfs. These
 * constants exist so the app and the packaging workflow agree on what is installed.
 *
 * @author Akash Yadav
 */
object UbuntuToolchain {

  /** Must match `GRADLE_DISTRIBUTION_VERSION` in `templates-api`'s constants. */
  const val GRADLE_VERSION = "8.14.3"

  const val SDK_RELEASE = "36.0.2"
  const val BUILD_TOOLS = "36.1.0"

  /**
   * Must equal `COMPILE_SDK_VERSION` in `templates-api`'s constants (Tiramisu = 33). Installing a
   * different level makes every generated project fail with
   * "Failed to find target with hash string 'android-NN'".
   */
  const val DEFAULT_PLATFORM = 33

  /** Whether the extracted toolchain has everything a build needs. */
  fun isReady(context: Context, platform: Int = DEFAULT_PLATFORM): Boolean =
    File(ProotConfig.jdkDir(context), "bin/java").isFile &&
        File(ProotConfig.gradleDir(context), "bin/gradle").isFile &&
        File(ProotConfig.sdkDir(context), "platforms/android-$platform").isDirectory &&
        File(ProotConfig.sdkDir(context), "build-tools/$BUILD_TOOLS").isDirectory
}
