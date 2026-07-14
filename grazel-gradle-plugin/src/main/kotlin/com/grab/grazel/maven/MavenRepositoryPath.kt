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

package com.grab.grazel.maven

import java.io.File

internal data class MavenPath(
    val coordinates: MavenCoordinates,
    val fileName: String,
) {
    companion object {
        /**
         * Splits a Maven repository-relative path by `/` and derives group/module/version/
         * fileName purely by position: the last segment is the file name, the two before it
         * are version and module, and everything before that (dot-joined) is the group. This
         * is an implicit format contract — it assumes a well-formed `<group-segments>/<module>/
         * <version>/<file>` layout with at least 4 segments — that every other proxy/index
         * function in this package relies on rather than re-deriving.
         */
        fun parse(path: String): MavenPath? {
            val parts = path.split("/")
            if (parts.size < 4) return null
            return MavenPath(
                coordinates = MavenCoordinates(
                    group = parts.dropLast(3).joinToString("."),
                    module = parts[parts.lastIndex - 2],
                    version = parts[parts.lastIndex - 1],
                ),
                fileName = parts.last()
            )
        }
    }
}

internal data class MavenCoordinates(
    val group: String,
    val module: String,
    val version: String,
) {
    val gav: String = "$group:$module:$version"
    val shortId: String = "$group:$module"

    fun mavenRelativePaths(fileName: String): Set<String> {
        val physicalPath = mavenRelativePath(fileName)
        val canonicalPath = canonicalMavenRelativePath(fileName)
        return setOf(physicalPath, canonicalPath)
    }

    fun canonicalMavenRelativePath(fileName: String): String =
        mavenRelativePath(canonicalMavenFileName(fileName))

    fun cacheDirectory(modulesCacheRoot: File): File {
        return listOf(modulesCacheRoot.path, group, module, version)
            .joinToString(File.separator)
            .let(::File)
    }

    /**
     * Reconstructs the canonical `<module>-<version>[-classifier].<ext>` filename for this
     * GAV, deriving the classifier as whatever follows the `<module>-<version>-` prefix. Bails
     * out to the original [fileName] whenever [looksLikeDifferentVersionedMavenFileName] flags
     * it as actually belonging to a different version of the same module, since blindly
     * stripping a prefix in that case would fabricate a bogus classifier rather than leave the
     * file alone.
     */
    private fun canonicalMavenFileName(fileName: String): String {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
            .takeIf { extension -> extension.isNotBlank() }
            ?: return fileName
        val mavenPrefix = "$module-$version"
        val baseName = fileName.removeSuffix(".$extension")
        if (looksLikeDifferentVersionedMavenFileName(baseName, mavenPrefix)) return fileName
        val classifier = baseName
            .takeIf { name -> name.startsWith("$mavenPrefix-") }
            ?.removePrefix("$mavenPrefix-")
            ?.takeIf { value -> value.isNotBlank() }
        return buildString {
            append(mavenPrefix)
            if (classifier != null) {
                append('-')
                append(classifier)
            }
            append('.')
            append(extension)
        }
    }

    /**
     * Guards [canonicalMavenFileName] against mistaking a same-module, different-version file
     * for a classified artifact of the selected version. A [baseName] only counts as
     * "different-versioned" when it starts with `<module>-`, does NOT start with the selected
     * version's prefix, and the remainder after the module prefix begins with a digit — the
     * digit check is the load-bearing heuristic distinguishing an actual version segment
     * (e.g. `module-2.0-sources`) from a classifier that merely happens to not match the
     * selected version prefix by coincidence.
     */
    private fun looksLikeDifferentVersionedMavenFileName(
        baseName: String,
        selectedVersionPrefix: String,
    ): Boolean {
        val modulePrefix = "$module-"
        if (!baseName.startsWith(modulePrefix)) return false
        if (baseName == selectedVersionPrefix || baseName.startsWith("$selectedVersionPrefix-")) return false
        return baseName
            .removePrefix(modulePrefix)
            .firstOrNull()
            ?.isDigit() == true
    }

    private fun mavenRelativePath(fileName: String): String {
        return listOf(
            group.replace('.', '/'),
            module,
            version,
            fileName
        ).joinToString("/")
    }

    companion object {
        fun parseOrNull(gav: String): MavenCoordinates? {
            val parts = gav.split(":")
            if (parts.size != 3) return null
            val (group, module, version) = parts
            if (group.isBlank() || module.isBlank() || version.isBlank()) return null
            return MavenCoordinates(group, module, version)
        }

        fun parse(gav: String): MavenCoordinates {
            return parseOrNull(gav) ?: error("Expected group:name:version coordinate, got $gav")
        }
    }
}

internal fun isConcreteMavenArtifactPath(path: String): Boolean {
    if (path.endsWith(".pom") || path.endsWith(".module")) return false
    if (path.endsWith(".sha1") || path.endsWith(".md5") || path.endsWith(".sha256")) return false
    if (path.endsWith("maven-metadata.xml")) return false
    return MavenPath.parse(path) != null
}
