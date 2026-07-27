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
    private val projectLabelPattern = Regex("""^//([^:]+):([^:]+)$""")

    /**
     * Aggregates every dependency-like reference a target makes (deps, plugins, lint checks,
     * associates, the single `instruments` target) into the facts
     * [WorkspaceRenderPlanBuilder]/render-plan discovery need: which maven repos are referenced,
     * which project targets are referenced (by project path -> target names), and which maven
     * artifact short ids are referenced (by repo -> `group:name`). `repoNames` also infers a repo
     * from [tags] carrying a `MAVEN_COMPILE_FILTER_TAG_PREFIX` tag — a maven-filtering tag implies
     * a dependency on [BASE_MAVEN_REPO] even when no [MavenDependency] literally appears in `deps`,
     * so tag scanning is a required second source, not a redundant one. Such tags carry no
     * artifact identity, so they contribute to `repoNames` only, never to `mavenArtifacts`. Project
     * references are recognized either as a typed [ProjectDependency] or as a [StringDependency]
     * matching the `//path:target` label pattern, since some call sites pass raw label strings
     * instead of typed dependencies.
     */
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
                    tags.asSequence()
                        .filter { tag -> tag.isMavenCompileFilterTag() }
                        .map { BASE_MAVEN_REPO }
                ).toSortedSet(),
            projectTargets = dependencies
                .asSequence()
                .mapNotNull { dependency -> dependency.projectReference() }
                .groupBy(
                    keySelector = { (projectPath, _) -> projectPath },
                    valueTransform = { (_, targetName) -> targetName }
                )
                .mapValues { (_, targetNames) -> targetNames.toSortedSet() }
                .toSortedMap(),
            mavenArtifacts = dependencies
                .asSequence()
                .filterIsInstance<MavenDependency>()
                .groupBy(
                    keySelector = MavenDependency::repo,
                    valueTransform = { dependency -> "${dependency.group}:${dependency.name}" }
                )
                .mapValues { (_, shortIds) -> shortIds.toSortedSet() }
                .toSortedMap()
        )
    }

    private fun String.isMavenCompileFilterTag(): Boolean =
        startsWith(MAVEN_COMPILE_FILTER_TAG_PREFIX)

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

/**
 * Must remain a pure, monotonic set-union (only ever grows the facts): `collectToQuiescence`
 * relies on this invariant for both correctness (activation-order confluence) and termination
 * of its deferred-activation drain.
 */
internal fun mergeTargetReferenceFacts(
    left: TargetReferenceFacts,
    right: TargetReferenceFacts
): TargetReferenceFacts =
    TargetReferenceFacts(
        repoNames = left.repoNames + right.repoNames,
        projectTargets = mergeSetsByKey(left.projectTargets, right.projectTargets),
        mavenArtifacts = mergeSetsByKey(left.mavenArtifacts, right.mavenArtifacts)
    )

internal fun TargetReferenceFacts.normalized(): TargetReferenceFacts =
    TargetReferenceFacts(
        repoNames = repoNames.toSortedSet(),
        projectTargets = projectTargets
            .mapValues { (_, targetNames) -> targetNames.toSortedSet() }
            .toSortedMap(),
        mavenArtifacts = mavenArtifacts
            .mapValues { (_, shortIds) -> shortIds.toSortedSet() }
            .toSortedMap()
    )

private fun mergeSetsByKey(
    left: Map<String, Set<String>>,
    right: Map<String, Set<String>>
): Map<String, Set<String>> {
    return (left.keys + right.keys)
        .associateWith { key ->
            left.getOrDefault(key, emptySet()) + right.getOrDefault(key, emptySet())
        }
        .toSortedMap()
}
