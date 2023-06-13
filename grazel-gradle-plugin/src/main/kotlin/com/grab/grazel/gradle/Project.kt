/*
 * Copyright 2022 Grabtaxi Holdings PTE LTD (GRAB)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.grab.grazel.gradle

import com.android.build.api.dsl.ApplicationBuildFeatures
import com.android.build.api.dsl.LibraryBuildFeatures
import com.android.build.gradle.BaseExtension
import com.grab.grazel.migrate.android.extractTestInstrumentationRunner
import com.grab.grazel.util.BUILD_BAZEL
import com.grab.grazel.util.WORKSPACE
import org.gradle.api.Project
import org.gradle.kotlin.dsl.findByType

const val KOTLIN_PLUGIN = "kotlin"
const val KOTLIN_ANDROID_PLUGIN = "kotlin-android"
const val KOTLIN_ANDROID_EXTENSION = "kotlin-android-extensions"
const val KOTLIN_PARCELIZE = "kotlin-parcelize"
const val KOTLIN_KAPT = "kotlin-kapt"
const val ANDROID_APPLICATION_PLUGIN = "com.android.application"
const val ANDROID_LIBRARY_PLUGIN = "com.android.library"
const val ANDROID_DYNAMIC_FEATURE = "com.android.dynamic-feature"
const val FIREBASE_CRASHLYTICS_PLUGIN = "com.google.firebase.crashlytics"
const val GOOGLE_PLAY_SERVICES_PLUGIN = "com.google.firebase.crashlytics"

val Project.isAndroidLibrary get() = plugins.hasPlugin(ANDROID_LIBRARY_PLUGIN)
val Project.isAndroidApplication get() = plugins.hasPlugin(ANDROID_APPLICATION_PLUGIN)
val Project.isAndroidDynamicFeature get() = plugins.hasPlugin(ANDROID_DYNAMIC_FEATURE)
val Project.isAndroid
    get() = isAndroidApplication
        || isAndroidLibrary
        || isAndroidDynamicFeature

val Project.hasDatabinding: Boolean
    get() = extensions.findByType<BaseExtension>()
        ?.buildFeatures
        ?.let { buildFeatures ->
            (buildFeatures as? LibraryBuildFeatures)?.dataBinding == true ||
                (buildFeatures as? ApplicationBuildFeatures)?.dataBinding == true ||
                buildFeatures.viewBinding == true
        } == true

val Project.hasCrashlytics get() = plugins.hasPlugin(FIREBASE_CRASHLYTICS_PLUGIN)
val Project.hasGooglePlayServicesPlugin get() = plugins.hasPlugin(GOOGLE_PLAY_SERVICES_PLUGIN)

val Project.isKotlinJvm get() = plugins.hasPlugin(KOTLIN_PLUGIN)
val Project.isKotlinAndroid get() = plugins.hasPlugin(KOTLIN_ANDROID_PLUGIN)
val Project.hasKotlinAndroidExtensions
    get() = plugins.hasPlugin(KOTLIN_ANDROID_EXTENSION)
        || plugins.hasPlugin(KOTLIN_PARCELIZE)
val Project.isKotlin get() = isKotlinJvm || isKotlinAndroid
val Project.hasKapt get() = plugins.hasPlugin(KOTLIN_KAPT)

val Project.hasTestInstrumentationRunner
    get() = !extensions
        .findByType<BaseExtension>()
        ?.extractTestInstrumentationRunner()
        .isNullOrBlank()

const val JAVA_PLUGIN = "java"
const val JAVA_LIBRARY_PLUGIN = "java-library"
const val APPLICATION = "application"
val Project.isJava get() = plugins.hasPlugin(JAVA_PLUGIN) || plugins.hasPlugin(JAVA_LIBRARY_PLUGIN)

val Project.isJvm get() = isKotlinJvm || isJava

/**
 * @return True if the given project is migrated to Bazel. Calculated by checking for presence of `BUILD.bazel` file for
 * subprojects and `WORKSPACE` file for root project.
 */
val Project.isMigrated: Boolean
    get() = if (this == rootProject) {
        file(WORKSPACE).exists()
    } else file(BUILD_BAZEL).exists()
