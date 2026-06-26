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
import com.grab.grazel.gradle.dependencies.WorkspacePlanService.Companion.SERVICE_NAME
import com.grab.grazel.gradle.dependencies.model.TargetTagKey
import com.grab.grazel.gradle.dependencies.model.WorkspacePlan
import com.grab.grazel.gradle.dependencies.model.WorkspaceRenderPlan
import com.grab.grazel.util.fromJson
import org.gradle.api.Project
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File

internal interface WorkspacePlanService : BuildService<WorkspacePlanService.Params>,
    AutoCloseable {
    fun populatePlan(workspacePlan: WorkspacePlan)

    fun populateRenderPlan(workspaceRenderPlan: WorkspaceRenderPlan)

    fun initPlan(workspacePlanJson: File): WorkspacePlan

    fun initRenderPlan(workspaceRenderPlanJson: File): WorkspaceRenderPlan

    fun getPlan(): WorkspacePlan?

    fun getRenderPlan(): WorkspaceRenderPlan?

    fun tagsFor(
        variantId: String,
        variantType: String,
        targetKind: String
    ): List<String>?

    fun isReferencedProjectPath(projectPath: String): Boolean

    fun referencedTargetNames(projectPath: String): Set<String>

    fun isReferencedTarget(projectPath: String, targetName: String): Boolean

    companion object {
        internal const val SERVICE_NAME = "WorkspacePlanService"

        internal fun register(@RootProject rootProject: Project) = rootProject
            .gradle
            .sharedServices
            .registerIfAbsent(SERVICE_NAME, DefaultWorkspacePlanService::class.java) {}
    }

    interface Params : BuildServiceParameters
}

internal abstract class DefaultWorkspacePlanService : WorkspacePlanService {
    private val lock = Any()
    private var workspacePlan: WorkspacePlan? = null
    private var workspaceRenderPlan: WorkspaceRenderPlan? = null
    private var targetTagsByKey: Map<TargetTagKey, List<String>>? = null

    override fun populatePlan(workspacePlan: WorkspacePlan) {
        synchronized(lock) {
            this.workspacePlan = workspacePlan
            targetTagsByKey = null
        }
    }

    override fun populateRenderPlan(workspaceRenderPlan: WorkspaceRenderPlan) {
        synchronized(lock) {
            this.workspaceRenderPlan = workspaceRenderPlan
        }
    }

    override fun initPlan(workspacePlanJson: File): WorkspacePlan {
        return synchronized(lock) {
            if (workspacePlan == null) {
                workspacePlan = fromJson(workspacePlanJson)
                targetTagsByKey = null
            }
            workspacePlan!!
        }
    }

    override fun initRenderPlan(workspaceRenderPlanJson: File): WorkspaceRenderPlan {
        return synchronized(lock) {
            if (workspaceRenderPlan == null) {
                workspaceRenderPlan = fromJson(workspaceRenderPlanJson)
            }
            workspaceRenderPlan!!
        }
    }

    override fun getPlan(): WorkspacePlan? = synchronized(lock) { workspacePlan }

    override fun getRenderPlan(): WorkspaceRenderPlan? = synchronized(lock) { workspaceRenderPlan }

    override fun tagsFor(
        variantId: String,
        variantType: String,
        targetKind: String
    ): List<String>? {
        return synchronized(lock) {
            tagsByKey()[TargetTagKey(
                variantId = variantId,
                variantType = variantType,
                targetKind = targetKind
            )]
        }
    }

    override fun isReferencedProjectPath(projectPath: String): Boolean =
        synchronized(lock) {
            projectPath in workspaceRenderPlan?.referencedProjectPaths.orEmpty()
        }

    override fun referencedTargetNames(projectPath: String): Set<String> =
        synchronized(lock) {
            workspaceRenderPlan
                ?.referencedProjectTargets
                .orEmpty()[projectPath]
                .orEmpty()
        }

    override fun isReferencedTarget(projectPath: String, targetName: String): Boolean =
        synchronized(lock) {
            targetName in workspaceRenderPlan
                ?.referencedProjectTargets
                .orEmpty()[projectPath]
                .orEmpty()
        }

    override fun close() {
        synchronized(lock) {
            workspacePlan = null
            workspaceRenderPlan = null
            targetTagsByKey = null
        }
    }

    private fun tagsByKey(): Map<TargetTagKey, List<String>> {
        targetTagsByKey?.let { tags -> return tags }
        return workspacePlan
            ?.tagPlan
            .orEmpty()
            .associate { tagPlan -> tagPlan.key to tagPlan.tags }
            .also { tags -> targetTagsByKey = tags }
    }
}
