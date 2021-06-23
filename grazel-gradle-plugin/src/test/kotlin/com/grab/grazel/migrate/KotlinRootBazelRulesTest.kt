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
import com.grab.grazel.di.DaggerGrazelComponent
import com.grab.grazel.gradle.ANDROID_LIBRARY_PLUGIN
import com.grab.grazel.gradle.KOTLIN_ANDROID_PLUGIN
import com.grab.grazel.migrate.internal.RootBazelFileBuilder
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.repositories
import org.junit.Before
import org.junit.Test

class KotlinRootBazelRulesTest {
    private lateinit var rootProject: Project
    private lateinit var subProject: Project
    private lateinit var rootBazelFileBuilder: RootBazelFileBuilder

    @Before
    fun setup() {
        rootProject = buildProject("root")
        rootProject.extensions.add(GrazelExtension.GRAZEL_EXTENSION, GrazelExtension(rootProject))
        rootBazelFileBuilder = DaggerGrazelComponent.factory().create(rootProject).rootBazelFileBuilder()

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
    fun `assert by default custom toolchain is not registered in root BUILD`() {
        rootProject.configure<GrazelExtension> {
            // Default setup
        }
        val rootBazelFileContents = rootBazelFileBuilder.build().asString()
        Truth.assertThat(rootBazelFileContents).isEmpty()
    }

    @Test
    fun `assert custom toolchain parameters are reflected in root BUILD`() {
        rootProject.configure<GrazelExtension> {
            rules {
                kotlin {
                    toolchain {
                        enabled = true
                        abiJars = true
                        apiVersion = "1.3"
                        languageVersion = "1.3"
                        jvmTarget = "1.6"
                        multiplexWorkers = true
                        reportUnusedDeps = "warn"
                        strictKotlinDeps = "warn"
                    }
                }
            }
        }
        val rootBazelFileContents = rootBazelFileBuilder.build().asString()
        Truth.assertThat(rootBazelFileContents).apply {
            // Kotlin compiler options
            contains(
                """kt_kotlinc_options(
  name = "kt_kotlinc_options",
  x_use_ir = False
)"""
            )

            // Java compiler options
            contains(
                """kt_kotlinc_options(
  name = "kt_kotlinc_options",
  x_use_ir = False
)"""
            )

            // Toolchain parameters
            contains(
                """define_kt_toolchain(
  name = "kotlin_toolchain",
  api_version = "1.3",
  experimental_use_abi_jars = True,
  experimental_multiplex_workers = True,
  javac_options = "//:kt_javac_options",
  jvm_target = "1.6",
  kotlinc_options = "//:kt_kotlinc_options",
  language_version = "1.3",
  experimental_report_unused_deps = "warn",
  experimental_strict_kotlin_deps = "warn"
)"""
            )
        }
    }
}