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
import com.grab.grazel.buildProject
import com.grab.grazel.fake.FakeConfiguration
import com.grab.grazel.fake.FakeProject
import com.grab.grazel.gradle.variant.VariantGraphKey
import com.grab.grazel.gradle.variant.VariantType
import org.gradle.api.Project
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
            VariantGraphKey(":A:debugAndroidBuild", VariantType.AndroidBuild) to buildBuildGraphs(),
            VariantGraphKey(":A:debugUnitTestTest", VariantType.Test) to buildTestGraphs()
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
            dependenciesGraphs.nodesByVariant(VariantGraphKey(":A:debugAndroidBuild", VariantType.AndroidBuild))
        )
    }

    @Test
    fun `nodesByVariant should return correct nodes for test variant`() {
        val testNodes = setOf(projectA, projectB, projectC, projectD, projectE)
        assertEquals(
            testNodes,
            dependenciesGraphs.nodesByVariant(VariantGraphKey(":A:debugUnitTestTest", VariantType.Test))
        )
    }

    @Test
    fun `nodesByVariant should return combined nodes for multiple variants`() {
        val buildNodes = setOf(projectA, projectB, projectC)
        val testNodes = setOf(projectA, projectB, projectC, projectD, projectE)
        assertEquals(
            testNodes + buildNodes,
            dependenciesGraphs.nodesByVariant(
                VariantGraphKey(":A:debugAndroidBuild", VariantType.AndroidBuild),
                VariantGraphKey(":A:debugUnitTestTest", VariantType.Test)
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
                VariantGraphKey(":A:debugAndroidBuild", VariantType.AndroidBuild)
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
                VariantGraphKey(":A:debugAndroidBuild", VariantType.AndroidBuild)
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
                VariantGraphKey(":A:debugUnitTestTest", VariantType.Test)
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
                VariantGraphKey(":A:debugAndroidBuild", VariantType.AndroidBuild),
                VariantGraphKey(":A:debugUnitTestTest", VariantType.Test)
            )
        )
    }

    @Test
    fun `reachabilityGraph maps test source set dependencies to main targets`() {
        val testGraph = ValueGraphBuilder.directed()
            .allowsSelfLoops(false)
            .build<Project, DependencyGraphEdge>().apply {
                putEdgeValue(projectA, projectB, ConfigurationEdge(FakeConfiguration()))
            }.run { ImmutableValueGraph.copyOf(this) }
        val graphs = DefaultDependencyGraphs(
            variantGraphs = mapOf(
                VariantGraphKey(":A:debugUnitTestTest", VariantType.Test) to testGraph
            )
        )

        assertEquals(
            mapOf(
                DependencyGraphNode(projectA, DependencyGraphSourceSet.Main) to emptySet(),
                DependencyGraphNode(projectA, DependencyGraphSourceSet.Test) to setOf(
                    DependencyGraphNode(projectA, DependencyGraphSourceSet.Main),
                    DependencyGraphNode(projectB, DependencyGraphSourceSet.Main)
                ),
                DependencyGraphNode(projectB, DependencyGraphSourceSet.Main) to emptySet()
            ),
            graphs.reachabilityGraph()
        )
    }

    @Test
    fun `reachabilityGraph adds source-set inheritance edge from test to main`() {
        val testGraph = ValueGraphBuilder.directed()
            .allowsSelfLoops(false)
            .build<Project, DependencyGraphEdge>().apply {
                putEdgeValue(projectA, projectB, ConfigurationEdge(FakeConfiguration()))
            }.run { ImmutableValueGraph.copyOf(this) }
        val graphs = DefaultDependencyGraphs(
            variantGraphs = mapOf(
                VariantGraphKey(":A:debugUnitTestTest", VariantType.Test) to testGraph
            )
        )

        assertEquals(
            setOf(
                DependencyGraphNode(projectA, DependencyGraphSourceSet.Main),
                DependencyGraphNode(projectB, DependencyGraphSourceSet.Main)
            ),
            graphs.reachabilityGraph().getValue(
                DependencyGraphNode(projectA, DependencyGraphSourceSet.Test)
            )
        )
    }

    @Test
    fun `reachabilityGraph keeps PAX testImplementation back edge acyclic as typed nodes`() {
        val rootProject = buildProject("root")
        val deliveriesModelFood = buildProject("deliveries-model-food", rootProject)
        val foodTestkit = buildProject("food-testkit", rootProject)
        val mainGraph = ValueGraphBuilder.directed()
            .allowsSelfLoops(false)
            .build<Project, DependencyGraphEdge>().apply {
                putEdgeValue(foodTestkit, deliveriesModelFood, ConfigurationEdge(FakeConfiguration("implementation")))
            }.run { ImmutableValueGraph.copyOf(this) }
        val testGraph = ValueGraphBuilder.directed()
            .allowsSelfLoops(false)
            .build<Project, DependencyGraphEdge>().apply {
                putEdgeValue(deliveriesModelFood, foodTestkit, ConfigurationEdge(FakeConfiguration("testImplementation")))
            }.run { ImmutableValueGraph.copyOf(this) }
        val graphs = DefaultDependencyGraphs(
            variantGraphs = mapOf(
                VariantGraphKey(":food-testkit:debugAndroidBuild", VariantType.AndroidBuild) to mainGraph,
                VariantGraphKey(":deliveries-model-food:debugUnitTestTest", VariantType.Test) to testGraph
            )
        )

        assertEquals(
            setOf(
                DependencyGraphNode(deliveriesModelFood, DependencyGraphSourceSet.Main),
                DependencyGraphNode(foodTestkit, DependencyGraphSourceSet.Main)
            ),
            graphs.reachabilityGraph().getValue(
                DependencyGraphNode(deliveriesModelFood, DependencyGraphSourceSet.Test)
            )
        )
        val groups = ProjectReachabilityOrder.consumersFirstGroups(graphs)
        assertEquals(
            listOf(deliveriesModelFood, foodTestkit),
            groups.flatMap(ProjectReachabilityGroup::projects)
        )
    }

    private fun buildBuildGraphs(): ImmutableValueGraph<Project, DependencyGraphEdge> =
        ValueGraphBuilder.directed()
            .allowsSelfLoops(false)
            .expectedNodeCount(6)
            .build<Project, DependencyGraphEdge>().apply {
                putEdgeValue(projectA, projectB, ConfigurationEdge(FakeConfiguration()))
                putEdgeValue(projectA, projectC, ConfigurationEdge(FakeConfiguration()))
                putEdgeValue(projectB, projectC, ConfigurationEdge(FakeConfiguration()))
            }.run { ImmutableValueGraph.copyOf(this) }

    private fun buildTestGraphs(): ImmutableValueGraph<Project, DependencyGraphEdge> =
        ValueGraphBuilder.directed()
            .allowsSelfLoops(false)
            .expectedNodeCount(6)
            .build<Project, DependencyGraphEdge>().apply {
                putEdgeValue(projectA, projectB, ConfigurationEdge(FakeConfiguration()))
                putEdgeValue(projectA, projectC, ConfigurationEdge(FakeConfiguration()))
                putEdgeValue(projectB, projectC, ConfigurationEdge(FakeConfiguration()))
                putEdgeValue(projectC, projectD, ConfigurationEdge(FakeConfiguration()))
                putEdgeValue(projectB, projectE, ConfigurationEdge(FakeConfiguration()))
                putEdgeValue(projectA, projectE, ConfigurationEdge(FakeConfiguration()))
            }.run { ImmutableValueGraph.copyOf(this) }

    @Test
    fun `mergeToProjectGraph should filter test graphs by default`() {
        // Create scenario where test graph would create artificial cycle
        val projectX = FakeProject("X")
        val projectY = FakeProject("Y")

        // Build graph: X -> Y
        val buildGraph = ValueGraphBuilder.directed()
            .allowsSelfLoops(false)
            .build<Project, DependencyGraphEdge>().apply {
                putEdgeValue(projectX, projectY, ConfigurationEdge(FakeConfiguration()))
            }.run { ImmutableValueGraph.copyOf(this) }

        // Test graph: Y -> X (would create cycle if merged)
        val testGraph = ValueGraphBuilder.directed()
            .allowsSelfLoops(false)
            .build<Project, DependencyGraphEdge>().apply {
                putEdgeValue(projectY, projectX, ConfigurationEdge(FakeConfiguration()))
            }.run { ImmutableValueGraph.copyOf(this) }

        val graphs = DefaultDependencyGraphs(
            variantGraphs = mapOf(
                VariantGraphKey(":X:debugAndroidBuild", VariantType.AndroidBuild) to buildGraph,
                VariantGraphKey(":Y:debugUnitTestTest", VariantType.Test) to testGraph
            )
        )

        // Default filter excludes Test graphs
        val merged = graphs.mergeToProjectGraph()

        // Should only see X -> Y from build graph, not Y -> X from test graph
        assertEquals(setOf(projectY), merged[projectX])
        assertEquals(emptySet(), merged[projectY])
    }

    @Test
    fun `mergeToProjectGraph should include all graphs when filter allows all`() {
        val projectX = FakeProject("X")
        val projectY = FakeProject("Y")

        val buildGraph = ValueGraphBuilder.directed()
            .allowsSelfLoops(false)
            .build<Project, DependencyGraphEdge>().apply {
                putEdgeValue(projectX, projectY, ConfigurationEdge(FakeConfiguration()))
            }.run { ImmutableValueGraph.copyOf(this) }

        val testGraph = ValueGraphBuilder.directed()
            .allowsSelfLoops(false)
            .build<Project, DependencyGraphEdge>().apply {
                putEdgeValue(projectY, projectX, ConfigurationEdge(FakeConfiguration()))
            }.run { ImmutableValueGraph.copyOf(this) }

        val graphs = DefaultDependencyGraphs(
            variantGraphs = mapOf(
                VariantGraphKey(":X:debugAndroidBuild", VariantType.AndroidBuild) to buildGraph,
                VariantGraphKey(":Y:debugUnitTestTest", VariantType.Test) to testGraph
            )
        )

        // Allow all variant types
        val merged = graphs.mergeToProjectGraph { true }

        // Should see both X -> Y and Y -> X
        assertEquals(setOf(projectY), merged[projectX])
        assertEquals(setOf(projectX), merged[projectY])
    }

    @Test
    fun `mergeToProjectGraph should filter only build graphs correctly`() {
        val projectX = FakeProject("X")
        val projectY = FakeProject("Y")
        val projectZ = FakeProject("Z")

        // AndroidBuild graph: X -> Y
        val androidBuildGraph = ValueGraphBuilder.directed()
            .allowsSelfLoops(false)
            .build<Project, DependencyGraphEdge>().apply {
                putEdgeValue(projectX, projectY, ConfigurationEdge(FakeConfiguration()))
            }.run { ImmutableValueGraph.copyOf(this) }

        // JvmBuild graph: Y -> Z
        val jvmBuildGraph = ValueGraphBuilder.directed()
            .allowsSelfLoops(false)
            .build<Project, DependencyGraphEdge>().apply {
                putEdgeValue(projectY, projectZ, ConfigurationEdge(FakeConfiguration()))
            }.run { ImmutableValueGraph.copyOf(this) }

        // Test graph: Z -> X (would create cycle)
        val testGraph = ValueGraphBuilder.directed()
            .allowsSelfLoops(false)
            .build<Project, DependencyGraphEdge>().apply {
                putEdgeValue(projectZ, projectX, ConfigurationEdge(FakeConfiguration()))
            }.run { ImmutableValueGraph.copyOf(this) }

        // AndroidTest graph: also excluded
        val androidTestGraph = ValueGraphBuilder.directed()
            .allowsSelfLoops(false)
            .build<Project, DependencyGraphEdge>().apply {
                putEdgeValue(projectZ, projectY, ConfigurationEdge(FakeConfiguration()))
            }.run { ImmutableValueGraph.copyOf(this) }

        val graphs = DefaultDependencyGraphs(
            variantGraphs = mapOf(
                VariantGraphKey(":X:debugAndroidBuild", VariantType.AndroidBuild) to androidBuildGraph,
                VariantGraphKey(":Y:defaultJvmBuild", VariantType.JvmBuild) to jvmBuildGraph,
                VariantGraphKey(":Z:debugUnitTestTest", VariantType.Test) to testGraph,
                VariantGraphKey(":Z:debugAndroidTest", VariantType.AndroidTest) to androidTestGraph
            )
        )

        // Default filter includes only build graphs (AndroidBuild, JvmBuild)
        val merged = graphs.mergeToProjectGraph()

        // Should only see build graph edges, not test edges
        assertEquals(setOf(projectY), merged[projectX])
        assertEquals(setOf(projectZ), merged[projectY])
        assertEquals(emptySet(), merged[projectZ]) // No test edges included
    }
}
