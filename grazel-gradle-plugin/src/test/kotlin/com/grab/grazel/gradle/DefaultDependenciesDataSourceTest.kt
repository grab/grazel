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

package com.grab.grazel.gradle

import com.android.build.gradle.AppExtension
import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.ConfigurationScope.BUILD
import com.grab.grazel.gradle.dependencies.BuildGraphType
import com.grab.grazel.gradle.dependencies.DefaultDependenciesDataSource
import com.grab.grazel.gradle.dependencies.DependencyResolutionService
import com.grab.grazel.gradle.dependencies.IGNORED_ARTIFACT_GROUPS
import com.grab.grazel.gradle.dependencies.MavenArtifact
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency.Companion.from
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.util.addGrazelExtension
import com.grab.grazel.util.createGrazelComponent
import com.grab.grazel.util.doEvaluate
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.repositories
import org.gradle.kotlin.dsl.the
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultDependenciesDataSourceTest {
    private lateinit var rootProject: Project
    private lateinit var androidProject: Project
    private lateinit var dependenciesDataSource: DefaultDependenciesDataSource
    private lateinit var dependencyResolutionService: DependencyResolutionService

    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private lateinit var projectDir: File

    fun configure(
        configureProject: Project.() -> Unit = {},
        grazelExtension: GrazelExtension.() -> Unit = {}
    ) {
        projectDir = temporaryFolder.newFolder("projecs")
        rootProject = buildProject("root", projectDir = projectDir).also { root ->
            root.addGrazelExtension(grazelExtension)
        }
        androidProject = buildProject("android", rootProject)
        with(androidProject) {
            with(plugins) {
                apply(ANDROID_APPLICATION_PLUGIN)
            }
            repositories {
                google()
                mavenCentral()
            }
            configure<AppExtension> {
                compileSdkVersion(30)
            }
            configureProject(this)

            dependencies {
                add(
                    "debugImplementation",
                    "com.android.support:appcompat-v7:28.0.0"
                )
                add(
                    "debugImplementation",
                    "com.android.support:animated-vector-drawable:28.0.0'"
                )
                add(
                    "implementation",
                    "com.google.dagger:dagger:2.37"
                )
            }
        }
        androidProject.doEvaluate()
        rootProject.createGrazelComponent().let { grazelComponent ->
            dependenciesDataSource = grazelComponent
                .dependenciesDataSource()
                .get() as DefaultDependenciesDataSource
            dependencyResolutionService = grazelComponent
                .dependencyResolutionService()
                .get().apply {
                    populateCache(
                        workspaceDependencies = WorkspaceDependencies(
                            result = buildMap {
                                put(
                                    "debug", listOf(
                                        from("com.android.support:appcompat-v7:28.0.0:debug"),
                                    )
                                )
                            }
                        )
                    )
                }
        }
    }


    @Test
    fun `assert first level module dependencies have default embedded artifacts excluded from them`() {
        configure()
        assertTrue(
            "First level module dependencies does not contain embedded artifacts",
            dependenciesDataSource
                .firstLevelModuleDependencies(androidProject)
                .none { it.moduleGroup in IGNORED_ARTIFACT_GROUPS })
    }

    @Test
    fun `assert dependencyArtifactMap returns artifact and corresponding artifact file`() {
        configure()
        val dependencyArtifactMap = dependenciesDataSource.dependencyArtifactMap(
            rootProject,
            "aar"
        )
        // assert only valid files are returned
        assertTrue("Only valid files are returned") {
            dependencyArtifactMap.values.all {
                it.extension == "aar" && it.exists()
            }
        }
        // assert valid maven coordinates
        assertTrue("Valid maven artifact ids are returned") {
            listOf(
                // We expect force version since dependency resolution happens
                "com.android.support:appcompat-v7:28.0.0",
                "com.android.support:cursoradapter:28.0.0"
            ).all { dep -> dep in dependencyArtifactMap.keys.map(MavenArtifact::toString) }
        }
    }

    @Test
    fun `assert collectMavenDeps returns variant specific classpath`() {
        configure()
        val debugVariant = androidProject.the<AppExtension>()
            .applicationVariants
            .first { it.name == "debug" }!!
        val deps = dependenciesDataSource.collectMavenDeps(
            androidProject,
            BuildGraphType(BUILD, debugVariant)
        )
        assertTrue(deps.size == 3, "collectMavenDeps returns variant specific classpath")
    }

    private fun assertCollectMavenDeps(
        grazel: GrazelExtension.() -> Unit = {},
        assertions: (List<BazelDependency>) -> Unit = {}
    ) {
        configure(grazelExtension = grazel)
        val debugVariant = androidProject.the<AppExtension>()
            .applicationVariants
            .first { it.name == "debug" }!!
        val deps = dependenciesDataSource.collectMavenDeps(
            androidProject,
            BuildGraphType(BUILD, debugVariant)
        )
        assertEquals(2, deps.size, "collectMavenDeps respects ignore list")
        assertEquals(
            "@maven//:com_android_support_animated_vector_drawable", deps.first().toString()
        )
        assertions(deps)
    }

    @Test
    fun `assert collectMavenDeps respects ignore list`() {
        assertCollectMavenDeps(grazel = {
            dependencies {
                ignoreArtifacts.add("com.android.support:appcompat-v7")
            }
        })
    }

    @Test
    fun `assert collectMavenDeps respects exclude list`() {
        assertCollectMavenDeps(grazel = {
            rules {
                mavenInstall {
                    excludeArtifacts.add("com.android.support:appcompat-v7")
                }
            }
        })
    }

    @Test
    fun `assert Dagger deps are replaced with target`() {
        assertCollectMavenDeps(
            grazel = {
                dependencies {
                    ignoreArtifacts.add("com.android.support:appcompat-v7")
                }
            }, assertions = { deps ->
                assertTrue(deps.any { it.toString() == "//:dagger" })
                assertTrue(deps.none { "com.google.dagger" in it.toString() })
            })
    }
}