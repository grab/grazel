/*
 * Copyright 2021 Grabtaxi Holdings PTE LTE (GRAB)
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

package com.grab.grazel

import com.grab.grazel.GrazelExtension.Companion.GRAZEL_EXTENSION
import com.grab.grazel.di.DaggerGrazelComponent
import com.grab.grazel.hybrid.doHybridBuild
import com.grab.grazel.tasks.internal.TaskManager
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create

class GrazelGradlePlugin : Plugin<Project> {
    private lateinit var project: Project

    override fun apply(project: Project) {
        this.project = project
        if (project != project.rootProject) {
            throw IllegalStateException("Grazel should be only applied to root build.gradle")
        }
        project.extensions.create<GrazelExtension>(GRAZEL_EXTENSION, project)

        val grazelComponent = DaggerGrazelComponent.factory().create(project)

        TaskManager(project, grazelComponent).configTasks()
        project.doHybridBuild()
    }
}