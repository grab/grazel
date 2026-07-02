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

package com.grab.grazel.migrate.dependencies

import com.grab.grazel.util.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private sealed interface StarlarkValue {
    object None : StarlarkValue
    data class Bool(val value: Boolean) : StarlarkValue
    data class IntValue(val value: Int) : StarlarkValue
    data class StringValue(val value: String) : StarlarkValue
    data class ListValue(val values: List<StarlarkValue>) : StarlarkValue
    data class DictValue(val entries: Map<String, StarlarkValue>) : StarlarkValue
}

private data class ResolvedArtifactHashInfo(
    val standard: Map<String, StarlarkValue>,
    val sha: StarlarkValue,
    var repository: String? = null,
    var dependencies: List<String>? = null,
    var dependencyHashes: Map<String, Int>? = null,
) {
    fun toStarlarkFields(): StarlarkValue.DictValue {
        val fields = linkedMapOf<String, StarlarkValue>(
            "standard" to StarlarkValue.DictValue(standard),
            "sha" to sha
        )
        repository?.let { value -> fields["repository"] = StarlarkValue.StringValue(value) }
        dependencies?.let { value ->
            fields["dependencies"] = StarlarkValue.ListValue(
                value.map(StarlarkValue::StringValue)
            )
        }
        dependencyHashes?.let { value ->
            fields["dependency_hashes"] = StarlarkValue.DictValue(
                value.mapValuesTo(linkedMapOf()) { (_, hash) -> StarlarkValue.IntValue(hash) }
            )
        }
        return StarlarkValue.DictValue(fields)
    }
}

