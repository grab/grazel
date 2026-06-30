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
import com.grab.grazel.extension.DeclaredDependencyMetadataAggregationMode
import com.grab.grazel.extension.DeclaredDependencyMetadataAggregationMode.PROJECT_TASK_FANOUT
import com.grab.grazel.extension.DeclaredDependencyMetadataAggregationMode.SINGLE_TASK
import com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadata
import com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadataMerger
import com.grab.grazel.gradle.dependencies.DeclaredProjectMetadataSource
import com.grab.grazel.gradle.dependencies.DeclaredProjectMetadataSnapshotter
import com.grab.grazel.gradle.dependencies.ProjectDeclaredDependencyMetadata
import com.grab.grazel.util.ProgressReporter
import com.grab.grazel.util.fromJson
import com.grab.grazel.util.logHeap
import com.grab.grazel.util.withProgress
import com.grab.grazel.util.writeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.UntrackedTask
import org.gradle.kotlin.dsl.register

internal data class DeclaredDependencyMetadataTaskOutput(
    val declaredDependencyMetadata: Provider<RegularFile>,
    val producerTask: TaskProvider<out Task>
)

internal data class DeclaredDependencyMetadataTaskOutputs(
    private val singleTask: TaskProvider<CollectDeclaredDependencyMetadataTask>,
    private val projectTaskFanout: TaskProvider<MergeDeclaredDependencyMetadataTask>
) {
    fun forMode(mode: DeclaredDependencyMetadataAggregationMode): DeclaredDependencyMetadataTaskOutput {
        return when (mode) {
            SINGLE_TASK -> DeclaredDependencyMetadataTaskOutput(
                declaredDependencyMetadata = singleTask.flatMap { it.declaredDependencyMetadata },
                producerTask = singleTask
            )
            PROJECT_TASK_FANOUT -> DeclaredDependencyMetadataTaskOutput(
                declaredDependencyMetadata = projectTaskFanout.flatMap { it.declaredDependencyMetadata },
                producerTask = projectTaskFanout
            )
        }
    }

    fun configureSingleTask(metadataSources: List<DeclaredProjectMetadataSource>) {
        singleTask.configure {
            configureCollector(metadataSources)
        }
    }

    fun configureProjectTaskFanout(
        metadataSources: List<DeclaredProjectMetadataSource>
    ) {
        MergeDeclaredDependencyMetadataTask.configureShards(
            mergeTask = projectTaskFanout,
            metadataSources = metadataSources
        )
    }
}

private data class DeclaredMetadataSnapshotResult(
    val projectPath: String,
    val metadata: ProjectDeclaredDependencyMetadata
)

internal object DeclaredDependencyMetadataTasks {
    fun register(
        rootProject: Project
    ): DeclaredDependencyMetadataTaskOutputs {
        val singleTask = CollectDeclaredDependencyMetadataTask.register(
            rootProject = rootProject
        )
        val projectTaskFanout = MergeDeclaredDependencyMetadataTask.register(
            rootProject = rootProject
        )
        return DeclaredDependencyMetadataTaskOutputs(
            singleTask = singleTask,
            projectTaskFanout = projectTaskFanout
        )
    }
}

@UntrackedTask(because = "Reads evaluated Gradle project and variant model objects directly")
internal abstract class CollectDeclaredDependencyMetadataTask : DefaultTask() {
    @get:OutputFile
    abstract val declaredDependencyMetadata: RegularFileProperty

    private lateinit var metadataSources: List<DeclaredProjectMetadataSource>

    init {
        group = GRAZEL_TASK_GROUP
        description = "Collects declared dependency metadata for aggregated dependency resolution"
    }

    @TaskAction
    fun action() {
        logger.logHeap("CollectDeclaredDependencyMetadata:start")
        val startedAt = System.nanoTime()
        val metadata = GradleServices.from(project).progressLoggerFactory.withProgress(
            "collecting declared dependency metadata"
        ) { reporter ->
            collectDeclaredDependencyMetadata(
                metadataSources = metadataSources,
                maxWorkerCount = project.gradle.startParameter.maxWorkerCount,
                reporter = reporter
            )
        }
        declaredDependencyMetadata.get().asFile.parentFile.mkdirs()
        writeJson(metadata, declaredDependencyMetadata.get())
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val outputBytes = declaredDependencyMetadata.get().asFile.length()
        logger.quiet(
            "Collected declared dependency metadata for ${metadata.projects.size} projects in " +
                "${elapsedMs}ms ($outputBytes bytes, mode SINGLE_TASK)"
        )
        logger.logHeap("CollectDeclaredDependencyMetadata:done")
    }

    internal fun configureCollector(metadataSources: List<DeclaredProjectMetadataSource>) {
        this.metadataSources = metadataSources
    }

    companion object {
        private const val TASK_NAME = "collectDeclaredDependencyMetadata"

        internal fun register(
            rootProject: Project
        ): TaskProvider<CollectDeclaredDependencyMetadataTask> {
            return rootProject.tasks.register<CollectDeclaredDependencyMetadataTask>(TASK_NAME) {
                declaredDependencyMetadata.set(declaredDependencyMetadataFile(rootProject))
            }
        }
    }
}

@UntrackedTask(because = "Reads evaluated Gradle project and variant model objects directly")
internal abstract class CollectProjectDeclaredDependencyMetadataTask : DefaultTask() {
    @get:OutputFile
    abstract val declaredDependencyMetadataShard: RegularFileProperty

    private lateinit var metadataSource: DeclaredProjectMetadataSource

