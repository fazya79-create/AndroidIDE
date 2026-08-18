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

package com.itsaky.androidide.activities

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.proot.InstallPhase
import com.itsaky.androidide.proot.ProotConfig
import com.itsaky.androidide.proot.ToolchainSetup
import com.itsaky.androidide.terminal.IdeTerminalSessionClient
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.flashError
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.app.terminal.TermuxTerminalSessionActivityClient
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.HashMap

/**
 * @author Akash Yadav
 */
class TerminalActivity : TermuxActivity() {

  override val navigationBarColor: Int
    get() = ContextCompat.getColor(this, android.R.color.black)
  override val statusBarColor: Int
    get() = ContextCompat.getColor(this, android.R.color.black)

  private val setupScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

  private var canAddNewSessions = true
    set(value) {
      field = value
      findViewById<View>(R.id.new_session_button)?.isEnabled = value
    }

  companion object {

    private val log = LoggerFactory.getLogger(TerminalActivity::class.java)
    private const val KEY_TERMINAL_CAN_ADD_SESSIONS = "ide.terminal.sessions.canAddSessions"

    const val EXTRA_ONBOARDING_RUN_IDESETUP = "ide.onboarding.terminal.runIdesetup"

    /** Shell name of the session that installs the build toolchain. */
    const val SETUP_SESSION_NAME = "IDE setup"

    /** Mirrors the private limit in `TermuxTerminalSessionActivityClient`. */
    private const val MAX_SESSIONS = 8
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    val controller = WindowCompat.getInsetsController(
      window, window.decorView)
    controller.isAppearanceLightNavigationBars = false
    controller.isAppearanceLightStatusBars = false
    super.onCreate(savedInstanceState)

    canAddNewSessions = savedInstanceState?.getBoolean(
      KEY_TERMINAL_CAN_ADD_SESSIONS, true) ?: true
  }

  override fun onCreateTerminalSessionClient(): TermuxTerminalSessionActivityClient {
    return IdeTerminalSessionClient(this)
  }

  override fun onDestroy() {
    setupScope.cancel()
    super.onDestroy()
  }

  private fun describe(phase: InstallPhase): String = when (phase) {
    is InstallPhase.Idle -> getString(R.string.msg_preparing)
    is InstallPhase.Downloading -> getString(
      R.string.msg_downloading_ubuntu,
      phase.percent,
      phase.receivedMb.toInt(),
      phase.totalMb.toInt()
    )

    is InstallPhase.Extracting -> getString(R.string.msg_extracting_ubuntu, phase.entry)
    is InstallPhase.Finalizing -> getString(R.string.msg_finalizing_ubuntu)
    is InstallPhase.Done -> getString(R.string.msg_finalizing_ubuntu)
    is InstallPhase.Failed -> phase.message
  }

  override fun onSaveInstanceState(savedInstanceState: Bundle) {
    super.onSaveInstanceState(savedInstanceState)
    savedInstanceState.putBoolean(KEY_TERMINAL_CAN_ADD_SESSIONS, canAddNewSessions)
  }

  override fun onServiceConnected(componentName: ComponentName?, service: IBinder?) {
    super.onServiceConnected(componentName, service)
    Environment.mkdirIfNotExits(Environment.TMP_DIR)
  }

  override fun onCreateNewSession(
    isFailsafe: Boolean,
    sessionName: String?,
    workingDirectory: String?
  ) {
    if (!canAddNewSessions) {
      flashError(R.string.msg_terminal_new_sessions_disabled)
      return
    }

    // Failsafe deliberately uses Android's shell: it exists to rescue a broken rootfs.
    if (isFailsafe) {
      super.onCreateNewSession(isFailsafe, sessionName, workingDirectory)
      return
    }

    // Falling through to the base implementation when the rootfs is missing lands the user in
    // Android's /system/bin/sh with no bash, no apt and no toolchain. Install it instead.
    if (!ProotConfig.isInstalled(this)) {
      installRootfsThen { addUbuntuSession(sessionName) }
      return
    }

    addUbuntuSession(sessionName)
  }

  /** Downloads and unpacks the Ubuntu rootfs, then runs [onReady]. */
  private fun installRootfsThen(onReady: () -> Unit) {
    val progress = MaterialAlertDialogBuilder(this)
      .setTitle(R.string.title_installing_ubuntu)
      .setMessage(R.string.msg_preparing)
      .setCancelable(false)
      .show()

    setupScope.launch {
      val ready = ToolchainSetup.prepare(this@TerminalActivity) { phase ->
        setupScope.launch { progress.setMessage(describe(phase)) }
      }

      progress.dismiss()

      if (!ready) {
        flashError(R.string.msg_cannot_create_terminal_session)
        return@launch
      }

      onReady()
    }
  }

  /**
   * Opens an interactive login shell inside the Ubuntu rootfs.
   *
   * The base implementation asks the service for a session with a `null` executable, which means
   * "the default shell" — a Termux bootstrap path that no longer exists in this build. The kernel
   * then falls back to Android's `/system/bin/sh`, where bash, apt and the toolchain are all
   * missing. Every non-failsafe session therefore has to go through proot explicitly.
   */
  private fun addUbuntuSession(sessionName: String?) {
    val service = termuxService ?: return

    if (service.termuxSessionsSize >= MAX_SESSIONS) {
      MaterialAlertDialogBuilder(this)
        .setTitle(R.string.title_max_terminals_reached)
        .setMessage(R.string.msg_max_terminals_reached)
        .setPositiveButton(android.R.string.ok, null)
        .show()
      return
    }

    ProotConfig.prepareMounts(this)
    ProotConfig.registerAndroidIds(this)
    ProotConfig.writeShellProfile(this)

    val session = service.createTermuxSession(
      /* executablePath = */ ProotConfig.prootBinary(this),
      /* arguments = */ ProotConfig.shellArgs(context = this).drop(1).toTypedArray(),
      /* stdin = */ null,
      /* workingDirectory = */ Environment.HOME.absolutePath,
      /* isFailSafe = */ false,
      /* sessionName = */ sessionName,
      /* additionalEnvironment = */ HashMap(ProotConfig.prootEnvMap(this))
    ) ?: run {
      flashError(R.string.msg_cannot_create_terminal_session)
      return
    }

    termuxTerminalSessionClient.setCurrentSession(session.terminalSession)
    drawer.closeDrawers()
  }

  override fun setupTermuxSessionOnServiceConnected(
    intent: Intent?,
    workingDir: String?,
    sessionName: String?,
    existingSession: TermuxSession?,
    launchFailsafe: Boolean
  ) {
    if (intent?.getBooleanExtra(EXTRA_ONBOARDING_RUN_IDESETUP, false) == true) {
      addIdesetupSession()
      return
    }

    super.setupTermuxSessionOnServiceConnected(
      intent,
      workingDir,
      sessionName,
      existingSession,
      launchFailsafe
    )
  }

  /**
   * Ensures the Ubuntu rootfs exists, then opens an interactive shell inside it.
   *
   * No toolchain installation happens here any more: the JDK, SDK and Gradle are extracted from
   * release assets during onboarding, so this only has to make the rootfs itself available.
   */
  private fun addIdesetupSession() {
    log.debug("Preparing the Ubuntu rootfs")

    if (ProotConfig.isInstalled(this)) {
      addUbuntuSession(SETUP_SESSION_NAME)
      return
    }

    installRootfsThen { addUbuntuSession(SETUP_SESSION_NAME) }
  }
}