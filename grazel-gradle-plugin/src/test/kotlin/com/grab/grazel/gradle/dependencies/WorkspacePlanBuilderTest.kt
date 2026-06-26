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
import com.grab.grazel.gradle.dependencies.model.OverrideTarget
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.TargetTagKey
import com.grab.grazel.gradle.dependencies.model.TargetTagPlan
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspacePlanBuilderTest {

    @Test
    fun `repo plan exposes selected root artifacts and override targets`() {
        val defaultOwnedTransitive = dependency("com.example:shared-transitive:2.0.0")
            .copy(direct = false)
        val debugRoot = dependency("com.example:debug-root:1.0.0")
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to listOf(defaultOwnedTransitive),
                "debug" to listOf(debugRoot)
            ),
            variantTransitiveClasspath = mapOf(
                "debug" to mapOf(debugRoot.shortId to setOf(defaultOwnedTransitive.shortId))
            )
        )

        val plan = WorkspacePlanBuilder().build(workspaceDependencies)

        val debugRepo = plan.repoPlan.getValue("debug_maven")
        assertEquals("debug", debugRepo.variantName)
        assertEquals(
            listOf("com.example:debug-root:1.0.0", "com.example:shared-transitive:2.0.0"),
            debugRepo.rootArtifacts.map(ResolvedDependency::id)
        )
        assertEquals(
            listOf("com.example:debug-root:1.0.0", "com.example:shared-transitive:2.0.0"),
            debugRepo.pinInputs.map(ResolvedDependency::id)
        )
        assertEquals(
            mapOf("com.example:shared-transitive" to "@maven//:com_example_shared_transitive"),
            debugRepo.overrideTargets
        )
        assertEquals(
            listOf("com.example:debug-root:1.0.0"),
            debugRepo.variantArtifacts.getValue("debug").map(ResolvedDependency::id)
        )
    }

    @Test
    fun `repo plan preserves override target closure carriers for coursier roots`() {
        val debugCarrier = dependency("com.example:debug-carrier:1.0.0")
        val freeRoot = dependency("com.example:free-root:1.0.0")
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to emptyList(),
                "debug" to listOf(debugCarrier),
                "free" to listOf(freeRoot)
            ),
            variantTransitiveClasspath = mapOf(
                "free" to mapOf(freeRoot.shortId to setOf(debugCarrier.shortId))
            )
        )

        val plan = WorkspacePlanBuilder().build(workspaceDependencies)

        val freeRepo = plan.repoPlan.getValue("free_maven")
        assertEquals(
            listOf("com.example:debug-carrier:1.0.0", "com.example:free-root:1.0.0"),
            freeRepo.rootArtifacts.map(ResolvedDependency::id)
        )
        assertEquals(
            mapOf("com.example:debug-carrier" to "@debug_maven//:com_example_debug_carrier"),
            freeRepo.overrideTargets
        )
    }

    @Test
    fun `repo plan rehydrates transitive artifacts from owning variant instead of global short id winner`() {
        val defaultShared = dependency("com.example:shared:2.0.0")
            .copy(direct = false)
        val debugRoot = dependency("com.example:debug-root:1.0.0")
        val debugShared = dependency("com.example:shared:1.0.0")
            .copy(direct = false)
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to listOf(defaultShared),
                "debug" to listOf(debugRoot, debugShared)
            ),
            variantTransitiveClasspath = mapOf(
                "debug" to mapOf(debugRoot.shortId to setOf(debugShared.shortId))
            )
        )

        val plan = WorkspacePlanBuilder().build(workspaceDependencies)

        val debugRepo = plan.repoPlan.getValue("debug_maven")
        assertEquals(
            listOf("com.example:debug-root:1.0.0", "com.example:shared:1.0.0"),
            debugRepo.rootArtifacts.map(ResolvedDependency::id)
        )
        assertEquals(emptyMap<String, String>(), debugRepo.overrideTargets)
    }

    @Test
    fun `repo plan redirects identical default-owned transitive roots to default repo`() {
        val defaultShared = dependency("com.example:shared:1.0.0")
            .copy(direct = false)
        val debugRoot = dependency("com.example:debug-root:1.0.0")
        val debugShared = dependency("com.example:shared:1.0.0")
            .copy(direct = false)
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to listOf(defaultShared),
                "debug" to listOf(debugRoot, debugShared)
            ),
            variantTransitiveClasspath = mapOf(
                "debug" to mapOf(debugRoot.shortId to setOf(debugShared.shortId))
            )
        )

        val plan = WorkspacePlanBuilder().build(workspaceDependencies)

        val debugRepo = plan.repoPlan.getValue("debug_maven")
        val debugSharedRoot = debugRepo.rootArtifacts
            .single { artifact -> artifact.shortId == "com.example:shared" }
        assertEquals(
            "Default-owned artifacts carried for Coursier should redirect Bazel labels to @maven",
            OverrideTarget(
                artifactShortId = "com.example:shared",
                label = MavenDependency(
                    group = "com.example",
                    name = "shared"
                )
            ),
            debugSharedRoot.overrideTarget
        )
        assertEquals(
            mapOf("com.example:shared" to "@maven//:com_example_shared"),
            debugRepo.overrideTargets
        )
    }

    @Test
    fun `repo plan applies configured override labels only to rooted artifacts`() {
        val lintRoot = dependency("com.android.tools.lint:lint-api:31.5.0-alpha02")
        val annotation = dependency("androidx.annotation:annotation:1.9.1")
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to listOf(annotation),
                "lint" to listOf(lintRoot)
            ),
            variantTransitiveClasspath = mapOf(
                "lint" to mapOf(lintRoot.shortId to setOf(annotation.shortId))
            )
        )

        val plan = WorkspacePlanBuilder(
            configuredOverrideTargets = mapOf(
                "androidx.annotation:annotation" to "@maven//:androidx_annotation_annotation_jvm",
                "com.example:unused" to "@maven//:com_example_unused"
            )
        ).build(workspaceDependencies)

        assertEquals(
            mapOf("androidx.annotation:annotation" to "@maven//:androidx_annotation_annotation_jvm"),
            plan.repoPlan.getValue("lint_maven").overrideTargets
        )
    }

    @Test
    fun `render plan keeps referenced repos plus override target closure and always materialized repos`() {
        val debugCarrier = dependency("com.example:debug-carrier:1.0.0")
        val freeRoot = dependency("com.example:free-root:1.0.0")
        val paidRoot = dependency("com.example:paid-root:1.0.0")
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to emptyList(),
                "debug" to listOf(debugCarrier),
                "free" to listOf(freeRoot),
                "paid" to listOf(paidRoot)
            ),
            variantTransitiveClasspath = mapOf(
                "free" to mapOf(freeRoot.shortId to setOf(debugCarrier.shortId))
            )
        )

        val plan = WorkspacePlanBuilder().build(workspaceDependencies)
        val renderPlan = WorkspaceRenderPlanBuilder().build(
            workspacePlan = plan,
            referencedRepoNames = setOf("free_maven")
        )

        assertEquals(
            setOf("debug_maven", "free_maven"),
            renderPlan.materializedRepoNames
        )
        assertEquals(setOf("debug_maven"), renderPlan.overrideTargetRepoNames)
    }

    @Test
    fun `render plan does not materialize candidate repos when referenced repos are not supplied`() {
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to emptyList(),
                "debug" to listOf(dependency("com.example:debug:1.0.0")),
                "free" to listOf(dependency("com.example:free:1.0.0"))
            )
        )

        val renderPlan = WorkspaceRenderPlanBuilder().build(
            workspacePlan = WorkspacePlanBuilder().build(workspaceDependencies),
            referencedRepoNames = emptySet()
        )

        assertEquals(
            emptySet<String>(),
            renderPlan.materializedRepoNames
        )
    }

    @Test
    fun `repo plan keeps aggregated repos as always materialized candidates`() {
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(DEFAULT_VARIANT to emptyList()),
            aggregatedRepos = mapOf("ksp_maven" to listOf(dependency("com.example:processor:1.0.0")))
        )

        val plan = WorkspacePlanBuilder().build(workspaceDependencies)
        val renderPlan = WorkspaceRenderPlanBuilder().build(
            workspacePlan = plan,
            referencedRepoNames = emptySet()
        )

        assertEquals(
            listOf("com.example:processor:1.0.0"),
            plan.repoPlan.getValue("ksp_maven").pinInputs.map(ResolvedDependency::id)
        )
        assertEquals(setOf("ksp_maven"), renderPlan.materializedRepoNames)
    }

    @Test
    fun `workspace plan carries precomputed target tag plan`() {
        val targetTagPlan = listOf(
            TargetTagPlan(
                key = TargetTagKey(
                    variantId = ":lib:debugAndroidBuild",
                    variantType = "AndroidBuild",
                    targetKind = "android_library"
                ),
                tags = listOf("@maven//:com_example_root", "@maven//:com_example_transitive")
            )
        )

        val plan = WorkspacePlanBuilder().build(
            workspaceDependencies = WorkspaceDependencies(variantDeps = mapOf(DEFAULT_VARIANT to emptyList())),
            targetTagPlan = targetTagPlan
        )

        assertEquals(targetTagPlan, plan.tagPlan)
    }

    private fun dependency(id: String): ResolvedDependency =
        ResolvedDependency.fromId(id, "MavenRepo")
}
