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
import com.grab.grazel.gradle.dependencies.model.WorkspacePlan
import com.grab.grazel.util.fromJson
import org.gradle.api.Project
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File

internal abstract class WorkspacePlanService : BuildService<BuildServiceParameters.None>, AutoCloseable {
    private val lock = Any()
    private var workspacePlan: WorkspacePlan? = null

    fun populatePlan(workspacePlan: WorkspacePlan) {
        synchronized(lock) {
            this.workspacePlan = workspacePlan
        }
    }

    fun initPlan(workspacePlanJson: File): WorkspacePlan {
        return synchronized(lock) {
            if (workspacePlan == null) {
                workspacePlan = fromJson(workspacePlanJson)
            }
            workspacePlan!!
        }
    }

    override fun close() {
        synchronized(lock) {
            workspacePlan = null
        }
    }

    companion object {
        internal const val SERVICE_NAME = "WorkspacePlanService"

        internal fun register(@RootProject rootProject: Project) = rootProject
            .gradle
            .sharedServices
            .registerIfAbsent(SERVICE_NAME, WorkspacePlanService::class.java) {}
    }
}
