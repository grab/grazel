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
import com.grab.grazel.gradle.variant.VariantType
import kotlinx.serialization.Serializable
import org.gradle.api.artifacts.result.ResolvedComponentResult
import java.io.Serializable as JavaSerializable

internal data class AggregatedDependencyRoot(
    val root: ResolvedComponentResult,
    val metadata: AggregatedDependencyRootMetadata
)

/**
 * Identity for a [WorkspaceDependencyRootInput] that is stable across the separate
 * `TaskProvider.configure` blocks (root-metadata, resolved-component and, when enabled,
 * pin-maven-artifacts) that each independently iterate the planned root inputs in
 * [com.grab.grazel.tasks.internal.WorkspaceDependencyInputsRegistrar]. Used to join a resolved
 * [org.gradle.api.artifacts.result.ResolvedComponentResult] back to its
 * [AggregatedDependencyRootMetadata] by key rather than by list position.
 *
 * Declared `JavaSerializable` — same as [AggregatedDependencyRootMetadata] below — for Gradle's
 * `@Input` value-snapshotter, which otherwise fails fingerprinting a plain Kotlin `data class`
 * with "cannot be serialized" even though every field is itself serializable.
 *
 * Travels as a parallel `@Input` list (`ResolveWorkspaceDependenciesTask.workspaceDependencyRootKeys`)
 * index-aligned with `workspaceDependencyRootComponents`, NOT as a wrapper class around
 * [org.gradle.api.artifacts.result.ResolvedComponentResult]. A `KeyedRootComponent(key, component)`
 * wrapper was tried first and rejected: Gradle failed to fingerprint it with "cannot be
 * serialized" even after making both the wrapper and [RootKey] implement `JavaSerializable` —
 * confirming the bare `List<ResolvedComponentResult>` `@Input` works via Gradle's own dedicated
 * handling for that exact declared property type, not generic Java-serialization fallback, and
 * that handling does not apply once the component is nested inside any wrapper, serializable or
 * not.
 */
internal data class RootKey(
    val projectPath: String,
    val configurationName: String,
    val kind: AggregatedDependencyRootKind
) : JavaSerializable {
    companion object {
        // Pinned so @Input fingerprints stay stable across compiler versions.
        private const val serialVersionUID = 1L
    }
}

internal fun AggregatedDependencyRootMetadata.rootKey(): RootKey = RootKey(
    projectPath = projectPath,
    configurationName = configurationName,
    kind = kind
)

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

internal fun ResolvedComponentResult.isBomComponent(): Boolean {
    val version = moduleVersion ?: return false
    return version.name.isBomArtifactName()
}

internal fun String?.isBomArtifactName(): Boolean =
    this?.endsWith("-bom", ignoreCase = true) == true ||
        this?.endsWith(".bom", ignoreCase = true) == true
