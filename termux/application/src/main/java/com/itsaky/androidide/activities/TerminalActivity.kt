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
import com.itsaky.androidide.proot.UbuntuToolchain
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
    /** API level chosen in the setup UI. */
    const val EXTRA_SETUP_SDK = "ide.onboarding.terminal.setup.sdk"

    /** JDK feature version chosen in the setup UI. */
    const val EXTRA_SETUP_JDK = "ide.onboarding.terminal.setup.jdk"

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

    if (isFailsafe || !ProotConfig.isInstalled(this)) {
      super.onCreateNewSession(isFailsafe, sessionName, workingDirectory)
      return
    }

    addUbuntuSession(sessionName)
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
    if (intent != null && intent.getBooleanExtra(EXTRA_ONBOARDING_RUN_IDESETUP, false)) {
      addIdesetupSession(
        platform = intent.getIntExtra(EXTRA_SETUP_SDK, UbuntuToolchain.DEFAULT_PLATFORM),
        jdk = intent.getStringExtra(EXTRA_SETUP_JDK) ?: UbuntuToolchain.DEFAULT_JDK
      )
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
   * Installs the Ubuntu rootfs (if needed) and starts a session running the toolchain
   * installer inside it. Replaces the old `idesetup.sh` flow, whose apt repository is dead.
   */
  private fun addIdesetupSession(platform: Int, jdk: String) {
    log.debug("Starting Ubuntu toolchain setup: platform={}, jdk={}", platform, jdk)

    val progress = MaterialAlertDialogBuilder(this)
      .setTitle(R.string.title_installing_ubuntu)
      .setMessage(R.string.msg_preparing)
      .setCancelable(false)
      .show()

    setupScope.launch {
      val bootCommand = ToolchainSetup.prepare(
        context = this@TerminalActivity,
        platform = platform,
        jdk = jdk
      ) { phase ->
        setupScope.launch { progress.setMessage(describe(phase)) }
      }

      progress.dismiss()

      if (bootCommand == null) {
        flashError(R.string.msg_cannot_create_terminal_session)
        return@launch
      }

      val session = termuxService.createTermuxSession(
        /* executablePath = */ ProotConfig.prootBinary(this@TerminalActivity),
        /* arguments = */ ProotConfig.shellArgs(
          context = this@TerminalActivity,
          bootCommand = bootCommand,
          keepAlive = false
        ).drop(1).toTypedArray(),
        /* stdin = */ null,
        /* workingDirectory = */ Environment.HOME.absolutePath,
        /* isFailSafe = */ false,
        /* sessionName = */ SETUP_SESSION_NAME,
        /* additionalEnvironment = */ HashMap(ProotConfig.prootEnvMap(this@TerminalActivity))
      ) ?: run {
        flashError(R.string.msg_cannot_create_terminal_session)
        return@launch
      }

      termuxTerminalSessionClient.setCurrentSession(session.terminalSession)
    }
  }
}