internal class MavenInstallLockfileReconstructor(
    private val repositoryRewrite: MavenInstallRepositoryRewrite,
) {
    private val proxyPrefixesByDescendingLength = repositoryRewrite
        .proxyToCanonicalUrl
        .keys
        .sortedByDescending(String::length)

    fun reconstruct(
        lockfileContents: String,
        canonicalRepositoryInputs: List<String>,
        baselineLockfileContents: String? = null,
    ): String {
        val rewrittenLockfile = rewriteUrls(Json.parseToJsonElement(lockfileContents).jsonObject)
        val baselineLockfile = baselineLockfileContents
            ?.let(Json::parseToJsonElement)
            ?.jsonObject
        val baselineArtifactNames = baselineLockfile
            ?.getValue("artifacts")
            ?.jsonObject
            ?.keys
            .orEmpty()
        val lockfile = baselineLockfile
            ?.let { baseline ->
                lockfileWithBaselineFacts(
                    currentLockfile = rewrittenLockfile,
                    baselineLockfile = baseline
                )
            }
            ?: rewrittenLockfile
        val normalizedLockfile = lockfileWithPomPackagingArtifactsSkipped(
            lockfile = lockfile,
            baselineArtifactNames = baselineArtifactNames
        )
        val inputHash = normalizedLockfile
            .getValue("__INPUT_ARTIFACTS_HASH")
            .jsonObject
            .toMutableMap()
            .apply {
                this["repositories"] = JsonPrimitive(
                    repositoryInputsHash(canonicalRepositoryInputs)
                )
            }
        val resolvedHash = resolvedArtifactsHash(normalizedLockfile)
        return renderLockfile(
            lockfile = normalizedLockfile,
            inputHash = JsonObject(inputHash),
            resolvedHash = JsonObject(
                resolvedHash.mapValuesTo(linkedMapOf()) { (_, hash) -> JsonPrimitive(hash) }
            )
        )
    }

    private fun lockfileWithBaselineFacts(
        currentLockfile: JsonObject,
        baselineLockfile: JsonObject,
    ): JsonObject {
        val baselineArtifacts = baselineLockfile
            .getValue("artifacts")
            .jsonObject
        val currentArtifacts = currentLockfile.getValue("artifacts").jsonObject
        val artifacts = currentArtifacts.entries.associateTo(linkedMapOf()) { (artifact, artifactInfoElement) ->
            val artifactInfo = artifactInfoElement.jsonObject
            val baselineArtifactInfo = baselineArtifacts[artifact]?.jsonObject
            artifact to when {
                baselineArtifactInfo != null &&
                    standardArtifactInfo(artifactInfo) == standardArtifactInfo(baselineArtifactInfo) -> {
                    requireSameShasums(
                        artifactInfo = artifactInfo,
                        artifact = artifact,
                        baselineArtifactInfo = baselineArtifactInfo
                    )
                    artifactInfoElement
                }

                else -> artifactInfoElement
            }
        }

        val baselineSkipped = baselineLockfile["skipped"]
            ?.jsonArray
            ?.map { skipped -> skipped.jsonPrimitive.content }
            .orEmpty()
        val baselineArtifactNames = baselineArtifacts.keys
        val currentSkipped = currentLockfile["skipped"]
            ?.jsonArray
            ?.map { skipped -> skipped.jsonPrimitive.content }
            .orEmpty()
        val skippedBaselineArtifacts = currentSkipped
            .filter { skipped -> skipped in baselineArtifactNames }
            .filterNot { skipped -> skipped in currentArtifacts }
            .toSortedSet()
        check(skippedBaselineArtifacts.isEmpty()) {
            "Local Maven reconstruction skipped artifacts that existed in the baseline: " +
                skippedBaselineArtifacts.joinToString()
        }

        val skipped = (baselineSkipped + currentSkipped)
            .toSortedSet()
            .map(::JsonPrimitive)

        return JsonObject(
            currentLockfile.toMutableMap().apply {
                this["artifacts"] = JsonObject(artifacts)
                if (skipped.isEmpty()) {
                    remove("skipped")
                } else {
                    this["skipped"] = JsonArray(skipped)
                }
            }
        )
    }

    private fun standardArtifactInfo(artifactInfo: JsonObject): JsonObject =
        JsonObject(artifactInfo.filterKeys { key -> key != "shasums" })

    private fun requireSameShasums(
        artifactInfo: JsonObject,
        artifact: String,
        baselineArtifactInfo: JsonObject,
    ) {
        val currentShasums = artifactInfo.getValue("shasums")
        val baselineShasums = baselineArtifactInfo.getValue("shasums")
        check(currentShasums == baselineShasums) {
            "Local Maven reconstruction changed shasums for $artifact"
        }
    }

    private fun rewriteUrls(lockfile: JsonObject): JsonObject =
        JsonObject(lockfile.mapValuesTo(linkedMapOf()) { (_, value) -> rewriteUrls(value) })

    private fun rewriteUrls(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> JsonObject(
                element.entries.associateTo(linkedMapOf()) { (key, value) ->
                    rewriteUrl(key) to rewriteUrls(value)
                }
            )

            is JsonArray -> JsonArray(element.map(::rewriteUrls))
            is JsonPrimitive -> if (element.isString) {
                JsonPrimitive(rewriteUrl(element.content))
            } else {
                element
            }

            JsonNull -> JsonNull
        }
    }

    private fun rewriteUrl(value: String): String {
        val proxyPrefix = proxyPrefixesByDescendingLength
            .firstOrNull { proxyUrl -> value.startsWith(proxyUrl) }
            ?: return value
        return repositoryRewrite
            .proxyToCanonicalUrl
            .getValue(proxyPrefix)
            .let(::lockfileRepositoryPrefix) + value.removePrefix(proxyPrefix)
    }

    private fun lockfileRepositoryPrefix(repositoryUrl: String): String =
        repositoryUrl.trimEnd('/') + "/"

    private fun lockfileWithPomPackagingArtifactsSkipped(
        lockfile: JsonObject,
        baselineArtifactNames: Set<String>,
    ): JsonObject {
        val pomPackagingArtifacts = lockfile.getValue("artifacts")
            .jsonObject
            .keys
            .filter(::isPomPackagingRoot)
            .filterNot { artifact -> artifact in baselineArtifactNames }
        if (pomPackagingArtifacts.isEmpty()) return lockfile

        val existingSkipped = lockfile["skipped"]
            ?.jsonArray
            ?.map { skipped -> skipped.jsonPrimitive.content }
            .orEmpty()
        val skipped = (existingSkipped + pomPackagingArtifacts)
            .toSortedSet()
            .map(::JsonPrimitive)
        return JsonObject(
            lockfile.toMutableMap().apply {
                this["skipped"] = JsonArray(skipped)
            }
        )
    }

    private fun repositoryInputsHash(canonicalRepositoryInputs: List<String>): Int =
        starlarkHash(
            starlarkRepr(
                StarlarkValue.ListValue(
                    canonicalRepositoryInputs.sorted().map(StarlarkValue::StringValue)
                )
            )
        )

    private fun resolvedArtifactsHash(lockfile: JsonObject): Map<String, Int> {
        val allInfos = linkedMapOf<String, ResolvedArtifactHashInfo>()
        lockfile.getValue("artifacts").jsonObject.entries.sortedBy { (dependency, _) -> dependency }
            .forEach { (dependency, dependencyInfoElement) ->
                val artifactKey = MavenInstallLockfileArtifactKey.parse(dependency)
                val dependencyInfo = dependencyInfoElement.jsonObject
                val commonInfo = dependencyInfo
                    .entries
                    .asSequence()
                    .filter { (key, _) -> key != "shasums" }
                    .sortedBy { (key, _) -> key }
                    .associateTo(linkedMapOf()) { (key, value) -> key to starlarkValue(value) }
                dependencyInfo.getValue("shasums").jsonObject.entries.sortedBy { (type, _) -> type }
                    .forEach { (type, shaElement) ->
                        val suffix = artifactKey.resolvedArtifactHashSuffix(type)
                        allInfos[dependency + suffix] = ResolvedArtifactHashInfo(
                            standard = commonInfo,
                            sha = starlarkValue(shaElement)
                        )
                    }
            }

        lockfile.getValue("repositories").jsonObject.forEach { (repository, artifactsElement) ->
            artifactsElement.jsonArray.forEach { artifactElement ->
                val artifact = artifactElement.jsonPrimitive.content
                allInfos.getValue(artifact).repository = repository
            }
        }

        lockfile.getValue("dependencies").jsonObject.entries.sortedBy { (dependency, _) -> dependency }
            .forEach { (dependency, dependenciesElement) ->
                allInfos.getValue(dependency).dependencies = dependenciesElement
                    .jsonArray
                    .map { dependencyElement -> dependencyElement.jsonPrimitive.content }
                    .sorted()
            }

        return computeFinalHash(allInfos)
    }

    private fun computeFinalHash(allInfos: LinkedHashMap<String, ResolvedArtifactHashInfo>): Map<String, Int> {
        val finalHashes = linkedMapOf<String, Int>()
        val backupHashes = allInfos.mapValuesTo(linkedMapOf()) { (_, value) ->
            starlarkHash(starlarkRepr(value.toStarlarkFields()))
        }
        val remaining = allInfos.keys.associateWithTo(linkedMapOf()) { 0 }
        val stack = mutableListOf<String>()
        while (remaining.isNotEmpty() || stack.isNotEmpty()) {
            val current = if (stack.isEmpty()) {
                remaining.keys.first().also { key -> remaining.remove(key) }
            } else {
                stack.removeAt(stack.lastIndex)
            }
            if (current in finalHashes) continue
            val currentInfo = allInfos.getValue(current)
            val dependencies = currentInfo.dependencies.orEmpty()
            val unprocessed = dependencies.firstOrNull { dependency -> dependency in remaining }
            if (unprocessed != null) {
                stack += current
                stack += unprocessed
                remaining.remove(unprocessed)
                continue
            }
            currentInfo.dependencyHashes = dependencies
                .associateWithTo(linkedMapOf()) { dependency ->
                    finalHashes[dependency] ?: backupHashes[dependency] ?: 0
                }
            finalHashes[current] = starlarkHash(starlarkRepr(currentInfo.toStarlarkFields()))
        }
        return finalHashes.toSortedMap()
    }

    private fun renderLockfile(
        lockfile: JsonObject,
        inputHash: JsonObject,
        resolvedHash: JsonObject,
    ): String {
        val lines = mutableListOf(
            "{",
            """  "__AUTOGENERATED_FILE_DO_NOT_MODIFY_THIS_FILE_MANUALLY": "THERE_IS_NO_DATA_ONLY_ZUUL",""",
            """  "__INPUT_ARTIFACTS_HASH": ${jsonEncodeIndent(inputHash, prefix = "  ")},""",
            """  "__RESOLVED_ARTIFACTS_HASH": ${jsonEncodeIndent(resolvedHash, prefix = "  ")},""",
        )
        lockfile["conflict_resolution"]?.takeIf(::isNonEmptyContainer)?.let { conflictResolution ->
            lines += """  "conflict_resolution": ${jsonEncodeIndent(conflictResolution, prefix = "  ")},"""
        }
        lines += """  "artifacts": ${jsonEncodeIndent(lockfile.getValue("artifacts"), prefix = "  ")},"""
        lines += """  "dependencies": ${jsonEncodeIndent(lockfile.getValue("dependencies"), prefix = "  ")},"""
        if (lockfile["m2local"]?.jsonPrimitive?.booleanOrNull == true) {
            lines += """  "m2local": true,"""
        }
        lines += """  "packages": ${jsonEncodeIndent(lockfile.getValue("packages"), prefix = "  ")},"""
        lines += """  "repositories": {"""
        val repositories = lockfile.getValue("repositories").jsonObject.entries.toList()
        repositories.forEachIndexed { index, (repository, artifacts) ->
            val suffix = if (index == repositories.lastIndex) "" else ","
            lines += """    ${jsonString(repository)}: ${jsonEncodeIndent(artifacts, prefix = "    ")}$suffix"""
        }
        lines += "  },"
        lines += """  "services": ${jsonEncodeIndent(lockfile.getValue("services"), prefix = "  ")},"""
        lockfile["skipped"]?.takeIf(::isNonEmptyContainer)?.let { skipped ->
            lines += """  "skipped": ${jsonEncodeIndent(skipped, prefix = "  ")},"""
        }
        lines += """  "version": "3""""
        lines += "}"
        lines += ""
        return lines.joinToString("\n")
    }
}

