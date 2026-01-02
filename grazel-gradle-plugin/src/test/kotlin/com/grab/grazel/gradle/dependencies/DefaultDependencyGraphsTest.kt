/*
 * Copyright 2022 Grabtaxi Holdings PTE LTD (GRAB)
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
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.junit.Test
import kotlin.test.assertEquals


class DefaultDependencyGraphsTest {
    private val projectA = FakeProject("A")
    private val projectB = FakeProject("B")
    private val projectC = FakeProject("C")
    private val projectD = FakeProject("D")
    private val projectE = FakeProject("E")

    private val dependenciesGraphs = DefaultDependencyGraphs(
        variantGraphs = mapOf(
            VariantGraphKey(":A:debugAndroidBuild") to buildBuildGraphs(),
            VariantGraphKey(":A:debugUnitTestTest") to buildTestGraphs()
        )
    )

    @Test
    fun `nodesByVariant should return all nodes if no variant key passed`() {
        val buildNodes = setOf(projectA, projectB, projectC)
        val testNodes = setOf(projectA, projectB, projectC, projectD, projectE)
        assertEquals(
            testNodes + buildNodes, dependenciesGraphs.nodesByVariant()
        )
    }

    @Test
    fun `nodesByVariant should return correct nodes for build variant`() {
        val buildNodes = setOf(projectA, projectB, projectC)
        assertEquals(
            buildNodes,
            dependenciesGraphs.nodesByVariant(VariantGraphKey(":A:debugAndroidBuild"))
        )
    }

    @Test
    fun `nodesByVariant should return correct nodes for test variant`() {
        val testNodes = setOf(projectA, projectB, projectC, projectD, projectE)
        assertEquals(
            testNodes,
            dependenciesGraphs.nodesByVariant(VariantGraphKey(":A:debugUnitTestTest"))
        )
    }

    @Test
    fun `nodesByVariant should return combined nodes for multiple variants`() {
        val buildNodes = setOf(projectA, projectB, projectC)
        val testNodes = setOf(projectA, projectB, projectC, projectD, projectE)
        assertEquals(
            testNodes + buildNodes,
            dependenciesGraphs.nodesByVariant(
                VariantGraphKey(":A:debugAndroidBuild"),
                VariantGraphKey(":A:debugUnitTestTest")
            )
        )
    }

    @Test
    fun `directDependenciesByVariant should return direct deps using VariantGraphKey`() {
        val directDepsFromAWithBuildScope = setOf(projectB, projectC)
        assertEquals(
            directDepsFromAWithBuildScope,
            dependenciesGraphs.directDependenciesByVariant(
                projectA,
                VariantGraphKey(":A:debugAndroidBuild")
            )
        )
    }

    @Test
    fun `dependenciesSubGraphByVariant should return deps using VariantGraphKey for build scope`() {
        val expectDeps = setOf(projectB, projectC)
        assertEquals(
            expectDeps,
            dependenciesGraphs.dependenciesSubGraphByVariant(
                projectB,
                VariantGraphKey(":A:debugAndroidBuild")
            )
        )
    }

    @Test
    fun `dependenciesSubGraphByVariant should return deps using VariantGraphKey for test scope`() {
        val expectDeps = setOf(projectB, projectC, projectD, projectE)
        assertEquals(
            expectDeps,
            dependenciesGraphs.dependenciesSubGraphByVariant(
                projectB,
                VariantGraphKey(":A:debugUnitTestTest")
            )
        )
    }

    @Test
    fun `dependenciesSubGraphByVariant should return all deps when no variant keys provided`() {
        val expectDeps = setOf(projectB, projectC, projectD, projectE)
        assertEquals(
            expectDeps,
            dependenciesGraphs.dependenciesSubGraphByVariant(projectB)
        )
    }

    @Test
    fun `dependenciesSubGraphByVariant should return combined deps for multiple variants`() {
        val expectDeps = setOf(projectB, projectC, projectD, projectE)
        assertEquals(
            expectDeps,
            dependenciesGraphs.dependenciesSubGraphByVariant(
                projectB,
                VariantGraphKey(":A:debugAndroidBuild"),
                VariantGraphKey(":A:debugUnitTestTest")
            )
        )
    }

    private fun buildBuildGraphs(): ImmutableValueGraph<Project, Configuration> =
        ValueGraphBuilder.directed()
            .allowsSelfLoops(false)
            .expectedNodeCount(6)
            .build<Project, Configuration>().apply {
                putEdgeValue(projectA, projectB, FakeConfiguration())
                putEdgeValue(projectA, projectC, FakeConfiguration())
                putEdgeValue(projectB, projectC, FakeConfiguration())
            }.run { ImmutableValueGraph.copyOf(this) }

    private fun buildTestGraphs(): ImmutableValueGraph<Project, Configuration> =
        ValueGraphBuilder.directed()
            .allowsSelfLoops(false)
            .expectedNodeCount(6)
            .build<Project, Configuration>().apply {
                putEdgeValue(projectA, projectB, FakeConfiguration())
                putEdgeValue(projectA, projectC, FakeConfiguration())
                putEdgeValue(projectB, projectC, FakeConfiguration())
                putEdgeValue(projectC, projectD, FakeConfiguration())
                putEdgeValue(projectB, projectE, FakeConfiguration())
                putEdgeValue(projectA, projectE, FakeConfiguration())
            }.run { ImmutableValueGraph.copyOf(this) }
}

