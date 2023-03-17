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

package com.grab.grazel.migrate.android

import com.android.build.gradle.AppExtension
import com.android.build.gradle.LibraryExtension
import com.grab.grazel.GrazelPluginTest
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.ANDROID_APPLICATION_PLUGIN
import com.grab.grazel.gradle.ANDROID_LIBRARY_PLUGIN
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.util.addGrazelExtension
import com.grab.grazel.util.createGrazelComponent
import com.grab.grazel.util.doEvaluate
import com.grab.grazel.util.truth
import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.the
import org.junit.Before
import org.junit.Test

class DefaultManifestValuesBuilderTest : GrazelPluginTest() {
    private lateinit var rootProject: Project
    private lateinit var androidBinary: Project
    private lateinit var androidLibrary: Project
    private lateinit var defaultManifestValuesBuilder: ManifestValuesBuilder

    @Before
    fun setUp() {
        rootProject = buildProject("root")
        rootProject.addGrazelExtension()

        androidLibrary = buildProject("android-library", rootProject)
        androidLibrary.run {
            plugins.apply {
                apply(ANDROID_LIBRARY_PLUGIN)
            }
            extensions.configure<LibraryExtension> {
                defaultConfig {
                    compileSdkVersion(29)
                    manifestPlaceholders.putAll(setOf("libraryPlaceholder" to "true"))
                }
                buildTypes {
                    getByName("debug") {
                        manifestPlaceholders.putAll(setOf("libraryBuildTypePlaceholder" to "true"))
                    }
                }
            }
        }
        androidBinary = buildProject("android-binary", rootProject)
        androidBinary.run {
            plugins.apply {
                apply(ANDROID_APPLICATION_PLUGIN)
            }
            extensions.configure<AppExtension> {
                defaultConfig {
                    applicationId = "com.test.grazel"
                    compileSdkVersion(29)
                    versionCode = 1
                    versionName = "1.0"
                    manifestPlaceholders.putAll(setOf("binaryPlaceholder" to "true"))
                }

                buildTypes {
                    getByName("debug") {
                        applicationIdSuffix = ".debug"
                    }
                }
            }
            dependencies {
                add("implementation", androidLibrary)
            }
        }

        androidBinary.doEvaluate()
        androidLibrary.doEvaluate()

        defaultManifestValuesBuilder = rootProject
            .createGrazelComponent()
            .manifestValuesBuilder()
    }


    @Test
    fun `assert manifest placeholder are parsed correctly`() {
        val appExtension = androidBinary.the<AppExtension>()
        val defaultConfig = androidBinary.the<AppExtension>().defaultConfig
        val debugVariant = appExtension.applicationVariants.first { it.buildType.name == "debug" }

        val matchedVariant = MatchedVariant(
            variantName = debugVariant.name,
            flavors = debugVariant.productFlavors.map { it.name }.toSet(),
            buildType = debugVariant.buildType.name,
            variant = debugVariant
        )

        val androidBinaryManifestValues = defaultManifestValuesBuilder.build(
            androidBinary,
            matchedVariant,
            defaultConfig,
        )
        androidBinaryManifestValues.truth {
            hasSize(8)
            containsEntry("versionCode", "1")
            containsEntry("versionName", "1.0")
            containsEntry("minSdkVersion", null)
            containsEntry("targetSdkVersion", null)
            containsEntry("binaryPlaceholder", "true")
            containsEntry("libraryPlaceholder", "true")
            containsEntry("libraryBuildTypePlaceholder", "true")
            containsEntry("applicationId", "com.test.grazel.debug")
        }
    }
}

