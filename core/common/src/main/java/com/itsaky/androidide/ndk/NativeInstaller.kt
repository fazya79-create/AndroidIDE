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

package com.itsaky.androidide.ndk

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

/** Progress of a native component installation. */
sealed class NativePhase {
  data class Downloading(
    val component: String,
    val percent: Int,
    val receivedMb: Double,
    val totalMb: Double
  ) : NativePhase()

  data class Extracting(val component: String) : NativePhase()
  data class Installed(val component: String, val version: String) : NativePhase()
  data class Failed(val component: String, val message: String) : NativePhase()
}

/**
 * Downloads and installs the NDK and CMake into the Android SDK directory, where AGP expects them.
 *
 * @author Akash Yadav
 */
class NativeInstaller(private val context: Context) {

  private val cancelled = AtomicBoolean(false)

  fun cancel() {
    cancelled.set(true)
  }

  companion object {

    private val log = LoggerFactory.getLogger(NativeInstaller::class.java)

    /**
     * Serializes installations process-wide.
     *
     * Both the SDK Manager screen and the build-time setup can trigger an install, and two
     * concurrent downloads would write the same staging directory. Callers therefore wait rather
     * than duplicating a 159 MB transfer.
     */
    private val lock = Mutex()

    /** Whether an installation is currently running. */
    val isBusy: Boolean
      get() = lock.isLocked
  }

  /**
   * Installs [ndk] and [cmake] unless already present. Returns true when both are available
   * afterwards.
   */
  suspend fun install(
    ndk: NativeComponents.Ndk?,
    cmake: NativeComponents.CMake?,
    onProgress: (NativePhase) -> Unit
  ): Boolean = withContext(Dispatchers.IO) {
    lock.withLock {
      runCatching {
        ndk?.let { installNdk(it, onProgress) }
        cmake?.let { installCMake(it, onProgress) }
      }.onFailure { error ->
        if (error is NativeCancelledException) {
          log.info("Native component installation cancelled")
        } else {
          log.error("Native component installation failed", error)
        }
        onProgress(NativePhase.Failed("", error.message ?: "Installation failed"))
        return@withLock false
      }
      true
    }
  }

  private fun installNdk(ndk: NativeComponents.Ndk, onProgress: (NativePhase) -> Unit) {
    val root = NativeComponents.ndkRoot(context)
    val archive = download(ndk.displayName, ndk.url, onProgress)

    try {
      onProgress(NativePhase.Extracting(ndk.displayName))
      val staging = File(root, ".staging-${ndk.release}").also {
        it.deleteRecursively()
        it.mkdirs()
      }

      // The archive holds a single `android-ndk-<release>/` directory; its contents have to end up
      // directly under the revision directory AGP looks for.
      extractTarXz(archive, staging, stripComponents = 1)

      val revision = readRevision(staging)
        ?: throw IllegalStateException("Could not determine the NDK revision")

      val target = File(root, revision)
      target.deleteRecursively()
      if (!staging.renameTo(target)) {
        staging.deleteRecursively()
        throw IllegalStateException("Could not move the NDK into place")
      }

      restoreExecutableBits(target)
      onProgress(NativePhase.Installed(ndk.displayName, revision))
    } finally {
      archive.delete()
    }
  }

  private fun installCMake(cmake: NativeComponents.CMake, onProgress: (NativePhase) -> Unit) {
    val root = NativeComponents.cmakeRoot(context)
    val archive = download(cmake.displayName, cmake.url, onProgress)

    try {
      onProgress(NativePhase.Extracting(cmake.displayName))
      val staging = File(root, ".staging-${cmake.version}").also {
        it.deleteRecursively()
        it.mkdirs()
      }

      // The archive's single top-level directory is the version itself (`4.1.2/bin/cmake`).
      extractTarXz(archive, staging, stripComponents = 1)

      val target = File(root, cmake.version)
      target.deleteRecursively()
      if (!staging.renameTo(target)) {
        staging.deleteRecursively()
        throw IllegalStateException("Could not move CMake into place")
      }

      restoreExecutableBits(target)
      onProgress(NativePhase.Installed(cmake.displayName, cmake.version))
    } finally {
      archive.delete()
    }
  }

