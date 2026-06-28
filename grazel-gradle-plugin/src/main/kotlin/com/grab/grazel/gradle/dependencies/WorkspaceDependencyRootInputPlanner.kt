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

import com.grab.grazel.gradle.isAndroidApplication
import com.grab.grazel.gradle.isAndroidTest
import com.grab.grazel.gradle.variant.ANDROID_TEST_VARIANT
import com.grab.grazel.gradle.variant.TEST_VARIANT
import com.grab.grazel.gradle.variant.Variant
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.variant.isWorkspaceAndroidLeaf
import com.grab.grazel.gradle.variant.isWorkspaceMainHierarchyRoot
import com.grab.grazel.gradle.variant.workspaceBuildTypeName
import com.grab.grazel.gradle.variant.workspaceProductFlavorNames
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalDependency

internal data class WorkspaceDependencyRootInput(
    val project: Project,
    val kind: AggregatedDependencyRootKind,
    val bucketName: String?,
    val metadataVariant: Variant<*>?,
    val variantType: VariantType?,
    val configuration: Configuration,
    val traverseProjectNodes: Boolean,
    val targetBuckets: Set<String> = emptySet(),
    val directDependencyShortIds: Set<String> = emptySet()
) {
    fun toMetadata(): AggregatedDependencyRootMetadata {
        return AggregatedDependencyRootMetadata(
            projectPath = project.path,
            kind = kind,
            configurationName = configuration.name,
            bucketName = bucketName,
            leafName = metadataVariant?.name,
            variantNames = (
                listOfNotNull(metadataVariant?.name) +
                    metadataVariant?.extendsFrom.orEmpty()
                ).toSortedSet(),
            variantType = variantType,
            buildType = metadataVariant?.workspaceBuildTypeName,
            productFlavors = metadataVariant?.workspaceProductFlavorNames.orEmpty(),
            targetBuckets = targetBuckets.toSortedSet(),
            traverseProjectNodes = traverseProjectNodes,
            directDependencyShortIds = directDependencyShortIds.toSortedSet(),
            rootExcludeRulesByShortId = configuration.extractExcludeRulesByShortId()
        )
    }
}

internal object WorkspaceDependencyRootInputPlanner {
    fun plan(
        migratableProjects: Iterable<Project>,
        variantsByProject: Map<Project, Iterable<Variant<*>>>
    ): List<WorkspaceDependencyRootInput> {
        val sortedProjects = migratableProjects.sortedBy { project -> project.path }
        val rootInputs = mutableListOf<WorkspaceDependencyRootInput>()

        sortedProjects
            .filter { project -> project.isAndroidApplication || project.isAndroidTest }
            .forEach { project ->
                rootInputs += planBinaryProjectRoots(
                    project = project,
                    variants = variantsByProject[project] ?: emptyList()
                )
            }

        sortedProjects.forEach { project ->
            rootInputs += planLintRoots(
                project = project,
                variants = variantsByProject[project] ?: emptyList()
            )
        }

        return rootInputs.filter { rootInput -> rootInput.configuration.isCanBeResolved }
    }

    private fun planBinaryProjectRoots(
        project: Project,
        variants: Iterable<Variant<*>>
    ): List<WorkspaceDependencyRootInput> {
        val standaloneTestProject = project.isAndroidTest
        val sortedVariants = variants.sortedForWorkspaceDependencyInputs()

        return buildList {
            sortedVariants
                .filter { variant ->
                    variant.variantType == VariantType.AndroidBuild &&
                        variant.isWorkspaceMainHierarchyRoot
                }
                .forEach { variant ->
                    addVariantRoots(
                        project = project,
                        variant = variant,
                        kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                        bucketName = variant.name,
                        targetBuckets = variant.targetBuckets(standaloneTestProject)
                    )
                }

            sortedVariants
                .filter { variant ->
                    (variant.variantType == VariantType.Test && variant.name == TEST_VARIANT) ||
                        (
                            variant.variantType == VariantType.AndroidTest &&
                                variant.name == ANDROID_TEST_VARIANT
                            )
                }
                .forEach { variant ->
                    addVariantRoots(
                        project = project,
                        variant = variant,
                        kind = AggregatedDependencyRootKind.TEST_HIERARCHY,
                        bucketName = variant.name,
                        targetBuckets = setOf(variant.name)
                    )
                }

            sortedVariants
                .filter { variant ->
                    variant.variantType == VariantType.AndroidBuild &&
                        variant.isWorkspaceAndroidLeaf
                }
                .forEach { variant ->
                    addVariantRoots(
                        project = project,
                        variant = variant,
                        kind = AggregatedDependencyRootKind.MAIN_LEAF,
                        bucketName = variant.name,
                        targetBuckets = variant.targetBuckets(standaloneTestProject)
                    )
                    addConfigurationRoots(
                        project = project,
                        kind = AggregatedDependencyRootKind.UNIT_TEST,
                        bucketName = variant.name,
                        metadataVariant = variant,
                        variantType = null,
                        configurations = variant.workspaceUnitTestClasspathConfigurations,
                        traverseProjectNodes = true
                    )
                    addConfigurationRoots(
                        project = project,
                        kind = AggregatedDependencyRootKind.ANDROID_TEST,
                        bucketName = variant.name,
                        metadataVariant = variant,
                        variantType = null,
                        configurations = variant.workspaceAndroidTestClasspathConfigurations,
                        traverseProjectNodes = true
                    )
                }
        }
    }

