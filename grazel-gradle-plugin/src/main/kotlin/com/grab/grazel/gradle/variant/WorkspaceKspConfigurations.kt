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

package com.grab.grazel.gradle.variant

import com.grab.grazel.gradle.hasKsp
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration

internal data class WorkspaceKspProcessorClasspath(
    val declarationConfigurations: Set<Configuration>,
    val processorClasspath: Configuration
)

internal fun Project.workspaceKspProcessorClasspath(): WorkspaceKspProcessorClasspath? {
    if (!hasKsp) return null

    val declarationConfigurations = configurations
        .filter { configuration -> configuration.isKspDeclarationBucket }
        .toSet()
    if (declarationConfigurations.isEmpty()) return null

    val processorClasspath = configurations.maybeCreate(WORKSPACE_KSP_PROCESSOR_CLASSPATH_NAME)
    if (processorClasspath.extendsFrom.isEmpty()) {
        processorClasspath.apply {
            isCanBeResolved = true
            isCanBeConsumed = false
            isVisible = false
            setExtendsFrom(declarationConfigurations)
        }
    }

    return WorkspaceKspProcessorClasspath(
        declarationConfigurations = declarationConfigurations,
        processorClasspath = processorClasspath
    )
}

private const val WORKSPACE_KSP_PROCESSOR_CLASSPATH_NAME = "grazelKspProcessorClasspath"

private val Configuration.isKspDeclarationBucket: Boolean
    get() = name.startsWith("ksp") && "classpath" !in name.lowercase()
