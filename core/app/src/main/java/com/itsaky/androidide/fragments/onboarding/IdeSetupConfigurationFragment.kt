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

package com.itsaky.androidide.fragments.onboarding

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.provider.Settings
import android.text.Html
import android.view.ViewGroup
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.github.appintro.SlidePolicy
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.LayoutOnboardngSetupConfigBinding
import com.itsaky.androidide.offline.BundleInstaller
import com.itsaky.androidide.offline.BundlePhase
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.tasks.runOnUiThread
import com.itsaky.androidide.utils.ConnectionInfo
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.getConnectionInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Downloads the prebuilt offline dependency bundle.
 *
 * This replaces the old SDK/JDK version pickers. Those versions are no longer a user choice: the
 * bundle is harvested for one pinned AGP/Gradle/Kotlin combination, and the toolchain itself is
 * installed into the Ubuntu rootfs by the terminal step that follows.
 *
 * @author Akash Yadav
 */
class IdeSetupConfigurationFragment : OnboardingFragment(), SlidePolicy {

  private var _content: LayoutOnboardngSetupConfigBinding? = null
  private val content: LayoutOnboardngSetupConfigBinding
    get() = checkNotNull(_content) { "Fragment has been destroyed" }

  private var backgroundDataRestrictionReceiver: BroadcastReceiver? = null
  private var networkStateChangeCallback: NetworkCallback? = null

  private var installer: BundleInstaller? = null
  private var installJob: Job? = null

  companion object {

    @JvmStatic
    fun newInstance(context: Context): IdeSetupConfigurationFragment {
      return IdeSetupConfigurationFragment().also {
        it.arguments = Bundle().apply {
          putCharSequence(KEY_ONBOARDING_TITLE, context.getString(R.string.title_offline_bundle))
          putCharSequence(
            KEY_ONBOARDING_SUBTITLE,
            context.getString(R.string.subtitle_offline_bundle)
          )
          putCharSequence(
            KEY_ONBOARDING_EXTRA_INFO,
            Html.fromHtml(
              context.getString(R.string.msg_offline_bundle),
              Html.FROM_HTML_MODE_COMPACT
            )
          )
        }
      }
    }
  }

  @SuppressLint("PrivateResource")
  override fun createContentView(parent: ViewGroup, attachToParent: Boolean) {
    _content = LayoutOnboardngSetupConfigBinding.inflate(layoutInflater, parent, attachToParent)

    content.apply {
      noConnection.root.setText(R.string.msg_no_internet)
      cellularConnection.root.setText(R.string.msg_connected_to_cellular)
      meteredConnection.root.setText(R.string.msg_connected_to_metered_connection)
      backgroundDataRestricted.root.setText(R.string.msg_disable_background_data_restriction)

      downloadAction.setOnClickListener { toggleInstall() }
    }

    updateInstalledState()
    updateConnectionStatus()
  }

  /** Whether the bundle is present, i.e. whether onboarding may advance. */
  fun isBundleInstalled(): Boolean = BundleInstaller.isInstalled(requireContext())

  private fun toggleInstall() {
    if (installJob?.isActive == true) {
      installer?.cancel()
      return
    }
    startInstall()
  }

  private fun startInstall() {
    val installer = BundleInstaller(requireContext()).also { this.installer = it }

    content.downloadAction.setText(android.R.string.cancel)
    content.bundleProgress.isVisible = true
    content.bundleProgress.isIndeterminate = true
    content.bundleStatus.setText(R.string.msg_preparing)

    installJob = viewLifecycleOwner.lifecycleScope.launch {
      installer.install { phase -> runOnUiThread { render(phase) } }
      installJob = null
      runOnUiThread { updateInstalledState() }
    }
  }

  private fun render(phase: BundlePhase) {
    if (_content == null) return

    when (phase) {
      is BundlePhase.Idle -> Unit

      is BundlePhase.Downloading -> content.apply {
        bundleProgress.isIndeterminate = false
        bundleProgress.progress = phase.percent
        bundleStatus.text = getString(
          R.string.msg_downloading_bundle,
          phase.percent,
          phase.receivedMb.toInt(),
          phase.totalMb.toInt()
        )
      }

      is BundlePhase.Extracting -> content.apply {
        bundleProgress.isIndeterminate = true
        bundleStatus.text = getString(R.string.msg_extracting_bundle, phase.entry)
      }

      is BundlePhase.Done -> content.bundleStatus.setText(R.string.msg_bundle_ready)

      is BundlePhase.Failed -> {
        content.bundleStatus.text = phase.message
        flashError(phase.message)
      }
    }
  }

