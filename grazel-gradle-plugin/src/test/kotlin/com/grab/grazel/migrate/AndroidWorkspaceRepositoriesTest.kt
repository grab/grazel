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
            .get()
            .create(listOf(buildRootProject))

        val generatedCode = statements {
            workspaceBuilder.addAndroidSdkRepositories(this)
        }.asString()
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
            .get()
            .create(listOf(buildRootProject))
        val generatedCode = statements {
            workspaceBuilder.addAndroidSdkRepositories(this)
        }.asString()
        Truth.assertThat(generatedCode).apply {
            contains("android_ndk_repository")
            contains("name = \"androidndk\"")
            doesNotContain("path =")
        }
    }

    @Test
    fun `assert android ndk repository is generated with api_level`() {
        val ndkApiLevel = 30
        val buildRootProject = buildRootProject {
            android.ndkApiLevel = ndkApiLevel
        }
        val workspaceBuilder = buildRootProject
            .createGrazelComponent()
            .workspaceBuilderFactory()
            .get()
            .create(listOf(buildRootProject))
        val generatedCode = statements {
            workspaceBuilder.addAndroidSdkRepositories(this)
        }.asString()
        Truth.assertThat(generatedCode).apply {
            contains("android_ndk_repository")
            contains("name = \"androidndk\"")
            contains("api_level = $ndkApiLevel")
        }
    }

    private fun buildRootProject(
        grazelExtensionModifier: GrazelExtension.() -> Unit = {}
    ): Project {
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
        rootProject.extensions.configure<GrazelExtension> {
            this.grazelExtensionModifier()
        }
        return rootProject
    }
}