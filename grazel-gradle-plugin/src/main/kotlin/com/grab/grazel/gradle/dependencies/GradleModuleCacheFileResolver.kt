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

package com.grab.grazel.gradle.dependencies

import com.google.common.io.Files
import com.grab.grazel.maven.MavenCoordinates
import com.grab.grazel.maven.MavenPath
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal class GradleModuleCacheFileIndexBuilder(
    private val fileResolver: GradleModuleCacheFileResolver
) {
    constructor(gradleUserHomeDir: File) : this(GradleModuleCacheFileResolver(gradleUserHomeDir))

    fun index(gavs: Iterable<String>): Map<String, File> {
        val index = sortedMapOf<String, File>()
        gavs
            .map { gav -> MavenCoordinates.parse(gav) }
            .sortedWith(
                compareBy<MavenCoordinates> { coordinates -> coordinates.group }
                    .thenBy { coordinates -> coordinates.module }
                    .thenBy { coordinates -> coordinates.version }
            )
            .forEach { coordinates ->
                fileResolver.cacheFiles(coordinates)
                    .forEach { file ->
                        coordinates.mavenRelativePaths(file.name).forEach { path ->
                            putMavenFile(index = index, path = path, file = file)
                        }
                    }
            }
        return index
    }
}

internal class GradleModuleCacheFileResolver(
    gradleUserHomeDir: File
) {
    private val modulesCacheRoot: File =
        gradleUserHomeDir.resolve("caches/modules-2/files-2.1")
    private val artifactPathCache = ConcurrentHashMap<String, ArtifactPathResolution>()
    private val cacheFilesByCoordinates = ConcurrentHashMap<MavenCoordinates, List<File>>()

    fun resolveArtifact(path: String): File? {
        return when (val resolution = artifactPathCache.computeIfAbsent(path, ::resolveArtifactUncached)) {
            is ArtifactPathResolution.Found -> resolution.file
            ArtifactPathResolution.Missing -> null
        }
    }

    private fun resolveArtifactUncached(path: String): ArtifactPathResolution {
        val mavenPath = MavenPath.parse(path) ?: return ArtifactPathResolution.Missing
        val matchingFiles = cacheFiles(mavenPath.coordinates)
            .filter { file ->
                file.name == mavenPath.fileName ||
                    path in mavenPath.coordinates.mavenRelativePaths(file.name)
            }
        return artifactPathResolution(singleMavenFileOrNull(files = matchingFiles, path = path))
    }

    fun cacheFiles(coordinates: MavenCoordinates): List<File> {
        return cacheFilesByCoordinates.computeIfAbsent(coordinates, ::cacheFilesUncached)
    }

    fun cacheDirectory(coordinates: MavenCoordinates): File =
        coordinates.cacheDirectory(modulesCacheRoot)

    /**
     * Gradle's `modules-2` cache stores each artifact under a hash-named subdirectory
     * (`<group>/<module>/<version>/<hash>/<file>`) with no guaranteed enumeration order, so
     * both the hash directories and the files within each are explicitly sorted by name here
     * to give deterministic candidate ordering. Because [singleMavenFileOrNull] only returns a
     * value when all candidates are byte-identical (and hard-fails on any genuine divergence),
     * this ordering does not change the served bytes — it only makes *which* identical-content
     * file is returned, and any error-message text, stable across JVM/filesystem runs rather than
     * dependent on incidental directory-listing order.
     */
    private fun cacheFilesUncached(coordinates: MavenCoordinates): List<File> =
        cacheDirectory(coordinates)
            .listFiles()
            .orEmpty()
            .filter { hashDirectory -> hashDirectory.isDirectory }
            .sortedBy { hashDirectory -> hashDirectory.name }
            .flatMap { hashDirectory ->
                hashDirectory
                    .listFiles()
                    .orEmpty()
                    .filter { file -> file.isFile }
                    .sortedBy { file -> file.name }
            }

    private sealed interface ArtifactPathResolution {
        data class Found(val file: File) : ArtifactPathResolution
        object Missing : ArtifactPathResolution
    }

    private fun artifactPathResolution(file: File?): ArtifactPathResolution =
        file?.let { ArtifactPathResolution.Found(it) } ?: ArtifactPathResolution.Missing
}

/**
 * Enforces that a single Maven path can only ever map to one physical file: a new mapping is
 * accepted only if there's no existing entry, or the existing entry is the same file (by
 * identity or, failing that, byte-for-byte content via [Files.equal]). Any genuine divergence
 * hard-fails rather than silently picking a winner, since silently choosing one would mean the
 * proxy could serve different bytes for the same path across requests depending on unrelated
 * indexing order.
 */
internal fun putMavenFile(
    index: MutableMap<String, File>,
    path: String,
    file: File,
) {
    val existing = index[path]
    when {
        existing == null -> index[path] = file
        existing == file -> Unit
        Files.equal(existing, file) -> Unit
        else -> error(
            "Gradle cache has multiple different files for Maven path $path: " +
                "${existing.absolutePath} and ${file.absolutePath}"
        )
    }
}

/**
 * Same single-file-per-path invariant as [putMavenFile], applied via fold to a candidate list
 * of module-cache matches for one path: any two candidates that aren't identical or
 * byte-equal ([Files.equal]) are treated as a genuine conflict and hard-fail rather than
 * arbitrarily selecting one, since the caller ([resolveArtifactUncached]) needs a single
 * unambiguous file to serve for that path.
 */
private fun singleMavenFileOrNull(
    files: List<File>,
    path: String,
): File? {
    return files.fold<File, File?>(null) { selected, file ->
        when {
            selected == null -> file
            selected == file -> selected
            Files.equal(selected, file) -> selected
            else -> error(
                "Gradle cache has multiple different files for Maven path $path: " +
                    "${selected.absolutePath} and ${file.absolutePath}"
            )
        }
    }
}
