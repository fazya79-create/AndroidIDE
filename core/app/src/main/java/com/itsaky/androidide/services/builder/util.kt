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

package com.itsaky.androidide.services.builder

import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.preferences.internal.BuildPreferences
import com.itsaky.androidide.proot.ProotConfig
import com.itsaky.androidide.tooling.api.messages.GradleDistributionParams
import java.io.File

/**
 * The distribution params. This considers [gradleInstallationDir] preference as well.
 */
val gradleDistributionParams: GradleDistributionParams
  get() {
    if (BuildPreferences.gradleInstallationDir.isNotBlank()) {
      return GradleDistributionParams.forInstallationDir(BuildPreferences.gradleInstallationDir)
    }

    // Prefer the Gradle installed in the Ubuntu rootfs. Falling back to the wrapper makes the
    // first build download a second, complete Gradle distribution (~130 MB) even though a
    // working one is already present.
    if (File(ProotConfig.gradleDir(IDEApplication.instance), "bin/gradle").isFile) {
      return GradleDistributionParams.forInstallationDir(ProotConfig.GUEST_GRADLE_HOME)
    }

    return GradleDistributionParams.WRAPPER
  }