    init {
        group = GRAZEL_TASK_GROUP
        description = "Collects declared dependency metadata for one Gradle project"
    }

    @TaskAction
    fun action() {
        declaredDependencyMetadataShard.get().asFile.parentFile.mkdirs()
        val projectMetadata = DeclaredProjectMetadataSnapshotter().snapshot(
            project = metadataSource.project,
            variants = metadataSource.variants
        )
        writeJson(
            DeclaredDependencyMetadata(
                projects = mapOf(
                    metadataSource.project.path to projectMetadata
                )
            ),
            declaredDependencyMetadataShard.get()
        )
    }

    private fun configureCollector(metadataSource: DeclaredProjectMetadataSource) {
        this.metadataSource = metadataSource
    }

    companion object {
        internal fun register(
            metadataSource: DeclaredProjectMetadataSource
        ): TaskProvider<CollectProjectDeclaredDependencyMetadataTask> {
            val sourceProject = metadataSource.project
            return sourceProject.tasks.register<CollectProjectDeclaredDependencyMetadataTask>(
                "collectProjectDeclaredDependencyMetadata"
            ) {
                configureCollector(metadataSource)
                declaredDependencyMetadataShard.set(
                    sourceProject.layout.buildDirectory.file(
                        "grazel/declared-dependency-metadata/project.json"
                    )
                )
            }
        }
    }
}

@CacheableTask
internal abstract class MergeDeclaredDependencyMetadataTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val declaredDependencyMetadataShards: ConfigurableFileCollection

    @get:OutputFile
    abstract val declaredDependencyMetadata: RegularFileProperty

    init {
        group = GRAZEL_TASK_GROUP
        description = "Merges project declared dependency metadata shards"
    }

    @TaskAction
    fun action() {
        logger.logHeap("MergeDeclaredDependencyMetadata:start")
        val startedAt = System.nanoTime()
        val shards = declaredDependencyMetadataShards.files.toList()
        val merged = GradleServices.from(project).progressLoggerFactory.withProgress(
            "merging declared dependency metadata"
        ) { reporter ->
            DeclaredDependencyMetadataMerger.mergeShards(
                shards.mapIndexed { index, shard ->
                    reporter.report("merging shard (${index + 1}/${shards.size})")
                    fromJson<DeclaredDependencyMetadata>(shard)
                }
            )
        }
        declaredDependencyMetadata.get().asFile.parentFile.mkdirs()
        writeJson(merged, declaredDependencyMetadata.get())
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val outputBytes = declaredDependencyMetadata.get().asFile.length()
        logger.quiet(
            "Collected declared dependency metadata for ${merged.projects.size} projects across " +
                "${shards.size} shards in ${elapsedMs}ms ($outputBytes bytes, mode PROJECT_TASK_FANOUT)"
        )
        logger.logHeap("MergeDeclaredDependencyMetadata:done")
    }

    companion object {
        private const val TASK_NAME = "mergeDeclaredDependencyMetadata"

        internal fun register(
            rootProject: Project
        ): TaskProvider<MergeDeclaredDependencyMetadataTask> {
            return rootProject.tasks.register<MergeDeclaredDependencyMetadataTask>(TASK_NAME) {
                declaredDependencyMetadata.set(declaredDependencyMetadataFile(rootProject))
            }
        }

        internal fun configureShards(
            mergeTask: TaskProvider<MergeDeclaredDependencyMetadataTask>,
            metadataSources: List<DeclaredProjectMetadataSource>
        ) {
            metadataSources.forEach { metadataSource ->
                val shardTask = CollectProjectDeclaredDependencyMetadataTask.register(
                    metadataSource = metadataSource
                )
                mergeTask.configure {
                    declaredDependencyMetadataShards.from(
                        shardTask.flatMap { task -> task.declaredDependencyMetadataShard }
                    )
                    dependsOn(shardTask)
                }
            }
        }
    }
}

internal fun collectDeclaredDependencyMetadata(
    metadataSources: List<DeclaredProjectMetadataSource>,
    maxWorkerCount: Int,
    reporter: ProgressReporter
): DeclaredDependencyMetadata {
    val snapshotter = DeclaredProjectMetadataSnapshotter()
    val workerCount = maxOf(1, maxWorkerCount)
    return runBlocking {
        val semaphore = Semaphore(workerCount)
        val completedSnapshots = Channel<DeclaredMetadataSnapshotResult>(Channel.UNLIMITED)
        DeclaredDependencyMetadataMerger.merge(
            buildList {
                metadataSources.forEach { metadataSource ->
                    launch(Dispatchers.Default) {
                        val result = semaphore.withPermit {
                            val metadata = snapshotter.snapshot(
                                project = metadataSource.project,
                                variants = metadataSource.variants
                            )
                            DeclaredMetadataSnapshotResult(
                                projectPath = metadataSource.project.path,
                                metadata = metadata
                            )
                        }
                        completedSnapshots.send(result)
                    }
                }

                try {
                    repeat(metadataSources.size) { index ->
                        val result = completedSnapshots.receive()
                        reporter.report(
                            "snapshotting ${result.projectPath} (${index + 1}/${metadataSources.size})"
                        )
                        add(result.projectPath to result.metadata)
                    }
                } finally {
                    completedSnapshots.close()
                }
            }
        )
    }
}

private fun declaredDependencyMetadataFile(rootProject: Project): Provider<RegularFile> =
    rootProject.layout.buildDirectory.file("grazel/declared-dependency-metadata.json")
