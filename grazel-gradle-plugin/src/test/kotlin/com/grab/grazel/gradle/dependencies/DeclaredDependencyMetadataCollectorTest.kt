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

import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.dependencies.model.ExcludeRule
import com.grab.grazel.gradle.variant.Variant
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.Test as UnitTest
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

class DeclaredDependencyMetadataCollectorTest {

    @Test
    fun `declared metadata excludes tool dependency buckets from main declarations`() {
        val project = ProjectBuilder.builder().withName("library").build()
        val implementation = project.configurations.create("implementation")
        val customTool = project.configurations.create("customTool")
        val kotlinExtension = project.configurations.create("kotlin-extension")
        val kotlinCompilerPluginClasspath = project.configurations.create("kotlinCompilerPluginClasspath")
        val annotationProcessor = project.configurations.create("annotationProcessor")

        project.dependencies.add("implementation", "com.example:runtime:1.0")
        project.dependencies.add("customTool", "com.example:custom-tool:1.0")
        project.dependencies.add("kotlin-extension", "androidx.compose.compiler:compiler:1.8.3")
        project.dependencies.add("kotlinCompilerPluginClasspath", "com.example:kotlin-plugin:1.0")
        project.dependencies.add("annotationProcessor", "com.example:processor:1.0")

        val metadata = DeclaredDependencyMetadataCollector().collect(
            variantsByProject = mapOf(
                project to listOf(
                    variant(
                        project = project,
                        variantConfigurations = setOf(
                            implementation,
                            customTool,
                            kotlinExtension,
                            kotlinCompilerPluginClasspath,
                            annotationProcessor
                        )
                    )
                )
            ),
            projects = listOf(project)
        )

        assertEquals(
            setOf("com.example:runtime:1.0"),
            metadata.projects.getValue(project.path)
                .variants
                .single()
                .declaredDependencies
        )
    }

    @Test
    fun `declared metadata preserves dependency configuration owner bucket`() {
        val project = ProjectBuilder.builder().withName("library").build()
        val gpsPaxImplementation = project.configurations.create("gpsPaxImplementation")

        project.dependencies.add("gpsPaxImplementation", "com.example:combo:1.0")

        val metadata = DeclaredDependencyMetadataCollector().collect(
            variantsByProject = mapOf(
                project to listOf(
                    variant(
                        project = project,
                        name = "gps",
                        variantConfigurations = setOf(gpsPaxImplementation)
                    )
                )
            ),
            projects = listOf(project)
        )

        assertEquals(
            setOf(
                DeclaredExternalDependency(
                    configurationName = "gpsPaxImplementation",
                    bucketName = "gpsPax",
                    id = "com.example:combo:1.0"
                )
            ),
            metadata.projects.getValue(project.path)
                .variants
                .single()
                .declaredDependencyDeclarations
        )
    }

    @Test
    fun `declared test dependencies keep concrete variant buckets`() {
        val project = ProjectBuilder.builder().withName("library").build()
        val freeDebugUnitTestImplementation = project.configurations.create("freeDebugUnitTestImplementation")
        val freeDebugAndroidTestImplementation = project.configurations.create("freeDebugAndroidTestImplementation")

        project.dependencies.add("freeDebugUnitTestImplementation", "com.example:unit-test:1.0")
        project.dependencies.add("freeDebugAndroidTestImplementation", "com.example:android-test:1.0")

        val metadata = DeclaredDependencyMetadataCollector().collect(
            variantsByProject = mapOf(
                project to listOf(
                    variant(
                        project = project,
                        name = "freeDebugUnitTest",
                        variantType = UnitTest,
                        extendsFrom = setOf("test", "free", "debug"),
                        variantConfigurations = setOf(freeDebugUnitTestImplementation)
                    ),
                    variant(
                        project = project,
                        name = "freeDebugAndroidTest",
                        variantType = AndroidTest,
                        extendsFrom = setOf("androidTest", "test", "free", "debug"),
                        variantConfigurations = setOf(freeDebugAndroidTestImplementation)
                    )
                )
            ),
            projects = listOf(project)
        )

        val declaredTestDeps = metadata.collectDeclaredTestDependenciesByProjectBucket(
            projectPaths = listOf(project.path)
        )

        assertEquals(
            setOf(ProjectDependencyBucket(project.path, "freeDebugUnitTest")),
            declaredTestDeps
                .filterValues { dependencies -> "com.example:unit-test" in dependencies.keys }
                .keys
        )
        assertEquals(
            setOf(ProjectDependencyBucket(project.path, "freeDebugAndroidTest")),
            declaredTestDeps
                .filterValues { dependencies -> "com.example:android-test" in dependencies.keys }
                .keys
        )
    }

    @Test
    fun `declared dependency bucket maps are sorted by short id`() {
        val project = ProjectBuilder.builder().withName("library").build()
        val implementation = project.configurations.create("implementation")

        project.dependencies.add("implementation", "com.example:zeta:1.0")
        project.dependencies.add("implementation", "com.example:alpha:1.0")

        val metadata = DeclaredDependencyMetadataCollector().collect(
            variantsByProject = mapOf(
                project to listOf(
                    variant(
                        project = project,
                        variantConfigurations = setOf(implementation)
                    )
                )
            ),
            projects = listOf(project)
        )

        val bucketDependencies = metadata.collectDeclaredMainDependenciesByProjectBucket(
            projectPaths = listOf(project.path)
        )

        assertEquals(
            listOf("com.example:alpha", "com.example:zeta"),
            bucketDependencies
                .getValue(ProjectDependencyBucket(project.path, DEFAULT_VARIANT))
                .keys
                .toList()
        )
    }

