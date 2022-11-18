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

package com.grab.grazel.fake

import com.google.common.graph.ImmutableValueGraph
import com.grab.grazel.gradle.dependencies.BuildGraphType
import com.grab.grazel.gradle.dependencies.DependencyGraphs
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration

internal class FakeDependencyGraphs(
    private val directDeps: Set<Project> = emptySet(),
    private val dependenciesSubGraph: Set<Project> = emptySet(),
    private val nodes: Set<Project> = emptySet(),
    override val buildGraphs: Map<BuildGraphType, ImmutableValueGraph<Project, Configuration>> = emptyMap()
) : DependencyGraphs {
    override fun nodes(vararg buildGraphType: BuildGraphType): Set<Project> = nodes

    override fun dependenciesSubGraph(
        project: Project,
        vararg buildGraphTypes: BuildGraphType
    ): Set<Project> =
        dependenciesSubGraph

    override fun directDependencies(
        project: Project,
        buildGraphTypes: BuildGraphType
    ): Set<Project> = directDeps
}