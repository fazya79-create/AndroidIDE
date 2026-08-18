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

package com.itsaky.androidide.templates.impl.nativeActivity

import com.itsaky.androidide.templates.Language
import com.itsaky.androidide.templates.NativeBuildSystem
import com.itsaky.androidide.templates.ProjectTemplate
import com.itsaky.androidide.templates.SpinnerWidget
import com.itsaky.androidide.templates.SrcSet
import com.itsaky.androidide.templates.base.modules.android.ManifestActivity
import com.itsaky.androidide.templates.base.modules.android.defaultAppModule
import com.itsaky.androidide.templates.base.util.AndroidModuleResManager.ResourceType.LAYOUT
import com.itsaky.androidide.templates.enumParameter
import com.itsaky.androidide.templates.impl.R
import com.itsaky.androidide.templates.impl.base.createRecipe
import com.itsaky.androidide.templates.impl.base.emptyThemesAndColors
import com.itsaky.androidide.templates.impl.baseProjectImpl
import java.io.File

/**
 * A project with C/C++ sources, built either with CMake or `ndk-build`.
 *
 * The build system is chosen in the wizard because it decides which script is generated and whether
 * CMake has to be installed alongside the NDK.
 *
 * @author Akash Yadav
 */
fun nativeActivityProject(): ProjectTemplate {
  val buildSystem = enumParameter<NativeBuildSystem> {
    name = R.string.wizard_native_build_system
    default = NativeBuildSystem.default
    displayName = NativeBuildSystem::displayName
  }

  return baseProjectImpl {
    templateName = R.string.template_native
    thumb = R.drawable.template_empty_activity

    // Appended after the shared widgets, so it appears below minSdk in the wizard.
    widgets(SpinnerWidget(buildSystem))

    defaultAppModule {
      nativeBuildSystem = buildSystem.value

      recipe = createRecipe {
        // `nativeBuildSystem` is read again here: the recipe runs after the user confirms, so the
        // value assigned above may be stale if the selection changed in between.
        val system = buildSystem.value
        nativeBuildSystem = system

        val libName = libraryName(data.packageName)
        val cppDir = File(srcFolder(SrcSet.Main), "cpp").also { it.mkdirs() }

        save(nativeLibSrc(data.packageName), File(cppDir, "native-lib.cpp"))
        save(nativeBuildScriptSrc(system, libName), File(cppDir, nativeBuildScriptName(system)))

        // ndk-build additionally needs Application.mk for the STL and C++ standard.
        if (system == NativeBuildSystem.NdkBuild) {
          save(applicationMkSrc(), File(cppDir, "Application.mk"))
        }

        sources {
          if (data.language == Language.Kotlin) {
            writeKtSrc(data.packageName, "MainActivity", source = nativeActivitySrcKt(libName))
          } else {
            writeJavaSrc(data.packageName, "MainActivity", source = nativeActivitySrcJava(libName))
          }
        }

        manifest {
          addActivity(ManifestActivity("MainActivity", isExported = true, isLauncher = true))
        }

        res {
          writeXmlResource("activity_main", LAYOUT, source = nativeLayoutSrc())
          emptyThemesAndColors()
        }
      }
    }
  }
}
