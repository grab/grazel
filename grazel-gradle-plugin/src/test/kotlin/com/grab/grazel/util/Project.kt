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

package com.grab.grazel.util

import com.grab.grazel.GrazelExtension
import com.grab.grazel.di.DaggerGrazelComponent
import com.grab.grazel.di.GrazelComponent
import org.gradle.api.Project
import org.gradle.api.internal.project.DefaultProject
import org.gradle.kotlin.dsl.the

/**
 * Forces an evaluation of the project thereby running all configurations
 */
fun Project.doEvaluate() {
    getTasksByName("tasks", false)
    (this as DefaultProject).evaluate()
}

internal fun Project.createGrazelComponent(): GrazelComponent {
    return DaggerGrazelComponent.factory().create(this)
}

internal fun Project.addGrazelExtension(configure: GrazelExtension.() -> Unit = {}) {
    val grazelGradlePluginExtension = GrazelExtension(rootProject)
    rootProject.extensions.add(GrazelExtension.GRAZEL_EXTENSION, grazelGradlePluginExtension)
    the<GrazelExtension>().apply(configure)
}