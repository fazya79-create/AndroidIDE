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
 * @author Akash Yadav
 */

/*
 * Versions used by generated projects.
 *
 * AGP is capped by `agp-tooling` in gradle/libs.versions.toml: the IDE's tooling API is compiled
 * against that version's model interfaces, and a newer AGP calls model methods the older interface
 * lacks — project sync then fails with
 * "Unsupported method: ModelBuilderParameter.getAdditionalArtifactsInModel()".
 * Raising AGP past 8.5.x therefore means raising `agp-tooling` in the same change.
 *
 * The Compose compiler extension is tied to one exact Kotlin release (Google's compatibility map),
 * so KOTLIN_VERSION and `compose_kotlinCompilerExtensionVersion` must move together.
 *
 * AGP 8.5 requires JDK 17 and Gradle 8.7+.
 */
const val ANDROID_GRADLE_PLUGIN_VERSION = "8.5.2"
const val GRADLE_DISTRIBUTION_VERSION = "8.14.3"
const val KOTLIN_VERSION = "1.9.25"

/**
 * The SDK platform generated projects compile against. `UbuntuToolchain.DEFAULT_PLATFORM` installs
 * exactly this API level — a mismatch surfaces as
 * "Failed to find target with hash string 'android-NN'".
 */
val TARGET_SDK_VERSION = Sdk.UpsideDownCake
val COMPILE_SDK_VERSION = Sdk.UpsideDownCake

const val JAVA_SOURCE_VERSION = "17"
const val JAVA_TARGET_VERSION = "17"
