package com.grab.grazel.fake

import com.grab.grazel.gradle.ConfigurationScope
import com.grab.grazel.gradle.dependencies.DependencyGraphs
import org.gradle.api.Project

internal class FakeDependencyGraphs(
    private val directDeps: Set<Project> = emptySet(),
    private val dependenciesSubGraph: Set<Project> = emptySet(),
    private val nodes: Set<Project> = emptySet()
) : DependencyGraphs {
    override fun nodes(vararg scopes: ConfigurationScope): Set<Project> = nodes

    override fun dependenciesSubGraph(
        project: Project,
        scopes: Array<ConfigurationScope>
    ): Set<Project> =
        dependenciesSubGraph

    override fun directDependencies(
        project: Project,
        scopes: Array<ConfigurationScope>
    ): Set<Project> = directDeps
}