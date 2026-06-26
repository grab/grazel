/*
 * Copyright 2023 Grabtaxi Holdings PTE LTD (GRAB)
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

import com.grab.grazel.di.GradleServices
import com.grab.grazel.di.GrazelComponent
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.dependencies.model.WorkspacePlan
import com.grab.grazel.gradle.dependencies.model.WorkspaceRenderPlan
import com.grab.grazel.migrate.dependencies.ArtifactPinner
import com.grab.grazel.util.fromJson
import dagger.Lazy
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.register
import javax.inject.Inject

@UntrackedTask(because = "Up to date check implemented manually")
internal open class PinMavenArtifactsTask
@Inject
constructor(
    private val artifactPinner: Lazy<ArtifactPinner>,
    private val gradleServices: GradleServices,
) : DefaultTask() {

    init {
        group = GRAZEL_TASK_GROUP
        description = "Pin maven artifacts"
    }

    @get:InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    val workspaceFile: RegularFileProperty = gradleServices.objectFactory.fileProperty()

    @get:InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    val workspacePlan: RegularFileProperty = gradleServices.objectFactory.fileProperty()

    @get:InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    val workspaceRenderPlan: RegularFileProperty = gradleServices.objectFactory.fileProperty()

    @get:InputFile
    @get:Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    val workspaceDependencies: RegularFileProperty = gradleServices.objectFactory.fileProperty()

    @get:Input
    val planParity: Property<Boolean> = gradleServices.objectFactory.property()

    @TaskAction
    fun action() {
        val assertPlanParity = planParity.getOrElse(false)
        artifactPinner.get().pinArtifacts(
            workspaceFile = workspaceFile.get().asFile,
            workspacePlan = fromJson<WorkspacePlan>(workspacePlan.get()),
            workspaceRenderPlan = fromJson<WorkspaceRenderPlan>(workspaceRenderPlan.get()),
            gradleServices = gradleServices,
            logger = logger,
            legacyWorkspaceDependencies = if (assertPlanParity) {
                fromJson<WorkspaceDependencies>(workspaceDependencies.get())
            } else {
                null
            },
            assertPlanParity = assertPlanParity
        )
    }

    companion object {
        private const val TASK_NAME = "pinMavenArtifacts"

        fun register(
            rootProject: Project,
            grazelComponent: GrazelComponent,
            configureTask: PinMavenArtifactsTask.() -> Unit = {}
        ) = rootProject.tasks.register<PinMavenArtifactsTask>(
            TASK_NAME,
            grazelComponent.artifactPinner(),
            GradleServices.from(rootProject)
        ).apply {
            configure {
                planParity.convention(
                    rootProject.providers.gradleProperty("grazel.internal.planParity")
                        .map { value -> value.toBoolean() }
                        .orElse(false)
                )
                configureTask()
            }
        }
    }
}
