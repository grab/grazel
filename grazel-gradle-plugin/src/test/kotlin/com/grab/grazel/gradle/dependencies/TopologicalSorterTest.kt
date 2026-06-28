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

import com.google.common.graph.ValueGraphBuilder
import com.grab.grazel.buildProject
import com.grab.grazel.fake.FakeDependencyGraphs
import com.grab.grazel.fake.FakeConfiguration
import com.grab.grazel.fake.FakeProject
import com.grab.grazel.gradle.variant.VariantGraphKey
import com.grab.grazel.gradle.variant.VariantType
import org.gradle.api.Project
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TopologicalSorterTest {

    @Test
    fun `sort should return empty list for empty graph`() {
        val graphs = FakeDependencyGraphs(projectGraph = emptyMap())
        val result = TopologicalSorter.sort(graphs)
        assertEquals(emptyList(), result)
    }

    @Test
    fun `sort should return single project for single node graph`() {
        val projectA = FakeProject("A")
        val graphs = FakeDependencyGraphs(
            projectGraph = mapOf(projectA to emptySet())
        )
        val result = TopologicalSorter.sort(graphs)
        assertEquals(listOf(projectA), result)
    }

    @Test
    fun `sort should process linear chain in correct order`() {
        // Graph: :app -> :lib -> :core
        // Expected order: :core, :lib, :app (dependencies before dependents)
        val projectCore = FakeProject("core")
        val projectLib = FakeProject("lib")
        val projectApp = FakeProject("app")

        val graphs = FakeDependencyGraphs(
            projectGraph = mapOf(
                projectCore to emptySet(),
                projectLib to setOf(projectCore),
                projectApp to setOf(projectLib)
            )
        )

        val result = TopologicalSorter.sort(graphs)

        assertEquals(listOf(projectCore, projectLib, projectApp), result)
    }

    @Test
    fun `sort should handle diamond pattern correctly`() {
        // Graph: :app -> :lib1 -> :core
        //             -> :lib2 -> :core
        // Expected: :core before both libs, both libs before :app
        val projectCore = FakeProject("core")
        val projectLib1 = FakeProject("lib1")
        val projectLib2 = FakeProject("lib2")
        val projectApp = FakeProject("app")

        val graphs = FakeDependencyGraphs(
            projectGraph = mapOf(
                projectCore to emptySet(),
                projectLib1 to setOf(projectCore),
                projectLib2 to setOf(projectCore),
                projectApp to setOf(projectLib1, projectLib2)
            )
        )

        val result = TopologicalSorter.sort(graphs)

        // Core must be first
        assertEquals(projectCore, result[0])

        // Both libs must come before app
        val lib1Index = result.indexOf(projectLib1)
        val lib2Index = result.indexOf(projectLib2)
        val appIndex = result.indexOf(projectApp)

        assertTrue(lib1Index < appIndex, "lib1 should come before app")
        assertTrue(lib2Index < appIndex, "lib2 should come before app")

        // App must be last
        assertEquals(projectApp, result.last())
    }

    @Test
    fun `sort should detect cycles and throw exception`() {
        // Graph with cycle: :a -> :b -> :c -> :a
        val projectA = FakeProject("a")
        val projectB = FakeProject("b")
        val projectC = FakeProject("c")

        val graphs = FakeDependencyGraphs(
            projectGraph = mapOf(
                projectA to setOf(projectB),
                projectB to setOf(projectC),
                projectC to setOf(projectA)
            )
        )

        val exception = assertFailsWith<IllegalStateException> {
            TopologicalSorter.sort(graphs)
        }

        assertTrue(exception.message!!.contains("Cycle detected"))
        assertTrue(exception.message!!.contains("Cycle path:"))
        assertTrue(exception.message!!.contains(" -> "))
        assertTrue(exception.message!!.contains(":a") || exception.message!!.contains("a"))
    }

    @Test
    fun `sort should handle complex graph with multiple roots`() {
        // Graph: :core1 (root)
        //        :core2 (root)
        //        :lib1 -> :core1
        //        :lib2 -> :core1, :core2
        //        :app -> :lib1, :lib2
        val projectCore1 = FakeProject("core1")
        val projectCore2 = FakeProject("core2")
        val projectLib1 = FakeProject("lib1")
        val projectLib2 = FakeProject("lib2")
        val projectApp = FakeProject("app")

        val graphs = FakeDependencyGraphs(
            projectGraph = mapOf(
                projectCore1 to emptySet(),
                projectCore2 to emptySet(),
                projectLib1 to setOf(projectCore1),
                projectLib2 to setOf(projectCore1, projectCore2),
                projectApp to setOf(projectLib1, projectLib2)
            )
        )

        val result = TopologicalSorter.sort(graphs)

        // Verify dependency ordering
        val core1Index = result.indexOf(projectCore1)
        val core2Index = result.indexOf(projectCore2)
        val lib1Index = result.indexOf(projectLib1)
        val lib2Index = result.indexOf(projectLib2)
        val appIndex = result.indexOf(projectApp)

        // Cores before libs
        assertTrue(core1Index < lib1Index, "core1 should come before lib1")
        assertTrue(core1Index < lib2Index, "core1 should come before lib2")
        assertTrue(core2Index < lib2Index, "core2 should come before lib2")

        // Libs before app
        assertTrue(lib1Index < appIndex, "lib1 should come before app")
        assertTrue(lib2Index < appIndex, "lib2 should come before app")
    }

    @Test
    fun `sort should handle disconnected components`() {
        // Two independent chains:
        // :app1 -> :lib1
        // :app2 -> :lib2
        val projectLib1 = FakeProject("lib1")
        val projectApp1 = FakeProject("app1")
        val projectLib2 = FakeProject("lib2")
        val projectApp2 = FakeProject("app2")

        val graphs = FakeDependencyGraphs(
            projectGraph = mapOf(
                projectLib1 to emptySet(),
                projectApp1 to setOf(projectLib1),
                projectLib2 to emptySet(),
                projectApp2 to setOf(projectLib2)
            )
        )

        val result = TopologicalSorter.sort(graphs)

        // Verify each chain maintains order
        val lib1Index = result.indexOf(projectLib1)
        val app1Index = result.indexOf(projectApp1)
        val lib2Index = result.indexOf(projectLib2)
        val app2Index = result.indexOf(projectApp2)

        assertTrue(lib1Index < app1Index, "lib1 should come before app1")
        assertTrue(lib2Index < app2Index, "lib2 should come before app2")
        assertEquals(4, result.size)
    }

    @Test
    fun `sort should handle projects with shared dependencies`() {
        // Graph: :app1 -> :shared
        //        :app2 -> :shared
        //        :shared (leaf)
        val projectShared = FakeProject("shared")
        val projectApp1 = FakeProject("app1")
        val projectApp2 = FakeProject("app2")

        val graphs = FakeDependencyGraphs(
            projectGraph = mapOf(
                projectShared to emptySet(),
                projectApp1 to setOf(projectShared),
                projectApp2 to setOf(projectShared)
            )
        )

        val result = TopologicalSorter.sort(graphs)

        // Shared must come first
        assertEquals(projectShared, result[0])

        // Both apps must come after shared
        val sharedIndex = result.indexOf(projectShared)
        val app1Index = result.indexOf(projectApp1)
        val app2Index = result.indexOf(projectApp2)

        assertTrue(sharedIndex < app1Index, "shared should come before app1")
        assertTrue(sharedIndex < app2Index, "shared should come before app2")
    }

    @Test
    fun `sort should detect self-loop cycle`() {
        // Graph with self-loop: :a -> :a
        val projectA = FakeProject("a")

        val graphs = FakeDependencyGraphs(
            projectGraph = mapOf(
                projectA to setOf(projectA)
            )
        )

        val exception = assertFailsWith<IllegalStateException> {
            TopologicalSorter.sort(graphs)
        }

        assertTrue(exception.message!!.contains("Cycle detected"))
        assertTrue(exception.message!!.contains("Cycle path:"))
        // Self-loop should show :a -> :a
        val message = exception.message!!
        val cyclePathMatch = Regex(":a -> :a").find(message)
        assertTrue(cyclePathMatch != null, "Self-loop should show :a -> :a in cycle path")
    }

    @Test
    fun `sort should detect cycle with blocked dependents`() {
        // Graph: :a -> :b -> :c -> :a (cycle)
        //        :d -> :a (dependent blocked by cycle)
        val projectA = FakeProject("a")
        val projectB = FakeProject("b")
        val projectC = FakeProject("c")
        val projectD = FakeProject("d")

        val graphs = FakeDependencyGraphs(
            projectGraph = mapOf(
                projectA to setOf(projectB),
                projectB to setOf(projectC),
                projectC to setOf(projectA),
                projectD to setOf(projectA)
            )
        )

        val exception = assertFailsWith<IllegalStateException> {
            TopologicalSorter.sort(graphs)
        }

        assertTrue(exception.message!!.contains("Cycle detected"))
        assertTrue(exception.message!!.contains("Cycle path:"))
        // The message should mention unprocessed projects
        assertTrue(exception.message!!.contains("Unprocessed projects"))
    }

    @Test
    fun `sort should detect nested cycle with back-edge`() {
        // Graph: :a -> :b -> :c -> :d -> :b (back-edge to :b, not :a)
        val projectA = FakeProject("a")
        val projectB = FakeProject("b")
        val projectC = FakeProject("c")
        val projectD = FakeProject("d")

        val graphs = FakeDependencyGraphs(
            projectGraph = mapOf(
                projectA to setOf(projectB),
                projectB to setOf(projectC),
                projectC to setOf(projectD),
                projectD to setOf(projectB)
            )
        )

        val exception = assertFailsWith<IllegalStateException> {
            TopologicalSorter.sort(graphs)
        }

        assertTrue(exception.message!!.contains("Cycle detected"))
        assertTrue(exception.message!!.contains("Cycle path:"))
        // Should contain :b in the cycle
        assertTrue(exception.message!!.contains(":b"))
    }

    @Test
    fun `sort should detect first cycle in graph with multiple disconnected cycles`() {
        // Two independent cycles:
        // Cycle 1: :a -> :b -> :a
        // Cycle 2: :c -> :d -> :c
        val projectA = FakeProject("a")
        val projectB = FakeProject("b")
        val projectC = FakeProject("c")
        val projectD = FakeProject("d")

        val graphs = FakeDependencyGraphs(
            projectGraph = mapOf(
                projectA to setOf(projectB),
                projectB to setOf(projectA),
                projectC to setOf(projectD),
                projectD to setOf(projectC)
            )
        )

        val exception = assertFailsWith<IllegalStateException> {
            TopologicalSorter.sort(graphs)
        }

        assertTrue(exception.message!!.contains("Cycle detected"))
        assertTrue(exception.message!!.contains("Cycle path:"))
        // Should detect at least one cycle (deterministically the first alphabetically)
        assertTrue(exception.message!!.contains(":a") || exception.message!!.contains(":c"))
    }

    @Test
    fun `sort should not detect cycle when test depends on build dependency`() {
        // Scenario: A depends on B (build), B test-depends on A (test)
        // This is NOT a real cycle - test deps are in separate graph
        // The merged project graph with default filtering should only include build deps
        val projectA = FakeProject("a")
        val projectB = FakeProject("b")

        // When mergeToProjectGraph() is called with default filter,
        // it will only include build graphs: A -> B
        // The test graph (B -> A) is filtered out
        val graphs = FakeDependencyGraphs(
            projectGraph = mapOf(
                projectA to setOf(projectB),  // A depends on B (build only)
                projectB to emptySet()         // B has no build deps (test dep filtered out)
            )
        )

        // Should succeed without cycle detection
        val result = TopologicalSorter.sort(graphs)
        assertEquals(listOf(projectB, projectA), result)
    }

    @Test
    fun `reachability groups fail instead of condensing cycles`() {
        // Graph: :app -> :a -> :b -> :a, and :b -> :leaf
        val projectApp = FakeProject("app")
        val projectA = FakeProject("a")
        val projectB = FakeProject("b")
        val projectLeaf = FakeProject("leaf")

        val graphs = FakeDependencyGraphs(
            projectGraph = mapOf(
                projectApp to setOf(projectA),
                projectA to setOf(projectB),
                projectB to setOf(projectA, projectLeaf),
                projectLeaf to emptySet()
            )
        )

        val exception = assertFailsWith<IllegalStateException> {
            ProjectReachabilityOrder.consumersFirstGroups(graphs)
        }

        val message = exception.message.orEmpty()
        assertTrue(message.contains("Typed dependency cycle detected"), message)
        assertTrue(message.contains(":a[Main]"), message)
        assertTrue(message.contains(":b[Main]"), message)
    }

    @Test
    fun `reachability groups handle deep dependency chain iteratively`() {
        val projects = (0 until 2_000).map { index -> FakeProject("p%04d".format(index)) }
        val graphs = FakeDependencyGraphs(
            projectGraph = projects.mapIndexed { index, project ->
                project to projects.getOrNull(index + 1)?.let(::setOf).orEmpty()
            }.toMap()
        )

        val groups = ProjectReachabilityOrder.consumersFirstGroups(graphs)

        assertEquals(projects.size, groups.size)
        assertEquals(projects.first(), groups.first().projects.single())
        assertEquals(projects.last(), groups.last().projects.single())
    }

    @Test
    fun `reachability groups do not fabricate cycle when test source set depends on a build dependency`() {
        val projectA = FakeProject("a")
        val projectB = FakeProject("b")
        val graphs = FakeDependencyGraphs(
            typedReachabilityGraph = mapOf(
                DependencyGraphNode(projectA, DependencyGraphSourceSet.Test) to setOf(
                    DependencyGraphNode(projectB, DependencyGraphSourceSet.Main)
                ),
                DependencyGraphNode(projectB, DependencyGraphSourceSet.Main) to setOf(
                    DependencyGraphNode(projectA, DependencyGraphSourceSet.Main)
                ),
                DependencyGraphNode(projectA, DependencyGraphSourceSet.Main) to emptySet()
            )
        )

        val groups = ProjectReachabilityOrder.consumersFirstGroups(graphs)

        assertEquals(listOf(projectA, projectB), groups.flatMap(ProjectReachabilityGroup::projects))
    }

    @Test
    fun `reachability groups keep consumer before app when path order would prefer app`() {
        val projectApp = FakeProject("app")
        val projectUiTest = FakeProject("ui-test")
        val graphs = FakeDependencyGraphs(
            typedReachabilityGraph = mapOf(
                DependencyGraphNode(projectUiTest, DependencyGraphSourceSet.AndroidTest) to setOf(
                    DependencyGraphNode(projectApp, DependencyGraphSourceSet.Main)
                ),
                DependencyGraphNode(projectApp, DependencyGraphSourceSet.Main) to emptySet()
            )
        )

        val groups = ProjectReachabilityOrder.consumersFirstGroups(graphs)

        assertEquals(listOf(projectUiTest, projectApp), groups.flatMap(ProjectReachabilityGroup::projects))
    }

    @Test
    fun `reachability groups preserve reversed path tie break for independent ready nodes`() {
        val projectA = FakeProject("a")
        val projectB = FakeProject("b")
        val graphs = FakeDependencyGraphs(
            typedReachabilityGraph = mapOf(
                DependencyGraphNode(projectA, DependencyGraphSourceSet.Main) to emptySet(),
                DependencyGraphNode(projectB, DependencyGraphSourceSet.Main) to emptySet()
            )
        )

        val groups = ProjectReachabilityOrder.consumersFirstGroups(graphs)

        assertEquals(listOf(projectB, projectA), groups.flatMap(ProjectReachabilityGroup::projects))
    }

    @Test
    fun `reachability groups collapse multiple source set nodes to first consumer project occurrence`() {
        val projectA = FakeProject("a")
        val projectB = FakeProject("b")
        val graphs = FakeDependencyGraphs(
            typedReachabilityGraph = mapOf(
                DependencyGraphNode(projectA, DependencyGraphSourceSet.Test) to setOf(
                    DependencyGraphNode(projectA, DependencyGraphSourceSet.Main)
                ),
                DependencyGraphNode(projectA, DependencyGraphSourceSet.Main) to emptySet(),
                DependencyGraphNode(projectB, DependencyGraphSourceSet.Main) to emptySet()
            )
        )

        val groups = ProjectReachabilityOrder.consumersFirstGroups(graphs)

        assertEquals(listOf(projectA, projectB), groups.flatMap(ProjectReachabilityGroup::projects))
    }

    @Test
    fun `reachability groups fail with typed diagnostic for genuine typed SCC`() {
        val rootProject = buildProject("root")
        val projectA = buildProject("a", rootProject)
        val projectB = buildProject("b", rootProject)
        val graph = ValueGraphBuilder
            .directed()
            .allowsSelfLoops(true)
            .immutable<Project, DependencyGraphEdge>()
            .putEdgeValue(projectA, projectB, ConfigurationEdge(FakeConfiguration("implementation")))
            .putEdgeValue(projectB, projectA, ConfigurationEdge(FakeConfiguration("api")))
            .build()
        val graphs = DefaultDependencyGraphs(
            variantGraphs = mapOf(
                VariantGraphKey(":a:debugAndroidBuild", VariantType.AndroidBuild) to graph
            )
        )

        val exception = assertFailsWith<IllegalStateException> {
            ProjectReachabilityOrder.consumersFirstGroups(graphs)
        }

        val message = exception.message.orEmpty()
        assertTrue(message.contains("Typed dependency cycle detected"), message)
        assertTrue(message.contains(":a[Main]"), message)
        assertTrue(message.contains(":b[Main]"), message)
        assertTrue(message.contains("ConfigurationEdge"), message)
        assertTrue(message.contains("implementation"), message)
        assertTrue(message.contains("api"), message)
    }

    @Test
    fun `reachability groups fail even when genuine cycle project appeared as test source first`() {
        val projectA = FakeProject("a")
        val projectB = FakeProject("b")
        val nodeATest = DependencyGraphNode(projectA, DependencyGraphSourceSet.Test)
        val nodeAMain = DependencyGraphNode(projectA, DependencyGraphSourceSet.Main)
        val nodeBMain = DependencyGraphNode(projectB, DependencyGraphSourceSet.Main)
        val graphs = FakeDependencyGraphs(
            typedReachabilityGraph = mapOf(
                nodeATest to setOf(nodeBMain),
                nodeAMain to setOf(nodeBMain),
                nodeBMain to setOf(nodeAMain)
            )
        )

        val exception = assertFailsWith<IllegalStateException> {
            ProjectReachabilityOrder.consumersFirstGroups(graphs)
        }

        val message = exception.message.orEmpty()
        assertTrue(message.contains("Typed dependency cycle detected"), message)
        assertTrue(message.contains(":a[Main]"), message)
        assertTrue(message.contains(":b[Main]"), message)
    }
}
