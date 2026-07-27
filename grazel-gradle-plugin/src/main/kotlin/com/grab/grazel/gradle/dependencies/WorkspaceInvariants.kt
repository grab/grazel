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

import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.TargetReferenceFacts
import com.grab.grazel.gradle.dependencies.model.WorkspacePlan
import com.grab.grazel.gradle.dependencies.model.WorkspaceRenderPlan

/** A breached generated-output invariant, named so failures are greppable. */
internal data class InvariantViolation(
    val invariant: String,
    val message: String
)

/**
 * Checks the two properties that must hold of generated output for every emitted maven
 * label to resolve during Bazel analysis:
 *
 *  - **I1** every referenced maven repo is rendered. A dropped repo makes Bazel analysis
 *    fail with `no such package '@@<repo>//'`.
 *  - **I2** every referenced artifact is pinned in the repo its label names, or is
 *    override-mapped there. A label naming an absent artifact fails analysis the same way.
 *
 * Deliberately one-directional on I1: a rendered repo that no target references is
 * legitimate — the KSP repo is referenced by rules rather than by target deps.
 *
 * I2 consults only [TargetReferenceFacts.mavenArtifacts]. Compile-filter tags contribute a
 * repo name with no artifact identity, so they cannot be checked at artifact granularity.
 *
 * There is deliberately no check that a coordinate resolves to the same version across
 * repos — variant repos intentionally pin lower versions than the aggregated repo carries.
 */
internal fun validateWorkspaceInvariants(
    workspacePlan: WorkspacePlan,
    targetReferences: TargetReferenceFacts,
    renderPlan: WorkspaceRenderPlan
): List<InvariantViolation> {
    val violations = mutableListOf<InvariantViolation>()
    val rendered = renderPlan.materializedRepoNames

    (targetReferences.repoNames - rendered).sorted().forEach { repoName ->
        violations += InvariantViolation(
            invariant = "I1",
            message = "maven repository '$repoName' is referenced by generated targets but " +
                "is not rendered in the WORKSPACE. Referencing targets: " +
                targetReferences.referrersOf(repoName)
        )
    }

    targetReferences.mavenArtifacts.toSortedMap().forEach { (repoName, shortIds) ->
        if (repoName !in rendered) return@forEach
        val candidate = workspacePlan.repoPlan[repoName] ?: return@forEach
        val available = candidate.pinInputs.mapTo(HashSet(), ResolvedDependency::shortId) +
            candidate.overrideTargets.keys
        (shortIds - available).sorted().forEach { shortId ->
            violations += InvariantViolation(
                invariant = "I2",
                message = "artifact '$shortId' is referenced as '@$repoName//' but is neither " +
                    "pinned in that repository nor override-mapped there. Referencing targets: " +
                    targetReferences.referrersOf(repoName)
            )
        }
    }

    return violations
}

private fun TargetReferenceFacts.referrersOf(repoName: String): String {
    val referrers = projectTargets
        .filterValues { targetNames -> targetNames.isNotEmpty() }
        .keys
        .sorted()
    return when {
        referrers.isEmpty() -> "unknown (no project targets recorded for '$repoName')"
        else -> referrers.take(REPORTED_REFERRERS).joinToString().let { shown ->
            if (referrers.size > REPORTED_REFERRERS) {
                "$shown and ${referrers.size - REPORTED_REFERRERS} more"
            } else {
                shown
            }
        }
    }
}

private const val REPORTED_REFERRERS = 10