  /** Downloads to a temporary file next to the destination, resuming a partial transfer. */
  private fun download(
    component: String,
    url: String,
    onProgress: (NativePhase) -> Unit
  ): File {
    val cache = File(context.cacheDir, "native-components").apply { mkdirs() }
    val target = File(cache, url.substringAfterLast('/'))
    val existing = if (target.isFile) target.length() else 0L

    val connection = open(URL(url), existing)
    val resumed = connection.responseCode == 206
    val remaining = connection.contentLengthLong.coerceAtLeast(0)
    val total = if (resumed) existing + remaining else remaining

    // A stale partial larger than the asset (or a server ignoring Range) means starting over.
    if (!resumed && existing > 0) {
      target.delete()
    }

    FileOutputStream(target, resumed).use { out ->
      connection.inputStream.use { input ->
        val buffer = ByteArray(128 * 1024)
        var received = if (resumed) existing else 0L
        var lastPercent = -1
        while (true) {
          if (cancelled.get()) throw NativeCancelledException()
          val read = input.read(buffer)
          if (read == -1) break
          out.write(buffer, 0, read)
          received += read
          if (total > 0) {
            val percent = ((received * 100) / total).toInt().coerceIn(0, 100)
            // Reporting every byte would flood the build output; a percent is enough.
            if (percent != lastPercent) {
              lastPercent = percent
              onProgress(
                NativePhase.Downloading(
                  component = component,
                  percent = percent,
                  receivedMb = received / (1024.0 * 1024.0),
                  totalMb = total / (1024.0 * 1024.0)
                )
              )
            }
          }
        }
      }
    }
    connection.disconnect()
    return target
  }

  private fun open(url: URL, resumeFrom: Long): HttpURLConnection {
    var current = url
    repeat(5) {
      val connection = current.openConnection() as HttpURLConnection
      connection.instanceFollowRedirects = true
      connection.connectTimeout = 30_000
      connection.readTimeout = 60_000
      connection.setRequestProperty("User-Agent", "AndroidIDE")
      if (resumeFrom > 0) {
        connection.setRequestProperty("Range", "bytes=$resumeFrom-")
      }
      val status = connection.responseCode
      if (status in 301..303 || status == 307 || status == 308) {
        val location = connection.getHeaderField("Location")
          ?: throw IllegalStateException("Redirect without a location")
        connection.disconnect()
        current = URL(current, location)
        return@repeat
      }
      if (status != 200 && status != 206) {
        throw IllegalStateException("Download failed with HTTP $status")
      }
      return connection
    }
    throw IllegalStateException("Too many redirects")
  }

  private fun extractTarXz(archive: File, into: File, stripComponents: Int) {
    TarArchiveInputStream(
      XZCompressorInputStream(BufferedInputStream(archive.inputStream(), 256 * 1024))
    ).use { tar ->
      while (true) {
        if (cancelled.get()) throw NativeCancelledException()
        val entry = tar.nextEntry ?: break

        val name = entry.name.replace('\\', '/')
          .split('/')
          .drop(stripComponents)
          .joinToString("/")
          .trim('/')
        if (name.isBlank() || name.split('/').any { it == ".." }) continue

        val target = File(into, name)
        if (!target.canonicalPath.startsWith(into.canonicalPath)) continue

        when {
          entry.isDirectory -> target.mkdirs()

          entry.isSymbolicLink -> {
            target.parentFile?.mkdirs()
            target.delete()
            runCatching {
              java.nio.file.Files.createSymbolicLink(
                target.toPath(),
                java.nio.file.Paths.get(entry.linkName)
              )
            }
          }

          else -> {
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { out -> tar.copyTo(out, 128 * 1024) }
            // Tar carries the mode; the executable bit matters for every compiler binary.
            if (entry.mode and 64 != 0) {
              target.setExecutable(true, false)
            }
          }
        }
      }
    }
  }

  /** Reads `Pkg.Revision` from the extracted NDK, which is the directory name AGP requires. */
  private fun readRevision(dir: File): String? {
    val properties = File(dir, "source.properties")
    if (!properties.isFile) return null
    return runCatching {
      Properties().apply { properties.inputStream().use { load(it) } }
        .getProperty("Pkg.Revision")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    }.getOrNull()
  }

  /**
   * Re-applies the executable bit across `bin` directories and shared objects.
   *
   * Tar modes are honoured above, but the toolchain also relies on wrapper scripts and `.so` files
   * whose modes vary between archives; missing one surfaces as "permission denied" mid-compile.
   */
  private fun restoreExecutableBits(root: File) {
    root.walkTopDown().forEach { file ->
      if (!file.isFile) return@forEach
      val isBinary = file.parentFile?.name == "bin" ||
          file.name.endsWith(".so") ||
          file.name.contains(".so.") ||
          file.extension.isEmpty() && file.parentFile?.name == "libexec"
      if (isBinary) {
        file.setExecutable(true, false)
      }
    }
  }
}

/** Thrown when the user cancels an installation. */
class NativeCancelledException : Exception("Installation cancelled")
