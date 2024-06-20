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

package com.grab.grazel.migrate.android

import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.migrate.dependencies.getDependencies
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Project

import org.gradle.kotlin.dsl.the

internal const val DETEKT_PLUGIN_ID = "io.gitlab.arturbosch.detekt"
private const val DETEKT_CHECKS_CONFIGURATION_NAME = "detektPlugins"
private const val DETEKT_MAVEN_REPO = "detekt_maven"


internal fun Project.detektPlugins(): List<BazelDependency>? {
    return getDependencies(DETEKT_CHECKS_CONFIGURATION_NAME, DETEKT_MAVEN_REPO)
}

internal fun detektConfig(project: Project): DetektConfigData {
    return if (project.hasDetekt()) {
        val extension = project.the<DetektExtension>()
        DetektConfigData(
            enabled = true,
            baseline = extension.baseline?.let { project.relativePath(it) },
            configs = extension.config.map {
                BazelDependency.FileDependency(
                    file = it,
                    rootProject = project.rootProject
                )
            }.toList(),
            debug = extension.debug.orNull(),
            parallel = extension.parallel.orNull(),
            allRules = extension.allRules.orNull(),
            buildUponDefaultConfig = extension.buildUponDefaultConfig.orNull(),
            disableDefaultRuleSets = extension.disableDefaultRuleSets.orNull(),
            autoCorrect = extension.autoCorrect.orNull(),
            detektChecks = project.detektPlugins(),
        )
    } else {
        DetektConfigData()
    }
}

private fun Boolean.orNull(): Boolean? {
    return if (this) this else null
}

private fun Project.hasDetekt() = plugins.hasPlugin(DETEKT_PLUGIN_ID)