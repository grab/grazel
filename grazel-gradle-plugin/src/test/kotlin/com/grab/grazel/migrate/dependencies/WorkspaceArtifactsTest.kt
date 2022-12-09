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

package com.grab.grazel.migrate.dependencies

import com.android.build.gradle.AppExtension
import com.google.common.truth.Truth
import com.grab.grazel.GrazelExtension
import com.grab.grazel.GrazelPluginTest
import com.grab.grazel.bazel.starlark.asString
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.ANDROID_APPLICATION_PLUGIN
import com.grab.grazel.util.createGrazelComponent
import com.grab.grazel.util.doEvaluate
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.repositories
import org.junit.Test

class WorkspaceArtifactsTest : GrazelPluginTest() {

    @Test
    fun `assert artifact versions are overridden with overrideArtifactVersions`() {
        val overrideArtifactVersion = "org.jacoco:org.jacoco.ant:7.7.7"
        val (rootProject, androidBinary) = buildAndroidProject {
            dependencies {
                overrideArtifactVersions.add(overrideArtifactVersion)
            }
        }
        val workspaceBuilder = rootProject
            .createGrazelComponent()
            .workspaceBuilderFactory()
            .get()
            .create(listOf(rootProject, androidBinary))
        val workspace = workspaceBuilder
            .build()
            .asString()

        Truth.assertThat(workspace).apply {
            contains(overrideArtifactVersion)
        }
    }

    private fun buildAndroidProject(
        grazelExtensionModifier: GrazelExtension.() -> Unit = {},
    ): Pair<Project, Project> {
        val rootProject = buildProject("root")
        rootProject.extensions.add(GrazelExtension.GRAZEL_EXTENSION, GrazelExtension(rootProject))
        val androidBinary = buildProject("android-binary", rootProject)
        androidBinary.run {
            plugins.apply {
                apply(ANDROID_APPLICATION_PLUGIN)
            }
            extensions.configure<AppExtension> {
                defaultConfig {
                    compileSdkVersion(32)
                    buildToolsVersion("32.0.0")
                }
            }
            repositories {
                mavenCentral()
                google()
            }
            doEvaluate()
        }
        rootProject.extensions.configure<GrazelExtension> {
            this.grazelExtensionModifier()
        }
        return rootProject to androidBinary
    }

}
