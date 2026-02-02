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

import com.google.common.graph.Graphs
import com.google.common.graph.ImmutableValueGraph
import com.grab.grazel.gradle.variant.VariantGraphKey
import com.grab.grazel.gradle.variant.VariantType
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration


internal interface DependencyGraphs {
    /**
     * Graph keyed by [VariantGraphKey]. Maps variant IDs to their corresponding dependency graphs.
     */
    val variantGraphs: Map<VariantGraphKey, ImmutableValueGraph<Project, Configuration>>

    /**
     * Returns all project nodes from graphs matching the given variant keys. If no keys are
     * provided, returns nodes from all variant graphs.
     */
    fun nodesByVariant(vararg variantKey: VariantGraphKey): Set<Project>

    /**
     * Returns the transitive dependency subgraph for a project, filtered by variant keys. If no
     * keys are provided, returns dependencies from all variant graphs.
     */
    fun dependenciesSubGraphByVariant(
        project: Project,
        vararg variantKeys: VariantGraphKey
    ): Set<Project>

    /** Returns direct dependencies for a project by variant key. */
    fun directDependenciesByVariant(
        project: Project,
        variantKey: VariantGraphKey
    ): Set<Project>

    /**
     * Merges variant graphs into a project-level dependency graph.
     *
     * @param variantTypeFilter Predicate to filter which variant types to include. Defaults to
     *    build graphs only (AndroidBuild, JvmBuild) to avoid artificial cycles from test
     *    dependencies.
     * @return Map of projects to their direct dependencies across filtered variants.
     */
    fun mergeToProjectGraph(
        variantTypeFilter: (VariantType) -> Boolean = { it.isBuildGraph }
    ): Map<Project, Set<Project>>
}

internal class DefaultDependencyGraphs(
    override val variantGraphs: Map<VariantGraphKey, ImmutableValueGraph<Project, Configuration>>
) : DependencyGraphs {

    override fun nodesByVariant(vararg variantKey: VariantGraphKey): Set<Project> {
        return when {
            variantKey.isEmpty() -> variantGraphs.values.flatMap { it.nodes() }.toSet()
            else -> {
                variantKey.flatMap {
                    variantGraphs[it]?.nodes() ?: emptySet()
                }.toSet()
            }
        }
    }

    override fun dependenciesSubGraphByVariant(
        project: Project,
        vararg variantKeys: VariantGraphKey
    ): Set<Project> = if (variantKeys.isEmpty()) {
        variantGraphs.values.flatMap {
            if (it.nodes().contains(project)) {
                Graphs.reachableNodes(it.asGraph(), project)
            } else {
                emptyList()
            }
        }
    } else {
        variantKeys.flatMap { variantKey ->
            variantGraphs[variantKey]?.let { graph ->
                if (graph.nodes().contains(project)) {
                    Graphs.reachableNodes(graph.asGraph(), project)
                } else {
                    emptySet()
                }
            } ?: emptySet()
        }
    }.toSet()

    override fun directDependenciesByVariant(
        project: Project,
        variantKey: VariantGraphKey
    ): Set<Project> = variantGraphs[variantKey]?.successors(project)?.toSet() ?: emptySet()

    override fun mergeToProjectGraph(
        variantTypeFilter: (VariantType) -> Boolean
    ): Map<Project, Set<Project>> {
        // Filter graphs by variant type
        val filteredGraphs = variantGraphs.filterKeys { key ->
            variantTypeFilter(key.variantType)
        }

        val allProjects = filteredGraphs.values.flatMap { it.nodes() }.toSet()
        return allProjects.associateWith { project ->
            filteredGraphs.values.flatMap { graph ->
                if (graph.nodes().contains(project)) {
                    graph.successors(project)
                } else emptySet()
            }.toSet()
        }
    }
}
