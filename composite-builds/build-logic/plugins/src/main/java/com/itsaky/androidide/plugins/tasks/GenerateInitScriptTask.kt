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

package com.itsaky.androidide.plugins.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Generates the Gradle init script for AndroidIDE.
 */
abstract class GenerateInitScriptTask : DefaultTask() {

  @get:Input
  abstract val downloadVersion: Property<String>

  @get:Input
  abstract val mavenGroupId: Property<String>

  @get:OutputDirectory
  abstract val outputDir: DirectoryProperty

  @TaskAction
  fun generate() {

    val outFile = this.outputDir.file("data/common/androidide.init.gradle")
      .also {
        it.get().asFile.parentFile.mkdirs()
      }

    outFile.get().asFile.bufferedWriter().use {

      it.write(
        """
      initscript {
          repositories {
              // The AndroidIDE artifacts are published to Maven Central. The old Sonatype
              // s01.oss repositories are gone: snapshots 404s and groups/public merely
              // redirects to Central, so listing them only adds a failed request plus a
              // redirect hop to every single dependency resolution.
              mavenCentral()
              google()
          }

          dependencies {
              classpath('${mavenGroupId.get()}:gradle-plugin:${downloadVersion.get()}') {
                  setChanging(false)
              }
          }
      }
      
      apply plugin: com.itsaky.androidide.gradle.AndroidIDEInitScriptPlugin

      // The published 'gradle-plugin' artifact has the retired Sonatype hosts compiled into its
      // BuildInfo, and it injects them as the FIRST repository of every handler. Every POM,
      // module and JAR then 404s there before resolving elsewhere, which on a phone turned
      // project configuration into a ~20 minute wait. Strip them after the plugin has run.
      def deadRepositoryHosts = ['s01.oss.sonatype.org']

      def stripDeadRepositories = { repositories ->
          try {
              repositories.removeIf { repository ->
                  def url = repository.hasProperty('url') ? repository.url : null
                  url != null && deadRepositoryHosts.any { host -> url.toString().contains(host) }
              }
          } catch (Throwable ignored) {
              // A handler may refuse mutation; a slower build beats a broken one.
          }
      }

      gradle.settingsEvaluated { settings ->
          stripDeadRepositories(settings.dependencyResolutionManagement.repositories)
          stripDeadRepositories(settings.pluginManagement.repositories)
      }

      gradle.rootProject { rootProject ->
          stripDeadRepositories(rootProject.buildscript.repositories)
          rootProject.allprojects { project ->
              stripDeadRepositories(project.buildscript.repositories)
              stripDeadRepositories(project.repositories)
          }
      }
    """
          .trimIndent()
      )
    }
  }

}