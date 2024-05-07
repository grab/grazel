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

import kotlinx.serialization.Serializable
import org.gradle.api.Project
import java.io.File

// TODO(arun) Rename this to BazelLabel
sealed class BazelDependency : Comparable<BazelDependency> {

    override fun compareTo(other: BazelDependency) = toString().compareTo(other.toString())

    data class ProjectDependency(
        val dependencyProject: Project,
        val suffix: String = "",
        val prefix: String = ""
    ) : BazelDependency() {

        override fun toString(): String {
            val relativeRootPath = dependencyProject
                .rootProject
                .relativePath(dependencyProject.projectDir)
            val buildTargetName = dependencyProject.name
            val sep = File.separator
            return when {
                relativeRootPath.contains(sep) -> {
                    val path = relativeRootPath
                        .split(sep)
                        .dropLast(1)
                        .joinToString(sep)
                    "//$path/$buildTargetName:$prefix$buildTargetName$suffix"
                }

                else -> "//$buildTargetName:$prefix$buildTargetName$suffix"
            }
        }
    }

    data class FileDependency(
        val file: File,
        val rootProject: Project
    ) : BazelDependency() {
        override fun toString(): String {
            val fileName = file.name
            val filePath = rootProject.relativePath(file)
            return if (fileName == filePath) {
                "//:$fileName"
            } else {
                // The file is not in root directory
                "//${filePath.substringBeforeLast(File.separator)}:$fileName"
            }
        }
    }

    data class StringDependency(val string: String) : BazelDependency() {
        override fun toString() = string
    }

    @Serializable
    data class MavenDependency(
        val repo: String = "maven",
        val group: String,
        val name: String
    ) : BazelDependency() {

        private fun String.toBazelPath(): String {
            return replace(".", "_").replace("-", "_")
        }

        override fun toString(): String {
            val group = group.toBazelPath()
            val name = name.toBazelPath()
            return "@$repo//:${group}_$name"
        }
    }
}