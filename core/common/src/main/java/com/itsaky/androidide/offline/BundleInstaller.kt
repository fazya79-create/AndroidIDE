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
import org.apache.commons.compress.compressors.brotli.BrotliCompressorInputStream
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
    val percent: Int,
    val receivedMb: Double,
    val totalMb: Double
  ) : BundlePhase()

  data class Extracting(val entry: String, val count: Int) : BundlePhase()
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
      File(dest, MARKER_NAME).takeIf { it.isFile }?.readText()?.trim() == OfflineBundle.VERSION
    }

    fun repositoryDir(context: Context): File =
      File(context.filesDir, OfflineBundle.REPOSITORY_PATH)
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
    return File(dest, MARKER_NAME).takeIf { it.isFile }
      ?.readText()?.trim() == OfflineBundle.VERSION
  }

  /**
   * Reads the published `checksums.txt` (`<sha256>  <filename>` per line). A missing manifest is
   * not fatal — verification is skipped rather than blocking the install.
   */
  private fun fetchChecksums(): Map<String, String> = runCatching {
    openRangedConnection(URL(OfflineBundle.checksumsUrl), 0L).let { connection ->
      try {
        connection.inputStream.bufferedReader().useLines { lines ->
          lines.mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
              parts[1].substringAfterLast('/') to parts[0].lowercase()
            } else {
              null
            }
          }.toMap()
        }
      } finally {
        connection.disconnect()
      }
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

      if (!resumed && existing > 0) {
        log.info("Server ignored the range request, restarting {}", entry.fileName)
        part.delete()
      }

      try {
        FileOutputStream(part, resumed).use { out ->
          connection.inputStream.use { input ->
            copyReportingProgress(
              input = input,
              out = out,
              startedAt = if (resumed) existing else 0L,
              total = total,
              onProgress = onProgress
            )
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
    dest.deleteRecursively()
    dest.mkdirs()
    val root = dest.canonicalPath
    var count = 0

    openArchive(entry, archive).use { source ->
      ZipInputStream(source).use { zip ->
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
            count++
            onProgress(BundlePhase.Extracting(name.substringAfterLast('/'), count))
          }
          zip.closeEntry()
        }
      }
    }

    if (count == 0) {
      throw IllegalStateException("Bundle archive was empty")
    }

    File(dest, MARKER_NAME).writeText(OfflineBundle.VERSION)
  }

  private fun openArchive(entry: OfflineBundle.Entry, archive: File): InputStream {
    val raw = archive.inputStream().buffered(BUFFER_SIZE)
    return if (entry.isBrotli) BrotliCompressorInputStream(raw) else raw
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
