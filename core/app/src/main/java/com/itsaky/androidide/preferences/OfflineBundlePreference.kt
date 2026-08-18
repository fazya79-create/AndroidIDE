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

package com.itsaky.androidide.preferences

import android.content.Context
import androidx.preference.Preference
import com.itsaky.androidide.offline.BundleInstaller
import com.itsaky.androidide.offline.BundlePhase
import com.itsaky.androidide.resources.R.drawable
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.tasks.runOnUiThread
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

/**
 * Downloads or re-downloads the offline dependency bundle.
 *
 * Onboarding already installs the bundle, but it is also reachable here so it can be refreshed
 * after a bundle version bump without clearing app data.
 *
 * @author Akash Yadav
 */
@Parcelize
class OfflineBundlePreference(
  override val key: String = "idepref_build_offlineBundle",
  override val title: Int = string.idepref_offlineBundle_title,
  override val summary: Int? = string.idepref_offlineBundle_summary,
  override val icon: Int? = drawable.ic_gradle
) : SimplePreference() {

  override fun onCreatePreference(context: Context): Preference {
    return super.onCreatePreference(context).also { updateSummary(it) }
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context

    if (BundleInstaller.isInstalled(context)) {
      DialogUtils.newMaterialDialogBuilder(context)
        .setTitle(title)
        .setMessage(string.msg_bundle_reinstall)
        .setPositiveButton(string.yes) { dialog, _ ->
          dialog.dismiss()
          install(preference)
        }
        .setNegativeButton(string.no) { dialog, _ -> dialog.dismiss() }
        .show()
      return true
    }

    install(preference)
    return true
  }

  private fun install(preference: Preference) {
    val context = preference.context
    val installer = BundleInstaller(context)
    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val dialog = DialogUtils.newMaterialDialogBuilder(context)
      .setTitle(title)
      .setMessage(string.msg_preparing)
      .setCancelable(false)
      .setNegativeButton(android.R.string.cancel) { _, _ -> installer.cancel() }
      .show()

    var job: Job? = null
    job = scope.launch {
      val installed = installer.install { phase ->
        runOnUiThread {
          when (phase) {
            is BundlePhase.Downloading -> dialog.setMessage(
              context.getString(
                string.msg_downloading_bundle,
                phase.percent,
                phase.receivedMb.toInt(),
                phase.totalMb.toInt()
              )
            )

            is BundlePhase.Extracting -> dialog.setMessage(
              context.getString(string.msg_extracting_bundle, phase.entry)
            )

            else -> Unit
          }
        }
      }

      dialog.dismiss()
      updateSummary(preference)

      if (installed) {
        flashSuccess(string.msg_bundle_ready)
      } else {
        flashError(string.msg_bundle_failed)
      }

      job?.cancel()
    }
  }

  private fun updateSummary(preference: Preference) {
    preference.setSummary(
      if (BundleInstaller.isInstalled(preference.context)) {
        string.msg_bundle_ready
      } else {
        string.msg_bundle_required
      }
    )
  }
}
