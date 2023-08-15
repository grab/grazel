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
import com.grab.grazel.di.qualifiers.RootProject
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import javax.inject.Inject

/**
 * Task to perform any work after all bazel scripts are successfully generated
 */
internal open class PostScriptGenerateTask
@Inject
constructor(
) : DefaultTask() {

    @TaskAction
    fun action() {
    }

    companion object {
        fun register(
            @RootProject rootProject: Project,
            grazelComponent: GrazelComponent,
            configureAction: PostScriptGenerateTask.() -> Unit = {}
        ) = rootProject.tasks
            .register<PostScriptGenerateTask>(
                "postScriptGenerateTask",
            ).apply {
                configure {
                    group = GRAZEL_TASK_GROUP
                    description = "Post script generation work"
                    configureAction(this)
                }
            }
    }
}