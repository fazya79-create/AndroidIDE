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
    const val EXTRA_ONBOARDING_RUN_IDESETUP_ARGS = "ide.onboarding.terminal.runIdesetup.args"

    /** Shell name of the session that installs the build toolchain. */
    const val SETUP_SESSION_NAME = "IDE setup"
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
    if (canAddNewSessions) {
      super.onCreateNewSession(isFailsafe, sessionName, workingDirectory)
    } else {
      flashError(R.string.msg_terminal_new_sessions_disabled)
    }
  }

  override fun setupTermuxSessionOnServiceConnected(
    intent: Intent?,
    workingDir: String?,
    sessionName: String?,
    existingSession: TermuxSession?,
    launchFailsafe: Boolean
  ) {
    if (intent != null) {
      val runIdesetup = intent.getBooleanExtra(EXTRA_ONBOARDING_RUN_IDESETUP, false)
      val runIdesetupArgs = intent.getStringArrayExtra(EXTRA_ONBOARDING_RUN_IDESETUP_ARGS)
      if (runIdesetup && !runIdesetupArgs.isNullOrEmpty()) {
        addIdesetupSession(runIdesetupArgs)
        return
      }
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
  private fun addIdesetupSession(args: Array<String>) {
    log.debug("Starting Ubuntu toolchain setup, ignored legacy args: {}", args.joinToString(" "))

    setupScope.launch {
      val bootCommand = ToolchainSetup.prepare(this@TerminalActivity) { phase ->
        log.debug("Ubuntu install phase: {}", phase)
      }

      if (bootCommand == null) {
        flashError(R.string.msg_cannot_create_terminal_session)
        return@launch
      }

      val session = termuxService.createTermuxSession(
        /* executablePath = */ ProotConfig.prootBinary(this@TerminalActivity),
        /* arguments = */ ProotConfig.shellArgs(
          context = this@TerminalActivity,
          bootCommand = bootCommand
        ).drop(1).toTypedArray(),
        /* stdin = */ null,
        /* workingDirectory = */ Environment.HOME.absolutePath,
        /* isFailSafe = */ false,
        /* sessionName = */ SETUP_SESSION_NAME
      ) ?: run {
        flashError(R.string.msg_cannot_create_terminal_session)
        return@launch
      }

      termuxTerminalSessionClient.setCurrentSession(session.terminalSession)
    }
  }
}