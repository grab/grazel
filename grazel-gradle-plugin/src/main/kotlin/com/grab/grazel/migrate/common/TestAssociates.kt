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

package com.grab.grazel.migrate.common

import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.BazelDependency.ProjectDependency
import com.grab.grazel.gradle.hasDatabinding
import com.grab.grazel.gradle.isAndroid
import com.grab.grazel.gradle.isKotlin
import org.gradle.api.Project

/**
 * For given `project`, calculate the associate target name. This is needed since we have macros like `kt_android_library`
 * or `kt_db_android_library` which are macros and hide the actual kotlin targets differently. This method chooses the correct
 * associate target - which is the Kotlin target based on project type.
 *
 * @param project The project for which associate needs to be calculated
 * @return The associate that was calculated, null otherwise
 */
internal fun calculateTestAssociate(project: Project, suffix: String = ""): BazelDependency? {
    return when {
        project.isKotlin && project.hasDatabinding -> {
            return BazelDependency.StringDependency(
                """${ProjectDependency(project, suffix)}-kotlin"""
            )
        }
        project.isKotlin && project.isAndroid -> return BazelDependency.StringDependency(
            """${ProjectDependency(project, suffix)}_kt"""
        )
        project.isKotlin -> return ProjectDependency(project, suffix)
        else -> null
    }
}