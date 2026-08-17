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

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/** Progress of the offline bundle installation. */
sealed class BundlePhase {
  data object Idle : BundlePhase()
  data class Downloading(
    val label: String,
    val percent: Int,
    val receivedMb: Double,
    val totalMb: Double
  ) : BundlePhase()

  data class Extracting(val label: String, val entry: String) : BundlePhase()
  data object Done : BundlePhase()
  data class Failed(val message: String) : BundlePhase()
}

class BundleCancelledException : Exception("Download cancelled")

/**
 * Downloads and installs the prebuilt offline dependency bundle.
 *
 * Downloads resume: the payload is streamed into a `.part` file and a subsequent attempt sends
 * `Range: bytes=<size>-`, so a dropped connection continues instead of starting over. GitHub's
 * release CDN answers ranged requests with `206 Partial Content`.
 */
class BundleInstaller(private val context: Context) {

  private val cancelled = AtomicBoolean(false)

  fun cancel() {
    cancelled.set(true)
  }

  companion object {

    private val log = LoggerFactory.getLogger(BundleInstaller::class.java)
    private const val BUFFER_SIZE = 64 * 1024
    private const val MARKER_NAME = ".installed"

    /** Whether every bundle entry is present for the current [OfflineBundle.VERSION]. */
    fun isInstalled(context: Context): Boolean = OfflineBundle.entries.all { entry ->
      val dest = File(context.filesDir, entry.destination)
      marker(dest).takeIf { it.isFile }?.readText()?.trim() == OfflineBundle.VERSION
    }

    private fun marker(destination: File) = File(destination, MARKER_NAME)

    fun repositoryDir(context: Context): File =
      File(context.filesDir, OfflineBundle.entries.first().destination)
  }

  suspend fun install(onProgress: (BundlePhase) -> Unit): Boolean = withContext(Dispatchers.IO) {
    try {
      val checksums = fetchChecksums()
      for (entry in OfflineBundle.entries) {
        if (isEntryInstalled(entry)) continue
        val staged = download(entry, checksums[entry.fileName], onProgress)
        extract(entry, staged, onProgress)
        staged.delete()
      }
      onProgress(BundlePhase.Done)
      true
    } catch (e: BundleCancelledException) {
      onProgress(BundlePhase.Failed(e.message ?: "Cancelled"))
      false
    } catch (e: Exception) {
      log.error("Offline bundle installation failed", e)
      onProgress(BundlePhase.Failed(e.message ?: "Installation failed"))
      false
    }
  }

  private fun isEntryInstalled(entry: OfflineBundle.Entry): Boolean {
    val dest = File(context.filesDir, entry.destination)
    return marker(dest).takeIf { it.isFile }?.readText()?.trim() == OfflineBundle.VERSION
  }

