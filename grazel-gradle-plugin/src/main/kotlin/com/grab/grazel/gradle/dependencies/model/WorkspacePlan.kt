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

package com.grab.grazel.gradle.dependencies.model

import kotlinx.serialization.Serializable

@Serializable
internal data class WorkspacePlan(
    val repoPlan: Map<String, CandidateMavenRepo> = emptyMap(),
    val tagPlan: List<TargetTagPlan> = emptyList()
)

@Serializable
internal data class CandidateMavenRepo(
    val kind: CandidateMavenRepoKind,
    val pinInputs: List<ResolvedDependency> = emptyList(),
    val overrideTargets: Map<String, String> = emptyMap()
)

@Serializable
internal enum class CandidateMavenRepoKind {
    VARIANT,
    AGGREGATED
}

@Serializable
internal data class TargetTagPlan(
    val key: TargetTagKey,
    val tags: List<String>
)

@Serializable
internal data class TargetTagKey(
    val variantId: String,
    val variantType: String,
    val targetKind: String
)

@Serializable
internal data class WorkspaceRenderPlan(
    val materializedRepoNames: Set<String> = emptySet(),
    val referencedProjectTargets: Map<String, Set<String>> = emptyMap()
) {
    val referencedProjectPaths: Set<String>
        get() = referencedProjectTargets.keys
}

@Serializable
internal data class TargetReferenceFacts(
    val repoNames: Set<String> = emptySet(),
    val projectTargets: Map<String, Set<String>> = emptyMap()
) {
    val projectPaths: Set<String>
        get() = projectTargets.keys
}
