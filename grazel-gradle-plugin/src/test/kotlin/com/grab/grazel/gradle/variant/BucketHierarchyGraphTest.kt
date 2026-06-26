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

package com.grab.grazel.gradle.variant

import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.Test as TestVariantType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BucketHierarchyGraphTest {

    @Test
    fun `builds typed graph from variant extendsFrom metadata`() {
        val default = node(DEFAULT_VARIANT, AndroidBuild)
        val debug = node("debug", AndroidBuild)
        val free = node("free", AndroidBuild)
        val freeDebug = node("freeDebug", AndroidBuild)
        val test = node(TEST_VARIANT, TestVariantType)
        val debugUnitTest = node("debugUnitTest", TestVariantType)
        val freeDebugUnitTest = node("freeDebugUnitTest", TestVariantType)
        val androidTest = node(ANDROID_TEST_VARIANT, AndroidTest)
        val debugAndroidTest = node("debugAndroidTest", AndroidTest)
        val freeDebugAndroidTest = node("freeDebugAndroidTest", AndroidTest)

        val graph = BucketHierarchyGraph.from(
            entries = listOf(
                entry(default),
                entry(debug, DEFAULT_VARIANT),
                entry(free, DEFAULT_VARIANT),
                entry(freeDebug, DEFAULT_VARIANT, "debug", "free", leaf = true),
                entry(test, DEFAULT_VARIANT),
                entry(debugUnitTest, TEST_VARIANT),
                entry(freeDebugUnitTest, TEST_VARIANT, "debugUnitTest", "freeDebug", leaf = true),
                entry(androidTest, DEFAULT_VARIANT, TEST_VARIANT),
                entry(debugAndroidTest, ANDROID_TEST_VARIANT),
                entry(freeDebugAndroidTest, ANDROID_TEST_VARIANT, "debugAndroidTest", "freeDebug", leaf = true)
            )
        )

        assertEquals(setOf(default, debug, free), graph.predecessorsOf(freeDebug))
        assertEquals(setOf(freeDebugUnitTest, freeDebugAndroidTest), graph.successorsOf(freeDebug))
        assertEquals(setOf(default, debug, free, test, debugUnitTest, freeDebug), graph.ancestorsOf(freeDebugUnitTest))
        assertTrue(graph.hasAncestor(freeDebugUnitTest, freeDebug))
        assertTrue(graph.hasAncestor(freeDebugAndroidTest, androidTest))
        assertFalse(graph.hasAncestor(freeDebug, test))
        assertEquals(6, graph.depthOf(freeDebugUnitTest))
    }

    @Test
    fun `keeps nodes separate by project and variant type`() {
        val appDebug = node("debug", AndroidBuild, projectPath = ":app")
        val testAppDebug = node("debug", AndroidBuild, projectPath = ":test-app")
        val debugUnitTest = node("debug", TestVariantType, projectPath = ":app")

        val graph = BucketHierarchyGraph.from(
            entries = listOf(
                entry(node(DEFAULT_VARIANT, AndroidBuild, projectPath = ":app")),
                entry(appDebug, DEFAULT_VARIANT, leaf = true),
                entry(node(DEFAULT_VARIANT, AndroidBuild, projectPath = ":test-app")),
                entry(testAppDebug, DEFAULT_VARIANT, leaf = true),
                entry(node(TEST_VARIANT, TestVariantType, projectPath = ":app")),
                entry(debugUnitTest, TEST_VARIANT, leaf = true)
            )
        )

        assertEquals(emptySet<BucketHierarchyNode>(), graph.predecessorsOf(appDebug).intersect(setOf(testAppDebug)))
        assertEquals(setOf(appDebug), graph.leafDescendantsOf(node(DEFAULT_VARIANT, AndroidBuild, ":app")))
        assertEquals(setOf(testAppDebug), graph.leafDescendantsOf(node(DEFAULT_VARIANT, AndroidBuild, ":test-app")))
        assertEquals(setOf(debugUnitTest), graph.leafDescendantsOf(node(TEST_VARIANT, TestVariantType, ":app")))
    }

    @Test
    fun `android build parent lookup ignores same named test nodes`() {
        val androidDefault = node(DEFAULT_VARIANT, AndroidBuild)
        val testDefault = node(DEFAULT_VARIANT, TestVariantType)
        val debug = node("debug", AndroidBuild)

        val graph = BucketHierarchyGraph.from(
            entries = listOf(
                entry(androidDefault),
                entry(testDefault),
                entry(debug, DEFAULT_VARIANT, leaf = true)
            )
        )

        assertEquals(setOf(androidDefault), graph.predecessorsOf(debug))
        assertFalse(graph.hasAncestor(debug, testDefault))
    }

    @Test
    fun `finds leaf descendants without bucket-name inference`() {
        val default = node(DEFAULT_VARIANT, AndroidBuild)
        val debug = node("debug", AndroidBuild)
        val freeDebug = node("freeDebug", AndroidBuild)
        val paidDebug = node("paidDebug", AndroidBuild)
        val freeRelease = node("freeRelease", AndroidBuild)

        val graph = BucketHierarchyGraph.from(
            entries = listOf(
                entry(default),
                entry(debug, DEFAULT_VARIANT),
                entry(freeDebug, DEFAULT_VARIANT, "debug", "free", leaf = true),
                entry(paidDebug, DEFAULT_VARIANT, "debug", "paid", leaf = true),
                entry(freeRelease, DEFAULT_VARIANT, "release", "free", leaf = true)
            )
        )

        assertEquals(setOf(freeDebug, paidDebug), graph.leafDescendantsOf(debug))
        assertFalse(
            "A bucket named free must not be inferred from leaf names unless it exists in extendsFrom metadata",
            graph.contains(node("free", AndroidBuild))
        )
    }

    @Test
    fun `finds closest common ancestors in multi-parent variant graph`() {
        val default = node(DEFAULT_VARIANT, AndroidBuild)
        val debug = node("debug", AndroidBuild)
        val demo = node("demo", AndroidBuild)
        val full = node("full", AndroidBuild)
        val free = node("free", AndroidBuild)
        val paid = node("paid", AndroidBuild)
        val demoFreeDebug = node("demoFreeDebug", AndroidBuild)
        val demoPaidDebug = node("demoPaidDebug", AndroidBuild)
        val fullFreeDebug = node("fullFreeDebug", AndroidBuild)
        val fullPaidDebug = node("fullPaidDebug", AndroidBuild)

        val graph = BucketHierarchyGraph.from(
            entries = listOf(
                entry(default),
                entry(debug, DEFAULT_VARIANT),
                entry(demo, DEFAULT_VARIANT),
                entry(full, DEFAULT_VARIANT),
                entry(free, DEFAULT_VARIANT),
                entry(paid, DEFAULT_VARIANT),
                entry(demoFreeDebug, DEFAULT_VARIANT, "debug", "demo", "free", leaf = true),
                entry(demoPaidDebug, DEFAULT_VARIANT, "debug", "demo", "paid", leaf = true),
                entry(fullFreeDebug, DEFAULT_VARIANT, "debug", "full", "free", leaf = true),
                entry(fullPaidDebug, DEFAULT_VARIANT, "debug", "full", "paid", leaf = true)
            )
        )

        assertEquals(
            setOf(default, debug),
            graph.commonAncestorsOf(setOf(demoFreeDebug, demoPaidDebug, fullFreeDebug, fullPaidDebug))
        )
        assertEquals(
            setOf(debug),
            graph.closestCommonAncestorsOf(setOf(demoFreeDebug, demoPaidDebug, fullFreeDebug, fullPaidDebug))
        )
    }

    @Test
    fun `closest common ancestors stays set valued when graph is ambiguous`() {
        val default = node(DEFAULT_VARIANT, AndroidBuild)
        val demo = node("demo", AndroidBuild)
        val free = node("free", AndroidBuild)
        val debug = node("debug", AndroidBuild)
        val release = node("release", AndroidBuild)
        val demoFreeDebug = node("demoFreeDebug", AndroidBuild)
        val demoFreeRelease = node("demoFreeRelease", AndroidBuild)

        val graph = BucketHierarchyGraph.from(
            entries = listOf(
                entry(default),
                entry(demo, DEFAULT_VARIANT),
                entry(free, DEFAULT_VARIANT),
                entry(debug, DEFAULT_VARIANT),
                entry(release, DEFAULT_VARIANT),
                entry(demoFreeDebug, DEFAULT_VARIANT, "demo", "free", "debug", leaf = true),
                entry(demoFreeRelease, DEFAULT_VARIANT, "demo", "free", "release", leaf = true)
            )
        )

        assertEquals(
            setOf(default, demo, free),
            graph.commonAncestorsOf(setOf(demoFreeDebug, demoFreeRelease))
        )
        assertEquals(
            setOf(demo, free),
            graph.closestCommonAncestorsOf(setOf(demoFreeDebug, demoFreeRelease))
        )
    }

    private fun node(
        name: String,
        variantType: VariantType,
        projectPath: String = ":app"
    ): BucketHierarchyNode {
        return BucketHierarchyNode(
            projectPath = projectPath,
            name = name,
            variantType = variantType
        )
    }

    private fun entry(
        node: BucketHierarchyNode,
        vararg extendsFrom: String,
        leaf: Boolean = false
    ): BucketHierarchyEntry {
        return BucketHierarchyEntry(
            node = node,
            extendsFrom = extendsFrom.toSortedSet(),
            leaf = leaf
        )
    }
}
