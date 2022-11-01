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

package com.grab.grazel.gradle

import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.rules.DAGGER_GROUP
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import com.grab.grazel.gradle.dependencies.DependencyGraphs
import dagger.Lazy
import org.gradle.api.Project
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Common metadata about a Gradle project.
 */
interface GradleProjectInfo {
    val rootProject: Project
    val grazelExtension: GrazelExtension
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
    private val dependencyGraphsProvider: Lazy<DependencyGraphs>,
    internal val dependenciesDataSource: DependenciesDataSource
) : GradleProjectInfo {

    private val projectGraph: DependencyGraphs get() = dependencyGraphsProvider.get()

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
