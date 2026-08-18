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

package com.itsaky.androidide.templates

/**
 * How a module's C/C++ sources are built.
 *
 * Both use the NDK — it holds the compiler and the Android sysroot. They differ in the build script
 * AGP invokes, which is why CMake needs an extra download and `ndk-build` does not.
 *
 * @property displayName Shown in the project wizard.
 * @property requiresCMake Whether the CMake package has to be installed as well.
 * @author Akash Yadav
 */
enum class NativeBuildSystem(val displayName: String, val requiresCMake: Boolean) {

  /** `CMakeLists.txt`, driven by the CMake package (which also bundles Ninja). */
  CMake("CMake", true),

  /** `Android.mk` plus `Application.mk`, driven by the NDK's own `ndk-build`. */
  NdkBuild("ndk-build", false),
  ;

  companion object {

    /** CMake is the default: it is what Android Studio generates and what most projects use. */
    @JvmStatic
    val default = CMake
  }
}
