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

import com.android.builder.model.LintOptions
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.BazelDependency.FileDependency
import com.grab.grazel.gradle.LINT_PLUGIN_ID
import com.grab.grazel.migrate.dependencies.getDependencies
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.kotlin.dsl.the

private const val LINT_CHECKS_CONFIGURATION_NAME = "lintChecks"
private const val LINT_MAVEN_REPO = "lint_maven"

fun Project.customLintRulesTargets(): List<BazelDependency>? {
    return getDependencies(LINT_CHECKS_CONFIGURATION_NAME, LINT_MAVEN_REPO)
}

internal fun lintConfigs(project: Project): LintConfigData {
    return if (project.plugins.hasPlugin(LINT_PLUGIN_ID)) {
        val lintOptions = project.the<LintOptions>()
        LintConfigData(
            enabled = true,
            lintConfig = lintOptions.lintConfig?.let {
                FileDependency(
                    file = it,
                    rootProject = project.rootProject
                )
            },
            baselinePath = lintOptions.baselineFile?.let { project.relativePath(it) },
            lintChecks = project.customLintRulesTargets()
        )
    } else {
        LintConfigData(
            enabled = false,
            lintConfig = null,
            baselinePath = null,
            lintChecks = project.customLintRulesTargets()
        )
    }
}

internal fun lintConfigs(
    lintOptions: com.android.build.gradle.internal.dsl.LintOptions,
    project: Project
) = LintConfigData(
    enabled = true,
    lintConfig = lintOptions.lintConfig?.let {
        FileDependency(
            file = it,
            rootProject = project.rootProject
        )
    },
    baselinePath = lintOptions.baselineFile?.let {
        project.relativePath(it)
    },
    lintChecks = project.customLintRulesTargets()
)