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

package com.grab.grazel.tasks.internal

import com.grab.grazel.util.BUILD_BAZEL
import com.grab.grazel.util.WORKSPACE
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.process.ExecOperations
import javax.inject.Inject

@CacheableTask
internal open class FormatBazelFileTask
@Inject
constructor(
    objectFactory: ObjectFactory,
    private val execOperations: ExecOperations,
    private val fileSystemOperations: FileSystemOperations,
    private val projectLayout: ProjectLayout,
) : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val inputFile: RegularFileProperty = objectFactory.fileProperty()

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val buildifierScript: RegularFileProperty = objectFactory.fileProperty()

    @get:OutputFile
    val outputFile: RegularFileProperty = objectFactory.fileProperty()

    @TaskAction
    fun action() {
        val input = inputFile.get().asFile
        if (input.exists()) {
            // Create a temp file to not touch the original file
            val tmpFile = projectLayout
                .buildDirectory
                .file("grazel/${input.name}.tmp")
                .get()
                .asFile
            fileSystemOperations.copy {
                from(input)
                into(tmpFile.parentFile)
                rename { tmpFile.name }
            }
            execOperations.exec {
                commandLine = listOf(
                    buildifierScript.get().asFile.absolutePath,
                    tmpFile.absolutePath,
                )
            }
            val formattedFile = outputFile.get().asFile
            fileSystemOperations.copy {
                from(tmpFile)
                into(formattedFile.parentFile)
                rename { formattedFile.name }
            }
        }
    }

    companion object {
        private const val FORMAT_BAZEL_FILE_TASK = "formatBazelScripts"
        private const val FORMAT_BUILD_BAZEL_FILE_TASK = "formatBuildBazel"
        private const val FORMAT_WORK_SPACE_FILE_TASK = "formatWorkSpace"

        private fun FormatBazelFileTask.configureConventions(
            buildifierScriptProvider: Provider<RegularFile>
        ) {
            group = GRAZEL_TASK_GROUP
            buildifierScript.set(buildifierScriptProvider)
        }

        fun registerRootFormattingTasks(
            project: Project,
            buildifierScriptProvider: Provider<RegularFile>,
            workspaceFormattingTask: FormatBazelFileTask.() -> Unit,
            rootBuildBazelTask: FormatBazelFileTask.() -> Unit,
        ): List<TaskProvider<out Task>> {
            require(project == project.rootProject) {
                "Can only register root formatting tasks on root project"
            }
            val result = mutableListOf<TaskProvider<out Task>>()
            val objects = project.serviceOf<ObjectFactory>()
            val exec = project.serviceOf<ExecOperations>()
            val fileSystem = project.serviceOf<FileSystemOperations>()
            val layout = project.serviceOf<ProjectLayout>()
            project.tasks.register<FormatBazelFileTask>(
                FORMAT_WORK_SPACE_FILE_TASK,
                objects, exec, fileSystem, layout
            ).apply {
                configure {
                    description = "Format $WORKSPACE file"
                    configureConventions(buildifierScriptProvider)
                    outputFile.set(
                        project
                            .objects
                            .fileProperty()
                            .apply { set(project.file(WORKSPACE)) }
                    )
                    workspaceFormattingTask(this)
                }
            }.let(result::add)

            project.tasks.register<FormatBazelFileTask>(
                FORMAT_BUILD_BAZEL_FILE_TASK,
                objects, exec, fileSystem, layout
            ).apply {
                configure {
                    description = "Format $BUILD_BAZEL file"
                    configureConventions(buildifierScriptProvider)
                    outputFile.set(
                        project
                            .objects
                            .fileProperty()
                            .apply { set(project.file(BUILD_BAZEL)) }
                    )
                    rootBuildBazelTask(this)
                }
            }.let(result::add)

            return result
        }

        /**
         * Register formatting task on the given project and provide callbacks to configure it.
         *
         * @param project The project instance to register for
         * @param configureAction Callback to configure the registered task. Can be called multiple times for all registered
         *                        tasks
         * @return The created provider for this task.
         */
        fun register(
            project: Project,
            buildifierScriptProvider: Provider<RegularFile>,
            configureAction: FormatBazelFileTask.() -> Unit
        ): TaskProvider<out Task> {
            require(project != project.rootProject) {
                "Can only register formatting tasks on non-root project"
            }
            val objects = project.serviceOf<ObjectFactory>()
            val exec = project.serviceOf<ExecOperations>()
            val fileSystem = project.serviceOf<FileSystemOperations>()
            val layout = project.serviceOf<ProjectLayout>()
            return project.tasks.register<FormatBazelFileTask>(
                FORMAT_BAZEL_FILE_TASK,
                objects, exec, fileSystem, layout
            ).apply {
                configure {
                    description = "Format $BUILD_BAZEL file"
                    configureConventions(buildifierScriptProvider)
                    outputFile.set(project.objects.fileProperty().apply {
                        set(project.file(BUILD_BAZEL))
                    })
                    configureAction(this)
                }
            }
        }
    }
}
