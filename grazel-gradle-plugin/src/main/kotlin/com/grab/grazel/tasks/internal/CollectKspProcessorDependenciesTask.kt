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

package com.grab.grazel.tasks.internal

import com.grab.grazel.gradle.MigrationChecker
import com.grab.grazel.gradle.dependencies.KspProcessorClassExtractor
import com.grab.grazel.gradle.dependencies.ResolvedComponentsVisitor
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.COMPILE
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.KSP
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.hasKsp
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.util.writeJson
import dagger.Lazy
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import java.util.TreeMap
import java.util.TreeSet

@CacheableTask
internal abstract class CollectKspProcessorDependenciesTask : DefaultTask() {

    @get:Internal
    abstract val kspConfigurations: ListProperty<Configuration>

    @get:Input
    abstract val kspDirectDependencies: MapProperty</*shortId*/ String, /*declaration*/ String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val kspClasspathFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val kspDependencies: RegularFileProperty

    init {
        group = GRAZEL_TASK_GROUP
        description = "Collects KSP processor dependencies for aggregated dependency resolution"
    }

    @TaskAction
    fun action() {
        val directDependencies = kspDirectDependencies.get()
        val configurations = kspConfigurations.get()
        val artifactMapping = configurations
            .flatMap { configuration ->
                configuration.incoming
                    .artifactView {
                        isLenient = true
                        componentFilter { id -> id is ModuleComponentIdentifier }
                    }
                    .artifacts
                    .mapNotNull { artifact ->
                        val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                            ?: return@mapNotNull null
                        val shortId = "${id.group}:${id.module}"
                        if (shortId in directDependencies) {
                            shortId to artifact.file.absolutePath
                        } else null
                    }
            }
            .associateTo(TreeMap()) { (shortId, fileName) -> shortId to fileName }
        val processorClassMap = KspProcessorClassExtractor.extractProcessorClasses(
            kspClasspathFiles.files,
            artifactMapping
        )
        val resolvedKspDependencies = configurations
            .asSequence()
            .flatMap { configuration ->
                val root = configuration.incoming.resolutionResult.rootComponent.get()
                ResolvedComponentsVisitor().visit(
                    root = root,
                    logger = logger::info
                ) { visitResult ->
                    val component = visitResult.component
                    val moduleVersion = component.moduleVersion ?: return@visit null
                    val shortId = "${moduleVersion.group}:${moduleVersion.name}"
                    if (shortId in directDependencies) {
                        ResolvedDependency(
                            id = component.toString(),
                            shortId = shortId,
                            direct = true,
                            version = moduleVersion.version,
                            dependencies = visitResult.transitiveDeps.mapTo(TreeSet()) { depResult ->
                                ResolvedDependency.createDependencyNotation(
                                    depResult.dependency,
                                    depResult.requiresJetifier,
                                    depResult.unjetifiedSource
                                )
                            },
                            excludeRules = emptySet(),
                            repository = visitResult.repository,
                            requiresJetifier = visitResult.requiresJetifier,
                            processorClass = processorClassMap[shortId]
                        )
                    } else null
                }.asSequence()
            }
            .toSortedSet()

        writeJson(
            ResolveDependenciesResult(
                variantName = DEFAULT_VARIANT,
                dependencies = mapOf(
                    COMPILE.name to emptySet(),
                    KSP.name to resolvedKspDependencies
                )
            ),
            kspDependencies.get()
        )
        kspConfigurations.empty()
    }

    companion object {
        private const val TASK_NAME = "collectKspProcessorDependencies"

        internal fun register(
            rootProject: Project,
            migrationChecker: Lazy<MigrationChecker>
        ): TaskProvider<CollectKspProcessorDependenciesTask> {
            val taskProvider = rootProject.tasks
                .register<CollectKspProcessorDependenciesTask>(TASK_NAME) {
                    kspConfigurations.convention(emptyList())
                    kspDirectDependencies.convention(emptyMap())
                    kspDependencies.set(rootProject.layout.buildDirectory.file("grazel/ksp-dependencies.json"))
                }

            rootProject.gradle.projectsEvaluated {
                val migratableProjects = rootProject.subprojects
                    .filter { project -> migrationChecker.get().canMigrate(project) }
                taskProvider.configure {
                    migratableProjects.forEach { project ->
                        addProjectKspConfigurations(project)
                    }
                }
            }

            return taskProvider
        }

        private fun CollectKspProcessorDependenciesTask.addProjectKspConfigurations(project: Project) {
            if (!project.hasKsp) return

            val kspDeclarationConfigs = project.configurations
                .filter { configuration -> configuration.name.isKspDeclarationBucketName() }
            if (kspDeclarationConfigs.isEmpty()) return

            val directDepShortIds = kspDeclarationConfigs
                .asSequence()
                .flatMap { configuration -> configuration.allDependencies }
                .filterIsInstance<ExternalDependency>()
                .filter { dependency -> !dependency.group.isNullOrBlank() }
                .map { dependency -> "${dependency.group}:${dependency.name}" }
                .toSet()
            if (directDepShortIds.isEmpty()) return

            val kspProcessorClasspath = project.configurations.maybeCreate("grazelKspProcessorClasspath")
            if (kspProcessorClasspath.extendsFrom.isEmpty()) {
                kspProcessorClasspath.apply {
                    isCanBeResolved = true
                    isCanBeConsumed = false
                    isVisible = false
                    setExtendsFrom(kspDeclarationConfigs)
                }
            }

            kspDirectDependencies.putAll(
                project.provider {
                    kspDeclarationConfigs
                        .asSequence()
                        .flatMap { configuration -> configuration.allDependencies }
                        .filterIsInstance<ExternalDependency>()
                        .filter { dependency -> !dependency.group.isNullOrBlank() }
                        .associateTo(TreeMap()) { dependency ->
                            val shortId = "${dependency.group}:${dependency.name}"
                            shortId to "$shortId:${dependency.version.orEmpty()}"
                        }
                }
            )

            kspConfigurations.add(kspProcessorClasspath)
            kspClasspathFiles.from(kspProcessorClasspath)
        }

        private fun String.isKspDeclarationBucketName(): Boolean {
            return startsWith("ksp") && "classpath" !in lowercase()
        }
    }
}
