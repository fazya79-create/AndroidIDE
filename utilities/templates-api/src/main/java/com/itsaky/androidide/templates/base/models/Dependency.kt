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

package com.itsaky.androidide.templates.base.models

import com.itsaky.androidide.templates.base.models.DependencyConfiguration.DebugImplementation

data class Dependency(val configuration: DependencyConfiguration,
                      val group: String, val artifact: String,
                      val version: String?
) {

  fun value(): String {
    return """
      ${configuration.configName}("${group}:${artifact}${optionalVersion()}")
    """.trimIndent()
  }

  fun platformValue(): String {
    return """
      ${configuration.configName}(platform("${group}:${artifact}${optionalVersion()}"))
    """.trimIndent()
  }

  private fun optionalVersion() = version?.let { ":${it}" } ?: ""

  object AndroidX {

    /*
     * Pinned to the last releases that still build against compileSdk 34. Newer majors
     * (lifecycle 2.9+, navigation 2.9+, core-ktx 1.15+, appcompat 1.7.1+) are compiled against
     * SDK 35/36 and make AGP fail with "requires a higher compileSdk", so they can only move
     * together with COMPILE_SDK_VERSION.
     */
    private const val lifecycleVersion = "2.8.7"

    private const val navigationVersion = "2.8.9"

    @JvmStatic
    val AppCompat = parseDependency("androidx.appcompat:appcompat:1.7.0")

    @JvmStatic
    val ConstraintLayout =
      parseDependency("androidx.constraintlayout:constraintlayout:2.2.0")

    @JvmStatic
    val LifeCycle_LiveData = parseDependency(
      "androidx.lifecycle:lifecycle-livedata:${lifecycleVersion}")

    @JvmStatic
    val LifeCycle_LiveData_Ktx = parseDependency(
      "androidx.lifecycle:lifecycle-livedata-ktx:${lifecycleVersion}")

    @JvmStatic
    val LifeCycle_ViewModel = parseDependency(
      "androidx.lifecycle:lifecycle-viewmodel:${lifecycleVersion}")

    @JvmStatic
    val LifeCycle_ViewModel_Ktx = parseDependency(
      "androidx.lifecycle:lifecycle-viewmodel-ktx:${lifecycleVersion}")

    @JvmStatic
    val Navigation_Fragment = parseDependency(
      "androidx.navigation:navigation-fragment:${navigationVersion}")

    @JvmStatic
    val Navigation_Ui =
      parseDependency("androidx.navigation:navigation-ui:${navigationVersion}")

    @JvmStatic
    val Navigation_Fragment_Ktx = parseDependency(
      "androidx.navigation:navigation-fragment-ktx:${navigationVersion}")

    @JvmStatic
    val Navigation_Ui_Ktx = parseDependency(
      "androidx.navigation:navigation-ui-ktx:${navigationVersion}")

    object Compose {

      @JvmStatic
      val Core_Ktx = parseDependency("androidx.core:core-ktx:1.13.1")

      @JvmStatic
      val LifeCycle_Runtime_Ktx = parseDependency(
        "androidx.lifecycle:lifecycle-runtime-ktx:${lifecycleVersion}")

      @JvmStatic
      val Activity = parseDependency("androidx.activity:activity-compose:1.9.3")

      @JvmStatic
      val BOM = parseDependency("androidx.compose:compose-bom:2024.06.00",
        isPlatform = true)

      @JvmStatic
      val UI = parseDependency("androidx.compose.ui:ui")

      @JvmStatic
      val UI_Graphics = parseDependency("androidx.compose.ui:ui-graphics")

      @JvmStatic
      val UI_Tooling_Preview =
        parseDependency("androidx.compose.ui:ui-tooling-preview")

      @JvmStatic
      val Material3 = parseDependency("androidx.compose.material3:material3")

      @JvmStatic
      val UI_Tooling = parseDependency("androidx.compose.ui:ui-tooling",
        configuration = DebugImplementation)

      @JvmStatic
      val UI_Test_Manifest =
        parseDependency("androidx.compose.ui:ui-test-manifest",
          configuration = DebugImplementation)
    }
  }

  object Google {

    @JvmStatic
    val Material = parseDependency("com.google.android.material:material:1.12.0")
  }
}