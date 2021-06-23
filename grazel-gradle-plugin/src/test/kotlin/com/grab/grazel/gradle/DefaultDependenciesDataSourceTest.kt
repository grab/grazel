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

package com.grab.grazel.gradle

import com.android.build.gradle.LibraryExtension
import com.google.common.truth.Truth
import com.grab.grazel.GrazelPluginTest
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.dependencies.*
import com.grab.grazel.util.FLAVOR1
import com.grab.grazel.util.FLAVOR2
import com.grab.grazel.util.FakeAndroidBuildVariantDataSource
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project
import org.gradle.kotlin.dsl.repositories
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFails

private const val IMPLEMENTATION = "implementation"

private const val APP_COMPAT = "androidx.appcompat:appcompat:%s"
private const val CONSTRAINT_LAYOUT = "androidx.constraintlayout:constraintlayout:%s"
private const val DAGGER = "com.google.dagger:dagger:%s"
private const val KOTLIN_STDLIB = "org.jetbrains.kotlin:kotlin-stdlib"

private const val APP_COMPAT_VERSION = "1.1.0"
private const val CL_VERSION = "2.0.2"
private const val DAGGER_VERSION = "2.28"

private const val APP_COMPAT_FORCE_VERSION = "1.1.0"
private const val CL_FORCE_VERSION = "1.1.3"
private const val DAGGER_FORCE_VERSION = "2.29"

private const val ROOT_PROJECT_NAME = "root"
private const val FLAVOR1_PROJECT_NAME = "flavor1"
private const val FLAVOR2_PROJECT_NAME = "flavor2"
private const val SUB_PROJECT_NAME = "subproject"

class DefaultDependenciesDataSourceTest : GrazelPluginTest() {

    private lateinit var rootProject: Project
    private lateinit var subProject: Project
    private lateinit var flavor1Project: Project
    private lateinit var flavor2Project: Project
    private lateinit var dependenciesDataSource: DefaultDependenciesDataSource
    private lateinit var repositoryDataSource: RepositoryDataSource
    private lateinit var fakeVariantDataSource: FakeAndroidBuildVariantDataSource
    private lateinit var configurationDataSource: DefaultConfigurationDataSource

    @Before
    fun setUp() {
        rootProject = buildProject(ROOT_PROJECT_NAME)
        subProject = buildProject(SUB_PROJECT_NAME, rootProject)

        flavor1Project = buildProject(FLAVOR1_PROJECT_NAME, rootProject)
        flavor2Project = buildProject(FLAVOR2_PROJECT_NAME, rootProject)

        fakeVariantDataSource = FakeAndroidBuildVariantDataSource()
        configurationDataSource = DefaultConfigurationDataSource(fakeVariantDataSource)
        repositoryDataSource = DefaultRepositoryDataSource(rootProject)

        dependenciesDataSource = DefaultDependenciesDataSource(
            rootProject = rootProject,
            configurationDataSource = configurationDataSource,
            artifactsConfig = ArtifactsConfig(ignoredList = listOf(KOTLIN_STDLIB)),
            repositoryDataSource = repositoryDataSource,
            dependencyResolutionService = DefaultDependencyResolutionService.register(rootProject)
        )
    }

    private fun resolveConfigurations() {
        // Force resolution
        subProject.configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
    }

    @Test
    fun `when resolved artifacts is called for list of projects, assert correct artifacts are returned`() {
        setUpDepsWithResolutionStrategy()
        resolveConfigurations()
        val resolvedArtifacts = dependenciesDataSource.resolvedArtifactsFor(
            listOf(rootProject, subProject)
        )

        // Even though APP_COMPAT was declared APP_COMPAT_FORCE_VERSION should be used due to forced module
        assertTrue(resolvedArtifacts.contains(APP_COMPAT.format(APP_COMPAT_FORCE_VERSION)))
    }

