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

import com.grab.grazel.gradle.dependencies.model.ExcludeRule
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.variant.VariantType
import kotlinx.serialization.Serializable
import org.gradle.api.artifacts.result.ResolvedComponentResult
import java.io.Serializable as JavaSerializable

@Serializable
internal data class AggregatedDependencyRootMetadata(
    val projectPath: String,
    val kind: AggregatedDependencyRootKind,
    val configurationName: String,
    val bucketName: String? = null,
    val leafName: String? = null,
    val variantNames: Set<String> = emptySet(),
    val variantType: VariantType? = null,
    val buildType: String? = null,
    val productFlavors: List<String> = emptyList(),
    val targetBuckets: Set<String> = emptySet(),
    val traverseProjectNodes: Boolean = true,
    val directDependencyShortIds: Set<String> = emptySet(),
    val rootExcludeRulesByShortId: Map<String, Set<ExcludeRule>> = emptyMap()
) : JavaSerializable

@Serializable
internal enum class AggregatedDependencyRootKind {
    MAIN_HIERARCHY,
    MAIN_LEAF,
    TEST_HIERARCHY,
    UNIT_TEST,
    ANDROID_TEST,
    LINT
}

@Serializable
internal data class AggregatedDependencyRootSnapshot(
    val metadata: AggregatedDependencyRootMetadata,
    val components: List<AggregatedDependencyComponent> = emptyList()
) {
    companion object {
        fun from(
            root: ResolvedComponentResult,
            metadata: AggregatedDependencyRootMetadata
        ): AggregatedDependencyRootSnapshot {
            val components = mutableListOf<AggregatedDependencyComponent>()
            ResolvedComponentsVisitor().visit(
                root = root,
                traverseProjectNodes = metadata.traverseProjectNodes
            ) { visitResult ->
                val moduleVersion = visitResult.component.moduleVersion ?: return@visit null
                val component = AggregatedDependencyComponent(
                    id = visitResult.component.toString(),
                    shortId = "${moduleVersion.group}:${moduleVersion.name}",
                    version = moduleVersion.version,
                    moduleName = moduleVersion.name,
                    repository = visitResult.repository,
                    directFromProject = visitResult.directFromProject,
                    directProjectPath = visitResult.directProjectPath,
                    directProjectVariantDisplayName = visitResult.directProjectVariantDisplayName,
                    requiresJetifier = visitResult.requiresJetifier,
                    transitiveDependencyNotations = visitResult.transitiveDeps
                        .filterNot { dependencyResult ->
                            dependencyResult.dependency.isBomComponent()
                        }
                        .mapTo(sortedSetOf()) { dependencyResult ->
                            ResolvedDependency.createDependencyNotation(
                                dependencyResult.dependency,
                                dependencyResult.requiresJetifier,
                                dependencyResult.unjetifiedSource
                            )
                        },
                    bom = visitResult.component.isBomComponent()
                )
                components.add(component)
                component
            }
            return AggregatedDependencyRootSnapshot(
                metadata = metadata,
                components = components.sorted()
            )
        }
    }
}

@Serializable
internal data class AggregatedDependencyComponent(
    val id: String,
    val shortId: String,
    val version: String,
    val moduleName: String,
    val repository: String,
    val directFromProject: Boolean,
    val directProjectPath: String? = null,
    val directProjectVariantDisplayName: String? = null,
    val requiresJetifier: Boolean,
    val transitiveDependencyNotations: Set<String> = emptySet(),
    val bom: Boolean = false
) : Comparable<AggregatedDependencyComponent> {
    override fun compareTo(other: AggregatedDependencyComponent): Int =
        compareValuesBy(
            this,
            other,
            AggregatedDependencyComponent::id,
            AggregatedDependencyComponent::repository,
            AggregatedDependencyComponent::directProjectPath,
            AggregatedDependencyComponent::directProjectVariantDisplayName,
            AggregatedDependencyComponent::directFromProject,
            AggregatedDependencyComponent::requiresJetifier
        )
}

private fun ResolvedComponentResult.isBomComponent(): Boolean {
    val version = moduleVersion ?: return false
    return version.name.endsWith("-bom", ignoreCase = true) ||
        version.name.endsWith(".bom", ignoreCase = true)
}