  private fun updateInstalledState() {
    val installed = isBundleInstalled()
    content.apply {
      bundleProgress.isVisible = !installed && installJob?.isActive == true
      downloadAction.isVisible = !installed
      if (installed) {
        bundleStatus.setText(R.string.msg_bundle_ready)
      } else if (installJob?.isActive != true) {
        bundleStatus.setText(R.string.msg_bundle_required)
        downloadAction.setText(R.string.action_download_bundle)
      }
    }
  }

  override fun onStart() {
    super.onStart()
    updateConnectionStatus()
    monitorNetworkState()
  }

  override fun onStop() {
    super.onStop()
    removeNetworkMonitors()
  }

  private fun updateConnectionStatus(networkCapabilities: NetworkCapabilities? = null) =
    updateConnectionStatus(getConnectionInfo(requireContext(), networkCapabilities))

  private fun updateConnectionStatus(connectionInfo: ConnectionInfo) = runOnUiThread {
    if (_content == null) return@runOnUiThread

    content.noConnection.root.isVisible = false
    content.cellularConnection.root.isVisible = false
    content.meteredConnection.root.isVisible = false
    content.backgroundDataRestricted.root.isVisible = false

    if (connectionInfo === ConnectionInfo.UNKNOWN || !connectionInfo.isConnected) {
      showNoConnectionWarning()
      return@runOnUiThread
    }

    if (connectionInfo.isCellularTransport) {
      addCellularTransportWarning()
    }

    if (connectionInfo.isMeteredConnection && !connectionInfo.isCellularTransport) {
      addMeteredConnectionWarning()
    }

    if (connectionInfo.isBackgroundDataRestricted) {
      addBackgroundDataRestrictedWarning()
    }
  }

  private fun addBackgroundDataRestrictedWarning() {
    content.backgroundDataRestricted.root.apply {
      setText(R.string.msg_disable_background_data_restriction)
      isVisible = true
    }
  }

  private fun addMeteredConnectionWarning() {
    content.meteredConnection.root.apply {
      setText(R.string.msg_connected_to_metered_connection)
      isVisible = true
    }
  }

  private fun addCellularTransportWarning() {
    content.cellularConnection.root.apply {
      setText(R.string.msg_connected_to_cellular)
      isVisible = true
    }
  }

  private fun showNoConnectionWarning() {
    content.noConnection.root.apply {
      isVisible = true
      setOnClickListener {
        it.context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
      }
    }
  }

  override fun onDestroyView() {
    super.onDestroyView()
    installer?.cancel()
    installJob = null
    installer = null
    _content = null
    backgroundDataRestrictionReceiver = null
    networkStateChangeCallback = null
  }

  override val isPolicyRespected: Boolean
    get() = isBundleInstalled() || getConnectionInfo(requireContext()).isConnected

  override fun onUserIllegallyRequestedNextPage() {
    flashError(string.msg_no_internet)
  }

  private fun monitorNetworkState() {
    val connectivityManager = requireContext().getSystemService<ConnectivityManager>() ?: return
    networkStateChangeCallback?.also {
      connectivityManager.registerDefaultNetworkCallback(it)
    }

    networkStateChangeCallback = object : NetworkCallback() {

      override fun onCapabilitiesChanged(
        network: Network,
        networkCapabilities: NetworkCapabilities
      ) {
        updateConnectionStatus(networkCapabilities)
      }

      override fun onLost(network: Network) {
        updateConnectionStatus(ConnectionInfo.UNKNOWN)
      }
    }

    val networkRequest = NetworkRequest.Builder()
      .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
      .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
      .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
      .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
      .build()
    connectivityManager.registerNetworkCallback(networkRequest, networkStateChangeCallback!!)

    backgroundDataRestrictionReceiver?.also {
      try {
        requireContext().unregisterReceiver(it)
      } catch (err: Throwable) {
        // not registered
      }
    }

    backgroundDataRestrictionReceiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        updateConnectionStatus()
      }
    }

    requireContext().registerReceiver(
      backgroundDataRestrictionReceiver!!,
      IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED)
    )
  }

  private fun removeNetworkMonitors() {
    networkStateChangeCallback?.also {
      requireContext().getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(it)
      networkStateChangeCallback = null
    }

    backgroundDataRestrictionReceiver?.also {
      requireContext().unregisterReceiver(it)
      backgroundDataRestrictionReceiver = null
    }
  }
}
