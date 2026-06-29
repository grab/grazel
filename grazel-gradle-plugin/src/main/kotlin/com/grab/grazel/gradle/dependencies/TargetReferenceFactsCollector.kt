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

import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.bazel.starlark.BazelDependency.ProjectDependency
import com.grab.grazel.bazel.starlark.BazelDependency.StringDependency
import com.grab.grazel.gradle.dependencies.model.TargetReferenceFacts
import com.grab.grazel.gradle.dependencies.model.WorkspaceRenderPlan

internal object TargetReferenceFactsCollector {
    private val tagLabelPattern = Regex("""^@(maven)//:""")
    private val projectLabelPattern = Regex("""^//([^:]+):([^:]+)$""")

    fun from(
        deps: Iterable<BazelDependency> = emptyList(),
        tags: Iterable<String> = emptyList(),
        plugins: Iterable<BazelDependency> = emptyList(),
        lintChecks: Iterable<BazelDependency> = emptyList(),
        associates: Iterable<BazelDependency> = emptyList(),
        instruments: BazelDependency? = null
    ): TargetReferenceFacts {
        val dependencies = buildList {
            addAll(deps)
            addAll(plugins)
            addAll(lintChecks)
            addAll(associates)
            instruments?.let(::add)
        }
        return TargetReferenceFacts(
            repoNames = (
                dependencies.asSequence()
                    .filterIsInstance<MavenDependency>()
                    .map(MavenDependency::repo) +
                    tags.asSequence().mapNotNull { tag -> tag.mavenRepoFromTag() }
                ).toSortedSet(),
            projectTargets = dependencies
                .asSequence()
                .mapNotNull { dependency -> dependency.projectReference() }
                .groupBy(
                    keySelector = { (projectPath, _) -> projectPath },
                    valueTransform = { (_, targetName) -> targetName }
                )
                .mapValues { (_, targetNames) -> targetNames.toSortedSet() }
                .toSortedMap()
        )
    }

    private fun String.mavenRepoFromTag(): String? =
        tagLabelPattern.find(this)?.groupValues?.get(1)

    private fun BazelDependency.projectReference(): Pair<String, String>? =
        when (this) {
            is ProjectDependency -> dependencyProject.path to targetName
            is StringDependency -> string.projectReference()
            else -> null
        }

    private fun String.projectReference(): Pair<String, String>? {
        val match = projectLabelPattern.find(this) ?: return null
        val projectPath = ":${match.groupValues[1].replace("/", ":")}"
        val targetName = match.groupValues[2]
        return projectPath to targetName
    }
}

internal fun Iterable<TargetReferenceFacts>.merged(): TargetReferenceFacts {
    return fold(TargetReferenceFacts()) { left, right ->
        mergeTargetReferenceFacts(left, right)
    }
        .normalized()
}

internal fun TargetReferenceFacts.asRenderPlan(): WorkspaceRenderPlan =
    WorkspaceRenderPlan(
        referencedProjectTargets = projectTargets
    )

internal fun mergeTargetReferenceFacts(
    left: TargetReferenceFacts,
    right: TargetReferenceFacts
): TargetReferenceFacts =
    TargetReferenceFacts(
        repoNames = left.repoNames + right.repoNames,
        projectTargets = mergeProjectTargets(left.projectTargets, right.projectTargets)
    )

internal fun TargetReferenceFacts.normalized(): TargetReferenceFacts =
    TargetReferenceFacts(
        repoNames = repoNames.toSortedSet(),
        projectTargets = projectTargets
            .mapValues { (_, targetNames) -> targetNames.toSortedSet() }
            .toSortedMap()
    )

private fun mergeProjectTargets(
    left: Map<String, Set<String>>,
    right: Map<String, Set<String>>
): Map<String, Set<String>> {
    return (left.keys + right.keys)
        .associateWith { projectPath ->
            left.getOrDefault(projectPath, emptySet()) +
                right.getOrDefault(projectPath, emptySet())
        }
        .toSortedMap()
}
