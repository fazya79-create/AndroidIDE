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

/**
 * Prepares the Ubuntu rootfs.
 *
 * The toolchain itself is no longer installed here: the JDK, Android SDK and Gradle arrive as
 * prebuilt release assets extracted into `<filesDir>/toolchains`, which is bound at `/opt` inside
 * the rootfs. Nothing needs a terminal session, and nothing is fetched from five separate hosts on
 * first launch.
 *
 * The rootfs stays a live download on purpose — it is a real Linux environment the user runs `apt`
 * and npm-installed CLIs inside.
 *
 * @author Akash Yadav
 */
object ToolchainSetup {

  private val log = LoggerFactory.getLogger(ToolchainSetup::class.java)

  /**
   * Installs the Ubuntu rootfs if needed and prepares its mounts.
   *
   * @return `true` when the rootfs is ready to run commands in.
   */
  suspend fun prepare(
    context: Context,
    onProgress: (InstallPhase) -> Unit = {}
  ): Boolean = withContext(Dispatchers.IO) {
    if (!ProotConfig.isAvailable(context)) {
      log.error("proot binary is missing for this ABI")
      return@withContext false
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
        return@withContext false
      }
    }

    ProotConfig.prepareMounts(context)
    ProotConfig.writeShellProfile(context)
    true
  }

  /** Deletes the rootfs, keeping the extracted toolchain in `<filesDir>/toolchains`. */
  fun resetRootfs(context: Context) {
    ProotConfig.rootfsDir(context).deleteRecursivelySafe()
  }
}
