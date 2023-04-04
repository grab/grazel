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

package com.grab.grazel.bazel.starlark

import com.grab.grazel.gradle.buildTargetName
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency

sealed class BazelDependency {
    data class ProjectDependency(
        val dependencyProject: Project,
        val suffix: String = "",
        val prefix: String = ""
    ) : BazelDependency() {

        override fun toString(): String {
            val relativeRootPath = dependencyProject
                .rootProject
                .relativePath(dependencyProject.projectDir)
            val buildTargetName = dependencyProject.buildTargetName()
            return when {
                relativeRootPath.contains("/") -> {
                    val path = relativeRootPath
                        .split("/")
                        .dropLast(1)
                        .joinToString("/")
                    "//$path/$buildTargetName:$prefix$buildTargetName$suffix"
                }
                else -> "//$buildTargetName:$prefix$buildTargetName$suffix"
            }
        }
    }

    data class StringDependency(val dep: String) : BazelDependency() {
        override fun toString() = dep
    }

    data class MavenDependency(val dependency: Dependency) : BazelDependency() {

        private fun String.toBazelPath(): String {
            return replace(".", "_").replace("-", "_")
        }

        override fun toString(): String {
            val group = dependency.group?.toBazelPath() ?: ""
            val name = dependency.name.toBazelPath()
            return "@maven//:${group}_$name"
        }
    }
}