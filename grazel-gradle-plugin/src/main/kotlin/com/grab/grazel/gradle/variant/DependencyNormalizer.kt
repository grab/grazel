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

package com.grab.grazel.gradle.variant

import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.BazelDependency.FileDependency
import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.bazel.starlark.BazelDependency.ProjectDependency
import com.grab.grazel.bazel.starlark.BazelDependency.StringDependency
import java.io.File
import javax.inject.Inject

/**
 * Normalizes [BazelDependency] instances for comparison by removing variant-specific suffixes.
 *
 * This is used in variant compression to determine if two dependencies are semantically equivalent
 * despite having different variant-specific naming.
 */
internal interface DependencyNormalizer {
    /**
     * Normalizes a dependency to a canonical form by removing variant-specific suffixes.
     *
     * @param dependency The dependency to normalize
     * @return A normalized string representation suitable for comparison
     */
    fun normalize(dependency: BazelDependency): String
}

internal class DefaultDependencyNormalizer @Inject constructor() : DependencyNormalizer {

    override fun normalize(dependency: BazelDependency): String {
        return when (dependency) {
            is ProjectDependency -> normalizeProjectDependency(dependency)
            is MavenDependency -> normalizeMavenDependency(dependency)
            is StringDependency -> normalizeStringDependency(dependency)
            is FileDependency -> normalizeFileDependency(dependency)
        }
    }

    /**
     * Normalizes a project dependency by removing variant-specific suffixes.
     *
     * Removes:
     * - Type suffixes: `_kt`, `_lib`
     * - Variant suffixes: `-{flavor}-{buildType}`, `-{buildType}`
     *
     * Examples:
     * - `//foo:foo_kt-free-debug` -> `//foo:foo`
     * - `//bar:bar_lib-debug` -> `//bar:bar`
     * - `//baz:baz-paid-release` -> `//baz:baz`
     */
    private fun normalizeProjectDependency(dependency: ProjectDependency): String {
        val relativeRootPath = dependency.dependencyProject
            .rootProject
            .relativePath(dependency.dependencyProject.projectDir)

        val buildTargetName = dependency.dependencyProject.name
        val sep = File.separator

        // Construct base path without suffix or prefix
        val basePath = when {
            sep in relativeRootPath -> {
                val path = relativeRootPath
                    .split(sep)
                    .dropLast(1)
                    .joinToString(sep)
                "//$path/$buildTargetName"
            }

            else -> "//$buildTargetName"
        }

        // Return normalized label without any suffix
        return "$basePath:$buildTargetName"
    }

    /**
     * Normalizes a Maven dependency to a canonical form.
     *
     * Format: `@{repo}//:{group}_{name}`
     *
     * Example:
     * - `@maven//:com_google_guava_guava` (stays the same)
     */
    private fun normalizeMavenDependency(dependency: MavenDependency): String {
        val group = dependency.group.toBazelPath()
        val name = dependency.name.toBazelPath()
        return "@${dependency.repo}//:${group}_$name"
    }

    /** Normalizes a string dependency by trimming whitespace. */
    private fun normalizeStringDependency(dependency: StringDependency): String {
        return dependency.string.trim()
    }

    /**
     * Normalizes a file dependency by relativizing to root project.
     *
     * Example:
     * - File in root: `//:file.jar`
     * - File in subdir: `//libs:file.jar`
     */
    private fun normalizeFileDependency(dependency: FileDependency): String {
        val fileName = dependency.file.name
        val filePath = dependency.file.absoluteFile
            .normalize()
            .relativeTo(dependency.rootProject.projectDir).toString()

        return if (fileName == filePath) {
            "//:$fileName"
        } else {
            // The file is not in root directory
            "//${filePath.substringBeforeLast(File.separator)}:$fileName"
        }
    }

    private fun String.toBazelPath(): String {
        return replace(".", "_").replace("-", "_")
    }
}
