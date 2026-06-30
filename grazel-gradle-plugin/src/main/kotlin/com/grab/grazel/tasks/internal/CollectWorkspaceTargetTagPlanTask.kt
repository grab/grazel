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

import com.grab.grazel.di.GrazelComponent
import com.grab.grazel.di.GradleServices
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.gradle.dependencies.WorkspaceTargetTagPlanCollector
import com.grab.grazel.util.withProgress
import com.grab.grazel.util.logHeap
import com.grab.grazel.util.writeJson
import dagger.Lazy
import org.gradle.api.DefaultTask
import org.gradle.api.Project
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
import javax.inject.Inject

@UntrackedTask(because = "Gradle project, variant, and declaration model inputs are not fully declared yet")
internal open class CollectWorkspaceTargetTagPlanTask
@Inject
constructor(
    private val targetTagPlanCollector: Lazy<WorkspaceTargetTagPlanCollector>,
    objectFactory: ObjectFactory,
    layout: ProjectLayout,
) : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val workspaceDependencies: RegularFileProperty = objectFactory.fileProperty()

    @get:Internal
    val dependencyResolutionService: Property<DefaultDependencyResolutionService> =
        objectFactory.property()

    @get:OutputFile
    val targetTagPlan: RegularFileProperty = objectFactory.fileProperty().apply {
        set(layout.buildDirectory.file("grazel/target-tag-plan.json"))
    }

    @TaskAction
    fun action() {
        logger.logHeap("CollectWorkspaceTargetTagPlan:start")
        dependencyResolutionService.get().init(workspaceDependencies.get().asFile)
        targetTagPlan.get().asFile.parentFile.mkdirs()
        val startedAt = System.nanoTime()
        val tagPlan = GradleServices.from(project).progressLoggerFactory.withProgress(
            "collecting workspace target tags"
        ) { reporter ->
            targetTagPlanCollector.get().collect(project.rootProject, reporter)
        }
        writeJson(
            tagPlan,
            targetTagPlan.get()
        )
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        logger.quiet(
            "Collected target tags for ${tagPlan.size} targets in ${elapsedMs}ms"
        )
        logger.logHeap("CollectWorkspaceTargetTagPlan:done")
    }

    companion object {
        private const val TASK_NAME = "collectWorkspaceTargetTagPlan"

        fun register(
            @RootProject rootProject: Project,
            grazelComponent: GrazelComponent,
            action: CollectWorkspaceTargetTagPlanTask.() -> Unit = {}
        ): TaskProvider<CollectWorkspaceTargetTagPlanTask> = register(
            rootProject = rootProject,
            targetTagPlanCollector = grazelComponent.workspaceTargetTagPlanCollector(),
            action = {
                dependencyResolutionService.set(grazelComponent.dependencyResolutionService())
                action(this)
            }
        )

        fun register(
            @RootProject rootProject: Project,
            targetTagPlanCollector: Lazy<WorkspaceTargetTagPlanCollector>,
            action: CollectWorkspaceTargetTagPlanTask.() -> Unit = {}
        ): TaskProvider<CollectWorkspaceTargetTagPlanTask> {
            return rootProject.tasks.register<CollectWorkspaceTargetTagPlanTask>(
                TASK_NAME,
                targetTagPlanCollector,
                rootProject.objects,
                rootProject.layout
            ).apply {
                configure {
                    group = GRAZEL_TASK_GROUP
                    description = "Collects planned Maven tag labels for generated targets"
                    action(this)
                }
            }
        }
    }
}
