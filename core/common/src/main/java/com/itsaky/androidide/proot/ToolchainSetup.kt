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

package com.itsaky.androidide.proot

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Prepares the Ubuntu rootfs and returns the shell command that installs the build toolchain.
 *
 * The original setup flow (`idesetup.sh`) installed OpenJDK through Termux's apt repository.
 * That repository is archived and its signing key expired in February 2025, so `apt update`
 * fails with `EXPKEYSIG` and can never be fixed upstream. Ubuntu's own apt is used instead.
 *
 * @author Akash Yadav
 */
object ToolchainSetup {

  private val log = LoggerFactory.getLogger(ToolchainSetup::class.java)

  /**
   * Installs the Ubuntu rootfs if needed, then returns the guest command that installs the
   * remaining toolchain pieces. Returns `null` if the rootfs could not be installed.
   */
  suspend fun prepare(
    context: Context,
    platform: Int = UbuntuToolchain.DEFAULT_PLATFORM,
    onProgress: (InstallPhase) -> Unit = {}
  ): String? = withContext(Dispatchers.IO) {
    if (!ProotConfig.isAvailable(context)) {
      log.error("proot binary is missing for this ABI")
      return@withContext null
    }

    if (!ProotConfig.isInstalled(context)) {
      var failed = false
      UbuntuInstaller(context).install { phase ->
        if (phase is InstallPhase.Failed) {
          failed = true
          log.error("Ubuntu installation failed: {}", phase.message)
        }
        onProgress(phase)
      }
      if (failed || !ProotConfig.isInstalled(context)) {
        return@withContext null
      }
    }

    ProotConfig.prepareMounts(context)
    ProotConfig.writeShellProfile(context)
    UbuntuToolchain.writeInstallScript(context, platform)
  }

  /** Human-readable summary of what still needs downloading, for the confirmation prompt. */
  fun missingSummary(context: Context, platform: Int = UbuntuToolchain.DEFAULT_PLATFORM): String =
    UbuntuToolchain.missing(context, platform)
      .joinToString(", ") { "${it.label} (${it.sizeHint})" }

  /** Deletes the rootfs, keeping the downloaded toolchain in `<filesDir>/toolchains`. */
  fun resetRootfs(context: Context) {
    ProotConfig.rootfsDir(context).deleteRecursivelySafe()
  }

  fun toolchainSize(context: Context): Long =
    File(ProotConfig.toolchainRoot(context).absolutePath).walkBottomUp()
      .filter { it.isFile }
      .sumOf { it.length() }
}
