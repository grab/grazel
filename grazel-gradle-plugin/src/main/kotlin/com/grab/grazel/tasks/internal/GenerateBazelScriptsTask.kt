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
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.gradle.dependencies.WorkspacePlanService
import com.grab.grazel.gradle.dependencies.WorkspaceRenderPlanService
import com.grab.grazel.gradle.dependencies.WorkspaceTargetTagPlanService
import com.grab.grazel.gradle.isAndroid
import com.grab.grazel.gradle.isJava
import com.grab.grazel.gradle.isKotlin
import com.grab.grazel.gradle.isMigrated
import com.grab.grazel.migrate.internal.ProjectBazelFileBuilder
import com.grab.grazel.util.BUILD_BAZEL
import com.grab.grazel.util.BUILD_BAZEL_IGNORE
import com.grab.grazel.util.GradleProvider
import com.grab.grazel.util.ansiGreen
import com.grab.grazel.util.ansiYellow
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
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.UntrackedTask
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.register
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

@UntrackedTask(because = "Up to dateness not implemented correctly")
internal open class GenerateBazelScriptsTask
@Inject
constructor(
    private val migrationChecker: Lazy<MigrationChecker>,
    private val bazelFileBuilder: Lazy<ProjectBazelFileBuilder.Factory>,
    private val workspacePlanService: GradleProvider<WorkspacePlanService>,
    private val workspaceRenderPlanService: GradleProvider<WorkspaceRenderPlanService>,
    private val workspaceTargetTagPlanService: GradleProvider<WorkspaceTargetTagPlanService>,
    objectFactory: ObjectFactory,
    private val layout: ProjectLayout,
    private val execOperations: ExecOperations,
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {

    private val rootProject get() = project.rootProject

    @get:InputFile
    val workspaceDependencies: RegularFileProperty = project.objects.fileProperty()

    @get:InputFile
    val workspacePlan: RegularFileProperty = project.objects.fileProperty()

    @get:InputFile
    val workspaceRenderPlan: RegularFileProperty = project.objects.fileProperty()

    @get:InputFile
    val targetTagPlan: RegularFileProperty = project.objects.fileProperty()

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val buildifierScript: RegularFileProperty = project.objects.fileProperty()

    @get:Internal
    val dependencyResolutionService: Property<DefaultDependencyResolutionService> =
        project.objects.property()

    @get:OutputFile
    val buildBazel: RegularFileProperty = objectFactory.fileProperty().apply {
        set(layout.projectDirectory.file(BUILD_BAZEL))
    }

    @get:Internal
    val stagedBuildBazel: RegularFileProperty = objectFactory.fileProperty().apply {
        set(layout.buildDirectory.file("grazel/$BUILD_BAZEL_IGNORE"))
    }

    @TaskAction
    fun action() {
        logger.logHeap("GeneratebazelScripts:${project.path}:start")
        val stagedBuildBazelFile = stagedBuildBazel.get().asFile
        val buildBazelFile = buildBazel.get().asFile
        val bazelIgnoreFile = project.file(BUILD_BAZEL_IGNORE)

        dependencyResolutionService.get()
            .init(workspaceDependencies.get().asFile)
        workspacePlanService.get()
            .initPlan(workspacePlan.get().asFile)
        workspaceRenderPlanService.get()
            .initRenderPlan(workspaceRenderPlan.get().asFile)
        workspaceTargetTagPlanService.get()
            .initTagPlan(targetTagPlan.get().asFile)

        if (migrationChecker.get().canMigrate(project)) {
            val projectBazelFileBuilder = bazelFileBuilder.get().create(project)
            val targets = projectBazelFileBuilder.targets()
            if (targets.isEmpty()) {
                logger.info("No reachable Bazel targets generated for ${project.path}")
                if (project.hasConcreteTargetPlugin) {
                    disableProjectBuildFile(stagedBuildBazelFile, bazelIgnoreFile)
                } else {
                    stagedBuildBazelFile.delete()
                }
                return
            }
            val content = projectBazelFileBuilder.build(targets)
            content.writeToFile(stagedBuildBazelFile)
            formatWithBuildifier(
                buildifierScript = buildifierScript.get().asFile,
                source = stagedBuildBazelFile,
                destination = buildBazelFile,
                execOperations = execOperations,
                fileSystemOperations = fileSystemOperations,
                projectLayout = layout,
            )
            val generatedMessage = "Generated ${rootProject.relativePath(buildBazelFile)}"
            logger.quiet(generatedMessage.ansiGreen)
        } else {
            logger.info("Skipping ${project.path}; project is not migratable")
            disableProjectBuildFile(stagedBuildBazelFile, bazelIgnoreFile)
        }
    }

    private val Project.hasConcreteTargetPlugin: Boolean
        get() = isAndroid || isJava || isKotlin

    private fun disableProjectBuildFile(
        stagedBuildBazelFile: File,
        bazelIgnoreFile: File
    ) {
        stagedBuildBazelFile.delete()
        bazelIgnoreFile.delete()
        val activeBuildBazelFile = project.file(BUILD_BAZEL)
        if (project.isMigrated && activeBuildBazelFile.renameTo(bazelIgnoreFile)) {
            project.logger.quiet("$activeBuildBazelFile renamed to $bazelIgnoreFile".ansiYellow)
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
                grazelComponent.workspacePlanService(),
                grazelComponent.workspaceRenderPlanService(),
                grazelComponent.workspaceTargetTagPlanService(),
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
