/*
 * Copyright 2021 Grabtaxi Holdings PTE LTE (GRAB)
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

package com.grab.grazel.gradle

import com.google.common.graph.ImmutableGraph
import com.google.common.graph.ImmutableValueGraph
import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.rules.DAGGER_GROUP
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import dagger.Lazy
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Common metadata about a Gradle project.
 */
interface GradleProjectInfo {
    val rootProject: Project
    val grazelExtension: GrazelExtension
    val projectGraph: ImmutableGraph<Project>
    val hasDagger: Boolean
    val hasDatabinding: Boolean
    val hasAndroidExtension: Boolean
    val hasGooglePlayServices: Boolean
}

@Singleton
@Suppress("UnstableApiUsage")
internal class DefaultGradleProjectInfo @Inject constructor(
    @param:RootProject
    override val rootProject: Project,
    override val grazelExtension: GrazelExtension,
    projectGraphProvider: Lazy<ImmutableValueGraph<Project, Configuration>>,
    internal val dependenciesDataSource: DependenciesDataSource
) : GradleProjectInfo {

    override val projectGraph: ImmutableGraph<Project> = projectGraphProvider.get().asGraph()

    override val hasDagger: Boolean by lazy {
        projectGraph.nodes().any { project ->
            dependenciesDataSource
                .mavenDependencies(project)
                .any { dependency -> dependency.group == DAGGER_GROUP }
        }
    }

    override val hasDatabinding: Boolean by lazy {
        projectGraph
            .nodes()
            .any { it.hasDatabinding }
    }

    override val hasAndroidExtension: Boolean by lazy {
        projectGraph
            .nodes()
            .any(Project::hasKotlinAndroidExtensions)
    }

    override val hasGooglePlayServices: Boolean by lazy {
        projectGraph
            .nodes()
            .any { project -> project.hasCrashlytics || project.hasGooglePlayServicesPlugin }
    }
}
