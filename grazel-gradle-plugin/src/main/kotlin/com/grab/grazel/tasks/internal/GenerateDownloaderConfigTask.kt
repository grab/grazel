/*
 * Copyright 2023 Grabtaxi Holdings PTE LTD (GRAB)
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

import com.grab.grazel.di.GrazelComponent
import com.grab.grazel.gradle.Repository
import com.grab.grazel.migrate.dependencies.UrlRewriter
import com.grab.grazel.util.ansiGreen
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.setProperty
import javax.inject.Inject

@CacheableTask
internal open class GenerateDownloaderConfigTask
@Inject
constructor(
    objectFactory: ObjectFactory,
) : DefaultTask() {

    @get:Input
    val allRepositories: SetProperty<Repository> = objectFactory.setProperty()

    @get:OutputFile
    val outputFile: RegularFileProperty = objectFactory.fileProperty()

    init {
        group = GRAZEL_TASK_GROUP
        description = "Generates downloader config for bazel"
    }

    @TaskAction
    fun action() {
        UrlRewriter().generate(
            outputFile = outputFile.get().asFile,
            allRepositories = allRepositories.get()
        )
        logger.quiet("Generated downloader config ${outputFile.get().asFile.absolutePath}".ansiGreen)
    }

    companion object {
        private const val GENERATE_DOWNLOADER_CONFIG_TASK_NAME = "generateDownloaderConfig"

        fun register(
            rootProject: Project,
            grazelComponent: GrazelComponent,
        ): TaskProvider<GenerateDownloaderConfigTask> = rootProject.tasks
            .register<GenerateDownloaderConfigTask>(
                GENERATE_DOWNLOADER_CONFIG_TASK_NAME
            ) {
                allRepositories.set(
                    grazelComponent.repositoryDataSource().get().allRepositoriesLazy
                )
                outputFile.set(project.file("bazel_downloader.cfg"))
            }
    }
}