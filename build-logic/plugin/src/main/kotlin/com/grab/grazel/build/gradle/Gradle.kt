/*
 * Copyright 2024 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.build.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.findByType

public open class ConfigurablePlugin(
    private val configuration: Project.() -> Unit
) : Plugin<Project> {
    override fun apply(project: Project): Unit = configuration(project)
}

/**
 * Configures a gradle extension if it exists and does nothing otherwise
 */
internal inline fun <reified T : Any> Project.configureIfExist(builder: T.() -> Unit) {
    extensions.findByType<T>()?.apply(builder)
}
