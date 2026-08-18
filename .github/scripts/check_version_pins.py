#!/usr/bin/env python3
"""Fail if the offline-bundle workflow's version pins disagree with the app's constants.

A bundle harvested for a different AGP than the APK ships is useless offline, and the failure only
shows up on a device. This runs in CI before anything is downloaded.
"""

import os
import re
import sys

CONSTANTS = "utilities/templates-api/src/main/java/com/itsaky/androidide/templates/constants.kt"
TOOLCHAIN = "core/common/src/main/java/com/itsaky/androidide/proot/UbuntuToolchain.kt"
COMPOSE = (
    "utilities/templates-api/src/main/java/com/itsaky/androidide/templates/base/"
    "modules/android/buildGradle.kt"
)
SDK = "utilities/templates-api/src/main/java/com/itsaky/androidide/templates/Sdk.kt"


def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def const(path, name):
    """Value of a `const val NAME = "value"` declaration."""
    match = re.search(rf'{re.escape(name)}\s*=\s*"([^"]+)"', read(path))
    return match.group(1) if match else None


def compile_sdk_api():
    """API level of the `Sdk` enum entry the template compiles against."""
    entry = re.search(r"COMPILE_SDK_VERSION\s*=\s*Sdk\.(\w+)", read(CONSTANTS))
    if not entry:
        return None
    name = entry.group(1)
    # Sdk.kt entries look like: Tiramisu("Tiramisu", "13", 33),
    match = re.search(rf'{name}\("[^"]*",\s*"[^"]*",\s*(\d+)\)', read(SDK))
    return match.group(1) if match else None


def platform():
    match = re.search(r"DEFAULT_PLATFORM\s*=\s*(\d+)", read(TOOLCHAIN))
    return match.group(1) if match else None


def main():
    checks = [
        ("AGP", os.environ["AGP_VERSION"], const(CONSTANTS, "ANDROID_GRADLE_PLUGIN_VERSION")),
        ("Gradle", os.environ["GRADLE_VERSION"], const(CONSTANTS, "GRADLE_DISTRIBUTION_VERSION")),
        ("Kotlin", os.environ["KOTLIN_VERSION"], const(CONSTANTS, "KOTLIN_VERSION")),
        (
            "Compose compiler",
            os.environ["COMPOSE_COMPILER_VERSION"],
            const(COMPOSE, "compose_kotlinCompilerExtensionVersion"),
        ),
        ("Gradle (toolchain)", os.environ["GRADLE_VERSION"], const(TOOLCHAIN, "GRADLE_VERSION")),
        ("compileSdk", os.environ["COMPILE_SDK"], compile_sdk_api()),
        ("SDK platform installed", os.environ["COMPILE_SDK"], platform()),
    ]

    failed = False
    for name, workflow, app in checks:
        if app is None:
            print(f"COULD NOT READ {name} from the sources")
            failed = True
        elif workflow != app:
            print(f"MISMATCH {name}: workflow={workflow!r} app={app!r}")
            failed = True
        else:
            print(f"ok {name} = {workflow}")

    if failed:
        sys.exit("version pins disagree with the app's constants")


if __name__ == "__main__":
    main()