    private fun MutableList<WorkspaceDependencyRootInput>.addVariantRoots(
        project: Project,
        variant: Variant<*>,
        kind: AggregatedDependencyRootKind,
        bucketName: String,
        targetBuckets: Set<String>
    ) {
        addConfigurationRoots(
            project = project,
            kind = kind,
            bucketName = bucketName,
            metadataVariant = variant,
            variantType = variant.variantType,
            configurations = variant.workspaceVariantClasspathConfigurations,
            traverseProjectNodes = true,
            targetBuckets = targetBuckets
        )
    }

    private fun planLintRoots(
        project: Project,
        variants: Iterable<Variant<*>>
    ): List<WorkspaceDependencyRootInput> {
        return buildList {
            variants
                .filter { variant -> variant.variantType == VariantType.Lint }
                .forEach { variant ->
                    addConfigurationRoots(
                        project = project,
                        kind = AggregatedDependencyRootKind.LINT,
                        bucketName = null,
                        metadataVariant = null,
                        variantType = null,
                        configurations = variant.workspaceLintClasspathConfigurations,
                        traverseProjectNodes = false,
                        directDependencyShortIds = variant.workspaceLintClasspathConfigurations
                            .flatMap { configuration -> configuration.directExternalDependencyShortIds() }
                            .toSet()
                    )
                }
        }
    }

    private fun MutableList<WorkspaceDependencyRootInput>.addConfigurationRoots(
        project: Project,
        kind: AggregatedDependencyRootKind,
        bucketName: String?,
        metadataVariant: Variant<*>?,
        variantType: VariantType?,
        configurations: Iterable<Configuration>,
        traverseProjectNodes: Boolean,
        targetBuckets: Set<String> = emptySet(),
        directDependencyShortIds: Set<String> = emptySet()
    ) {
        configurations
            .distinctBy { configuration -> configuration.name }
            .sortedBy { configuration -> configuration.name }
            .forEach { configuration ->
                add(
                    WorkspaceDependencyRootInput(
                        project = project,
                        kind = kind,
                        bucketName = bucketName,
                        metadataVariant = metadataVariant,
                        variantType = variantType,
                        configuration = configuration,
                        traverseProjectNodes = traverseProjectNodes,
                        targetBuckets = targetBuckets,
                        directDependencyShortIds = directDependencyShortIds
                    )
                )
            }
    }

    private fun Iterable<Variant<*>>.sortedForWorkspaceDependencyInputs(): List<Variant<*>> {
        return sortedWith(
            compareBy<Variant<*>> { variant -> variant.variantType.name }
                .thenBy { variant -> variant.name }
        )
    }

    private fun Variant<*>.targetBuckets(standaloneTestProject: Boolean): Set<String> {
        return if (standaloneTestProject) {
            setOf(ANDROID_TEST_VARIANT)
        } else {
            setOf(name)
        }
    }

    private fun Configuration.directExternalDependencyShortIds(): Set<String> {
        return incoming.dependencies
            .asSequence()
            .filterIsInstance<ExternalDependency>()
            .map { dependency -> "${dependency.group}:${dependency.name}" }
            .toSet()
    }
}
