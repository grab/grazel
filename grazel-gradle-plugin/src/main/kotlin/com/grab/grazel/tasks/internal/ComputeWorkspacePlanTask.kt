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

import com.grab.grazel.gradle.dependencies.DefaultWorkspacePlanService
import com.grab.grazel.gradle.dependencies.WorkspacePlanBuilder
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.util.GradleProvider
import com.grab.grazel.util.fromJson
import com.grab.grazel.util.logHeap
import com.grab.grazel.util.writeJson
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.register

@CacheableTask
internal abstract class ComputeWorkspacePlanTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val workspaceDependencies: RegularFileProperty

    @get:Input
    abstract val configuredOverrideTargets: MapProperty<String, String>

    @get:Internal
    abstract val workspacePlanService: Property<DefaultWorkspacePlanService>

    @get:OutputFile
    abstract val workspacePlan: RegularFileProperty

    init {
        group = GRAZEL_TASK_GROUP
        description = "Computes the structured workspace dependency plan"
        configuredOverrideTargets.convention(project.objects.mapProperty<String, String>())
    }

    @TaskAction
    fun action() {
        logger.logHeap("ComputeWorkspacePlan:start")
        val plan = WorkspacePlanBuilder(
            configuredOverrideTargets = configuredOverrideTargets.get()
        ).build(
            workspaceDependencies = fromJson<WorkspaceDependencies>(
                workspaceDependencies.get()
            )
        )
        workspacePlan.get().asFile.parentFile.mkdirs()
        writeJson(plan, workspacePlan.get())
        workspacePlanService.get().populatePlan(plan)
        logger.logHeap("ComputeWorkspacePlan:done")
    }

    companion object {
        private const val TASK_NAME = "computeWorkspacePlan"

        internal fun register(
            rootProject: Project,
            workspacePlanService: GradleProvider<DefaultWorkspacePlanService>,
            configureAction: ComputeWorkspacePlanTask.() -> Unit = {}
        ): TaskProvider<ComputeWorkspacePlanTask> {
            return rootProject.tasks.register<ComputeWorkspacePlanTask>(TASK_NAME) {
                workspacePlan.set(rootProject.layout.buildDirectory.file("grazel/workspace-plan.json"))
                this.workspacePlanService.set(workspacePlanService)
                configureAction(this)
            }
        }
    }
}
