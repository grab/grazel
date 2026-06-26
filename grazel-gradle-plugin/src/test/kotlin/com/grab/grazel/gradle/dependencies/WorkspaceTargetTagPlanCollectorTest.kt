/*
 * Copyright 2026 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.gradle.dependencies

import com.google.common.truth.Truth.assertThat
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.ANDROID_APPLICATION_PLUGIN
import com.grab.grazel.gradle.ANDROID_LIBRARY_PLUGIN
import com.grab.grazel.gradle.KOTLIN_ANDROID_PLUGIN
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.TargetTagKey
import com.grab.grazel.gradle.dependencies.model.TargetTagPlan
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.util.addGrazelExtension
import com.grab.grazel.util.createGrazelComponent
import com.grab.grazel.util.doEvaluate
import com.grab.grazel.util.initDependencyGraphsForTest
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.the
import com.android.build.gradle.AppExtension
import com.android.build.gradle.LibraryExtension
import org.gradle.kotlin.dsl.configure
import org.junit.Test

class WorkspaceTargetTagPlanCollectorTest {

    @Test
    fun `collects android library maven tag closure before target generation`() {
        val rootProject = buildProject("root").also {
            it.addGrazelExtension()
        }
        val appProject = buildProject("app", rootProject)
        val libraryProject = buildProject("lib", rootProject)

        configureAndroidApplication(appProject)
        configureAndroidLibrary(libraryProject)
        appProject.dependencies {
            add("implementation", libraryProject)
        }
        libraryProject.dependencies {
            add("implementation", "com.example:root:1.0")
        }

        libraryProject.doEvaluate()
        appProject.doEvaluate()

        val grazelComponent = rootProject.createGrazelComponent()
        grazelComponent.initDependencyGraphsForTest(rootProject)
        grazelComponent.dependencyResolutionService().get().populateCache(
            WorkspaceDependencies(
                variantDeps = mapOf(
                    DEFAULT_VARIANT to listOf(
                        ResolvedDependency.fromId("com.example:root:1.0", "repo"),
                        ResolvedDependency.fromId("com.example:child:1.0", "repo")
                    )
                ),
                variantTransitiveClasspath = mapOf(
                    DEFAULT_VARIANT to mapOf("com.example:root" to setOf("com.example:child"))
                ),
                transitiveClasspath = mapOf("com.example:root" to setOf("com.example:child"))
            )
        )

        val tagPlan = grazelComponent.workspaceTargetTagPlanCollector().get().collect(rootProject)

        assertThat(tagPlan).contains(
            TargetTagPlan(
                key = TargetTagKey(
                    variantId = ":lib:debugAndroidBuild",
                    variantType = "AndroidBuild",
                    targetKind = "android_library"
                ),
                tags = listOf("@maven//:com_example_child", "@maven//:com_example_root")
            )
        )
    }

    private fun configureAndroidApplication(project: Project) {
        project.plugins.apply(ANDROID_APPLICATION_PLUGIN)
        project.plugins.apply(KOTLIN_ANDROID_PLUGIN)
        project.configure<AppExtension> {
            namespace = "test.app"
            defaultConfig {
                compileSdkVersion(32)
            }
        }
    }

    private fun configureAndroidLibrary(project: Project) {
        project.plugins.apply(ANDROID_LIBRARY_PLUGIN)
        project.plugins.apply(KOTLIN_ANDROID_PLUGIN)
        project.configure<LibraryExtension> {
            namespace = "test.lib"
            defaultConfig {
                compileSdkVersion(32)
            }
        }
    }
}
