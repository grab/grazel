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

import com.grab.grazel.di.GradleServices
import com.grab.grazel.gradle.MigrationChecker
import com.grab.grazel.gradle.dependencies.KspProcessorClassExtractor
import com.grab.grazel.gradle.dependencies.ResolvedComponentsVisitor
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.COMPILE
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.KSP
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.createWorkspaceKspProcessorClasspath
import com.grab.grazel.util.withProgress
import com.grab.grazel.util.writeJson
import dagger.Lazy
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import java.util.TreeSet

@CacheableTask
internal abstract class CollectKspProcessorDependenciesTask : DefaultTask() {

    @get:Input
    abstract val kspRootComponents: ListProperty<ResolvedComponentResult>

    @get:Input
    abstract val kspDirectDependencyShortIds: SetProperty<String>

    @get:Nested
    abstract val kspArtifacts: ListProperty<KspArtifactInput>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val kspClasspathFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val kspDependencies: RegularFileProperty

    init {
        group = GRAZEL_TASK_GROUP
        description = "Collects KSP processor dependencies for aggregated dependency resolution"
    }

    @TaskAction
    fun action() {
        val directDependencies = kspDirectDependencyShortIds.get()
        val artifactsByShortId = kspArtifacts.get()
            .associate { artifact ->
                artifact.shortId.get() to artifact.file.get().asFile
            }
        val processorClassMap = KspProcessorClassExtractor.extractProcessorClasses(
            artifactsByShortId
        )
        val roots = kspRootComponents.get()
        val resolvedKspDependencies = GradleServices.from(project).progressLoggerFactory.withProgress(
            "scanning KSP processors"
        ) { reporter ->
            roots
                .asSequence()
                .flatMapIndexed { index, root ->
                    reporter.report("scanning (${index + 1}/${roots.size}): KSP root")
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
        }

        kspDependencies.get().asFile.parentFile.mkdirs()
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
        logger.quiet(
            "Scanned ${resolvedKspDependencies.size} KSP processors across ${roots.size} roots"
        )
    }

    companion object {
        private const val TASK_NAME = "collectKspProcessorDependencies"

        internal fun register(
            rootProject: Project,
            migrationChecker: Lazy<MigrationChecker>
        ): TaskProvider<CollectKspProcessorDependenciesTask> {
            val taskProvider = rootProject.tasks
                .register<CollectKspProcessorDependenciesTask>(TASK_NAME) {
                    kspRootComponents.convention(emptyList())
                    kspDirectDependencyShortIds.convention(emptySet())
                    kspArtifacts.convention(emptyList())
                    kspDependencies.set(rootProject.layout.buildDirectory.file("grazel/ksp-dependencies.json"))
                }

            rootProject.gradle.projectsEvaluated {
                val migratableProjects = rootProject.subprojects
                    .filter { project -> migrationChecker.get().canMigrate(project) }
                taskProvider.configure {
                    migratableProjects.forEach { project ->
                        addProjectKspConfigurations(
                            task = this,
                            project = project
                        )
                    }
                }
            }

            return taskProvider
        }

        private fun addProjectKspConfigurations(
            task: CollectKspProcessorDependenciesTask,
            project: Project
        ) {
            val kspClasspath = createWorkspaceKspProcessorClasspath(project) ?: return

            val directDepShortIds = kspClasspath.declarationConfigurations
                .asSequence()
                .flatMap { configuration -> configuration.allDependencies }
                .filterIsInstance<ExternalDependency>()
                .filter { dependency -> !dependency.group.isNullOrBlank() }
                .mapTo(TreeSet()) { dependency -> "${dependency.group}:${dependency.name}" }
            if (directDepShortIds.isEmpty()) return

            task.kspDirectDependencyShortIds.addAll(
                project.provider {
                    directDepShortIds
                }
            )

            task.kspArtifacts.addAll(
                project.provider {
                    kspClasspath.processorClasspath.incoming
                        .artifactView {
                            isLenient = true
                            componentFilter { id -> id is ModuleComponentIdentifier }
                        }
                        .artifacts
                        .mapNotNull { artifact ->
                            val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                                ?: return@mapNotNull null
                            val shortId = "${id.group}:${id.module}"
                            if (shortId in directDepShortIds) {
                                project.objects
                                    .newInstance(KspArtifactInput::class.java)
                                    .apply {
                                        this.shortId.set(shortId)
                                        file.set(artifact.file)
                                    }
                            } else null
                        }
                        .sortedBy { artifact -> artifact.shortId.get() }
                }
            )

            task.kspRootComponents.add(kspClasspath.processorClasspath.incoming.resolutionResult.rootComponent)
            task.kspClasspathFiles.from(kspClasspath.processorClasspath)
        }
    }

    internal abstract class KspArtifactInput {
        @get:Input
        abstract val shortId: Property<String>

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val file: RegularFileProperty
    }
}
