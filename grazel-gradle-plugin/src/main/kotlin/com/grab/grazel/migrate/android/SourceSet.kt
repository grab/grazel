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

package com.grab.grazel.migrate.android

import com.android.build.gradle.api.AndroidSourceSet
import com.android.build.gradle.internal.utils.toImmutableSet
import com.grab.grazel.migrate.android.PathResolveMode.DIRECTORY
import com.grab.grazel.migrate.android.PathResolveMode.FILES
import com.grab.grazel.migrate.android.SourceSetType.ASSETS
import com.grab.grazel.migrate.android.SourceSetType.JAVA
import com.grab.grazel.migrate.android.SourceSetType.JAVA_KOTLIN
import com.grab.grazel.migrate.android.SourceSetType.KOTLIN
import com.grab.grazel.migrate.android.SourceSetType.RESOURCES
import com.grab.grazel.util.commonPath
import org.gradle.api.Project
import java.io.File

private const val JAVA_PATTERN = "**/*.java"
private const val KOTLIN_PATTERN = "**/*.kt"
private const val ALL_PATTERN = "**"

private const val JAVA_DEFAULT_TEST_DIR = "src/test/java"
private const val KOTLIN_DEFAULT_TEST_DIR = "src/test/kotlin"

enum class SourceSetType(val patterns: Sequence<String>) {
    JAVA(patterns = sequenceOf(JAVA_PATTERN)),
    JAVA_KOTLIN(patterns = sequenceOf(JAVA_PATTERN, KOTLIN_PATTERN)),
    KOTLIN(patterns = sequenceOf(KOTLIN_PATTERN)),
    RESOURCES(patterns = sequenceOf(ALL_PATTERN)),
    ASSETS(patterns = sequenceOf(ALL_PATTERN))
}

enum class PathResolveMode {
    /**
     * If source set directory exists then directly return the directory
     */
    DIRECTORY,

    /**
     * Try to expand the directory based on matching patterns from [SourceSetType] and then
     * find the most common directory and return the pattern. If only one file is present, return
     * path to that file alone
     */
    FILES
}

internal fun AndroidSourceSet.toResourceSet(
    project: Project
): Set<BazelSourceSet> {
    fun File.isValid() = exists() && walk().drop(1).any()
    fun File.isNotInBuildDir() = !project.relativePath(this).startsWith("build/")

    val manifestPath = manifest.srcFile
        .takeIf { it.exists() && it.isNotInBuildDir() }
        ?.let(project::relativePath)
    val resources = res.srcDirs
        .filter(File::isValid)
        .filter(File::isNotInBuildDir)
    val assets = assets.srcDirs
        .filter(File::isValid)
        .filter(File::isNotInBuildDir)

    return if (resources.size <= 1 && assets.size <= 1) {
        // Happy path, most modules would be like this with one single res and assets dir.
        setOf(
            BazelSourceSet(
                name = name,
                res = resources.firstOrNull()?.let(project::relativePath),
                assets = assets.firstOrNull()?.let(project::relativePath),
                manifest = manifestPath
            )
        )
    } else {
        // Special case: res and assets have custom dirs, hence manually map each of them as a source
        // set dir for Bazel.
        return LinkedHashSet<BazelSourceSet>().apply {
            resources.mapIndexedTo(this) { index, resDir ->
                // No need to duplicate manifest across res directories for same source set, assign to
                // first entry alone
                val sourceSetManifest = if (index == 0) manifestPath else null
                BazelSourceSet(
                    name = name,
                    res = project.relativePath(resDir),
                    assets = null,
                    manifest = sourceSetManifest,
                )
            }
            assets.mapIndexedTo(this) { index, assets ->
                // No need to duplicate manifest across res directories for same source set, assign to
                // first entry alone
                val sourceSetManifest = if (index == 0) manifestPath else null
                BazelSourceSet(
                    name = name,
                    res = null,
                    assets = project.relativePath(assets),
                    manifest = sourceSetManifest,
                )
            }
        }.toImmutableSet()
    }
}


/**
 * Given a list of directories specified by `dirs` and list of file patterns specified by `patterns`
 * will return list of `dir/pattern` where `dir`s has at least one file matching the pattern.
 */
internal fun Project.filterSourceSetPaths(
    dirs: Sequence<File>,
    patterns: Sequence<String>,
    pathResolveMode: PathResolveMode = FILES
): Sequence<String> = dirs.filter(File::exists)
    .map(::relativePath)
    .flatMap { dir ->
        when (pathResolveMode) {
            DIRECTORY -> sequenceOf(dir)
            FILES -> patterns.flatMap { pattern ->
                val matchedFiles = fileTree(dir).matching { include(pattern) }.files
                when {
                    matchedFiles.isEmpty() -> sequenceOf()
                    else -> {
                        val commonPath = commonPath(*matchedFiles.map { it.path }.toTypedArray())
                        val relativePath = relativePath(commonPath)
                        when (matchedFiles.size) {
                            1 -> sequenceOf(relativePath)
                            else -> sequenceOf("$relativePath/$pattern")
                        }
                    }
                }
            }
        }
    }
    .filter { !it.startsWith("build/") }
    .distinct()

internal fun Project.filterNonDefaultSourceSetDirs(
    dirs: Sequence<File>,
): Sequence<String> = dirs.filter(File::exists)
    .map(::relativePath)
    .filter { dir ->
        dir != JAVA_DEFAULT_TEST_DIR && dir != KOTLIN_DEFAULT_TEST_DIR
    }

internal fun Project.androidSources(
    sourceSets: List<AndroidSourceSet>,
    sourceSetType: SourceSetType,
    pathResolveMode: PathResolveMode = FILES
): Sequence<String> {
    val sourceSetChoosers: AndroidSourceSet.() -> Sequence<File> =
        when (sourceSetType) {
            JAVA, JAVA_KOTLIN, KOTLIN -> {
                { java.srcDirs.asSequence() }
            }

            RESOURCES -> {
                { res.srcDirs.asSequence() }
            }

            ASSETS -> {
                {
                    assets.srcDirs
                        .asSequence()
                        .filter { it.endsWith("assets") } // Filter all custom resource sets
                }
            }
        }
    val dirs = sourceSets.asSequence().flatMap(sourceSetChoosers)
    val dirsKotlin = dirs.map { File(it.path.replace("/java", "/kotlin")) }
    return filterSourceSetPaths(dirs + dirsKotlin, sourceSetType.patterns, pathResolveMode)
}