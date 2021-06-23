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

import com.google.common.truth.Truth
import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.starlark.asString
import com.grab.grazel.buildProject
import com.grab.grazel.configuration.KotlinCompiler
import com.grab.grazel.di.DaggerGrazelComponent
import com.grab.grazel.gradle.ANDROID_LIBRARY_PLUGIN
import com.grab.grazel.gradle.KOTLIN_ANDROID_PLUGIN
import com.grab.grazel.migrate.internal.WorkspaceBuilder
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.repositories
import org.junit.Before
import org.junit.Test

class KotlinWorkspaceRulesTest {
    private lateinit var rootProject: Project
    private lateinit var subProject: Project

    private lateinit var workspaceFactory: WorkspaceBuilder.Factory

    @Before
    fun setup() {
        rootProject = buildProject("root")
        rootProject.extensions.add(GrazelExtension.GRAZEL_EXTENSION, GrazelExtension(rootProject))
        val grazelComponent = DaggerGrazelComponent.factory().create(rootProject)
        workspaceFactory = grazelComponent.workspaceBuilderFactory()

        subProject = buildProject("subproject", rootProject)
        subProject.run {
            plugins.apply {
                apply(ANDROID_LIBRARY_PLUGIN)
                apply(KOTLIN_ANDROID_PLUGIN)
            }
            repositories {
                mavenCentral()
                google()
                jcenter()
            }
            dependencies {
                add("implementation", "com.google.dagger:dagger:2.33")
            }
        }
    }

    @Test
    fun `assert default rule_kotlin repository and compiler in WORKSPACE`() {
        rootProject.configure<GrazelExtension> {
            // Default setup
        }
        val workspaceStatements = workspaceFactory.create(listOf(rootProject, subProject)).build().asString()
        Truth.assertThat(workspaceStatements).apply {
            // Default http archive
            contains(
                """http_archive(
  name = "io_bazel_rules_kotlin","""
            )

            // Compiler
            val kotlinCompiler = KotlinCompiler()
            contains(
                """
                    KOTLIN_VERSION = "${kotlinCompiler.version}"
                    KOTLINC_RELEASE_SHA = "${kotlinCompiler.sha}"
                    """.trimIndent()
            )
            contains("kotlin_repositories(compiler_release = KOTLINC_RELEASE)")

            // Toolchain
            contains(
                """
                    load("@io_bazel_rules_kotlin//kotlin:kotlin.bzl",  "kt_register_toolchains")
                    
                    kt_register_toolchains()
                    """.trimIndent()
            )
        }
    }

    @Test
    fun `assert custom repository registration in WORKSPACE`() {
        rootProject.configure<GrazelExtension> {
            rules {
                kotlin {
                    toolchain {
                        gitRepository {
                            commit = "eae21653baad4b403fee9e8a706c9d4fbd0c27c6"
                            remote = "https://github.com/bazelbuild/rules_kotlin.git"
                        }
                    }
                }
            }
        }
        val workspaceStatements = workspaceFactory.create(listOf(rootProject, subProject)).build().asString()
        Truth.assertThat(workspaceStatements).apply {
            contains(
                """git_repository(
  name = "io_bazel_rules_kotlin",
  commit = "eae21653baad4b403fee9e8a706c9d4fbd0c27c6",
  remote = "https://github.com/bazelbuild/rules_kotlin.git"
)"""
            )
        }
    }

    @Test
    fun `assert custom toolchain registration in WORKSPACE`() {
        rootProject.configure<GrazelExtension> {
            rules {
                kotlin {
                    toolchain {
                        enabled = true
                    }
                }
            }
        }
        val workspaceStatements = workspaceFactory.create(listOf(rootProject, subProject)).build().asString()
        Truth.assertThat(workspaceStatements).apply {
            contains("""register_toolchains("//:kotlin_toolchain")""")
            doesNotContain("kt_register_toolchains()")
        }
    }
}