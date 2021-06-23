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

package com.grab.grazel.migrate.android

import com.android.build.gradle.AppExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.google.common.graph.ImmutableValueGraph
import com.google.common.graph.MutableValueGraph
import com.google.common.graph.ValueGraphBuilder
import com.google.common.truth.Truth
import com.grab.grazel.GrazelExtension
import com.grab.grazel.GrazelExtension.Companion.GRAZEL_EXTENSION
import com.grab.grazel.GrazelPluginTest
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.ANDROID_APPLICATION_PLUGIN
import com.grab.grazel.gradle.ANDROID_LIBRARY_PLUGIN
import com.grab.grazel.gradle.AndroidBuildVariantDataSource
import com.grab.grazel.gradle.DefaultAndroidBuildVariantDataSource
import com.grab.grazel.util.doEvaluate
import dagger.Lazy
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
    private lateinit var defaultManifestValuesBuilder: DefaultManifestValuesBuilder

    @Before
    fun setUp() {
        rootProject = buildProject("root")
        rootProject.extensions.add(GRAZEL_EXTENSION, GrazelExtension(rootProject))

        androidLibrary = buildProject("android-library", rootProject)
        androidLibrary.run {
            plugins.apply {
                apply(ANDROID_LIBRARY_PLUGIN)
            }
            extensions.configure<LibraryExtension> {
                defaultConfig {
                    compileSdkVersion(29)
                    manifestPlaceholders = mapOf("libraryPlaceholder" to "true")
                }
                buildTypes {
                    getByName("debug") {
                        manifestPlaceholders = mapOf("libraryBuildTypePlaceholder" to "true")
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
                    compileSdkVersion(29)
                    versionCode = 1
                    versionName = "1.0"
                    manifestPlaceholders = mapOf("binaryPlaceholder" to "true")
                }
            }
            dependencies {
                add("implementation", androidLibrary)
            }
        }

        val dependencyGraph: MutableValueGraph<Project, Configuration> = ValueGraphBuilder.directed()
            .allowsSelfLoops(false)
            .expectedNodeCount(rootProject.subprojects.size)
            .build()
        with(dependencyGraph) {
            val configuration = ConfigurationStub()
            addNode(androidBinary)
            addNode(androidLibrary)
            putEdgeValue(androidBinary, androidLibrary, configuration)
        }

        val buildVariantDataSource: AndroidBuildVariantDataSource = DefaultAndroidBuildVariantDataSource()
        defaultManifestValuesBuilder =
            DefaultManifestValuesBuilder(Lazy { ImmutableValueGraph.copyOf(dependencyGraph) }, buildVariantDataSource)
    }

    @Test
    fun `assert manifest placeholder are parsed correctly`() {
        androidBinary.doEvaluate()
        androidLibrary.doEvaluate()
        val defaultConfig = androidBinary.the<BaseAppModuleExtension>().defaultConfig
        val androidBinaryManifestValues = defaultManifestValuesBuilder.build(
            androidBinary,
            defaultConfig,
            "test.packageName"
        )
        Truth.assertThat(androidBinaryManifestValues).apply {
            hasSize(8)
            containsEntry("versionCode", "1")
            containsEntry("versionName", "1.0")
            containsEntry("minSdkVersion", null)
            containsEntry("targetSdkVersion", null)
            containsEntry("binaryPlaceholder", "true")
            containsEntry("libraryPlaceholder", "true")
            containsEntry("libraryBuildTypePlaceholder", "true")
            containsEntry("applicationId", "test.packageName")
        }
    }
}

