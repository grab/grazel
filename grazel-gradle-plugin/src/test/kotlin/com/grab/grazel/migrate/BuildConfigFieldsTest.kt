/*
 * Copyright 2021 Grabtaxi Holdings PTE LTE (GRAB)
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