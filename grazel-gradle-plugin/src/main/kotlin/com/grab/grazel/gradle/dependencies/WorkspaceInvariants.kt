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
 * There is deliberately no version-consistency check. Variant repos may hold the same
 * coordinate at different versions on purpose: when two repos agree on a version the
 * artifact is redirected to `@maven`, and when they differ each repo keeps its own. A
 * check here would flag that intended behaviour.
 */
internal fun validateWorkspaceInvariants(
    workspacePlan: WorkspacePlan,
    targetReferences: TargetReferenceFacts,
    renderPlan: WorkspaceRenderPlan,
    /**
     * Short ids of user-configured artifact version overrides. Every one of these is appended
     * to every variant repo's rendered artifact set, so they are available in a repo even when
     * absent from its pin inputs. Without them I2 would fail a build whose output is correct,
     * which would punish the very escape hatch consumers use to work around placement defects.
     */
    overriddenArtifactShortIds: Set<String> = emptySet()
): List<InvariantViolation> {
    val violations = mutableListOf<InvariantViolation>()
    val rendered = renderPlan.materializedRepoNames

    (targetReferences.repoNames - rendered).sorted().forEach { repoName ->
        violations += InvariantViolation(
            invariant = "I1",
            message = "maven repository '$repoName' is referenced by generated targets but " +
                "is not rendered in the WORKSPACE"
        )
    }

    targetReferences.mavenArtifacts.toSortedMap().forEach { (repoName, shortIds) ->
        if (repoName !in rendered) return@forEach
        val candidate = workspacePlan.repoPlan[repoName] ?: return@forEach
        val available = candidate.pinInputs.mapTo(HashSet(), ResolvedDependency::shortId) +
            candidate.overrideTargets.keys +
            overriddenArtifactShortIds
        (shortIds - available).sorted().forEach { shortId ->
            violations += InvariantViolation(
                invariant = "I2",
                message = "artifact '$shortId' is referenced as '@$repoName//' but is neither " +
                    "pinned in that repository nor override-mapped there"
            )
        }
    }

    return violations
}

/**
 * Renders violations as a single actionable failure. Generation stops here rather than
 * emitting a WORKSPACE already known to be broken — the alternative is that the same facts
 * get rediscovered from a Bazel analysis error in someone else's terminal.
 */
internal fun List<InvariantViolation>.asFailureMessage(): String = buildString {
    appendLine("$size generated-output invariant violation(s) found:")
    this@asFailureMessage
        .sortedWith(compareBy(InvariantViolation::invariant, InvariantViolation::message))
        .forEach { violation ->
            appendLine("  ${violation.invariant}: ${violation.message}")
        }
    append("Generation stopped. These labels would not resolve in the generated WORKSPACE.")
}
