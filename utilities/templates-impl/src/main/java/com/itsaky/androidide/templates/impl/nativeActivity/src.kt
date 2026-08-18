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

import com.itsaky.androidide.templates.NativeBuildSystem
import com.itsaky.androidide.templates.base.AndroidModuleTemplateBuilder

/** `CMakeLists.txt` for the generated native module. */
fun cmakeListsSrc(libName: String): String = """
cmake_minimum_required(VERSION 3.22.1)

project("$libName")

add_library($libName SHARED
        native-lib.cpp)

target_link_libraries($libName
        android
        log)
""".trimStart()

/**
 * `Android.mk` for the ndk-build variant.
 *
 * `LOCAL_PATH` has to be resolved with `my-dir` before anything else; every other path in the file
 * is relative to it.
 */
fun androidMkSrc(libName: String): String = """
LOCAL_PATH := ${'$'}(call my-dir)

include ${'$'}(CLEAR_VARS)

LOCAL_MODULE    := $libName
LOCAL_SRC_FILES := native-lib.cpp
LOCAL_LDLIBS    := -llog -landroid

include ${'$'}(BUILD_SHARED_LIBRARY)
""".trimStart()

/**
 * `Application.mk` for the ndk-build variant.
 *
 * `APP_STL := c++_shared` is required for anything using the C++ standard library; without it the
 * link step fails on `std::` symbols.
 */
fun applicationMkSrc(): String = """
APP_STL := c++_shared
APP_CPPFLAGS := -std=c++17
""".trimStart()

/**
 * The JNI implementation.
 *
 * The symbol name encodes the package: `Java_<package with _ separators>_<class>_<method>`. A
 * mismatch here is only discovered at runtime as `UnsatisfiedLinkError`, so it is derived from the
 * same package name the template writes into the manifest.
 */
fun nativeLibSrc(packageName: String): String {
  val jniPackage = packageName.replace("_", "_1").replace('.', '_')
  return """
#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_${jniPackage}_MainActivity_stringFromJNI(JNIEnv *env, jobject /* this */) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}
""".trimStart()
}

/** `activity_main.xml` with a single centered label filled in from native code. */
fun nativeLayoutSrc(): String = """
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <TextView
        android:id="@+id/sample_text"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>
""".trimStart()

/**
 * `MainActivity` that loads the shared library and shows the string it returns.
 *
 * The library name passed to `System.loadLibrary` must match the module name in the CMake or
 * ndk-build script, minus the `lib` prefix and `.so` suffix.
 */
internal fun AndroidModuleTemplateBuilder.nativeActivitySrcKt(libName: String): String = """
package ${data.packageName}

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ${data.packageName}.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

  private lateinit var binding: ActivityMainBinding

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    binding.sampleText.text = stringFromJNI()
  }

  private external fun stringFromJNI(): String

  companion object {
    init {
      System.loadLibrary("$libName")
    }
  }
}
""".trimStart()

internal fun AndroidModuleTemplateBuilder.nativeActivitySrcJava(libName: String): String = """
package ${data.packageName};

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import ${data.packageName}.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

  static {
    System.loadLibrary("$libName");
  }

  private ActivityMainBinding binding;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    binding = ActivityMainBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());

    binding.sampleText.setText(stringFromJNI());
  }

  public native String stringFromJNI();
}
""".trimStart()

/** Source for the chosen build system's script. */
internal fun nativeBuildScriptSrc(system: NativeBuildSystem, libName: String): String =
  when (system) {
    NativeBuildSystem.CMake -> cmakeListsSrc(libName)
    NativeBuildSystem.NdkBuild -> androidMkSrc(libName)
  }

/** Name of the chosen build system's script. */
internal fun nativeBuildScriptName(system: NativeBuildSystem): String = when (system) {
  NativeBuildSystem.CMake -> "CMakeLists.txt"
  NativeBuildSystem.NdkBuild -> "Android.mk"
}

/** The library name derived from the project's package, kept valid for both build systems. */
internal fun libraryName(packageName: String): String =
  packageName.substringAfterLast('.')
    .lowercase()
    .filter { it.isLetterOrDigit() || it == '_' }
    .ifEmpty { "native_lib" }
