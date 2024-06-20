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
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.dependencies.DependencyGraphs
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import dagger.Lazy
import org.gradle.api.Project
import org.gradle.kotlin.dsl.provideDelegate
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Common metadata about a Gradle project.
 */
@Deprecated(message = "Consider migrating to target API")
interface GradleProjectInfo {
    val rootProject: Project
    val grazelExtension: GrazelExtension
    val hasDagger: Boolean
    val hasAndroidExtension: Boolean
    val hasGooglePlayServices: Boolean
    val rootLintXml: File // TODO(arun) Implementing here due to lack of better place for root project data.
    val rootDetektYml: File
}

internal class DefaultGradleProjectInfo(
    override val rootProject: Project,
    override val grazelExtension: GrazelExtension,
    private val dependencyGraphsProvider: Lazy<DependencyGraphs>,
    private val workspaceDependencies: WorkspaceDependencies
) : GradleProjectInfo {

    @Singleton
    class Factory
    @Inject constructor(
        @param:RootProject
        private val rootProject: Project,
        private val grazelExtension: GrazelExtension,
        private val dependencyGraphsProvider: Lazy<DependencyGraphs>,
    ) {
        fun create(
            workspaceDependencies: WorkspaceDependencies
        ): GradleProjectInfo = DefaultGradleProjectInfo(
            rootProject,
            grazelExtension,
            dependencyGraphsProvider,
            workspaceDependencies
        )
    }

    private val projectGraph: DependencyGraphs get() = dependencyGraphsProvider.get()

    override val hasDagger: Boolean by lazy {
        workspaceDependencies
            .result
            .values
            .parallelStream()
            .flatMap { it.stream() }
            .anyMatch { it.shortId.contains("com.google.dagger") }
    }

    override val hasAndroidExtension: Boolean by lazy {
        projectGraph
            .nodes()
            .any(Project::hasKotlinAndroidExtensions)
    }

    override val hasGooglePlayServices: Boolean by lazy {
        rootProject
            .subprojects
            .any { project -> project.hasCrashlytics || project.hasGooglePlayServicesPlugin }
    }
    override val rootLintXml: File by lazy {
        rootProject.file("lint.xml")
    }
    override val rootDetektYml: File
        get() = rootProject.file("detekt-config.yml")
}