    @Test
    fun `when resolved artifacts is called for list of projects with override versions, assert correct artifacts are returned`() {
        setUpDepsWithResolutionStrategy()
        resolveConfigurations()
        val overrideVersion = "1.1.2"
        val resolvedArtifacts = dependenciesDataSource.resolvedArtifactsFor(
            projects = listOf(rootProject, subProject),
            overrideArtifactVersions = listOf(CONSTRAINT_LAYOUT.format(overrideVersion))
        )
        // Assert that user overriden version is used instead of automatically detected version
        assertTrue(resolvedArtifacts.contains(CONSTRAINT_LAYOUT.format(overrideVersion)))

        // Assert malformed entry in override repository fails
        assertFails(message = "invalid:syntax is not a proper maven coordinate, please ensure version is correctly specified") {
            dependenciesDataSource.resolvedArtifactsFor(
                projects = emptyList(),
                overrideArtifactVersions = listOf("invalid:syntax")
            )
        }
    }

    @Test
    fun `when project contains duplicate artifacts, assert only forced versions are returned`() {
        setUpDepsWithResolutionStrategy()
        resolveConfigurations()
        val resolvedArtifacts = dependenciesDataSource.resolvedArtifactsFor(
            listOf(rootProject, subProject)
        )
        assertTrue(resolvedArtifacts.contains(DAGGER.format(DAGGER_FORCE_VERSION)))
        assertFalse(resolvedArtifacts.contains(DAGGER.format(DAGGER_VERSION)))
    }

    @Test
    fun `when no filter applied, assert all project deps should be returned`() {
        setUpFlavorModulesDep()
        val deps = dependenciesDataSource.projectDependencies(subProject)
        assertTrue(deps.any { it.second.name == FLAVOR1_PROJECT_NAME })
        assertTrue(deps.any { it.second.name == FLAVOR2_PROJECT_NAME })
    }

    @Test
    fun `when filter variants applied, assert ignored variants project deps should not returned`() {
        setUpFlavorModulesDep()
        fakeVariantDataSource.ignoreFlavorsName = listOf(FLAVOR1)
        val deps = dependenciesDataSource.projectDependencies(subProject)
        assertFalse(deps.any { it.second.name == FLAVOR1_PROJECT_NAME })
        assertTrue(deps.any { it.second.name == FLAVOR2_PROJECT_NAME })
    }

    @Test
    fun `when filter variants applied, assert ignored variants maven deps should not returned`() {
        setUpFlavorModulesDep()
        fakeVariantDataSource.ignoreFlavorsName = listOf(FLAVOR2)
        fakeVariantDataSource.ignoreVariantName = listOf("release" to null)
        val deps = dependenciesDataSource.mavenDependencies(subProject)
        assertTrue(deps.toList().size == 2)
        assertTrue(deps.any { APP_COMPAT.contains(it.name) })
        assertTrue(deps.any { DAGGER.contains(it.name) })
        assertFalse(deps.any { CONSTRAINT_LAYOUT.contains(it.name) })
    }


    @Test
    fun `when no filter applied, assert all maven deps should be returned`() {
        setUpFlavorModulesDep()
        val deps = dependenciesDataSource.mavenDependencies(subProject)
        assertTrue(deps.toList().size == 3)
        assertTrue(deps.any { APP_COMPAT.contains(it.name) })
        assertTrue(deps.any { CONSTRAINT_LAYOUT.contains(it.name) })
        assertTrue(deps.any { DAGGER.contains(it.name) })
    }

    @Test
    fun `when ignore artifacts is given, assert artifact is excluded in resolved and declared dependencies`() {
        setUpDepsWithResolutionStrategy()
        resolveConfigurations()
        val resolvedArtifacts = dependenciesDataSource.resolvedArtifactsFor(listOf(subProject))
        val declaredArtifacts = dependenciesDataSource.mavenDependencies(subProject)
        assertTrue(
            "Resolved artifacts does not contain ignored artifact",
            resolvedArtifacts.none { it.contains(KOTLIN_STDLIB) }
        )
        assertTrue(
            "Declared artifacts does not contain ignore artifact",
            declaredArtifacts.none { MavenArtifact(it.group, it.name).id.contains(KOTLIN_STDLIB) })
    }

    @Test
    fun `assert first level module dependencies have default embedded artifacts excluded from them`() {
        setUpDepsWithResolutionStrategy()
        resolveConfigurations()
        assertTrue(
            "First level module dependencies does not contain embedded artifacts",
            dependenciesDataSource.firstLevelModuleDependencies(subProject)
                .none { DEP_GROUP_EMBEDDED_BY_RULES.contains(it.moduleGroup) })
    }

