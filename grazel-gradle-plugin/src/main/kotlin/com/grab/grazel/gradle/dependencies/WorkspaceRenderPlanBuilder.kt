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

import com.grab.grazel.gradle.dependencies.model.CandidateMavenRepo
import com.grab.grazel.gradle.dependencies.model.CandidateMavenRepoKind.AGGREGATED
import com.grab.grazel.gradle.dependencies.model.WorkspacePlan
import com.grab.grazel.gradle.dependencies.model.WorkspaceRenderPlan
import com.grab.grazel.gradle.variant.ANDROID_TEST_VARIANT
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.LINT_VARIANT
import com.grab.grazel.gradle.variant.TEST_VARIANT
import com.grab.grazel.migrate.dependencies.toMavenRepoName

internal class WorkspaceRenderPlanBuilder(
    alwaysMaterializedVariants: Set<String> = setOf(
        DEFAULT_VARIANT,
        TEST_VARIANT,
        ANDROID_TEST_VARIANT,
        LINT_VARIANT
    )
) {
    private val alwaysMaterializedVariantRepos = alwaysMaterializedVariants
        .mapTo(sortedSetOf(), String::toMavenRepoName)

    fun build(
        workspacePlan: WorkspacePlan,
        referencedRepoNames: Set<String> = emptySet()
    ): WorkspaceRenderPlan {
        val baseMaterializableRepos = workspacePlan.repoPlan
            .filterValues { candidate -> candidate.hasMaterializedRoot() }
            .keys
            .toSortedSet()
        val overrideTargetRepos = workspacePlan.repoPlan
            .filterValues { candidate -> candidate.hasPlannedArtifacts() }
            .keys
            .toSortedSet()
        val alwaysMaterializedRepoNames = workspacePlan.repoPlan
            .filter { (repoName, candidate) ->
                candidate.hasMaterializedRoot() &&
                    (repoName in alwaysMaterializedVariantRepos || candidate.kind == AGGREGATED)
            }
            .keys
            .toSortedSet()

        val materializedRepoNames = (referencedRepoNames + alwaysMaterializedRepoNames)
            .filterTo(sortedSetOf()) { repoName -> repoName in baseMaterializableRepos }
        val reposToScan = ArrayDeque(materializedRepoNames)
        while (reposToScan.isNotEmpty()) {
            val candidate = workspacePlan.repoPlan[reposToScan.removeFirst()] ?: continue
            candidate.overrideTargets.values
                .asSequence()
                .mapNotNull { label -> label.referencedMavenRepo(overrideTargetRepos) }
                .forEach { repoName ->
                    if (materializedRepoNames.add(repoName)) {
                        reposToScan.add(repoName)
                    }
                }
        }

        return WorkspaceRenderPlan(
            materializedRepoNames = materializedRepoNames.toSortedSet()
        )
    }

    private fun CandidateMavenRepo.hasMaterializedRoot(): Boolean =
        kind == AGGREGATED || pinInputs.any { artifact ->
            artifact.direct && artifact.shortId !in overrideTargets
        }

    private fun CandidateMavenRepo.hasPlannedArtifacts(): Boolean =
        kind == AGGREGATED || pinInputs.isNotEmpty()

    private fun String.referencedMavenRepo(availableRepos: Set<String>): String? =
        removePrefix("@")
            .substringBefore("//")
            .takeIf(availableRepos::contains)
}
