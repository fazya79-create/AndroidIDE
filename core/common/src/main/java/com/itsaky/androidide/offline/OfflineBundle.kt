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
 * The bundle is a Maven repository harvested from a real resolution of the project template, so a
 * first build resolves entirely from disk instead of spending ~20 minutes on network round trips.
 * It only covers the pinned AGP/Gradle/Kotlin trio in `templates-api`; bumping any of those
 * requires regenerating the bundle via the `offline-bundle` workflow and raising [VERSION].
 */
object OfflineBundle {

  /** Release tag holding the assets. Bump together with the harvested contents. */
  const val VERSION = "offline-bundle-v2"

  private const val BASE_URL =
    "https://github.com/fazya79-create/AndroidIDE/releases/download/$VERSION"

  /**
   * An entry of the bundle.
   *
   * @property fileName Remote file name, also used for the on-disk staging file.
   * @property destination Where the payload ends up, relative to `filesDir`.
   */
  data class Entry(
    val fileName: String,
    val destination: String
  ) {

    val url: String
      get() = "$BASE_URL/$fileName"

    /**
     * Brotli-compressed payloads are streamed through a decompressor before unzipping. The inner
     * archive stores its entries uncompressed so the JARs stay mmap-friendly on device, which
     * makes the outer Brotli layer worth roughly a 45% saving.
     */
    val isBrotli: Boolean
      get() = fileName.endsWith(".br")
  }

  val entries = listOf(
    Entry(
      fileName = "localMvnRepository.zip.br",
      destination = "home/maven/localMvnRepository"
    )
  )

  /** Name of the checksum manifest published alongside the assets. */
  const val CHECKSUMS = "checksums.txt"

  val checksumsUrl: String
    get() = "$BASE_URL/$CHECKSUMS"

  /** Directory the harvested Maven repository is extracted to. */
  const val REPOSITORY_PATH = "home/maven/localMvnRepository"
}
