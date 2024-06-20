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

package com.grab.grazel.migrate.dependencies

import org.gradle.api.Project
import com.grab.grazel.bazel.starlark.BazelDependency
import org.gradle.api.artifacts.ProjectDependency

fun Project.getDependencies(
    configurationName: String,
    mavenRepoName: String
): List<BazelDependency>? {
    return configurations.asSequence().filter { it.name.contains(configurationName) }
        .flatMap { targetConfiguration ->
            targetConfiguration
                .dependencies
                .asSequence()
                .map {
                    if (it is ProjectDependency) {
                        BazelDependency.ProjectDependency(it.dependencyProject)
                    } else {
                        BazelDependency.MavenDependency(
                            group = it.group!!,
                            name = it.name,
                            repo = mavenRepoName
                        )
                    }
                }
        }.let { it.toList().ifEmpty { null } }
}