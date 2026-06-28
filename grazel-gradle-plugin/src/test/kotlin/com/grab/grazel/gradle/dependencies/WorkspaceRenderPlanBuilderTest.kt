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

import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.gradle.dependencies.model.CandidateMavenRepo
import com.grab.grazel.gradle.dependencies.model.CandidateMavenRepoKind.AGGREGATED
import com.grab.grazel.gradle.dependencies.model.CandidateMavenRepoKind.VARIANT
import com.grab.grazel.gradle.dependencies.model.OverrideTarget
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspacePlan
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceRenderPlanBuilderTest {

    @Test
    fun `materializes referenced repos always materialized repos and aggregated repos`() {
        val plan = WorkspacePlan(
            repoPlan = mapOf(
                "maven" to variantRepo("default", "com.example:main:1.0.0"),
                "debug_maven" to variantRepo("debug", "com.example:debug:1.0.0"),
                "free_maven" to variantRepo("free", "com.example:free:1.0.0"),
                "ksp_maven" to aggregatedRepo("com.example:processor:1.0.0")
            )
        )

        val renderPlan = WorkspaceRenderPlanBuilder().build(
            workspacePlan = plan,
            referencedRepoNames = setOf("debug_maven", "missing_maven")
        )

        assertEquals(
            setOf("debug_maven", "ksp_maven", "maven"),
            renderPlan.materializedRepoNames
        )
    }

    @Test
    fun `follows override target closure transitively`() {
        val plan = WorkspacePlan(
            repoPlan = mapOf(
                "a_maven" to variantRepo(
                    variantName = "a",
                    artifact = "com.example:a:1.0.0",
                    overrideTargets = mapOf("com.example:b" to mavenLabel("b_maven", "com.example", "b"))
                ),
                "b_maven" to variantRepo(
                    variantName = "b",
                    artifact = "com.example:b:1.0.0",
                    overrideTargets = mapOf("com.example:c" to mavenLabel("c_maven", "com.example", "c"))
                ),
                "c_maven" to variantRepo("c", "com.example:c:1.0.0"),
                "d_maven" to variantRepo("d", "com.example:d:1.0.0")
            )
        )

        val renderPlan = WorkspaceRenderPlanBuilder(alwaysMaterializedVariants = emptySet()).build(
            workspacePlan = plan,
            referencedRepoNames = setOf("a_maven")
        )

        assertEquals(
            setOf("a_maven", "b_maven", "c_maven"),
            renderPlan.materializedRepoNames
        )
    }

    @Test
    fun `materializes only variant repos with direct owned pin inputs`() {
        val directOwned = dependency("com.example:owned:1.0.0")
        val directOverride = dependency("com.example:direct-override:1.0.0")
            .copy(overrideTarget = overrideTarget("maven", "com.example", "direct-override"))
        val transitive = dependency("com.example:transitive:1.0.0")
            .copy(direct = false)
        val overrideCarrier = dependency("com.example:override-carrier:1.0.0")
            .copy(
                direct = false,
                overrideTarget = overrideTarget("maven", "com.example", "override-carrier")
            )
        val plan = WorkspacePlan(
            repoPlan = mapOf(
                "owned_maven" to variantRepo("owned", directOwned),
                "direct_override_maven" to variantRepo("directOverride", directOverride),
                "transitive_maven" to variantRepo("transitive", transitive),
                "override_carrier_maven" to variantRepo("overrideCarrier", overrideCarrier)
            )
        )

        val renderPlan = WorkspaceRenderPlanBuilder(alwaysMaterializedVariants = emptySet()).build(
            workspacePlan = plan,
            referencedRepoNames = plan.repoPlan.keys
        )

        assertEquals(
            setOf("owned_maven"),
            renderPlan.materializedRepoNames
        )
    }

    private fun variantRepo(
        variantName: String,
        artifact: String,
        overrideTargets: Map<String, String> = emptyMap()
    ): CandidateMavenRepo = variantRepo(
        variantName = variantName,
        dependency = dependency(artifact),
        overrideTargets = overrideTargets
    )

    private fun variantRepo(
        variantName: String,
        dependency: ResolvedDependency,
        overrideTargets: Map<String, String> = emptyMap()
    ): CandidateMavenRepo = CandidateMavenRepo(
        variantName = variantName,
        kind = VARIANT,
        pinInputs = listOf(dependency),
        overrideTargets = overrideTargets
    )

    private fun aggregatedRepo(artifact: String): CandidateMavenRepo {
        val dependency = dependency(artifact)
        return CandidateMavenRepo(
            kind = AGGREGATED,
            pinInputs = listOf(dependency)
        )
    }

    private fun dependency(id: String): ResolvedDependency =
        ResolvedDependency.fromId(id, "maven")

    private fun overrideTarget(repo: String, group: String, name: String): OverrideTarget =
        OverrideTarget(
            artifactShortId = "$group:$name",
            label = MavenDependency(repo = repo, group = group, name = name)
        )

    private fun mavenLabel(repo: String, group: String, name: String): String =
        MavenDependency(repo = repo, group = group, name = name).toString()
}
