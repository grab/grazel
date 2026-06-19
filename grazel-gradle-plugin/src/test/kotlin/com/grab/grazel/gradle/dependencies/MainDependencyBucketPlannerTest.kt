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

import com.grab.grazel.gradle.dependencies.model.ExcludeRule
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainDependencyBucketPlannerTest {

    @Test
    fun `places same artifact with default and build type versions in nearest buckets`() {
        val defaultDependency = dependency("com.example:library:1.0")
        val debugDependency = dependency("com.example:library:2.0")

        val plan = MainDependencyBucketPlanner().plan(
            variants = listOf(
                leaf("freeDebug", "debug", "free"),
                leaf("freeRelease", "release", "free")
            ),
            hierarchyBucketClosures = mapOf(
                DEFAULT_VARIANT to mapOf(defaultDependency.shortId to defaultDependency),
                "debug" to mapOf(debugDependency.shortId to debugDependency)
            ),
            leafClosures = mapOf(
                "freeDebug" to mapOf(debugDependency.shortId to debugDependency),
                "freeRelease" to mapOf(defaultDependency.shortId to defaultDependency)
            )
        )

        assertEquals(mapOf(defaultDependency.shortId to defaultDependency), plan.defaultBucket)
        assertEquals(mapOf(debugDependency.shortId to debugDependency), plan.buildTypeBuckets["debug"])
        assertEquals(emptyMap<String, Map<String, ResolvedDependency>>(), plan.leafBuckets)
    }

    @Test
    fun `keeps same artifact and version with different excludes in separate nearest buckets`() {
        val defaultDependency = dependency("com.example:library:1.0")
        val debugDependency = dependency("com.example:library:1.0").copy(
            excludeRules = setOf(ExcludeRule("com.example", "blocked"))
        )

        val plan = MainDependencyBucketPlanner().plan(
            variants = listOf(
                leaf("freeDebug", "debug", "free"),
                leaf("freeRelease", "release", "free")
            ),
            hierarchyBucketClosures = mapOf(
                DEFAULT_VARIANT to mapOf(defaultDependency.shortId to defaultDependency),
                "debug" to mapOf(debugDependency.shortId to debugDependency)
            ),
            leafClosures = mapOf(
                "freeDebug" to mapOf(debugDependency.shortId to debugDependency),
                "freeRelease" to mapOf(defaultDependency.shortId to defaultDependency)
            )
        )

        assertEquals(mapOf(defaultDependency.shortId to defaultDependency), plan.defaultBucket)
        assertEquals(mapOf(debugDependency.shortId to debugDependency), plan.buildTypeBuckets["debug"])
    }

    @Test
    fun `places common flavor dependency in flavor bucket instead of leaf buckets`() {
        val flavorDependency = dependency("com.example:flavor-only:1.0")

        val plan = MainDependencyBucketPlanner().plan(
            variants = listOf(
                leaf("freeDebug", "debug", "free"),
                leaf("freeRelease", "release", "free"),
                leaf("paidDebug", "debug", "paid")
            ),
            hierarchyBucketClosures = emptyMap(),
            leafClosures = mapOf(
                "freeDebug" to mapOf(flavorDependency.shortId to flavorDependency),
                "freeRelease" to mapOf(flavorDependency.shortId to flavorDependency),
                "paidDebug" to emptyMap()
            )
        )

        assertEquals(emptyMap<String, ResolvedDependency>(), plan.defaultBucket)
        assertEquals(mapOf(flavorDependency.shortId to flavorDependency), plan.flavorBuckets["free"])
        assertEquals(emptyMap<String, Map<String, ResolvedDependency>>(), plan.leafBuckets)
    }

    @Test
    fun `places dependency only used by one leaf in that leaf bucket`() {
        val leafDependency = dependency("com.example:leaf-only:1.0")

        val plan = MainDependencyBucketPlanner().plan(
            variants = listOf(
                leaf("freeDebug", "debug", "free"),
                leaf("freeRelease", "release", "free")
            ),
            hierarchyBucketClosures = emptyMap(),
            leafClosures = mapOf(
                "freeDebug" to mapOf(leafDependency.shortId to leafDependency),
                "freeRelease" to emptyMap()
            )
        )

        assertEquals(mapOf(leafDependency.shortId to leafDependency), plan.leafBuckets["freeDebug"])
        assertEquals(emptyMap<String, Map<String, ResolvedDependency>>(), plan.flavorBuckets)
    }

    @Test
    fun `does not promote filtered leaf dependency to default when non default hierarchy owns it`() {
        val debugDependency = dependency("com.example:debug-only:1.0")

        val plan = MainDependencyBucketPlanner().plan(
            variants = listOf(
                leaf("freeDebug", "debug", "free")
            ),
            hierarchyBucketClosures = mapOf(
                "debug" to mapOf(debugDependency.shortId to debugDependency)
            ),
            leafClosures = mapOf(
                "freeDebug" to mapOf(debugDependency.shortId to debugDependency)
            )
        )

        assertEquals(emptyMap<String, ResolvedDependency>(), plan.defaultBucket)
        assertEquals(mapOf(debugDependency.shortId to debugDependency), plan.buildTypeBuckets["debug"])
    }

    @Test
    fun `explicit deeper hierarchy bucket wins over inferred ancestor bucket`() {
        val freeDependency = dependency("com.example:free-only:1.0")

        val plan = MainDependencyBucketPlanner().plan(
            variants = listOf(
                hierarchy("debug", setOf(DEFAULT_VARIANT), buildType = "debug"),
                hierarchy("free", setOf("debug"), productFlavors = listOf("free")),
                leafWithParents("demoFreeDebug", setOf("free"), "debug", "free"),
                leafWithParents("fullFreeDebug", setOf("free"), "debug", "free")
            ),
            hierarchyBucketClosures = mapOf(
                "free" to mapOf(freeDependency.shortId to freeDependency)
            ),
            leafClosures = mapOf(
                "demoFreeDebug" to mapOf(freeDependency.shortId to freeDependency),
                "fullFreeDebug" to mapOf(freeDependency.shortId to freeDependency)
            )
        )

        assertNull(plan.buildTypeBuckets["debug"])
        assertEquals(mapOf(freeDependency.shortId to freeDependency), plan.flavorBuckets["free"])
        assertEquals(emptyMap<String, Map<String, ResolvedDependency>>(), plan.leafBuckets)
    }

    @Test
    fun `explicit deeper hierarchy bucket wins over explicit ancestor bucket`() {
        val dependency = dependency("com.example:explicit-owner:1.0")
        val dependencyMap = mapOf(dependency.shortId to dependency)

        val plan = MainDependencyBucketPlanner().plan(
            variants = listOf(
                hierarchy("debug", setOf(DEFAULT_VARIANT), buildType = "debug"),
                hierarchy("free", setOf("debug"), productFlavors = listOf("free")),
                leafWithParents("demoFreeDebug", setOf("free"), "debug", "free"),
                leafWithParents("fullFreeDebug", setOf("free"), "debug", "free")
            ),
            hierarchyBucketClosures = mapOf(
                "debug" to dependencyMap,
                "free" to dependencyMap
            ),
            leafClosures = mapOf(
                "demoFreeDebug" to dependencyMap,
                "fullFreeDebug" to dependencyMap
            )
        )

        assertNull(plan.buildTypeBuckets["debug"])
        assertEquals(dependencyMap, plan.flavorBuckets["free"])
        assertEquals(emptyMap<String, Map<String, ResolvedDependency>>(), plan.leafBuckets)
    }

    @Test
    fun `leaf named hierarchy bucket is emitted as leaf bucket`() {
        val dependency = dependency("com.example:compile-only:1.0")
        val dependencyMap = mapOf(dependency.shortId to dependency)

        val plan = MainDependencyBucketPlanner().plan(
            variants = listOf(
                leaf("freeDebug", "debug", "free")
            ),
            hierarchyBucketClosures = mapOf(
                "freeDebug" to dependencyMap
            ),
            leafClosures = emptyMap()
        )

        assertEquals(dependencyMap, plan.leafBuckets["freeDebug"])
        assertEquals(
            listOf(CoveredDependency("freeDebug", dependency)),
            plan.coveredDependencies()
        )
    }

    @Test
    fun `overlapping peer flavor buckets keep independent ownership`() {
        val dependency = dependency("com.example:flavor-overlap:1.0")
        val dependencyMap = mapOf(dependency.shortId to dependency)

        val plan = MainDependencyBucketPlanner().plan(
            variants = listOf(
                leaf("demoFreeDebug", "debug", "demo", "free"),
                leaf("demoPaidDebug", "debug", "demo", "paid"),
                leaf("fullFreeDebug", "debug", "full", "free"),
                leaf("fullPaidDebug", "debug", "full", "paid")
            ),
            hierarchyBucketClosures = mapOf(
                "demo" to dependencyMap,
                "free" to dependencyMap,
                "full" to dependencyMap,
                "paid" to dependencyMap
            ),
            leafClosures = mapOf(
                "demoFreeDebug" to dependencyMap,
                "demoPaidDebug" to dependencyMap,
                "fullFreeDebug" to dependencyMap,
                "fullPaidDebug" to dependencyMap
            )
        )

        assertEquals(dependencyMap, plan.flavorBuckets["demo"])
        assertEquals(dependencyMap, plan.flavorBuckets["free"])
        assertEquals(dependencyMap, plan.flavorBuckets["full"])
        assertEquals(dependencyMap, plan.flavorBuckets["paid"])
        assertEquals(emptyMap<String, Map<String, ResolvedDependency>>(), plan.leafBuckets)
    }

    @Test
    fun `selected peer bucket covering all candidate leaves suppresses candidate bucket`() {
        val dependency = dependency("com.example:debug-only:1.0")
        val dependencyMap = mapOf(dependency.shortId to dependency)

        val plan = MainDependencyBucketPlanner().plan(
            variants = listOf(
                leaf("demoFreeDebug", "debug", "demo", "free"),
                leaf("demoPaidDebug", "debug", "demo", "paid"),
                leaf("fullFreeDebug", "debug", "full", "free"),
                leaf("fullPaidDebug", "debug", "full", "paid")
            ),
            hierarchyBucketClosures = mapOf(
                "debug" to dependencyMap
            ),
            leafClosures = mapOf(
                "demoFreeDebug" to dependencyMap,
                "demoPaidDebug" to dependencyMap,
                "fullFreeDebug" to dependencyMap,
                "fullPaidDebug" to dependencyMap
            )
        )

        assertEquals(dependencyMap, plan.buildTypeBuckets["debug"])
        assertEquals(emptyMap<String, Map<String, ResolvedDependency>>(), plan.flavorBuckets)
        assertEquals(emptyMap<String, Map<String, ResolvedDependency>>(), plan.leafBuckets)
    }

    @Test
    fun `leaf main buckets are included in covered dependencies`() {
        val leafDependency = dependency("com.example:leaf-only:1.0")

        val plan = MainDependencyBucketPlanner().plan(
            variants = listOf(
                leaf("freeDebug", "debug", "free"),
                leaf("freeRelease", "release", "free")
            ),
            hierarchyBucketClosures = emptyMap(),
            leafClosures = mapOf(
                "freeDebug" to mapOf(leafDependency.shortId to leafDependency),
                "freeRelease" to emptyMap()
            )
        )

        assertEquals(
            listOf(CoveredDependency("freeDebug", leafDependency)),
            plan.coveredDependencies()
        )
    }

    private fun leaf(
        name: String,
        buildType: String,
        vararg productFlavors: String
    ): MainBucketVariant {
        return leafWithParents(
            name = name,
            extendsFrom = (setOf(DEFAULT_VARIANT, buildType) + productFlavors).toSortedSet(),
            buildType = buildType,
            productFlavors = productFlavors.toList()
        )
    }

    private fun leafWithParents(
        name: String,
        extendsFrom: Set<String>,
        buildType: String,
        vararg productFlavors: String
    ): MainBucketVariant {
        return leafWithParents(
            name = name,
            extendsFrom = extendsFrom,
            buildType = buildType,
            productFlavors = productFlavors.toList()
        )
    }

    private fun leafWithParents(
        name: String,
        extendsFrom: Set<String>,
        buildType: String,
        productFlavors: List<String>
    ): MainBucketVariant {
        return MainBucketVariant(
            name = name,
            extendsFrom = extendsFrom.toSortedSet(),
            buildType = buildType,
            productFlavors = productFlavors,
            leaf = true
        )
    }

    private fun hierarchy(
        name: String,
        extendsFrom: Set<String>,
        buildType: String? = null,
        productFlavors: List<String> = emptyList()
    ): MainBucketVariant {
        return MainBucketVariant(
            name = name,
            extendsFrom = extendsFrom.toSortedSet(),
            buildType = buildType,
            productFlavors = productFlavors,
            leaf = false
        )
    }

    private fun dependency(id: String): ResolvedDependency {
        return ResolvedDependency.fromId(id, "maven")
    }
}
