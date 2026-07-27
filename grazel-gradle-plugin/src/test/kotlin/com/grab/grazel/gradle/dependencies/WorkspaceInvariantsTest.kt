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
import com.grab.grazel.gradle.dependencies.model.TargetReferenceFacts
import com.grab.grazel.gradle.dependencies.model.WorkspacePlan
import com.grab.grazel.gradle.dependencies.model.WorkspaceRenderPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceInvariantsTest {

    @Test
    fun `reports a referenced repo that is not rendered`() {
        val violations = validateWorkspaceInvariants(
            workspacePlan = WorkspacePlan(
                repoPlan = mapOf("flavor_maven" to variantRepo("com.example:sdk:1.0.0"))
            ),
            targetReferences = TargetReferenceFacts(repoNames = setOf("flavor_maven")),
            renderPlan = WorkspaceRenderPlan(materializedRepoNames = emptySet())
        )

        assertEquals(listOf("I1"), violations.map { it.invariant })
        assertTrue(violations.single().message.contains("flavor_maven"))
    }

    @Test
    fun `accepts a rendered repo that no target references`() {
        val violations = validateWorkspaceInvariants(
            workspacePlan = WorkspacePlan(
                repoPlan = mapOf(
                    "ksp_maven" to CandidateMavenRepo(
                        kind = AGGREGATED,
                        pinInputs = listOf(dependency("com.example:processor:1.0.0"))
                    )
                )
            ),
            targetReferences = TargetReferenceFacts(),
            renderPlan = WorkspaceRenderPlan(materializedRepoNames = setOf("ksp_maven"))
        )

        assertEquals(emptyList<InvariantViolation>(), violations)
    }

    @Test
    fun `reports a referenced artifact missing from the repo its label names`() {
        val violations = validateWorkspaceInvariants(
            workspacePlan = WorkspacePlan(
                repoPlan = mapOf("maven" to variantRepo("com.example:present:1.0.0"))
            ),
            targetReferences = TargetReferenceFacts(
                repoNames = setOf("maven"),
                mavenArtifacts = mapOf("maven" to setOf("com.example:absent"))
            ),
            renderPlan = WorkspaceRenderPlan(materializedRepoNames = setOf("maven"))
        )

        assertEquals(listOf("I2"), violations.map { it.invariant })
        assertTrue(violations.single().message.contains("com.example:absent"))
    }

    @Test
    fun `accepts a referenced artifact that is override mapped rather than pinned`() {
        val violations = validateWorkspaceInvariants(
            workspacePlan = WorkspacePlan(
                repoPlan = mapOf(
                    "maven" to CandidateMavenRepo(
                        kind = VARIANT,
                        pinInputs = listOf(dependency("com.example:other:1.0.0")),
                        overrideTargets = mapOf("com.example:patched" to "@//patches:patched")
                    )
                )
            ),
            targetReferences = TargetReferenceFacts(
                repoNames = setOf("maven"),
                mavenArtifacts = mapOf("maven" to setOf("com.example:patched"))
            ),
            renderPlan = WorkspaceRenderPlan(materializedRepoNames = setOf("maven"))
        )

        assertEquals(emptyList<InvariantViolation>(), violations)
    }

    @Test
    fun `failure message lists every violation grouped under its invariant`() {
        val message = listOf(
            InvariantViolation("I1", "maven repository 'flavor_maven' is not rendered"),
            InvariantViolation("I2", "artifact 'com.example:absent' is not pinned in 'maven'")
        ).asFailureMessage()

        assertTrue(message.contains("2 generated-output invariant violation"))
        assertTrue(message.contains("I1: maven repository 'flavor_maven' is not rendered"))
        assertTrue(message.contains("I2: artifact 'com.example:absent' is not pinned in 'maven'"))
    }

    private fun variantRepo(id: String): CandidateMavenRepo = CandidateMavenRepo(
        kind = VARIANT,
        pinInputs = listOf(dependency(id))
    )

    private fun dependency(id: String): ResolvedDependency =
        ResolvedDependency.fromId(id, "maven")
}
