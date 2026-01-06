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

import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.starlark.asString
import com.grab.grazel.buildProject
import com.grab.grazel.di.GrazelComponent
import com.grab.grazel.gradle.ANDROID_LIBRARY_PLUGIN
import com.grab.grazel.gradle.KOTLIN_ANDROID_PLUGIN
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.migrate.internal.WorkspaceBuilder
import com.grab.grazel.util.addGrazelExtension
import com.grab.grazel.util.createGrazelComponent
import com.grab.grazel.util.truth
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.repositories
import org.junit.Before
import org.junit.Test

class KotlinWorkspaceRulesTest {
    private lateinit var rootProject: Project
    private lateinit var subProject: Project

    private lateinit var grazelComponent: GrazelComponent
    private lateinit var workspaceFactory: WorkspaceBuilder.Factory

    @Before
    fun setup() {
        rootProject = buildProject("root")
        grazelComponent = rootProject.createGrazelComponent()
        rootProject.addGrazelExtension()
        workspaceFactory = grazelComponent.workspaceBuilderFactory().get()

        subProject = buildProject("subproject", rootProject)
        subProject.run {
            plugins.apply {
                apply(ANDROID_LIBRARY_PLUGIN)
                apply(KOTLIN_ANDROID_PLUGIN)
            }
            repositories {
                mavenCentral()
                google()
            }
        }
    }

    @Test
    fun `assert default rule_kotlin repository and compiler in WORKSPACE`() {
        val kotlinTag = "1.6.21"
        val kotlinSha = "somesha256"
        rootProject.configure<GrazelExtension> {
            rules.kotlin.compiler {
                tag = kotlinTag
                sha = kotlinSha
            }
        }
        generateWorkspace().truth {
            // Default http archive
            contains(
                """http_archive(
  name = "io_bazel_rules_kotlin","""
            )
            contains(
                """
                    KOTLIN_VERSION = "$kotlinTag"
                    KOTLINC_RELEASE_SHA = "$kotlinSha"
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
        generateWorkspace().truth {
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
        generateWorkspace().truth {
            contains("""register_toolchains("//:kotlin_toolchain")""")
            doesNotContain("kt_register_toolchains()")
        }
    }

    @Test
    fun `assert KSP compiler not generated when not configured`() {
        val kotlinTag = "1.6.21"
        val kotlinSha = "somesha256"
        rootProject.configure<GrazelExtension> {
            rules.kotlin.compiler {
                tag = kotlinTag
                sha = kotlinSha
            }
        }
        generateWorkspace().truth {
            contains("""KOTLIN_VERSION = "$kotlinTag"""")
            contains("""KOTLINC_RELEASE_SHA = "$kotlinSha"""")
            contains("kotlin_repositories(compiler_release = KOTLINC_RELEASE)")
            doesNotContain("KSP_VERSION")
            doesNotContain("KSP_COMPILER_RELEASE")
            doesNotContain("ksp_version")
            doesNotContain("ksp_compiler_release")
        }
    }

    @Test
    fun `assert KSP compiler generated when configured`() {
        val kotlinTag = "1.8.10"
        val kotlinSha = "kotlinsha256"
        val kspTag = "1.8.10-1.0.9"
        val kspSha = "kspsha256"
        rootProject.configure<GrazelExtension> {
            rules.kotlin {
                compiler {
                    tag = kotlinTag
                    sha = kotlinSha
                }
                kspCompiler {
                    tag = kspTag
                    sha = kspSha
                }
            }
        }
        generateWorkspace().truth {
            // Kotlin compiler vars
            contains("""KOTLIN_VERSION = "$kotlinTag"""")
            contains("""KOTLINC_RELEASE_SHA = "$kotlinSha"""")
            // KSP compiler vars
            contains("""KSP_VERSION = "$kspTag"""")
            contains("""KSP_COMPILER_RELEASE_SHA = "$kspSha"""")
            // Load statement should include ksp_version
            contains("ksp_version")
            // KSP_COMPILER_RELEASE assignment
            contains("KSP_COMPILER_RELEASE = ksp_version(")
            // kotlin_repositories should include ksp_compiler_release
            contains("ksp_compiler_release = KSP_COMPILER_RELEASE")
        }
    }

    fun generateWorkspace() = workspaceFactory
        .create(
            projectsToMigrate = listOf(rootProject, subProject),
            gradleProjectInfo = grazelComponent.gradleProjectInfoFactory().get()
                .create(WorkspaceDependencies(emptyMap()))
        ).build()
        .asString()
}