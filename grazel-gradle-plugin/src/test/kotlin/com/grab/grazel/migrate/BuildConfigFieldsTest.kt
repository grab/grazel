/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.migrate

import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import com.google.common.truth.Truth
import com.grab.grazel.GrazelExtension
import com.grab.grazel.GrazelExtension.Companion.GRAZEL_EXTENSION
import com.grab.grazel.GrazelPluginTest
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.ANDROID_APPLICATION_PLUGIN
import com.grab.grazel.gradle.AndroidBuildVariantDataSource
import com.grab.grazel.gradle.DefaultAndroidBuildVariantDataSource
import com.grab.grazel.migrate.android.extractBuildConfig
import com.grab.grazel.util.doEvaluate
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.the
import org.junit.Before
import org.junit.Test

class BuildConfigFieldsTest : GrazelPluginTest() {
    private lateinit var rootProject: Project
    private lateinit var androidBinary: Project
    private lateinit var androidBuildVariantDataSource: AndroidBuildVariantDataSource

    @Before
    fun setUp() {
        rootProject = buildProject("root")

        val grazelGradlePluginExtension = GrazelExtension(rootProject)
        rootProject.extensions.add(GRAZEL_EXTENSION, grazelGradlePluginExtension)
        androidBuildVariantDataSource = DefaultAndroidBuildVariantDataSource()

        androidBinary = buildProject("android-binary", rootProject)
        androidBinary.run {
            plugins.apply {
                apply(ANDROID_APPLICATION_PLUGIN)
            }
            extensions.configure<AppExtension> {
                defaultConfig {
                    compileSdkVersion(29)
                    versionCode = 1
                    versionName = "1.0"
                    buildConfigField("long", "SOME_LONG", "0")
                    buildConfigField("int", "SOME_INT", "0")
                    buildConfigField("boolean", "SOME_BOOLEAN", "false")
                    buildConfigField("String", "SOME_STRING", "\"Something\"")
                }
            }
        }
    }

    @Test
    fun `assert build config is extracted correctly in android binary target`() {
        androidBinary.doEvaluate()
        androidBinary
            .the<BaseExtension>()
            .extractBuildConfig(androidBinary, androidBuildVariantDataSource)
            .let { buildConfigData ->
                Truth.assertThat(buildConfigData.strings).apply {
                    hasSize(2)
                    containsEntry("SOME_STRING", "\"Something\"")
                    containsEntry("VERSION_NAME", "\"1.0\"")
                }

                Truth.assertThat(buildConfigData.booleans).apply {
                    hasSize(1)
                    containsEntry("SOME_BOOLEAN", "false")
                }

                Truth.assertThat(buildConfigData.ints).apply {
                    hasSize(2)
                    containsEntry("SOME_INT", "0")
                    containsEntry("VERSION_CODE", "1")
                }

                Truth.assertThat(buildConfigData.longs).apply {
                    hasSize(1)
                    containsEntry("SOME_LONG", "0")
                }
            }
    }
}