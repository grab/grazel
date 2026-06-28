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

import com.grab.grazel.gradle.dependencies.WorkspacePlanService
import com.grab.grazel.gradle.dependencies.WorkspaceRenderPlanBuilder
import com.grab.grazel.gradle.dependencies.asRenderPlan
import com.grab.grazel.gradle.dependencies.model.TargetReferenceFacts
import com.grab.grazel.util.GradleProvider
import com.grab.grazel.util.fromJson
import com.grab.grazel.util.logHeap
import com.grab.grazel.util.writeJson
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

@CacheableTask
internal abstract class FinalizeWorkspacePlanTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val workspacePlan: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val targetMavenRepoReferences: RegularFileProperty

    @get:Internal
    abstract val workspacePlanService: Property<WorkspacePlanService>

    @get:OutputFile
    abstract val workspaceRenderPlan: RegularFileProperty

    init {
        group = GRAZEL_TASK_GROUP
        description = "Finalizes the structured workspace render plan"
    }

    @TaskAction
    fun action() {
        logger.logHeap("FinalizeWorkspacePlan:start")
        val plan = workspacePlanService.get().initPlan(workspacePlan.get().asFile)
        val targetReferences = fromJson<TargetReferenceFacts>(targetMavenRepoReferences.get())
        val targetReferencesPlan = targetReferences.asRenderPlan()
        val renderPlan = WorkspaceRenderPlanBuilder().build(
            workspacePlan = plan,
            referencedRepoNames = targetReferences.repoNames
        ).copy(
            referencedProjectTargets = targetReferencesPlan.referencedProjectTargets
        )
        workspaceRenderPlan.get().asFile.parentFile.mkdirs()
        writeJson(renderPlan, workspaceRenderPlan.get())
        workspacePlanService.get().populateRenderPlan(renderPlan)
        logger.logHeap("FinalizeWorkspacePlan:done")
    }

    companion object {
        private const val TASK_NAME = "finalizeWorkspacePlan"

        internal fun register(
            rootProject: Project,
            workspacePlanService: GradleProvider<WorkspacePlanService>,
            configureAction: FinalizeWorkspacePlanTask.() -> Unit = {}
        ): TaskProvider<FinalizeWorkspacePlanTask> {
            return rootProject.tasks.register<FinalizeWorkspacePlanTask>(TASK_NAME) {
                workspaceRenderPlan.set(
                    rootProject.layout.buildDirectory.file("grazel/workspace-render-plan.json")
                )
                this.workspacePlanService.set(workspacePlanService)
                configureAction(this)
            }
        }
    }
}