private fun isNonEmptyContainer(element: JsonElement): Boolean =
    when (element) {
        is JsonArray -> element.isNotEmpty()
        is JsonObject -> element.isNotEmpty()
        else -> true
    }

private fun isPomPackagingRoot(artifactKey: String): Boolean {
    return MavenInstallLockfileArtifactKey.parse(artifactKey).isPomPackagingRoot
}

private fun starlarkValue(element: JsonElement): StarlarkValue {
    return when (element) {
        is JsonObject -> StarlarkValue.DictValue(
            element.entries.associateTo(linkedMapOf()) { (key, value) -> key to starlarkValue(value) }
        )

        is JsonArray -> StarlarkValue.ListValue(element.map { value -> starlarkValue(value) })
        is JsonPrimitive -> when {
            element is JsonNull -> StarlarkValue.None
            element.isString -> StarlarkValue.StringValue(element.content)
            element.booleanOrNull != null -> StarlarkValue.Bool(element.booleanOrNull!!)
            element.intOrNull != null -> StarlarkValue.IntValue(element.intOrNull!!)
            else -> StarlarkValue.StringValue(element.content)
        }

        JsonNull -> StarlarkValue.None
    }
}

private fun jsonEncodeIndent(element: JsonElement, prefix: String): String {
    return when (element) {
        is JsonObject -> {
            if (element.isEmpty()) return "{}"
            val childPrefix = "$prefix  "
            element.entries
                .sortedBy { (key, _) -> key }
                .joinToString(
                    separator = ",\n",
                    prefix = "{\n",
                    postfix = "\n$prefix}"
                ) { (key, value) ->
                    "$childPrefix${jsonString(key)}: ${jsonEncodeIndent(value, childPrefix)}"
                }
        }

        is JsonArray -> {
            if (element.isEmpty()) return "[]"
            val childPrefix = "$prefix  "
            element.joinToString(
                separator = ",\n",
                prefix = "[\n",
                postfix = "\n$prefix]"
            ) { value -> "$childPrefix${jsonEncodeIndent(value, childPrefix)}" }
        }

        is JsonPrimitive -> when {
            element is JsonNull -> "null"
            element.isString -> jsonString(element.content)
            else -> element.content
        }

        JsonNull -> "null"
    }
}

private fun starlarkRepr(value: StarlarkValue): String {
    return when (value) {
        StarlarkValue.None -> "None"
        is StarlarkValue.Bool -> if (value.value) "True" else "False"
        is StarlarkValue.IntValue -> value.value.toString()
        is StarlarkValue.StringValue -> jsonString(value.value)
        is StarlarkValue.ListValue -> value.values.joinToString(
            separator = ", ",
            prefix = "[",
            postfix = "]",
            transform = ::starlarkRepr
        )

        is StarlarkValue.DictValue -> value.entries.entries.joinToString(
            separator = ", ",
            prefix = "{",
            postfix = "}"
        ) { (key, item) ->
            "${jsonString(key)}: ${starlarkRepr(item)}"
        }
    }
}

private fun starlarkHash(value: String): Int = value.hashCode()

private fun jsonString(value: String): String {
    return buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\r' -> append("\\r")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                else -> {
                    if (char.code in 0x00..0x1F) {
                        append("\\x")
                        append(char.code.toString(16).padStart(2, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
        append('"')
    }
}
