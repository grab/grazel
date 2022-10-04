package com.grab.grazel.gradle.dependencies

import com.google.common.graph.Graphs
import com.google.common.graph.ImmutableValueGraph
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration


internal interface DependencyGraphs {
    fun nodes(vararg buildGraphType: BuildGraphType): Set<Project>
    fun dependenciesSubGraph(
        project: Project,
        vararg buildGraphTypes: BuildGraphType
    ): Set<Project>

    fun directDependencies(
        project: Project,
        buildGraphType: BuildGraphType
    ): Set<Project>
}

internal class DefaultDependencyGraphs(
    private val buildGraphs: Map<BuildGraphType, ImmutableValueGraph<Project, Configuration>>
) : DependencyGraphs {
    override fun nodes(vararg buildGraphType: BuildGraphType): Set<Project> {
        return when {
            buildGraphType.isEmpty() -> buildGraphs.values.flatMap { it.nodes() }.toSet()
            else -> {
                buildGraphType.flatMap {
                    buildGraphs.getValue(it).nodes()
                }.toSet()
            }
        }
    }

    override fun dependenciesSubGraph(
        project: Project,
        vararg buildGraphTypes: BuildGraphType
    ): Set<Project> =
        if (buildGraphTypes.isEmpty()) {
            buildGraphs.values.flatMap {
                if (it.nodes().contains(project)) {
                    Graphs.reachableNodes(it.asGraph(), project)
                } else {
                    emptyList()
                }
            }
        } else {
            buildGraphTypes.flatMap { buildGraphType ->
                Graphs.reachableNodes(buildGraphs.getValue(buildGraphType).asGraph(), project)
            }
        }.toSet()

    override fun directDependencies(
        project: Project,
        buildGraphType: BuildGraphType
    ): Set<Project> = buildGraphs.getValue(buildGraphType).successors(project).toSet()
}