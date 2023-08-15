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

package com.grab.grazel.di

import org.gradle.api.Project
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.internal.logging.progress.ProgressLoggerFactory
import org.gradle.process.ExecOperations
import org.gradle.workers.WorkerExecutor

/**
 * A data class to hold all the gradle services and make them injectable via Dagger as Dagger expects
 * every dependency in a `@Inject` constructor to be available in the graph.
 *
 * Note: Even though instance of this can be injected by Dagger, it is not recommended to inject
 * that way since the default instance available on Dagger will be derived from root project instance.
 * Where needed, manually acquire an instance by using [GradleServices.from] method.
 */
data class GradleServices(
    val execOperations: ExecOperations,
    val objectFactory: ObjectFactory,
    val layout: ProjectLayout,
    val fileSystemOperations: FileSystemOperations,
    val workerExecutor: WorkerExecutor,
    val progressLoggerFactory: ProgressLoggerFactory,
) {
    companion object {
        fun from(project: Project) = GradleServices(
            execOperations = project.serviceOf(),
            objectFactory = project.serviceOf(),
            layout = project.serviceOf(),
            fileSystemOperations = project.serviceOf(),
            workerExecutor = project.serviceOf(),
            progressLoggerFactory = project.serviceOf()
        )
    }
}