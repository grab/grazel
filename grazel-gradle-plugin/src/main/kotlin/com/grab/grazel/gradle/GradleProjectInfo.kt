/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
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
