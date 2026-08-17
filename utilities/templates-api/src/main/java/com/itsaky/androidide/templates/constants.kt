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
 * Pinned so a single harvested offline Maven repository can satisfy every generated project.
 * These are the versions CodeOnTheGo ships, i.e. a combination already proven to build on-device.
 * Changing any of them invalidates the offline bundle and requires re-harvesting it.
 */
const val ANDROID_GRADLE_PLUGIN_VERSION = "8.11.0"
const val GRADLE_DISTRIBUTION_VERSION = "8.14.3"
const val KOTLIN_VERSION = "1.9.22"

val TARGET_SDK_VERSION = Sdk.Tiramisu
val COMPILE_SDK_VERSION = Sdk.Tiramisu

const val JAVA_SOURCE_VERSION = "17"
const val JAVA_TARGET_VERSION = "17"