  /**
   * Reads the published `checksums.txt` (`<sha256>  <filename>` per line). A missing manifest is
   * not fatal — verification is skipped rather than blocking the install.
   */
  private fun fetchChecksums(): Map<String, String> = runCatching {
    openConnection(URL(OfflineBundle.checksumsUrl), rangeFrom = 0L).use { input ->
      input.bufferedReader().lineSequence().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size >= 2) parts[1].substringAfterLast('/') to parts[0].lowercase() else null
      }.toMap()
    }
  }.getOrElse {
    log.warn("Could not fetch bundle checksums, skipping verification")
    emptyMap()
  }

  private fun download(
    entry: OfflineBundle.Entry,
    expectedSha256: String?,
    onProgress: (BundlePhase) -> Unit
  ): File {
    val staging = File(context.cacheDir, "offline-bundle").apply { mkdirs() }
    val target = File(staging, entry.fileName)
    val part = File(staging, "${entry.fileName}.part")

    if (target.isFile && (expectedSha256 == null || sha256(target) == expectedSha256)) {
      return target
    }

    var attempt = 0
    while (true) {
      attempt++
      val existing = if (part.isFile) part.length() else 0L
      val connection = openRangedConnection(URL(entry.url), existing)
      val resumed = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
      val remaining = connection.contentLengthLong
      val total = if (resumed && remaining > 0) existing + remaining else remaining
      val startedAt = if (resumed) existing else 0L

      if (!resumed && existing > 0) {
        log.info("Server ignored the range request, restarting {}", entry.fileName)
        part.delete()
      }

      try {
        FileOutputStream(part, resumed).use { out ->
          connection.inputStream.use { input ->
            copyReportingProgress(input, out, entry.label, startedAt, total, onProgress)
          }
        }
      } finally {
        connection.disconnect()
      }

      if (expectedSha256 == null || sha256(part) == expectedSha256) {
        break
      }

      // A corrupt payload cannot be repaired by resuming; drop it and start over once.
      log.warn("Checksum mismatch for {}, discarding partial download", entry.fileName)
      part.delete()
      if (attempt >= 2) {
        throw IllegalStateException("Downloaded file is corrupt, please retry")
      }
    }

    target.delete()
    if (!part.renameTo(target)) {
      throw IllegalStateException("Unable to finalize ${entry.fileName}")
    }
    return target
  }

  private fun copyReportingProgress(
    input: InputStream,
    out: FileOutputStream,
    label: String,
    startedAt: Long,
    total: Long,
    onProgress: (BundlePhase) -> Unit
  ) {
    val buffer = ByteArray(BUFFER_SIZE)
    var received = startedAt
    var lastPercent = -1
    while (true) {
      if (cancelled.get()) throw BundleCancelledException()
      val read = input.read(buffer)
      if (read == -1) break
      out.write(buffer, 0, read)
      received += read
      if (total <= 0) continue
      val percent = ((received * 100) / total).toInt().coerceIn(0, 100)
      if (percent != lastPercent) {
        lastPercent = percent
        onProgress(
          BundlePhase.Downloading(
            label = label,
            percent = percent,
            receivedMb = received / (1024.0 * 1024.0),
            totalMb = total / (1024.0 * 1024.0)
          )
        )
      }
    }
  }

  private fun extract(
    entry: OfflineBundle.Entry,
    archive: File,
    onProgress: (BundlePhase) -> Unit
  ) {
    val dest = File(context.filesDir, entry.destination)
    if (!entry.isArchive) {
      dest.parentFile?.mkdirs()
      archive.copyTo(dest, overwrite = true)
      marker(dest.parentFile ?: dest).writeText(OfflineBundle.VERSION)
      return
    }

    dest.deleteRecursively()
    dest.mkdirs()
    val root = dest.canonicalPath

    ZipInputStream(archive.inputStream().buffered(BUFFER_SIZE)).use { zip ->
      while (true) {
        if (cancelled.get()) throw BundleCancelledException()
        val zipEntry = zip.nextEntry ?: break
        val name = zipEntry.name.replace('\\', '/').trimStart('/')
        if (name.isBlank() || name.split('/').any { it == ".." }) {
          zip.closeEntry()
          continue
        }

        val target = File(dest, name)
        // Zip-slip guard: a crafted entry name must not escape the destination.
        if (!target.canonicalPath.startsWith(root)) {
          zip.closeEntry()
          continue
        }

        if (zipEntry.isDirectory) {
          target.mkdirs()
        } else {
          target.parentFile?.mkdirs()
          FileOutputStream(target).use { out -> zip.copyTo(out, BUFFER_SIZE) }
          onProgress(BundlePhase.Extracting(entry.label, name.substringAfterLast('/')))
        }
        zip.closeEntry()
      }
    }

    marker(dest).writeText(OfflineBundle.VERSION)
  }

  private fun openRangedConnection(url: URL, from: Long): HttpURLConnection {
    var current = url
    repeat(5) {
      val connection = (current.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 30_000
        readTimeout = 60_000
        setRequestProperty("User-Agent", "AndroidIDE")
        if (from > 0) setRequestProperty("Range", "bytes=$from-")
      }

      val status = connection.responseCode
      if (status in 301..303 || status == 307 || status == 308) {
        val location = connection.getHeaderField("Location")
          ?: throw IllegalStateException("Redirect without a location header")
        connection.disconnect()
        current = URL(current, location)
        return@repeat
      }

      if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
        connection.disconnect()
        throw IllegalStateException("Download failed with HTTP $status")
      }
      return connection
    }
    throw IllegalStateException("Too many redirects")
  }

  private fun openConnection(url: URL, rangeFrom: Long): InputStream =
    openRangedConnection(url, rangeFrom).inputStream

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(BUFFER_SIZE)
      while (true) {
        val read = input.read(buffer)
        if (read == -1) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}
