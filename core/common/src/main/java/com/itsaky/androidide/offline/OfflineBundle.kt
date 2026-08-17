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

package com.itsaky.androidide.offline

/**
 * Coordinates of the prebuilt offline dependency bundle.
 *
 * The bundle is a Maven repository harvested from a real resolution of the project template, so
 * a first build resolves entirely from disk instead of spending ~20 minutes on network round trips.
 * It only covers the pinned AGP/Gradle/Kotlin trio in `templates-api`; bumping any of those
 * requires regenerating the bundle via the `offline-bundle` workflow and raising [VERSION].
 */
object OfflineBundle {

  /** Release tag holding the assets. Bump together with the harvested contents. */
  const val VERSION = "offline-bundle-v1"

  private const val BASE_URL =
    "https://github.com/fazya79-create/AndroidIDE/releases/download/$VERSION"

  const val REPOSITORY_ARCHIVE = "localMvnRepository.zip"

  /**
   * An entry of the bundle.
   *
   * @property fileName Remote file name, also used for the on-disk staging file.
   * @property label Shown to the user while downloading.
   * @property destination Where the payload ends up, relative to `filesDir`.
   * @property isArchive Whether the payload is a zip that must be extracted.
   */
  data class Entry(
    val fileName: String,
    val label: String,
    val destination: String,
    val isArchive: Boolean
  ) {

    val url: String
      get() = "$BASE_URL/$fileName"
  }

  val entries = listOf(
    Entry(
      fileName = REPOSITORY_ARCHIVE,
      label = "Dependency repository",
      destination = "home/maven/localMvnRepository",
      isArchive = true
    )
  )

  /** Name of the checksum manifest published alongside the assets. */
  const val CHECKSUMS = "checksums.txt"

  val checksumsUrl: String
    get() = "$BASE_URL/$CHECKSUMS"
}
