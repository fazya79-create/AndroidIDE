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

package com.itsaky.androidide.proot

import android.content.Context
import java.io.File

/**
 * Which pieces of the Ubuntu-based build toolchain are still missing.
 *
 * @author Akash Yadav
 */
enum class ToolchainPiece(val label: String, val sizeHint: String) {
  UBUNTU("Ubuntu 24.04 base", "30 MB"),
  JDK("OpenJDK 17", "180 MB"),
  SDK("Android SDK tools", "149 MB"),
  PLATFORM("SDK platform", "60 MB"),
  GRADLE("Gradle ${UbuntuToolchain.GRADLE_VERSION}", "130 MB")
}

/**
 * Installs the Gradle build toolchain into the Ubuntu rootfs. Replaces the original
 * `idesetup.sh` flow, which relied on Termux's apt repository — that repository's signing
 * key expired in Feb 2025 and the project is archived, so `apt update` can no longer work.
 *
 * @author Akash Yadav
 */
object UbuntuToolchain {

  /** Must match `GRADLE_DISTRIBUTION_VERSION` in `templates-api`'s constants. */
  const val GRADLE_VERSION = "8.14.3"
  const val SDK_RELEASE = "36.0.2"
  const val BUILD_TOOLS = "36.1.0"
  /**
   * Must equal `COMPILE_SDK_VERSION` in `templates-api`'s constants (Tiramisu = 33). Installing a
   * different level makes every generated project fail with
   * "Failed to find target with hash string 'android-NN'".
   */
  const val DEFAULT_PLATFORM = 34

  private const val SDK_URL =
    "https://github.com/HomuHomu833/android-sdk-custom/releases/download/" +
      "$SDK_RELEASE/android-sdk-aarch64-linux-gnu.tar.xz"

  private const val GRADLE_URL =
    "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"

  /**
   * Temurin ships a self-contained JDK directory. apt's `openjdk-17-jdk-headless` would install
   * to a versioned path inside the rootfs, and symlinking `/opt/jdk` to it produces a
   * guest-absolute link that is dangling when read from the host — which breaks JDK detection.
   */
  private const val JDK_URL =
    "https://github.com/adoptium/temurin17-binaries/releases/download/" +
      "jdk-17.0.20%2B8/OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.20_8.tar.gz"

  private const val SCRIPT_DIR = "root/androidide-setup"
  private const val SCRIPT_NAME = "install-toolchain.sh"

  /** Returns the pieces that still need to be installed for [platform]. */
  fun missing(context: Context, platform: Int = DEFAULT_PLATFORM): List<ToolchainPiece> {
    val pieces = mutableListOf<ToolchainPiece>()
    if (!ProotConfig.isInstalled(context)) pieces += ToolchainPiece.UBUNTU
    if (!File(ProotConfig.jdkDir(context), "bin/java").isFile) pieces += ToolchainPiece.JDK
    val sdk = ProotConfig.sdkDir(context)
    if (!File(sdk, "cmdline-tools/bin/sdkmanager").isFile) pieces += ToolchainPiece.SDK
    if (!File(sdk, "platforms/android-$platform").isDirectory) pieces += ToolchainPiece.PLATFORM
    if (!File(ProotConfig.gradleDir(context), "bin/gradle").isFile) pieces += ToolchainPiece.GRADLE
    return pieces
  }

  fun isReady(context: Context, platform: Int = DEFAULT_PLATFORM): Boolean =
    missing(context, platform).isEmpty()

  /**
   * Writes the installer script into the rootfs and returns the guest command that runs it.
   * Every step is guarded by an existence check, so re-running never re-downloads.
   */
  fun writeInstallScript(context: Context, platform: Int = DEFAULT_PLATFORM): String {
    val dir = File(ProotConfig.rootfsDir(context), SCRIPT_DIR).apply { mkdirs() }
    val script = File(dir, SCRIPT_NAME)
    runCatching {
      script.writeText(buildScript(platform))
      script.setExecutable(true)
    }
    // `exit` terminates the login shell so the terminal closes and onboarding can continue.
    return "bash /${SCRIPT_DIR}/$SCRIPT_NAME && exit 0 || exit 1"
  }

  private fun buildScript(platform: Int): String = buildString {
    val opt = ProotConfig.GUEST_OPT
    val sdk = ProotConfig.GUEST_SDK_ROOT
    val jdk = ProotConfig.GUEST_JAVA_HOME
    val gradle = ProotConfig.GUEST_GRADLE_HOME

    appendLine("set -e")
    appendLine("export DEBIAN_FRONTEND=noninteractive")
    appendLine()
    appendLine("echo '==> updating package lists'")
    appendLine("apt-get update -y")
    appendLine("apt-get install -y --no-install-recommends curl unzip xz-utils ca-certificates")
    appendLine()
    appendLine("if [ ! -x $jdk/bin/java ]; then")
    appendLine("  echo '==> downloading OpenJDK 17'")
    appendLine("  cd $opt")
    appendLine("  curl -fL --retry 3 -o jdk.tar.gz $JDK_URL")
    appendLine("  echo '==> extracting OpenJDK 17'")
    appendLine("  rm -rf jdk jdk-tmp && mkdir jdk-tmp")
    appendLine("  tar -xzf jdk.tar.gz -C jdk-tmp --strip-components=1")
    appendLine("  rm -f jdk.tar.gz")
    appendLine("  mv jdk-tmp jdk")
    appendLine("fi")
    appendLine("$jdk/bin/java -version")
    appendLine()
    appendLine("if [ ! -x $sdk/cmdline-tools/bin/sdkmanager ]; then")
    appendLine("  echo '==> downloading Android SDK tools'")
    appendLine("  cd $opt")
    appendLine("  curl -fL --retry 3 -o sdk.tar.xz $SDK_URL")
    appendLine("  echo '==> extracting Android SDK tools'")
    appendLine("  tar -xJf sdk.tar.xz")
    appendLine("  rm -f sdk.tar.xz")
    appendLine("fi")
    appendLine()
    appendLine("export JAVA_HOME=$jdk")
    appendLine("export ANDROID_HOME=$sdk")
    appendLine("export ANDROID_SDK_ROOT=$sdk")
    appendLine("export PATH=\$JAVA_HOME/bin:$sdk/cmdline-tools/bin:\$PATH")
    appendLine()
    appendLine("if [ ! -d $sdk/platforms/android-$platform ]; then")
    appendLine("  echo '==> installing platform android-$platform'")
    appendLine(
      "  sdkmanager --sdk_root=$sdk \"platforms;android-$platform\" \"build-tools;$BUILD_TOOLS\""
    )
    appendLine("fi")
    appendLine()
    appendLine("if [ ! -x $gradle/bin/gradle ]; then")
    appendLine("  echo '==> downloading Gradle $GRADLE_VERSION'")
    appendLine("  cd $opt")
    appendLine("  curl -fL --retry 3 -o gradle.zip $GRADLE_URL")
    appendLine("  unzip -q gradle.zip")
    appendLine("  rm -f gradle.zip")
    appendLine("  mv gradle-$GRADLE_VERSION gradle")
    appendLine("fi")
    appendLine("$gradle/bin/gradle --version")
    appendLine()
    appendLine("echo '==> toolchain ready'")
  }
}
