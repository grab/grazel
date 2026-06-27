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
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultOverrideCarrierPlannerTest {

    @Test
    fun `uses default selected version for lower non direct variant dependency`() {
        val defaultDependency = dependency("com.example:annotations:1.1", "maven")
            .copy(direct = false)
        val lintDependency = dependency("com.example:annotations:1.0", "lint_maven")
            .copy(direct = false)

        val planned = DefaultOverrideCarrierPlanner().plan(
            mapOf(
                DEFAULT_VARIANT to mapOf(defaultDependency.shortId to defaultDependency),
                "lint" to mapOf(lintDependency.shortId to lintDependency)
            )
        )

        val lintAnnotations = planned
            .getValue("lint")
            .single { dependency -> dependency.shortId == "com.example:annotations" }
        assertEquals("com.example:annotations:1.1", lintAnnotations.id)
        assertEquals(false, lintAnnotations.direct)
        assertEquals(
            OverrideTarget(
                artifactShortId = "com.example:annotations",
                label = MavenDependency(group = "com.example", name = "annotations")
            ),
            lintAnnotations.overrideTarget
        )
    }

    @Test
    fun `preserves direct override carrier even when default owns the artifact`() {
        val defaultDependency = dependency("com.example:library:1.0", "maven")
        val overrideCarrier = dependency("com.example:library:1.0", "debug_maven")
            .copy(
                overrideTarget = OverrideTarget(
                    artifactShortId = "com.example:library",
                    label = MavenDependency(
                        repo = "debug_maven",
                        group = "com.example",
                        name = "library"
                    )
                )
            )

        val planned = DefaultOverrideCarrierPlanner().plan(
            mapOf(
                DEFAULT_VARIANT to mapOf(defaultDependency.shortId to defaultDependency),
                "free" to mapOf(overrideCarrier.shortId to overrideCarrier)
            )
        )

        assertEquals(listOf(overrideCarrier), planned.getValue("free"))
    }

    private fun dependency(id: String, repository: String): ResolvedDependency =
        ResolvedDependency.fromId(id, repository)
}
