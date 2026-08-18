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

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.R
import com.itsaky.androidide.app.EdgeToEdgeIDEActivity
import com.itsaky.androidide.databinding.ActivitySdkManagerBinding
import com.itsaky.androidide.ndk.NativeComponents
import com.itsaky.androidide.ndk.NativeInstaller
import com.itsaky.androidide.ndk.NativePhase
import com.itsaky.androidide.tasks.runOnUiThread
import com.itsaky.androidide.utils.flashError
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Lets the user pick and install the NDK and CMake builds used for native projects.
 *
 * A full screen rather than a dialog: the download is well over a hundred megabytes, and leaving
 * the screen must not cancel it.
 *
 * @author Akash Yadav
 */
class SdkManagerActivity : EdgeToEdgeIDEActivity() {

  private var _binding: ActivitySdkManagerBinding? = null
  private val binding: ActivitySdkManagerBinding
    get() = checkNotNull(_binding) { "Activity has been destroyed" }

  private var installJob: Job? = null
  private var installer: NativeInstaller? = null

  override fun bindLayout(): View {
    _binding = ActivitySdkManagerBinding.inflate(layoutInflater)
    return _binding!!.root
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    binding.apply {
      setSupportActionBar(toolbar)
      supportActionBar!!.setDisplayHomeAsUpEnabled(true)
      supportActionBar!!.setTitle(R.string.title_sdk_manager)
      toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

      ndkVersion.setAdapter(
        ArrayAdapter(
          this@SdkManagerActivity,
          com.google.android.material.R.layout.m3_auto_complete_simple_item,
          NativeComponents.Ndk.entries.map { it.displayName }.reversed()
        )
      )
      ndkVersion.setText(NativeComponents.Ndk.default.displayName, false)
      ndkVersion.setOnItemClickListener { _, _, _, _ -> updateState() }

      cmakeVersion.setAdapter(
        ArrayAdapter(
          this@SdkManagerActivity,
          com.google.android.material.R.layout.m3_auto_complete_simple_item,
          NativeComponents.CMake.entries.map { it.displayName }.reversed()
        )
      )
      cmakeVersion.setText(NativeComponents.CMake.default.displayName, false)
      cmakeVersion.setOnItemClickListener { _, _, _, _ -> updateState() }

      downloadAction.setOnClickListener { toggleInstall() }
    }

    updateState()
  }

  override fun onDestroy() {
    _binding = null
    super.onDestroy()
  }

  private fun selectedNdk() = NativeComponents.Ndk.fromDisplayName(binding.ndkVersion.text)

  private fun selectedCMake() = NativeComponents.CMake.fromDisplayName(binding.cmakeVersion.text)

  private fun toggleInstall() {
    if (installJob?.isActive == true) {
      installer?.cancel()
      return
    }

    val ndk = selectedNdk()
    val cmake = selectedCMake()
    val installedNdks = NativeComponents.installedNdks(this)
    val installedCMakes = NativeComponents.installedCMakes(this)

    // Only fetch what is actually missing; re-selecting an installed version is a no-op.
    val ndkToInstall = ndk.takeUnless { installedNdks.any { existing -> matches(existing, it) } }
    val cmakeToInstall = cmake.takeUnless { installedCMakes.contains(it.version) }

    if (ndkToInstall == null && cmakeToInstall == null) {
      updateState()
      return
    }

    val installer = NativeInstaller(this).also { this.installer = it }
    binding.downloadAction.setText(android.R.string.cancel)

    installJob = lifecycleScope.launch {
      val ok = installer.install(ndkToInstall, cmakeToInstall) { phase ->
        runOnUiThread { render(phase) }
      }
      installJob = null
      runOnUiThread {
        if (!ok) {
          flashError(R.string.msg_native_install_failed)
        }
        updateState()
      }
    }
  }

  private fun render(phase: NativePhase) {
    if (_binding == null) return

    // Each component reports into the row directly beneath its own dropdown.
    val isNdk = when (phase) {
      is NativePhase.Downloading -> phase.component.startsWith("NDK")
      is NativePhase.Extracting -> phase.component.startsWith("NDK")
      is NativePhase.Installed -> phase.component.startsWith("NDK")
      is NativePhase.Failed -> phase.component.startsWith("NDK")
    }

    val progress = if (isNdk) binding.ndkProgress else binding.cmakeProgress
    val status = if (isNdk) binding.ndkStatus else binding.cmakeStatus

    when (phase) {
      is NativePhase.Downloading -> {
        progress.isVisible = true
        progress.isIndeterminate = false
        progress.progress = phase.percent
        status.text = getString(
          R.string.msg_native_downloading,
          phase.percent,
          phase.receivedMb.toInt(),
          phase.totalMb.toInt()
        )
      }

      is NativePhase.Extracting -> {
        progress.isVisible = true
        progress.isIndeterminate = true
        status.setText(R.string.msg_native_extracting)
      }

      is NativePhase.Installed -> {
        progress.isVisible = false
        status.text = getString(R.string.msg_native_installed, phase.version)
      }

      is NativePhase.Failed -> {
        progress.isVisible = false
        status.text = phase.message
      }
    }
  }

  private fun updateState() {
    if (_binding == null) return

    val installedNdks = NativeComponents.installedNdks(this)
    val installedCMakes = NativeComponents.installedCMakes(this)
    val ndk = selectedNdk()
    val cmake = selectedCMake()

    val ndkInstalled = installedNdks.any { matches(it, ndk) }
    val cmakeInstalled = installedCMakes.contains(cmake.version)

    binding.apply {
      if (installJob?.isActive != true) {
        ndkProgress.isVisible = false
        cmakeProgress.isVisible = false

        ndkStatus.text = if (ndkInstalled) {
          getString(R.string.msg_native_component_installed)
        } else {
          getString(R.string.msg_native_component_size, ndk.sizeMb)
        }

        cmakeStatus.text = if (cmakeInstalled) {
          getString(R.string.msg_native_component_installed)
        } else {
          getString(R.string.msg_native_component_size, cmake.sizeMb)
        }

        val pending = (if (ndkInstalled) 0 else ndk.sizeMb) +
            (if (cmakeInstalled) 0 else cmake.sizeMb)

        downloadAction.isEnabled = pending > 0
        downloadAction.text = if (pending > 0) {
          getString(R.string.action_download_native_size, pending)
        } else {
          getString(R.string.msg_native_all_installed)
        }
      }
    }
  }

  /**
   * Whether an installed revision corresponds to a release.
   *
   * The directory is named after `Pkg.Revision` (e.g. `28.2.13676358`) while the release is named
   * `r28c`, so the major number is the only thing the two share.
   */
  private fun matches(revision: String, ndk: NativeComponents.Ndk): Boolean {
    val major = ndk.release.removePrefix("r").takeWhile { it.isDigit() }
    return revision.substringBefore('.') == major
  }
}
