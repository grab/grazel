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
import org.junit.Test

class VariantDependencyConfigurationRolesTest {
    @Test
    fun `declared dependency configurations preserve collector classification`() {
        val project = ProjectBuilder.builder().withName("library").build()
        val implementation = project.configuration("implementation")
        val gpsPaxImplementation = project.configuration("gpsPaxImplementation")
        val runtimeOnly = project.configuration("runtimeOnly")
        val customTool = project.configuration("customTool")
        val privateImplementation = project.configuration("_internalImplementation")
        val metadata = project.configuration("implementationDependenciesMetadata")
        val kapt = project.configuration("kaptDebug")
        val kotlinCompilerPlugin = project.configuration("kotlinCompilerPluginClasspath")
        val lint = project.configuration("lintChecks")

        val variant = variant(
            project = project,
            configurations = setOf(
                implementation,
                gpsPaxImplementation,
                runtimeOnly,
                customTool,
                privateImplementation,
                metadata,
                kapt,
                kotlinCompilerPlugin,
                lint
            )
        )

        assertEquals(
            setOf(implementation, gpsPaxImplementation, runtimeOnly),
            variant.declaredDependencyConfigurations
        )
    }

    @Test
    fun `compile only declared dependency configurations preserve collector classification`() {
        val project = ProjectBuilder.builder().withName("library").build()
        val compileOnly = project.configuration("compileOnly")
        val debugCompileOnly = project.configuration("debugCompileOnly")
        val metadata = project.configuration("debugCompileOnlyDependenciesMetadata")
        val implementation = project.configuration("implementation")

        val variant = variant(
            project = project,
            configurations = setOf(compileOnly, debugCompileOnly, metadata, implementation)
        )

        assertEquals(
            setOf(compileOnly, debugCompileOnly),
            variant.compileOnlyDeclaredDependencyConfigurations
        )
    }

    @Test
    fun `declaration bucket name preserves suffix stripping`() {
        val project = ProjectBuilder.builder().withName("library").build()

        assertEquals(DEFAULT_VARIANT, project.configuration("implementation").declarationBucketName())
        assertEquals("gpsPax", project.configuration("gpsPaxImplementation").declarationBucketName())
        assertEquals("freeDebugUnitTest", project.configuration("freeDebugUnitTestRuntimeOnly").declarationBucketName())
    }

    @Test
    fun `compile only bucket name is typed by variant kind`() {
        val project = ProjectBuilder.builder().withName("library").build()

        assertEquals("debug", variant(project, "debug", VariantType.AndroidBuild).compileOnlyBucketName)
        assertEquals(TEST_VARIANT, variant(project, "debugUnitTest", VariantType.Test).compileOnlyBucketName)
        assertEquals(
            ANDROID_TEST_VARIANT,
            variant(project, "debugAndroidTest", VariantType.AndroidTest).compileOnlyBucketName
        )
    }

    private fun Project.configuration(name: String): Configuration =
        configurations.create(name)

    private fun variant(
        project: Project,
        name: String = DEFAULT_VARIANT,
        variantType: VariantType = VariantType.AndroidBuild,
        configurations: Set<Configuration> = emptySet()
    ): Variant<Any> {
        return object : Variant<Any> {
            override val name: String = name
            override val backingVariant: Any = Any()
            override val project: Project = project
            override val variantType: VariantType = variantType
            override val extendsFrom: Set<String> = emptySet()
            override val variantConfigurations: Set<Configuration> = configurations
            override val compileConfiguration: Set<Configuration> = emptySet()
            override val runtimeConfiguration: Set<Configuration> = emptySet()
            override val annotationProcessorConfiguration: Set<Configuration> = emptySet()
            override val kspConfiguration: Set<Configuration> = emptySet()
            override val kotlinCompilerPluginConfiguration: Set<Configuration> = emptySet()
        }
    }
}