    @Test
    fun `assert hasIgnoredArtifacts returns true when a project has ignored artifacts`() {
        setUpDepsWithResolutionStrategy()
        resolveConfigurations()
        dependenciesDataSource = DefaultDependenciesDataSource(
            rootProject = rootProject,
            configurationDataSource = configurationDataSource,
            artifactsConfig = ArtifactsConfig(ignoredList = listOf(DAGGER.split(":%s").first())),
            repositoryDataSource = repositoryDataSource,
            dependencyResolutionService = DefaultDependencyResolutionService.register(rootProject)
        )
        assertTrue(
            "hasIgnoredArtifacts returns true when project contains any ignored artifacts",
            dependenciesDataSource.hasIgnoredArtifacts(subProject)
        )
    }

    @Test
    fun `assert hasIgnoredArtifacts does not consider embedded artifacts as ignored artifacts`() {
        setUpDepsWithResolutionStrategy()
        resolveConfigurations()
        assertFalse(
            "hasIgnoredArtifacts does not consider embedded artifacts for calculation ",
            dependenciesDataSource.hasIgnoredArtifacts(subProject)
        )
    }

    @Test
    fun `assert excluded artifacts are filtered in resolveArtifactsFor and mavenDeps`() {
        setUpDepsWithResolutionStrategy()
        resolveConfigurations()
        val excludedDagger = DAGGER.split(":%s").first()
        dependenciesDataSource = DefaultDependenciesDataSource(
            rootProject = rootProject,
            configurationDataSource = configurationDataSource,
            artifactsConfig = ArtifactsConfig(excludedList = listOf(excludedDagger)),
            repositoryDataSource = repositoryDataSource,
            dependencyResolutionService = DefaultDependencyResolutionService.register(rootProject)
        )
        val resolvedArtifacts = dependenciesDataSource.resolvedArtifactsFor(
            listOf(rootProject, subProject)
        )
        Truth.assertThat(resolvedArtifacts).doesNotContain(excludedDagger)
        assertTrue(dependenciesDataSource.mavenDependencies(subProject).none { DAGGER.contains(it.name) })
    }


    private fun setUpFlavorModulesDep() {
        // sub -> flavor1
        //  | -> flavor2
        subProject.run {
            plugins.apply {
                apply(ANDROID_LIBRARY_PLUGIN)
            }

            extensions.configure<LibraryExtension> {
                flavorDimensions("service")
                productFlavors {
                    create(FLAVOR1) {
                        dimension = "service"
                    }
                    create(FLAVOR2) {
                        dimension = "service"
                    }
                }
            }
            repositories {
                mavenCentral()
                google()
                jcenter()
            }
            dependencies {
                add("$FLAVOR1${IMPLEMENTATION.capitalize()}", project(":$FLAVOR1_PROJECT_NAME"))
                add("$FLAVOR2${IMPLEMENTATION.capitalize()}", project(":$FLAVOR2_PROJECT_NAME"))
                add(IMPLEMENTATION, DAGGER.format(DAGGER_FORCE_VERSION))
                add("$FLAVOR1${IMPLEMENTATION.capitalize()}", APP_COMPAT.format(APP_COMPAT_FORCE_VERSION))
                add("release${IMPLEMENTATION.capitalize()}", CONSTRAINT_LAYOUT.format(CL_FORCE_VERSION))
            }
        }
    }

    private fun setUpDepsWithResolutionStrategy() {
        subProject.run {
            plugins.apply {
                apply(KOTLIN_PLUGIN)
            }
            repositories {
                google()
                jcenter()
            }
            configurations.configureEach {
                resolutionStrategy {
                    setForcedModules(
                        arrayOf(
                            APP_COMPAT.format(APP_COMPAT_FORCE_VERSION),
                            CONSTRAINT_LAYOUT.format(CL_FORCE_VERSION),
                            DAGGER.format(DAGGER_FORCE_VERSION)
                        )
                    )
                }
            }
            dependencies {
                add(IMPLEMENTATION, APP_COMPAT.format(APP_COMPAT_VERSION))
                add(IMPLEMENTATION, CONSTRAINT_LAYOUT.format(CL_VERSION))
                add(IMPLEMENTATION, DAGGER.format(DAGGER_VERSION))
                add(IMPLEMENTATION, "$KOTLIN_STDLIB:1.3.72")
            }
        }
    }
}