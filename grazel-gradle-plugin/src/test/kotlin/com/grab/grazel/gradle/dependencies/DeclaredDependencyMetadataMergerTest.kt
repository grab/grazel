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

import com.grab.grazel.gradle.variant.Variant
import com.grab.grazel.gradle.variant.VariantType
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

class DeclaredDependencyMetadataMergerTest {
    @Test
    fun `merge sorts project metadata deterministically`() {
        val app = projectMetadata("debug")
        val lib = projectMetadata("release")

        val forward = DeclaredDependencyMetadataMerger.merge(
            listOf(":app" to app, ":lib" to lib)
        )
        val reverse = DeclaredDependencyMetadataMerger.merge(
            listOf(":lib" to lib, ":app" to app)
        )

        assertEquals(listOf(":app", ":lib"), forward.projects.keys.toList())
        assertEquals(forward, reverse)
    }

    @Test
    fun `merge shard files keeps project metadata deterministic`() {
        val app = DeclaredDependencyMetadata(projects = mapOf(":app" to projectMetadata("debug")))
        val lib = DeclaredDependencyMetadata(projects = mapOf(":lib" to projectMetadata("release")))

        val forward = DeclaredDependencyMetadataMerger.mergeShards(listOf(app, lib))
        val reverse = DeclaredDependencyMetadataMerger.mergeShards(listOf(lib, app))

        assertEquals(listOf(":app", ":lib"), forward.projects.keys.toList())
        assertEquals(forward, reverse)
    }

    @Test
    fun `project metadata plan freezes variant callback collections`() {
        val project = ProjectBuilder.builder().withName("library").build()
        val variants = mutableListOf(
            variant(project = project, name = "debug"),
            variant(project = project, name = "release")
        )

        val plan = DeclaredProjectMetadataPlanner.plan(
            projects = listOf(project),
            variantsByProject = mapOf(project to variants)
        )
        variants += variant(project = project, name = "lateVariant")

        assertEquals(
            listOf("debug", "release"),
            plan.single().variants.map { variant -> variant.name }
        )
    }

    private fun projectMetadata(variantName: String): ProjectDeclaredDependencyMetadata {
        return ProjectDeclaredDependencyMetadata(
            projectType = DeclaredProjectType.OTHER,
            variants = listOf(
                DeclaredVariantDependencyMetadata(
                    name = variantName,
                    variantType = VariantType.AndroidBuild,
                    extendsFrom = emptySet(),
                    variantConfigurationNames = emptySet(),
                    compileConfigurationNames = emptySet(),
                    runtimeConfigurationNames = emptySet(),
                    kspConfigurationNames = emptySet(),
                    androidLeafVariant = false,
                    buildType = null,
                    productFlavors = emptyList(),
                    declaredDependencies = emptySet(),
                    declaredDependencyDeclarations = emptySet(),
                    declaredProjectDependencies = emptySet(),
                    excludeRulesByShortId = emptyMap(),
                    compileOnlyBucketName = variantName,
                    compileOnlyDependenciesByShortId = emptyMap()
                )
            )
        )
    }

    private fun variant(
        project: Project,
        name: String
    ): Variant<Any> {
        return object : Variant<Any> {
            override val name: String = name
            override val backingVariant: Any = Any()
            override val project: Project = project
            override val variantType: VariantType = VariantType.AndroidBuild
            override val extendsFrom: Set<String> = emptySet()
            override val variantConfigurations: Set<Configuration> = emptySet()
            override val compileConfiguration: Set<Configuration> = emptySet()
            override val runtimeConfiguration: Set<Configuration> = emptySet()
            override val annotationProcessorConfiguration: Set<Configuration> = emptySet()
            override val kspConfiguration: Set<Configuration> = emptySet()
            override val kotlinCompilerPluginConfiguration: Set<Configuration> = emptySet()
        }
    }
}
