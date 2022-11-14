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

import com.grab.grazel.di.GrazelComponent
import com.grab.grazel.gradle.dependencies.MavenInstallArtifactsCalculator
import dagger.Lazy
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import javax.inject.Inject

internal open class ResolveDependenciesTask
@Inject
constructor(
    private val mavenInstallArtifactsCalculator: Lazy<MavenInstallArtifactsCalculator>
) : DefaultTask() {

    @TaskAction
    fun action() {
        val arifactsMap = mavenInstallArtifactsCalculator.get()
            .calculate()
        arifactsMap
    }

    internal companion object {
        private const val TASK_NAME = "resolveDependencies"

        fun register(
            rootProject: Project,
            grazelComponent: GrazelComponent
        ): TaskProvider<ResolveDependenciesTask> {
            return rootProject.tasks.register<ResolveDependenciesTask>(
                TASK_NAME,
                grazelComponent.mavenInstallArtifactsCalculator()
            ).apply {
                configure {
                    group = GRAZEL_TASK_GROUP
                }
            }
        }
    }
}