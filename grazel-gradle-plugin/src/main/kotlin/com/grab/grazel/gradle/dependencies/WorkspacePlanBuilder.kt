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
import com.grab.grazel.gradle.dependencies.model.CandidateMavenRepoKind.VARIANT
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.dependencies.model.WorkspacePlan

internal class WorkspacePlanBuilder(
    private val configuredOverrideTargets: Map<String, String> = emptyMap()
) {
    /**
     * The seam between resolution/bucketing output ([WorkspaceDependencies]) and maven_install
     * rendering ([WorkspaceRenderPlanBuilder]): turns per-variant root artifacts into [VARIANT]-kind
     * repos and per-aggregated-bucket artifacts into [AGGREGATED]-kind repos, computing each
     * variant repo's own override targets ([calculateMavenInstallOverrideTargets]) against
     * [configuredOverrideTargets] — aggregated repos deliberately have no override targets computed
     * here since they aggregate artifacts across variants rather than owning a single variant's
     * override semantics. Variant repos are keyed by [toMavenRepoName] before merging with
     * aggregated repos, so a name collision between the two would silently let one clobber the
     * other in the final `+` — callers must keep variant and aggregated bucket names disjoint.
     */
    fun build(
        workspaceDependencies: WorkspaceDependencies
    ): WorkspacePlan {
        val rootArtifactsByVariant = workspaceDependencies.mavenInstallRootArtifactsByVariant()
        val variantRepos = workspaceDependencies.variantDeps
            .keys
            .sorted()
            .associate { variantName ->
                val repoName = variantName.toMavenRepoName()
                val rootArtifacts = rootArtifactsByVariant
                    .getValue(variantName)
                    .sortedBy(ResolvedDependency::id)
                repoName to CandidateMavenRepo(
                    kind = VARIANT,
                    pinInputs = rootArtifacts,
                    overrideTargets = calculateMavenInstallOverrideTargets(
                        artifacts = rootArtifacts,
                        owningMavenRepoName = repoName,
                        configuredOverrideTargets = configuredOverrideTargets
                    )
                )
            }
        val aggregatedRepos = workspaceDependencies.aggregatedRepos
            .toSortedMap()
            .mapValues { (_, artifacts) ->
                val sortedArtifacts = artifacts.sortedBy(ResolvedDependency::id)
                CandidateMavenRepo(
                    kind = AGGREGATED,
                    pinInputs = sortedArtifacts
                )
        }
        return WorkspacePlan(
            repoPlan = variantRepos + aggregatedRepos
        )
    }
}
