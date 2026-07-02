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

package com.grab.grazel.proxy

import com.grab.grazel.maven.MavenCoordinates
import com.grab.grazel.migrate.dependencies.MavenInstallLockfileArtifactKey
import com.grab.grazel.migrate.dependencies.mavenInstallJsonName
import com.grab.grazel.util.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

internal data class MavenInstallLockfileFallbackIndex(
    val allowedOriginArtifactPaths: Set<String>,
    val additionalComponentGavs: Set<String>,
)

internal fun activeMavenInstallLockfileFallbackIndex(
    rootDirectory: File,
    activeMavenRepos: Set<String>,
): MavenInstallLockfileFallbackIndex {
    val fallbackIndexes = activeMavenRepos
        .asSequence()
        .map { repoName -> rootDirectory.resolve(mavenInstallJsonName(repoName)) }
        .filter(File::exists)
        .map { lockfile -> mavenInstallLockfileFallbackIndex(lockfile.readText()) }
        .toList()
    return MavenInstallLockfileFallbackIndex(
        allowedOriginArtifactPaths = fallbackIndexes.flatMapTo(sortedSetOf()) { index ->
            index.allowedOriginArtifactPaths
        },
        additionalComponentGavs = fallbackIndexes.flatMapTo(sortedSetOf()) { index ->
            index.additionalComponentGavs
        }
    )
}

internal fun mavenInstallLockfileFallbackIndex(lockfileContents: String): MavenInstallLockfileFallbackIndex {
    val lockfile = Json.parseToJsonElement(lockfileContents).jsonObject
    val artifactFallbacks = lockfile
        .getValue("artifacts")
        .jsonObject
        .asSequence()
        .map { (artifactKey, artifactInfo) ->
            fallbackForLockfileArtifact(
                artifactKey = artifactKey,
                artifactInfo = artifactInfo.jsonObject
            )
        }
        .toList()
    return MavenInstallLockfileFallbackIndex(
        allowedOriginArtifactPaths = artifactFallbacks.flatMapTo(sortedSetOf()) { fallback ->
            fallback.paths
        },
        additionalComponentGavs = artifactFallbacks.mapNotNullTo(sortedSetOf()) { fallback ->
            fallback.gav
        }
    )
}

private data class LockfileArtifactFallback(
    val paths: Set<String>,
    val gav: String?,
)

private fun fallbackForLockfileArtifact(
    artifactKey: String,
    artifactInfo: JsonObject,
): LockfileArtifactFallback {
    val key = MavenInstallLockfileArtifactKey.parse(artifactKey)
    val version = artifactInfo.getValue("version").jsonPrimitive.contentOrNull
        ?: return LockfileArtifactFallback(paths = emptySet(), gav = null)
    val shasums = artifactInfo.getValue("shasums").jsonObject
    val coordinates = MavenCoordinates(
        group = key.group,
        module = key.artifact,
        version = version
    )
    val paths = shasums
        .asSequence()
        .filter { (_, checksum) ->
            key.extension == "pom" ||
                checksum.jsonPrimitive.contentOrNull != null
        }
        .mapTo(sortedSetOf()) { (classifierKey, _) ->
            mavenPathForLockfileArtifact(
                key = key,
                version = version,
                classifierKey = classifierKey
            )
        }
    return LockfileArtifactFallback(
        paths = paths,
        gav = coordinates.gav
    )
}

private fun mavenPathForLockfileArtifact(
    key: MavenInstallLockfileArtifactKey,
    version: String,
    classifierKey: String,
): String {
    val classifier = classifierKey
        .takeUnless { value -> value == "jar" }
        .orEmpty()
    val fileName = listOfNotNull(
        "${key.artifact}-$version",
        classifier.takeIf(String::isNotBlank)
    ).joinToString(separator = "-") + ".${key.extension}"
    return MavenCoordinates(
        group = key.group,
        module = key.artifact,
        version = version
    ).canonicalMavenRelativePath(fileName)
}
