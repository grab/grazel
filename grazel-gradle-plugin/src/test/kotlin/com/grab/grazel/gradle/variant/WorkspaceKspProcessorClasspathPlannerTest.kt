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

package com.grab.grazel.gradle.variant

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WorkspaceKspProcessorClasspathPlannerTest {
    @Test
    fun `plans processor roots from variant KSP role instead of ksp configuration names`() {
        val project = ProjectBuilder.builder().withName("library").build()
        val declaration = project.declarationConfiguration(
            name = "processorBucket",
            dependencyNotation = "com.example:main-processor:1.0"
        )
        val classpath = project.processorClasspath(
            name = "grazelMainKspClasspath",
            declaration
        )
        project.declarationConfiguration(
            name = "kspLooksRelevantButIsUnattached",
            dependencyNotation = "com.example:ignored-processor:1.0"
        )

        val inputs = WorkspaceKspProcessorClasspathPlanner.plan(
            migratableProjects = listOf(project),
            variantsByProject = mapOf(
                project to listOf(
                    variant(
                        project = project,
                        name = DEFAULT_VARIANT,
                        variantType = VariantType.AndroidBuild,
                        kspConfigurations = setOf(classpath)
                    )
                )
            )
        )

        assertEquals(listOf("grazelMainKspClasspath"), inputs.map { input -> input.processorClasspath.name })
        assertEquals(setOf("com.example:main-processor"), inputs.single().directDependencyShortIds)
        assertFalse("com.example:ignored-processor" in inputs.single().directDependencyShortIds)
    }

    @Test
    fun `dedupes shared classpaths and preserves test specific direct processors`() {
        val project = ProjectBuilder.builder().withName("library").build()
        val baseDeclaration = project.declarationConfiguration(
            name = "baseProcessors",
            dependencyNotation = "com.example:base-processor:1.0"
        )
        val testDeclaration = project.declarationConfiguration(
            name = "unitSpecProcessors",
            dependencyNotation = "com.example:test-processor:1.0"
        )
        val mainClasspath = project.processorClasspath("grazelMainKspClasspath", baseDeclaration)
        val testClasspath = project.processorClasspath("grazelTestKspClasspath", baseDeclaration, testDeclaration)

        val inputs = WorkspaceKspProcessorClasspathPlanner.plan(
            migratableProjects = listOf(project),
            variantsByProject = mapOf(
                project to listOf(
                    variant(
                        project = project,
                        name = DEFAULT_VARIANT,
                        variantType = VariantType.AndroidBuild,
                        kspConfigurations = setOf(mainClasspath)
                    ),
                    variant(
                        project = project,
                        name = "debugUnitTest",
                        variantType = VariantType.Test,
                        kspConfigurations = setOf(testClasspath)
                    ),
                    variant(
                        project = project,
                        name = "releaseUnitTest",
                        variantType = VariantType.Test,
                        kspConfigurations = setOf(testClasspath)
                    )
                )
            )
        )

        assertEquals(
            listOf("grazelMainKspClasspath", "grazelTestKspClasspath"),
            inputs.map { input -> input.processorClasspath.name }
        )
        assertEquals(
            setOf("com.example:base-processor"),
            inputs.single { input -> input.processorClasspath == mainClasspath }.directDependencyShortIds
        )
        assertEquals(
            setOf("com.example:base-processor", "com.example:test-processor"),
            inputs.single { input -> input.processorClasspath == testClasspath }.directDependencyShortIds
        )
    }

    @Test
    fun `skips variants whose KSP configuration cannot be read`() {
        val project = ProjectBuilder.builder().withName("library").build()

        val inputs = WorkspaceKspProcessorClasspathPlanner.plan(
            migratableProjects = listOf(project),
            variantsByProject = mapOf(
                project to listOf(
                    variantWithUnavailableKspConfiguration(
                        project = project,
                        name = DEFAULT_VARIANT,
                        variantType = VariantType.AndroidBuild
                    )
                )
            )
        )

        assertEquals(emptyList<WorkspaceKspProcessorClasspathInput>(), inputs)
    }

    private fun Project.declarationConfiguration(
        name: String,
        dependencyNotation: String
    ): Configuration {
        val configuration = configurations.create(name)
        dependencies.add(name, dependencyNotation)
        return configuration
    }

    private fun Project.processorClasspath(
        name: String,
        vararg declarations: Configuration
    ): Configuration {
        return configurations.create(name).apply {
            isCanBeResolved = true
            isCanBeConsumed = false
            setExtendsFrom(declarations.toSet())
        }
    }

    private fun variant(
        project: Project,
        name: String,
        variantType: VariantType,
        kspConfigurations: Set<Configuration>
    ): Variant<Any> {
        return object : Variant<Any> {
            override val name: String = name
            override val backingVariant: Any = Any()
            override val project: Project = project
            override val variantType: VariantType = variantType
            override val extendsFrom: Set<String> = emptySet()
            override val variantConfigurations: Set<Configuration> = emptySet()
            override val compileConfiguration: Set<Configuration> = emptySet()
            override val runtimeConfiguration: Set<Configuration> = emptySet()
            override val annotationProcessorConfiguration: Set<Configuration> = emptySet()
            override val kspConfiguration: Set<Configuration> = kspConfigurations
            override val kotlinCompilerPluginConfiguration: Set<Configuration> = emptySet()
        }
    }

    private fun variantWithUnavailableKspConfiguration(
        project: Project,
        name: String,
        variantType: VariantType
    ): Variant<Any> {
        return object : Variant<Any> {
            override val name: String = name
            override val backingVariant: Any = Any()
            override val project: Project = project
            override val variantType: VariantType = variantType
            override val extendsFrom: Set<String> = emptySet()
            override val variantConfigurations: Set<Configuration> = emptySet()
            override val compileConfiguration: Set<Configuration> = emptySet()
            override val runtimeConfiguration: Set<Configuration> = emptySet()
            override val annotationProcessorConfiguration: Set<Configuration> = emptySet()
            override val kspConfiguration: Set<Configuration>
                get() = error("KSP configuration is unavailable")
            override val kotlinCompilerPluginConfiguration: Set<Configuration> = emptySet()
        }
    }
}
