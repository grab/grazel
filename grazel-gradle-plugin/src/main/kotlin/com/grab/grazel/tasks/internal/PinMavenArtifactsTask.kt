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
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.migrate.dependencies.ArtifactPinner
import dagger.Lazy
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
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
    val workspaceDependencies: RegularFileProperty = gradleServices.objectFactory.fileProperty()

    @get:Internal
    val dependencyResolutionService: Property<DefaultDependencyResolutionService> = project
        .objects.property()

    @TaskAction
    fun action() {
        artifactPinner.get().pinArtifacts(
            workspaceFile = workspaceFile.get().asFile,
            workspaceDependencies = dependencyResolutionService
                .get()
                .get(workspaceDependencies.get().asFile),
            gradleServices = gradleServices,
            logger = logger
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
            configure { configureTask() }
        }
    }
}