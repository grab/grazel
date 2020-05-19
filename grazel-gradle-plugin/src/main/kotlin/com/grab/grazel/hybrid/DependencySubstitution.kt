/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.hybrid

import com.grab.grazel.gradle.isMigrated
import com.jakewharton.picnic.TextAlignment.MiddleCenter
import com.jakewharton.picnic.table
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.artifacts.ProjectDependency
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Register rules for substituting modules built by Bazel.
 *
 * @receiver `Project` The root project instance
 */
internal fun Project.registerDependencySubstitutionRules(
    artifactSearcherFactory: ArtifactSearcherFactory = DefaultArtifactSearcherFactory()
) {
    if (this != rootProject) {
        throw IllegalArgumentException("Expected root project instance for substituting dependencies")
    }
    afterEvaluate {
        logger.debug("Caching migrated modules and their artifacts")
        var measuredMillis = System.currentTimeMillis()

        val artifactCache = subprojects
            .filter(Project::isMigrated)
            .map { project ->
                val artifactSearcher = artifactSearcherFactory.newInstance(project)
                val builtArtifacts = artifactSearcher.findArtifacts()
                project.path to builtArtifacts
            }.toMap()

        measuredMillis = System.currentTimeMillis() - measuredMillis
        val measuredSeconds = TimeUnit.MILLISECONDS.toSeconds(measuredMillis)
        logger.quiet("Caching artifacts took $measuredSeconds seconds.")

        subprojects {
            afterEvaluate {
                val sourceProject = this
                configurations.configureEach {
                    val configuration = this
                    withDependencies {
                        performSubstitution(sourceProject, configuration, this, artifactCache)
                    }
                }
            }
        }
    }
}

private fun performSubstitution(
    sourceProject: Project,
    configuration: Configuration,
    dependencySet: DependencySet,
    artifactCache: Map<String, List<File>>
) {
    val migratedProjects = dependencySet.asSequence()
        .filterIsInstance<ProjectDependency>()
        .filter { it.dependencyProject.isMigrated }
        .toList()

    if (migratedProjects.isEmpty()) return

    dependencySet.removeAll(migratedProjects)

    table {
        cellStyle {
            border = true
        }
        header {
            cellStyle {
                alignment = MiddleCenter
            }
            row {
                cell(sourceProject.path) {
                    columnSpan = 3
                }
            }
            row("Configuration", "Project", "Substitution Artifacts")
        }
        body {
            migratedProjects.forEach { projectDependency: ProjectDependency ->
                val rootProject = sourceProject.rootProject
                val depProject = projectDependency.dependencyProject
                val buildArtifacts = artifactCache.getOrDefault(depProject.path, emptyList())

                if (buildArtifacts.isNotEmpty()) {
                    val artifactNames = buildArtifacts.map { it.name }
                    val dependency = sourceProject
                        .dependencies
                        .create(rootProject.files(buildArtifacts))
                    dependencySet.add(dependency)
                    row(configuration.name, depProject.path, artifactNames)
                } else {
                    sourceProject.logger.error("Build artifact was not found for ${depProject.path}, skipping substitution")
                    throw GradleException("Dependency substitution failed for ${sourceProject.path} for dependency ${depProject.path}")
                }
            }
        }
    }.let { table -> println(table.toString()) }
}
