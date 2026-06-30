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

package com.grab.grazel.gradle.dependencies

import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.dependencies.model.WorkspaceRenderPlan
import com.grab.grazel.util.fromJson
import org.gradle.api.Project
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File

internal abstract class WorkspaceRenderPlanService : BuildService<BuildServiceParameters.None>, AutoCloseable {
    private val lock = Any()
    private var workspaceRenderPlan: WorkspaceRenderPlan? = null

    /**
     * During target-reference collection this receives the accumulated render plan before each
     * consumer is inspected, allowing generated targets to see dependencies already discovered by
     * downstream consumers. Finalization later replaces it with the complete render plan.
     */
    fun populateRenderPlan(workspaceRenderPlan: WorkspaceRenderPlan) {
        synchronized(lock) {
            this.workspaceRenderPlan = workspaceRenderPlan
        }
    }

    fun initRenderPlan(workspaceRenderPlanJson: File): WorkspaceRenderPlan {
        return synchronized(lock) {
            if (workspaceRenderPlan == null) {
                workspaceRenderPlan = fromJson(workspaceRenderPlanJson)
            }
            workspaceRenderPlan!!
        }
    }

    fun isReferencedProjectPath(projectPath: String): Boolean =
        synchronized(lock) {
            projectPath in workspaceRenderPlan?.referencedProjectPaths.orEmpty()
        }

    fun referencedTargetNames(projectPath: String): Set<String> =
        synchronized(lock) {
            workspaceRenderPlan
                ?.referencedProjectTargets
                .orEmpty()[projectPath]
                .orEmpty()
        }

    fun isReferencedTarget(projectPath: String, targetName: String): Boolean =
        synchronized(lock) {
            targetName in workspaceRenderPlan
                ?.referencedProjectTargets
                .orEmpty()[projectPath]
                .orEmpty()
        }

    override fun close() {
        synchronized(lock) {
            workspaceRenderPlan = null
        }
    }

    companion object {
        internal const val SERVICE_NAME = "WorkspaceRenderPlanService"

        internal fun register(@RootProject rootProject: Project) = rootProject
            .gradle
            .sharedServices
            .registerIfAbsent(SERVICE_NAME, WorkspaceRenderPlanService::class.java) {}
    }
}
