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

import com.grab.grazel.bazel.starlark.writeToFile
import com.grab.grazel.di.GrazelComponent
import com.grab.grazel.gradle.MigrationChecker
import com.grab.grazel.gradle.isMigrated
import com.grab.grazel.migrate.internal.ProjectBazelFileBuilder
import com.grab.grazel.util.BUILD_BAZEL
import com.grab.grazel.util.BUILD_BAZEL_IGNORE
import com.grab.grazel.util.ansiGreen
import com.grab.grazel.util.ansiYellow
import dagger.Lazy
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.internal.logging.progress.ProgressLogger
import org.gradle.kotlin.dsl.register
import javax.inject.Inject

internal open class GenerateBazelScriptsTask
@Inject
constructor(
    private val migrationChecker: Lazy<MigrationChecker>,
    private val bazelFileBuilder: Lazy<ProjectBazelFileBuilder.Factory>,
    private val progressLogger: Lazy<ProgressLogger>
) : DefaultTask() {

    private val rootProject get() = project.rootProject

    init {
        outputs.upToDateWhen { false } // This task is supposed to run always until we figure out up-to-date checks
    }

    @TaskAction
    fun action() {
        val buildBazelFile = project.file(BUILD_BAZEL)
        val bazelIgnoreFile = project.file(BUILD_BAZEL_IGNORE)

        // Check if current project can be migrated
        if (migrationChecker.get().canMigrate(project)) {
            // If yes, proceed to generate build.bazel
            val projectBazelFileBuilder = bazelFileBuilder.get().create(project)
            val content = projectBazelFileBuilder.build()
            if (content.isNotEmpty()) {
                content.writeToFile(buildBazelFile)
                val generatedMessage = "Generated ${rootProject.relativePath(buildBazelFile)}"
                progressLogger.get().progress(generatedMessage)
                logger.quiet(generatedMessage.ansiGreen)
                bazelIgnoreFile.delete()
            } else {
                // No content was generated, delete the file
                buildBazelFile.delete()
                val deletedMessage = "Deleted ${rootProject.relativePath(buildBazelFile)}"
                progressLogger.get().progress(deletedMessage.ansiGreen)
                logger.quiet(deletedMessage)
            }
        } else {
            // If not migrateable but was already migrated, rename build.bazel to build.bazelignore if it exists
            bazelIgnoreFile.delete()
            if (project.isMigrated) {
                if (buildBazelFile.renameTo(bazelIgnoreFile)) {
                    project.logger.quiet("$buildBazelFile renamed to $bazelIgnoreFile".ansiYellow)
                }
            }
        }
    }

    companion object {
        private const val TASK_NAME = "generateBazelScripts"

        internal fun register(
            project: Project,
            grazelComponent: GrazelComponent,
            configureAction: GenerateBazelScriptsTask.() -> Unit = {}
        ): TaskProvider<GenerateBazelScriptsTask> {
            val genTask = project.tasks.register<GenerateBazelScriptsTask>(
                TASK_NAME,
                grazelComponent.migrationChecker(),
                grazelComponent.projectBazelFileBuilderFactory(),
                grazelComponent.progressLogger()
            ).apply {
                configure {
                    group = GRAZEL_TASK_GROUP
                    description = "Generate $BUILD_BAZEL for this project"
                    configureAction(this)
                }
            }
            return genTask
        }
    }
}