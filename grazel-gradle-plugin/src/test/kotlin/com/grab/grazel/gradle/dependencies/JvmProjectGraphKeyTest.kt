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

import com.google.common.graph.ImmutableValueGraph
import com.google.common.graph.ValueGraphBuilder
import com.grab.grazel.fake.FakeConfiguration
import com.grab.grazel.fake.FakeProject
import com.grab.grazel.gradle.variant.VariantGraphKey
import com.grab.grazel.gradle.variant.VariantType
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Tests for JVM (Kotlin library) project graph keys. Validates that Kotlin/JVM projects also get
 * project-prefixed keys.
 */
class JvmProjectGraphKeyTest {
    private val kotlinLibA = FakeProject("kotlin-lib-a", ":kotlin-lib-a")
    private val kotlinLibB = FakeProject("kotlin-lib-b", ":kotlin-lib-b")
    private val depProject = FakeProject("dep", ":dep")

    @Test
    fun `JVM projects should get project-prefixed graph keys`() {
        // When: Creating keys for JVM projects using factory method
        val keyA = VariantGraphKey.from(kotlinLibA, "default", VariantType.JvmBuild)
        val keyB = VariantGraphKey.from(kotlinLibB, "default", VariantType.JvmBuild)

        // Then: Keys should include project path
        assertEquals(":kotlin-lib-a:defaultJvmBuild", keyA.variantId)
        assertEquals(":kotlin-lib-b:defaultJvmBuild", keyB.variantId)
    }

    @Test
    fun `two Kotlin libraries should not share the same graph key`() {
        // When: Creating keys for two different Kotlin libraries
        val keyA = VariantGraphKey.from(kotlinLibA, "default", VariantType.JvmBuild)
        val keyB = VariantGraphKey.from(kotlinLibB, "default", VariantType.JvmBuild)

        // Then: Keys should be different
        assertNotEquals(keyA, keyB)
    }

    @Test
    fun `JVM test variant should also get project-prefixed keys`() {
        // When: Creating test keys for JVM projects
        val testKeyA = VariantGraphKey.from(kotlinLibA, "test", VariantType.Test)
        val testKeyB = VariantGraphKey.from(kotlinLibB, "test", VariantType.Test)

        // Then: Test keys should include project path and be different
        assertEquals(":kotlin-lib-a:testTest", testKeyA.variantId)
        assertEquals(":kotlin-lib-b:testTest", testKeyB.variantId)
        assertNotEquals(testKeyA, testKeyB)
    }

    @Test
    fun `JVM project dependency graph should isolate projects correctly`() {
        // Given: Two JVM projects with different dependencies
        val libAGraph = ValueGraphBuilder.directed()
            .allowsSelfLoops(false)
            .build<Project, Configuration>().apply {
                putEdgeValue(kotlinLibA, depProject, FakeConfiguration())
            }.let { ImmutableValueGraph.copyOf(it) }

        val libBGraph = ValueGraphBuilder.directed()
            .allowsSelfLoops(false)
            .build<Project, Configuration>().apply {
                // libB has only one dependency (different from libA's dep)
                putEdgeValue(kotlinLibB, kotlinLibA, FakeConfiguration())
            }.let { ImmutableValueGraph.copyOf(it) }

        val graphs = DefaultDependencyGraphs(
            variantGraphs = mapOf(
                VariantGraphKey.from(kotlinLibA, "default", VariantType.JvmBuild) to libAGraph,
                VariantGraphKey.from(kotlinLibB, "default", VariantType.JvmBuild) to libBGraph
            )
        )

        // When: Looking up dependencies
        val keyA = VariantGraphKey.from(kotlinLibA, "default", VariantType.JvmBuild)
        val keyB = VariantGraphKey.from(kotlinLibB, "default", VariantType.JvmBuild)

        val depsA = graphs.directDependenciesByVariant(kotlinLibA, keyA)
        val depsB = graphs.directDependenciesByVariant(kotlinLibB, keyB)

        // Then: Each project should see only its own dependencies
        assertEquals(setOf(depProject), depsA)
        assertEquals(setOf(kotlinLibA), depsB)

        // And: libA should not see libB's dependency structure
        assert(kotlinLibB !in depsA) { "libA deps should not contain kotlinLibB" }
    }

    @Test
    fun `hardcoded JVM key without project path should be different from factory-created key`() {
        // This test documents the difference between hardcoded keys (current implementation)
        // and properly prefixed keys (what the factory produces)

        // Given: A hardcoded key (like current KotlinProjectDataExtractor)
        val hardcodedKey = VariantGraphKey("defaultJvmBuild", VariantType.JvmBuild)

        // And: A properly prefixed key from the factory
        val properKey = VariantGraphKey.from(kotlinLibA, "default", VariantType.JvmBuild)

        // Then: They should be different
        assertNotEquals(hardcodedKey, properKey)
        assertEquals("defaultJvmBuild", hardcodedKey.variantId)
        assertEquals(":kotlin-lib-a:defaultJvmBuild", properKey.variantId)
    }
}