/*
 * Copyright 2026 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.gradle.variant

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalDependency

internal data class WorkspaceKspProcessorClasspathInput(
    val project: Project,
    val declarationConfigurations: Set<Configuration>,
    val processorClasspath: Configuration,
    val directDependencyShortIds: Set<String>
)

private data class KspProcessorClasspathKey(
    val projectPath: String,
    val configurationName: String
)

private class KspProcessorClasspathState(
    private val project: Project,
    private val processorClasspath: Configuration
) {
    private val declarationConfigurations = linkedSetOf<Configuration>()
    private val directDependencyShortIds = sortedSetOf<String>()

    fun add(
        declarationConfigurations: Set<Configuration>,
        directDependencyShortIds: Set<String>
    ) {
        this.declarationConfigurations.addAll(declarationConfigurations)
        this.directDependencyShortIds.addAll(directDependencyShortIds)
    }

    fun toInput(): WorkspaceKspProcessorClasspathInput {
        return WorkspaceKspProcessorClasspathInput(
            project = project,
            declarationConfigurations = declarationConfigurations.toSet(),
            processorClasspath = processorClasspath,
            directDependencyShortIds = directDependencyShortIds.toSortedSet()
        )
    }
}

internal object WorkspaceKspProcessorClasspathPlanner {
    fun plan(
        migratableProjects: Iterable<Project>,
        variantsByProject: Map<Project, Iterable<Variant<*>>>
    ): List<WorkspaceKspProcessorClasspathInput> {
        val inputsByClasspath = linkedMapOf<KspProcessorClasspathKey, KspProcessorClasspathState>()
        migratableProjects
            .sortedBy(Project::getPath)
            .forEach { project ->
                (variantsByProject[project] ?: emptyList())
                    .sortedWith(compareBy<Variant<*>> { variant -> variant.variantType.name }.thenBy { variant -> variant.name })
                    .forEach { variant ->
                        kspConfigurationsOrEmpty(variant).forEach { processorClasspath ->
                            val declarationConfigurations = declarationConfigurationsFor(processorClasspath)
                            val directShortIds = directDependencyShortIds(declarationConfigurations)
                            if (directShortIds.isNotEmpty()) {
                                inputsByClasspath
                                    .getOrPut(
                                        KspProcessorClasspathKey(
                                            projectPath = project.path,
                                            configurationName = processorClasspath.name
                                        )
                                    ) {
                                        KspProcessorClasspathState(
                                            project = project,
                                            processorClasspath = processorClasspath
                                        )
                                    }
                                    .add(
                                        declarationConfigurations = declarationConfigurations,
                                        directDependencyShortIds = directShortIds
                                    )
                            }
                        }
                    }
            }

        return inputsByClasspath
            .values
            .map(KspProcessorClasspathState::toInput)
            .sortedWith(compareBy<WorkspaceKspProcessorClasspathInput> { input -> input.project.path }
                .thenBy { input -> input.processorClasspath.name })
    }

    private fun kspConfigurationsOrEmpty(variant: Variant<*>): Set<Configuration> {
        return try {
            variant.kspConfiguration
        } catch (_: Exception) {
            emptySet()
        }
    }

}

private fun declarationConfigurationsFor(processorClasspath: Configuration): Set<Configuration> {
    val inheritedConfigurations = processorClasspath.extendsFrom.toSet()
    return inheritedConfigurations.ifEmpty { setOf(processorClasspath) }
}

private fun directDependencyShortIds(configurations: Set<Configuration>): Set<String> {
    return configurations
        .asSequence()
        .flatMap { configuration -> configuration.allDependencies.asSequence() }
        .filterIsInstance<ExternalDependency>()
        .filter { dependency -> !dependency.group.isNullOrBlank() }
        .mapTo(sortedSetOf()) { dependency -> "${dependency.group}:${dependency.name}" }
}
