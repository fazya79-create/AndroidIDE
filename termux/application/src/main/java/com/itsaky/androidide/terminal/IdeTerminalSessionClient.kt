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

package com.itsaky.androidide.terminal

import com.itsaky.androidide.activities.TerminalActivity
import com.termux.app.terminal.TermuxTerminalSessionActivityClient
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * [TerminalSessionClient] delegate for AndroidIDE.
 *
 * @author Akash Yadav
 */
class IdeTerminalSessionClient(
  activity: TerminalActivity
) : TermuxTerminalSessionActivityClient(activity) {

  override fun onSessionFinished(finishedSession: TerminalSession) {
    val termuxSession = mActivity?.termuxService?.getTermuxSessionForTerminalSession(
      finishedSession)

    // if the finished session was performing tools installation
    // then report the result and leave the terminal, so onboarding can continue
    if (termuxSession?.executionCommand?.shellName == TerminalActivity.SETUP_SESSION_NAME) {
      mActivity.setResult(finishedSession.exitStatus)
      if (finishedSession.exitStatus == 0) {
        // Drop the session from the service too, otherwise it lingers as an idle shell and
        // keeps the foreground service (and its notification) alive.
        mActivity.termuxService?.removeTermuxSession(finishedSession)
        mActivity.finishActivityIfNotFinishing()
        return
      }
    }

    super.onSessionFinished(finishedSession)
  }
}