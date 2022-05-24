package com.grab.grazel.gradle.dependencies

import com.google.common.graph.Graphs
import com.google.common.graph.ImmutableValueGraph
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.gradle.ConfigurationScope
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration


internal interface DependencyGraphs {
    fun nodes(vararg scopes: ConfigurationScope): Set<Project>
    fun dependenciesSubGraph(
        project: Project,
        scopes: Array<ConfigurationScope> = ConfigurationScope.values()
    ): Set<Project>

    fun directDependencies(
        project: Project,
        scopes: Array<ConfigurationScope> = ConfigurationScope.values()
    ): Set<Project>
}

internal fun DependencyGraphs.directProjectDependencies(
    project: Project,
    vararg scopes: ConfigurationScope
) = directDependencies(project, *scopes).map { BazelDependency.ProjectDependency(it) }

internal class DefaultDependencyGraphs(
    private val buildGraph: ImmutableValueGraph<Project, Configuration>,
    private val testGraph: ImmutableValueGraph<Project, Configuration>
) : DependencyGraphs {
    override fun nodes(vararg scopes: ConfigurationScope): Set<Project> {
        return when {
            scopes.isEmpty() -> buildGraph.nodes() + testGraph.nodes()
            else -> {
                scopes.flatMap {
                    when (it) {
                        ConfigurationScope.BUILD -> buildGraph.nodes()
                        ConfigurationScope.TEST -> testGraph.nodes()
                        else -> emptySet<Project>()
                    }
                }.toSet()
            }
        }
    }

    override fun dependenciesSubGraph(
        project: Project,
        scopes: Array<ConfigurationScope>
    ): Set<Project> =
        scopes.flatMap {
            when (it) {
                ConfigurationScope.BUILD -> Graphs.reachableNodes(buildGraph.asGraph(), project)
                ConfigurationScope.TEST -> Graphs.reachableNodes(testGraph.asGraph(), project)
                else -> emptySet<Project>()
            }
        }.toSet()

    override fun directDependencies(
        project: Project,
        scopes: Array<ConfigurationScope>
    ): Set<Project> =
        scopes.flatMap {
            when (it) {
                ConfigurationScope.BUILD -> buildGraph.successors(project)
                ConfigurationScope.TEST -> testGraph.successors(project)
                else -> emptySet<Project>()
            }
        }.toSet()
}

internal fun DependencyGraphs.dependenciesSubGraph(
    project: Project,
    vararg scopes: ConfigurationScope
): Set<Project> {
    return dependenciesSubGraph(project, arrayOf(*scopes))
}

internal fun DependencyGraphs.directDependencies(
    project: Project,
    vararg scopes: ConfigurationScope
): Set<Project> {
    return directDependencies(project, arrayOf(*scopes))
}