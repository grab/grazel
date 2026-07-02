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
        fun parse(gav: String): MavenCoordinates {
            val parts = gav.split(":")
            require(parts.size == 3) { "Expected group:name:version coordinate, got $gav" }
            return MavenCoordinates(
                group = parts[0],
                module = parts[1],
                version = parts[2]
            )
        }
    }
}

internal fun isConcreteMavenArtifactPath(path: String): Boolean {
    if (path.endsWith(".pom") || path.endsWith(".module")) return false
    if (path.endsWith(".sha1") || path.endsWith(".md5") || path.endsWith(".sha256")) return false
    if (path.endsWith("maven-metadata.xml")) return false
    return MavenPath.parse(path) != null
}
