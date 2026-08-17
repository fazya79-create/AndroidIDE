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

package com.itsaky.androidide.utils

import android.app.Application
import android.graphics.Color
import com.itsaky.androidide.preferences.internal.DevOpsPreferences
import com.ms_square.debugoverlay.DebugOverlay
import com.ms_square.debugoverlay.Position
import com.ms_square.debugoverlay.modules.CpuUsageModule
import com.ms_square.debugoverlay.modules.FpsModule
import com.ms_square.debugoverlay.modules.MemInfoModule
import org.slf4j.LoggerFactory

/**
 * Installs the performance overlay (FPS, CPU and memory) when enabled in Developer options.
 *
 * The overlay samples continuously, so it is disabled by default — measuring frame drops must
 * not be what causes them.
 *
 * @author Akash Yadav
 */
object PerfOverlay {

  private val log = LoggerFactory.getLogger(PerfOverlay::class.java)

  private var installed = false

  fun installIfEnabled(application: Application) {
    if (installed || !DevOpsPreferences.perfOverlayEnabled) {
      return
    }

    runCatching {
      DebugOverlay.Builder(application)
        .modules(FpsModule(), CpuUsageModule(), MemInfoModule())
        .position(Position.BOTTOM_START)
        .bgColor(Color.parseColor("#80000000"))
        .textColor(Color.WHITE)
        .textSize(10f)
        .allowSystemLayer(false)
        .notification(false)
        .build()
        .install()

      installed = true
    }.onFailure { error ->
      log.warn("Unable to install the performance overlay", error)
    }
  }
}
