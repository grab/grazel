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

package com.grab.grazel.tasks.internal

import com.grab.grazel.gradle.dependencies.AggregatedDependencyRootMetadata
import com.grab.grazel.gradle.dependencies.AggregatedDependencyRootKind
import com.grab.grazel.gradle.dependencies.model.ExcludeRule
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.util.writeJson
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

@CacheableTask
internal abstract class CollectWorkspaceDependencyRootMetadataTask : DefaultTask() {
    @get:Nested
    abstract val workspaceDependencyRootMetadataItems: ListProperty<WorkspaceDependencyRootMetadataInput>

    @get:OutputFile
    abstract val workspaceDependencyRootMetadata: RegularFileProperty

    init {
        group = GRAZEL_TASK_GROUP
        description = "Collects workspace dependency root metadata for aggregated dependency resolution"
    }

    @TaskAction
    fun action() {
        workspaceDependencyRootMetadata.get().asFile.parentFile.mkdirs()
        writeJson(
            workspaceDependencyRootMetadataItems.get().map { input -> input.toMetadata() },
            workspaceDependencyRootMetadata.get()
        )
    }

    companion object {
        private const val TASK_NAME = "collectWorkspaceDependencyRootMetadata"

        internal fun register(rootProject: Project): TaskProvider<CollectWorkspaceDependencyRootMetadataTask> {
            return rootProject.tasks.register<CollectWorkspaceDependencyRootMetadataTask>(TASK_NAME) {
                workspaceDependencyRootMetadataItems.convention(emptyList())
                workspaceDependencyRootMetadata.set(
                    rootProject.layout.buildDirectory.file("grazel/workspace-dependency-root-metadata.json")
                )
            }
        }
    }
}

internal abstract class WorkspaceDependencyRootMetadataInput {
    @get:Input
    abstract val projectPath: Property<String>

    @get:Input
    abstract val kind: Property<AggregatedDependencyRootKind>

    @get:Input
    abstract val configurationName: Property<String>

    @get:Input
    @get:Optional
    abstract val bucketName: Property<String>

    @get:Input
    @get:Optional
    abstract val leafName: Property<String>

    @get:Input
    abstract val variantNames: SetProperty<String>

    @get:Input
    @get:Optional
    abstract val variantType: Property<String>

    @get:Input
    @get:Optional
    abstract val buildType: Property<String>

    @get:Input
    abstract val productFlavors: ListProperty<String>

    @get:Input
    abstract val targetBuckets: SetProperty<String>

    @get:Input
    abstract val traverseProjectNodes: Property<Boolean>

    @get:Input
    abstract val directDependencyShortIds: SetProperty<String>

    @get:Nested
    abstract val rootExcludeRules: ListProperty<RootExcludeRulesInput>

    internal fun setMetadata(metadata: Provider<AggregatedDependencyRootMetadata>) {
        projectPath.set(metadata.map { root -> root.projectPath })
        kind.set(metadata.map { root -> root.kind })
        configurationName.set(metadata.map { root -> root.configurationName })
        bucketName.set(metadata.map { root -> root.bucketName.orEmpty() })
        leafName.set(metadata.map { root -> root.leafName.orEmpty() })
        variantNames.set(metadata.map { root -> root.variantNames })
        variantType.set(metadata.map { root -> root.variantType?.name.orEmpty() })
        buildType.set(metadata.map { root -> root.buildType.orEmpty() })
        productFlavors.set(metadata.map { root -> root.productFlavors })
        targetBuckets.set(metadata.map { root -> root.targetBuckets })
        traverseProjectNodes.set(metadata.map { root -> root.traverseProjectNodes })
        directDependencyShortIds.set(metadata.map { root -> root.directDependencyShortIds })
        rootExcludeRules.set(
            metadata.map { root ->
                root.rootExcludeRulesByShortId
                    .toSortedMap()
                    .map { (shortId, rules) ->
                        RootExcludeRulesInput(
                            shortId = shortId,
                            rules = toExcludeRuleInputs(rules)
                        )
                    }
            }
        )
    }

    internal fun toMetadata(): AggregatedDependencyRootMetadata {
        return AggregatedDependencyRootMetadata(
            projectPath = projectPath.get(),
            kind = kind.get(),
            configurationName = configurationName.get(),
            bucketName = bucketName.get().ifBlank { null },
            leafName = leafName.get().ifBlank { null },
            variantNames = variantNames.get().toSortedSet(),
            variantType = variantType.get().ifBlank { null }?.let { VariantType.valueOf(it) },
            buildType = buildType.get().ifBlank { null },
            productFlavors = productFlavors.get(),
            targetBuckets = targetBuckets.get().toSortedSet(),
            traverseProjectNodes = traverseProjectNodes.get(),
            directDependencyShortIds = directDependencyShortIds.get().toSortedSet(),
            rootExcludeRulesByShortId = rootExcludeRules.get()
                .associate { input ->
                    input.shortId to toExcludeRules(input.rules)
                }
                .toSortedMap()
        )
    }
}

internal data class RootExcludeRulesInput(
    @get:Input
    val shortId: String,
    @get:Nested
    val rules: List<ExcludeRuleInput>
)

internal data class ExcludeRuleInput(
    @get:Input
    val group: String,
    @get:Input
    val artifact: String
)

private val excludeRuleComparator = compareBy<ExcludeRule> { rule -> rule.group }
    .thenBy { rule -> rule.artifact }

private fun toExcludeRuleInputs(excludeRules: Set<ExcludeRule>): List<ExcludeRuleInput> {
    return excludeRules.sortedWith(excludeRuleComparator)
        .map { rule -> ExcludeRuleInput(rule.group, rule.artifact) }
}

private fun toExcludeRules(excludeRuleInputs: List<ExcludeRuleInput>): Set<ExcludeRule> {
    return excludeRuleInputs.mapTo(sortedSetOf(excludeRuleComparator)) { rule ->
        ExcludeRule(rule.group, rule.artifact)
    }
}
