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
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.DefaultGradleProjectInfo
import com.grab.grazel.gradle.GradleProjectInfo
import com.grab.grazel.gradle.MigrationChecker
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.gradle.dependencies.model.WorkspacePlan
import com.grab.grazel.gradle.dependencies.model.WorkspaceRenderPlan
import com.grab.grazel.migrate.internal.RootBazelFileBuilder
import com.grab.grazel.migrate.internal.WorkspaceBuilder
import com.grab.grazel.util.BUILD_BAZEL
import com.grab.grazel.util.BUILD_BAZEL_IGNORE
import com.grab.grazel.util.WORKSPACE
import com.grab.grazel.util.ansiGreen
import com.grab.grazel.util.fromJson
import com.grab.grazel.util.logHeap
import dagger.Lazy
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.register
import org.gradle.process.ExecOperations
import javax.inject.Inject

@UntrackedTask(because = "Up to dateness not implemented correctly")
internal open class GenerateRootBazelScriptsTask
@Inject
constructor(
    private val migrationChecker: Lazy<MigrationChecker>,
    private val workspaceBuilderFactory: Lazy<WorkspaceBuilder.Factory>,
    private val rootBazelBuilderFactory: Lazy<RootBazelFileBuilder.Factory>,
    private val gradleProjectInfoFactory: Lazy<DefaultGradleProjectInfo.Factory>,
    objectFactory: ObjectFactory,
    private val layout: ProjectLayout,
    private val execOperations: ExecOperations,
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {

    @get:InputFile
    val workspaceDependencies: RegularFileProperty = project.objects.fileProperty()

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val workspacePlan: RegularFileProperty = project.objects.fileProperty()

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val workspaceRenderPlan: RegularFileProperty = project.objects.fileProperty()

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val buildifierScript: RegularFileProperty = project.objects.fileProperty()

    @get:OutputFile
    val workspaceFile: RegularFileProperty = objectFactory
        .fileProperty()
        .convention(layout.projectDirectory.file(WORKSPACE))

    @get:OutputFile
    val buildBazel: RegularFileProperty = objectFactory
        .fileProperty()
        .convention(layout.projectDirectory.file(BUILD_BAZEL))

    @get:Internal
    val stagedBuildBazel: RegularFileProperty = objectFactory
        .fileProperty()
        .convention(layout.buildDirectory.file("grazel/$BUILD_BAZEL_IGNORE"))

    @get:Internal
    val dependencyResolutionService: Property<DefaultDependencyResolutionService> = project
        .objects.property()

    @TaskAction
    fun action() {
        logger.logHeap("GenerateRootBazelScripts:start")
        val rootProject = project.rootProject
        val projectsToMigrate = rootProject
            .subprojects
            .filter { migrationChecker.get().canMigrate(it) }
        val workspaceDependencies = dependencyResolutionService
            .get()
            .init(workspaceDependencies.get().asFile)
        val workspacePlanModel = fromJson<WorkspacePlan>(workspacePlan.get())
        val workspaceRenderPlan = fromJson<WorkspaceRenderPlan>(workspaceRenderPlan.get())

        val gradleProjectInfo: GradleProjectInfo = gradleProjectInfoFactory.get()
            .create(workspaceDependencies)

        workspaceBuilderFactory.get().create(
            projectsToMigrate = projectsToMigrate,
            gradleProjectInfo = gradleProjectInfo,
            workspacePlan = workspacePlanModel,
            materializedMavenRepos = workspaceRenderPlan.materializedRepoNames,
        ).build().writeToFile(workspaceFile.get().asFile)
        formatWithBuildifier(
            buildifierScript = buildifierScript.get().asFile,
            source = workspaceFile.get().asFile,
            destination = workspaceFile.get().asFile,
            execOperations = execOperations,
            fileSystemOperations = fileSystemOperations,
            projectLayout = layout,
        )
        logger.quiet("Generated WORKSPACE".ansiGreen)
        logger.logHeap("GenerateRootBazelScripts:workspace-done")

        val rootBuildBazelContents = rootBazelBuilderFactory.get()
            .create(
                gradleProjectInfo,
                workspaceDependencies,
                logger
            )
            .build()
        if (rootBuildBazelContents.isNotEmpty()) {
            rootBuildBazelContents.writeToFile(stagedBuildBazel.get().asFile)
            formatWithBuildifier(
                buildifierScript = buildifierScript.get().asFile,
                source = stagedBuildBazel.get().asFile,
                destination = buildBazel.get().asFile,
                execOperations = execOperations,
                fileSystemOperations = fileSystemOperations,
                projectLayout = layout,
            )
            logger.quiet("Generated $BUILD_BAZEL".ansiGreen)
        }
    }

    companion object {
        private const val TASK_NAME = "generateRootBazelScripts"

        fun register(
            @RootProject rootProject: Project,
            grazelComponent: GrazelComponent,
            action: GenerateRootBazelScriptsTask.() -> Unit = {}
        ) = rootProject.tasks.register<GenerateRootBazelScriptsTask>(
            TASK_NAME,
            grazelComponent.migrationChecker(),
            grazelComponent.workspaceBuilderFactory(),
            grazelComponent.rootBazelFileFactory(),
            grazelComponent.gradleProjectInfoFactory(),
            rootProject.objects,
            rootProject.layout
        ).apply {
            configure {
                group = GRAZEL_TASK_GROUP
                description = "Generate $BUILD_BAZEL for root project"
                action(this)
            }
        }
    }
}
