/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.migrate

import com.android.build.gradle.AppExtension
import com.google.common.truth.Truth
import com.grab.grazel.GrazelExtension
import com.grab.grazel.GrazelExtension.Companion.GRAZEL_EXTENSION
import com.grab.grazel.GrazelPluginTest
import com.grab.grazel.bazel.starlark.asString
import com.grab.grazel.bazel.starlark.statements
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.ANDROID_APPLICATION_PLUGIN
import com.grab.grazel.util.createGrazelComponent
import com.grab.grazel.util.doEvaluate
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.junit.Test

class AndroidWorkspaceRepositoriesTest : GrazelPluginTest() {

    @Test
    fun `assert android sdk repository is generated based on values from android binary target`() {
        val buildRootProject = buildRootProject()
        val workspaceBuilder = buildRootProject
            .createGrazelComponent()
            .workspaceBuilderFactory()
            .create(listOf(buildRootProject))

        val generatedCode = statements { workspaceBuilder.addAndroidSdkRepositories(this) }.asString()
        Truth.assertThat(generatedCode).apply {
            contains("android_sdk_repository")
            contains("name = \"androidsdk\"")
            contains("api_level = 29")
            contains("build_tools_version = \"29.0.3\"")
        }
    }

    @Test
    fun `assert android ndk repository is generated with empty path values`() {
        val buildRootProject = buildRootProject()
        val workspaceBuilder = buildRootProject
            .createGrazelComponent()
            .workspaceBuilderFactory()
            .create(listOf(buildRootProject))
        val generatedCode = statements { workspaceBuilder.addAndroidSdkRepositories(this) }.asString()
        Truth.assertThat(generatedCode).apply {
            contains("android_ndk_repository")
            contains("name = \"androidndk\"")
            doesNotContain("path =")
        }
    }

    private fun buildRootProject(): Project {
        val rootProject = buildProject("root")
        rootProject.extensions.add(GRAZEL_EXTENSION, GrazelExtension(rootProject))
        val androidBinary = buildProject("android-binary", rootProject)
        androidBinary.run {
            plugins.apply {
                apply(ANDROID_APPLICATION_PLUGIN)
            }
            extensions.configure<AppExtension> {
                defaultConfig {
                    compileSdkVersion(29)
                    buildToolsVersion("29.0.3")
                }
            }
            doEvaluate()
        }
        return rootProject
    }
}