    @Test
    fun `declared dependency bucketing ignores legacy id-only metadata`() {
        val metadata = DeclaredDependencyMetadata(
            projects = mapOf(
                ":library" to ProjectDeclaredDependencyMetadata(
                    projectType = DeclaredProjectType.OTHER,
                    variants = listOf(
                        DeclaredVariantDependencyMetadata(
                            name = DEFAULT_VARIANT,
                            variantType = VariantType.AndroidBuild,
                            extendsFrom = emptySet(),
                            variantConfigurationNames = emptySet(),
                            compileConfigurationNames = emptySet(),
                            runtimeConfigurationNames = emptySet(),
                            kspConfigurationNames = emptySet(),
                            androidLeafVariant = false,
                            buildType = null,
                            productFlavors = emptyList(),
                            declaredDependencies = setOf("com.example:legacy:1.0"),
                            declaredDependencyDeclarations = emptySet(),
                            declaredProjectDependencies = emptySet(),
                            excludeRulesByShortId = emptyMap(),
                            compileOnlyBucketName = DEFAULT_VARIANT,
                            compileOnlyDependenciesByShortId = emptyMap()
                        )
                    )
                )
            )
        )

        assertEquals(
            emptyMap<ProjectDependencyBucket, Map<String, ResolvedDependency>>(),
            metadata.collectDeclaredMainDependenciesByProjectBucket(listOf(":library"))
        )
    }

    @Test
    fun `configuration exclude metadata intersects duplicate declarations`() {
        val project = ProjectBuilder.builder().withName("library").build()
        val implementation = project.configurations.create("implementation")
        val commonRule = ExcludeRule("com.example", "common-blocked")
        val firstOnlyRule = ExcludeRule("com.example", "first-only-blocked")
        val secondOnlyRule = ExcludeRule("com.example", "second-only-blocked")

        val firstDependency = addExternalModuleDependency(
            project = project,
            configurationName = "implementation",
            dependencyNotation = "com.example:library:1.0"
        )
        firstDependency.exclude(mapOf("group" to commonRule.group, "module" to commonRule.artifact))
        firstDependency.exclude(mapOf("group" to firstOnlyRule.group, "module" to firstOnlyRule.artifact))
        val secondDependency = addExternalModuleDependency(
            project = project,
            configurationName = "implementation",
            dependencyNotation = "com.example:library:2.0"
        )
        secondDependency.exclude(mapOf("group" to commonRule.group, "module" to commonRule.artifact))
        secondDependency.exclude(mapOf("group" to secondOnlyRule.group, "module" to secondOnlyRule.artifact))

        assertEquals(
            mapOf("com.example:library" to setOf(commonRule)),
            implementation.extractExcludeRulesByShortId()
        )
        assertEquals(
            mapOf("com.example:library" to setOf(commonRule)),
            implementation.extractDeclaredExcludeRulesByShortId()
        )
    }

    @Test
    fun `declared exclude metadata ignores non declaration configurations`() {
        val project = ProjectBuilder.builder().withName("library").build()
        val implementation = project.configurations.create("implementation")
        val customTool = project.configurations.create("customTool")
        val rule = ExcludeRule("com.example", "blocked")
        val toolRule = ExcludeRule("com.example", "tool-blocked")

        val declaredDependency = addExternalModuleDependency(
            project = project,
            configurationName = "implementation",
            dependencyNotation = "com.example:library:1.0"
        )
        declaredDependency.exclude(mapOf("group" to rule.group, "module" to rule.artifact))
        val toolDependency = addExternalModuleDependency(
            project = project,
            configurationName = "customTool",
            dependencyNotation = "com.example:tool:1.0"
        )
        toolDependency.exclude(mapOf("group" to toolRule.group, "module" to toolRule.artifact))

        val metadata = DeclaredDependencyMetadataCollector().collect(
            variantsByProject = mapOf(
                project to listOf(
                    variant(
                        project = project,
                        variantConfigurations = setOf(implementation, customTool)
                    )
                )
            ),
            projects = listOf(project)
        )

        assertEquals(
            mapOf("com.example:library" to setOf(rule)),
            metadata.projects.getValue(project.path)
                .variants
                .single()
                .excludeRulesByShortId
        )
    }

    private fun variant(
        project: Project,
        variantConfigurations: Set<Configuration>,
        name: String = DEFAULT_VARIANT,
        variantType: VariantType = VariantType.AndroidBuild,
        extendsFrom: Set<String> = emptySet()
    ): Variant<Any> {
        return object : Variant<Any> {
            override val name: String = name
            override val backingVariant: Any = Any()
            override val project: Project = project
            override val variantType: VariantType = variantType
            override val extendsFrom: Set<String> = extendsFrom
            override val variantConfigurations: Set<Configuration> = variantConfigurations
            override val compileConfiguration: Set<Configuration> = emptySet()
            override val runtimeConfiguration: Set<Configuration> = emptySet()
            override val annotationProcessorConfiguration: Set<Configuration> = emptySet()
            override val kspConfiguration: Set<Configuration> = emptySet()
            override val kotlinCompilerPluginConfiguration: Set<Configuration> = emptySet()
        }
    }

    private fun addExternalModuleDependency(
        project: Project,
        configurationName: String,
        dependencyNotation: Any
    ): ExternalModuleDependency {
        return project.dependencies.add(configurationName, dependencyNotation) as ExternalModuleDependency
    }
}
