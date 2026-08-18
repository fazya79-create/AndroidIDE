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
 * Coordinates of the prebuilt release assets.
 *
 * Everything a build needs is downloaded and extracted, so nothing is installed through a terminal
 * session: the JDK, Android SDK and Gradle distribution replace the shell installer, and the
 * harvested Maven repository replaces every dependency download.
 *
 * The Ubuntu rootfs is deliberately absent. It is a live Linux environment the user runs `apt` and
 * npm-installed CLIs inside, so freezing it into an asset would defeat its purpose.
 *
 * These assets only cover the pinned AGP/Gradle/Kotlin trio in `templates-api`; bumping any of
 * those requires regenerating the bundle and raising [VERSION].
 */
object OfflineBundle {

  /** Release tag holding the dependency repository. Bump together with the harvested contents. */
  const val VERSION = "offline-bundle-v3"

  /** Release tag holding the JDK, Android SDK and Gradle archives. */
  const val TOOLCHAIN_VERSION = "toolchain-v3"

  private const val REPO_BASE =
    "https://github.com/fazya79-create/AndroidIDE/releases/download"

  /**
   * An entry of the bundle.
   *
   * @property fileName Remote file name, also used for the on-disk staging file.
   * @property destination Where the payload ends up, relative to `filesDir`.
   * @property tag Release tag the file is published under.
   * @property sizeMb Approximate download size, shown before the download starts.
   */
  data class Entry(
    val fileName: String,
    val destination: String,
    val tag: String,
    val sizeMb: Int
  ) {

    val url: String
      get() = "$REPO_BASE/$tag/$fileName"

    /**
     * Brotli-compressed payloads are streamed through a decompressor before unzipping. The inner
     * archive stores its entries uncompressed so the JARs stay mmap-friendly on device.
     */
    val isBrotli: Boolean
      get() = fileName.endsWith(".br")

    val checksumsUrl: String
      get() = "$REPO_BASE/$tag/$CHECKSUMS"
  }

  /** Directory the harvested Maven repository is extracted to. */
  const val REPOSITORY_PATH = "home/maven/localMvnRepository"

  /**
   * Parent of the extracted toolchain components. This is `ProotConfig.toolchainRoot`, which is
   * already bound into the rootfs at `/opt`, so the extracted JDK/SDK/Gradle appear exactly where
   * the guest expects them without any extra plumbing.
   */
  const val TOOLCHAIN_PATH = "toolchains"

  val entries = listOf(
    Entry(
      fileName = "localMvnRepository.zip.br",
      destination = REPOSITORY_PATH,
      tag = VERSION,
      sizeMb = 315
    ),
    Entry(
      fileName = "jdk.zip.br",
      destination = "$TOOLCHAIN_PATH/jdk",
      tag = TOOLCHAIN_VERSION,
      sizeMb = 155
    ),
    Entry(
      fileName = "android-sdk.zip.br",
      destination = "$TOOLCHAIN_PATH/android-sdk",
      tag = TOOLCHAIN_VERSION,
      sizeMb = 70
    ),
    Entry(
      fileName = "gradle.zip.br",
      destination = "$TOOLCHAIN_PATH/gradle",
      tag = TOOLCHAIN_VERSION,
      sizeMb = 125
    )
  )

  /** Name of the checksum manifest published alongside the assets. */
  const val CHECKSUMS = "checksums.txt"

  /** Total download size, for the pre-download prompt. */
  val totalSizeMb: Int
    get() = entries.sumOf { it.sizeMb }
}
