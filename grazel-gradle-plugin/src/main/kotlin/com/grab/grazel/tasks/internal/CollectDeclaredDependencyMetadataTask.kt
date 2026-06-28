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

import com.grab.grazel.extension.DeclaredDependencyMetadataAggregationMode
import com.grab.grazel.extension.DeclaredDependencyMetadataAggregationMode.PROJECT_TASK_FANOUT
import com.grab.grazel.extension.DeclaredDependencyMetadataAggregationMode.SINGLE_TASK
import com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadata
import com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadataMerger
import com.grab.grazel.gradle.dependencies.DeclaredProjectMetadataSource
import com.grab.grazel.gradle.dependencies.DeclaredProjectMetadataSnapshotter
import com.grab.grazel.gradle.dependencies.ProjectDeclaredDependencyMetadata
import com.grab.grazel.util.fromJson
import com.grab.grazel.util.logHeap
import com.grab.grazel.util.writeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.io.File

internal data class DeclaredDependencyMetadataTaskOutput(
    val declaredDependencyMetadata: Provider<RegularFile>,
    val producerTask: TaskProvider<out Task>
)

internal data class DeclaredDependencyMetadataTaskOutputs(
    val singleTask: DeclaredDependencyMetadataTaskOutput,
    val projectTaskFanout: DeclaredDependencyMetadataTaskOutput,
    private val singleProducerTask: TaskProvider<CollectDeclaredDependencyMetadataTask>,
    private val projectTaskFanoutMergeTask: TaskProvider<MergeDeclaredDependencyMetadataTask>
) {
    fun forMode(mode: DeclaredDependencyMetadataAggregationMode): DeclaredDependencyMetadataTaskOutput {
        return when (mode) {
            SINGLE_TASK -> singleTask
            PROJECT_TASK_FANOUT -> projectTaskFanout
        }
    }

    fun configureSingleTask(metadataSources: List<DeclaredProjectMetadataSource>) {
        singleProducerTask.configure {
            configureCollector(metadataSources)
        }
    }

    fun configureProjectTaskFanout(
        rootProject: Project,
        metadataSources: List<DeclaredProjectMetadataSource>
    ) {
        MergeDeclaredDependencyMetadataTask.configureShards(
            rootProject = rootProject,
            mergeTask = projectTaskFanoutMergeTask,
            metadataSources = metadataSources
        )
    }
}

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
            singleTask = DeclaredDependencyMetadataTaskOutput(
                declaredDependencyMetadata = singleTask.flatMap { it.declaredDependencyMetadata },
                producerTask = singleTask
            ),
            projectTaskFanout = DeclaredDependencyMetadataTaskOutput(
                declaredDependencyMetadata = projectTaskFanout.flatMap { it.declaredDependencyMetadata },
                producerTask = projectTaskFanout
            ),
            singleProducerTask = singleTask,
            projectTaskFanoutMergeTask = projectTaskFanout
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
        val metadata = collectDeclaredDependencyMetadata(
            metadataSources = metadataSources,
            maxWorkerCount = project.gradle.startParameter.maxWorkerCount
        )
        declaredDependencyMetadata.get().asFile.parentFile.mkdirs()
        writeJson(metadata, declaredDependencyMetadata.get())
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        logger.lifecycle(
            "Grazel: declared dependency metadata mode=SINGLE_TASK " +
                "projects=${metadata.projects.size} " +
                "aggregateJsonBytes=${declaredDependencyMetadata.get().asFile.length()} " +
                "elapsedMs=$elapsedMs"
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
            rootProject: Project,
            metadataSource: DeclaredProjectMetadataSource
        ): TaskProvider<CollectProjectDeclaredDependencyMetadataTask> {
            val sourceProject = metadataSource.project
            val safeProjectPath = sourceProject.path.toSafeFileName()
            return rootProject.tasks.register<CollectProjectDeclaredDependencyMetadataTask>(
                "collect${sourceProject.path.toPascalTaskName()}DeclaredDependencyMetadata"
            ) {
                configureCollector(metadataSource)
                declaredDependencyMetadataShard.set(
                    rootProject.layout.buildDirectory.file(
                        "grazel/declared-dependency-metadata/$safeProjectPath.json"
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
        val merged = DeclaredDependencyMetadataMerger.mergeShards(
            declaredDependencyMetadataShards.files
                .sortedBy(File::getAbsolutePath)
                .map { shard -> fromJson<DeclaredDependencyMetadata>(shard) }
        )
        declaredDependencyMetadata.get().asFile.parentFile.mkdirs()
        writeJson(merged, declaredDependencyMetadata.get())
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        logger.lifecycle(
            "Grazel: declared dependency metadata mode=PROJECT_TASK_FANOUT " +
                "projects=${merged.projects.size} " +
                "shards=${declaredDependencyMetadataShards.files.size} " +
                "aggregateJsonBytes=${declaredDependencyMetadata.get().asFile.length()} " +
                "elapsedMs=$elapsedMs"
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
            rootProject: Project,
            mergeTask: TaskProvider<MergeDeclaredDependencyMetadataTask>,
            metadataSources: List<DeclaredProjectMetadataSource>
        ) {
            metadataSources.forEach { metadataSource ->
                val shardTask = CollectProjectDeclaredDependencyMetadataTask.register(
                    rootProject = rootProject,
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

private fun collectDeclaredDependencyMetadata(
    metadataSources: List<DeclaredProjectMetadataSource>,
    maxWorkerCount: Int
): DeclaredDependencyMetadata {
    val snapshotter = DeclaredProjectMetadataSnapshotter()
    val workerCount = maxOf(1, maxWorkerCount)
    return runBlocking {
        val semaphore = Semaphore(workerCount)
        DeclaredDependencyMetadataMerger.merge(
            metadataSources.map { metadataSource ->
                async(Dispatchers.Default) {
                    semaphore.withPermit {
                        metadataSource.project.path to snapshotter.snapshot(
                            project = metadataSource.project,
                            variants = metadataSource.variants
                        )
                    }
                }
            }.awaitAll()
        )
    }
}

private fun declaredDependencyMetadataFile(rootProject: Project): Provider<RegularFile> =
    rootProject.layout.buildDirectory.file("grazel/declared-dependency-metadata.json")

private fun String.toSafeFileName(): String {
    return removePrefix(":")
        .ifBlank { "root" }
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
}

private fun String.toPascalTaskName(): String {
    return removePrefix(":")
        .split(":")
        .filter(String::isNotBlank)
        .joinToString(separator = "") { segment ->
            segment
                .split(Regex("[^A-Za-z0-9]+"))
                .filter(String::isNotBlank)
                .joinToString(separator = "") { token ->
                    token.replaceFirstChar { char -> char.uppercaseChar() }
                }
        }
        .ifBlank { "Root" }
}
