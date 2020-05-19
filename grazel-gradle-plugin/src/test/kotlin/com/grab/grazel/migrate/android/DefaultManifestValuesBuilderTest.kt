/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
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

