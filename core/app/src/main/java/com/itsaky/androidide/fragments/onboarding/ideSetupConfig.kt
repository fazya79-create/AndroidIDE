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

package com.itsaky.androidide.fragments.onboarding

import com.itsaky.androidide.proot.UbuntuToolchain

/**
 * Android SDK platforms offered during setup.
 *
 * These are API levels installed through `sdkmanager`, not the `androidide-tools` release names the
 * archived setup script used. The platform generated projects compile against is installed
 * regardless of what is picked here, so choosing a newer level only adds to the installation.
 *
 * @author Akash Yadav
 */
enum class SdkVersion(val api: Int, val label: String) {

  API_33(33, "Android 13"),
  API_34(34, "Android 14"),
  API_35(35, "Android 15"),
  API_36(36, "Android 16"),
  ;

  val displayName = "API $api ($label)"

  companion object {

    /** The level generated projects compile against, preselected in the dropdown. */
    @JvmStatic
    val default = entries.first { it.api == UbuntuToolchain.DEFAULT_PLATFORM }

    @JvmStatic
    fun fromDisplayName(displayName: CharSequence) =
      entries.firstOrNull { it.displayName.contentEquals(displayName) } ?: default

    @JvmStatic
    fun fromApi(api: Int) = entries.firstOrNull { it.api == api } ?: default
  }
}

/**
 * JDK versions offered during setup. Each maps to an Eclipse Temurin aarch64 build that the
 * installer downloads; the selection is honoured rather than assumed.
 *
 * @author Akash Yadav
 */
enum class JdkVersion(val version: String) {

  JDK_17("17"),
  JDK_21("21"),
  ;

  val displayName = "JDK $version"

  companion object {

    /**
     * JDK 17 is the default: it is the minimum AGP requires, and the version the project templates
     * target. Gradle 8.14 also runs on 21, so either choice builds.
     */
    @JvmStatic
    val default = JDK_17

    @JvmStatic
    fun fromDisplayName(displayName: CharSequence) =
      entries.firstOrNull { it.displayName.contentEquals(displayName) } ?: default

    @JvmStatic
    fun fromVersion(version: CharSequence) =
      entries.firstOrNull { it.version.contentEquals(version) } ?: default
  }
}
