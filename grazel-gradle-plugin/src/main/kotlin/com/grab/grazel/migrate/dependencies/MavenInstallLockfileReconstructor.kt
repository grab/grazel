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

internal data class MavenInstallRepositoryRewrite(
    val proxyToCanonicalUrl: Map<String, String>,
)

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
    ): String {
        val lockfile = rewriteUrls(Json.parseToJsonElement(lockfileContents).jsonObject)
            .withPomPackagingArtifactsSkipped()
        val inputHash = lockfile
            .getValue("__INPUT_ARTIFACTS_HASH")
            .jsonObject
            .toMutableMap()
            .apply {
                this["repositories"] = JsonPrimitive(
                    repositoryInputsHash(canonicalRepositoryInputs)
                )
            }
        val resolvedHash = resolvedArtifactsHash(lockfile)
        return renderLockfile(
            lockfile = lockfile,
            inputHash = JsonObject(inputHash),
            resolvedHash = JsonObject(
                resolvedHash.mapValuesTo(linkedMapOf()) { (_, hash) -> JsonPrimitive(hash) }
            )
        )
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
        return repositoryRewrite.proxyToCanonicalUrl.getValue(proxyPrefix) + value.removePrefix(proxyPrefix)
    }

    private fun JsonObject.withPomPackagingArtifactsSkipped(): JsonObject {
        val pomPackagingArtifacts = getValue("artifacts")
            .jsonObject
            .keys
            .filter(String::isPomPackagingRoot)
        if (pomPackagingArtifacts.isEmpty()) return this

        val existingSkipped = this["skipped"]
            ?.jsonArray
            ?.map { skipped -> skipped.jsonPrimitive.content }
            .orEmpty()
        val skipped = (existingSkipped + pomPackagingArtifacts)
            .toSortedSet()
            .map(::JsonPrimitive)
        return JsonObject(
            toMutableMap().apply {
                this["skipped"] = JsonArray(skipped)
            }
        )
    }

    private fun repositoryInputsHash(canonicalRepositoryInputs: List<String>): Int =
        starlarkHash(starlarkRepr(canonicalRepositoryInputs.sorted()))

    private fun resolvedArtifactsHash(lockfile: JsonObject): Map<String, Int> {
        val allInfos = linkedMapOf<String, MutableMap<String, Any?>>()
        lockfile.getValue("artifacts").jsonObject.entries.sortedBy { (dependency, _) -> dependency }
            .forEach { (dependency, dependencyInfoElement) ->
                val dependencyInfo = dependencyInfoElement.jsonObject
                val commonInfo = dependencyInfo
                    .entries
                    .asSequence()
                    .filter { (key, _) -> key != "shasums" }
                    .sortedBy { (key, _) -> key }
                    .associateTo(linkedMapOf()) { (key, value) -> key to value.toStarlarkValue() }
                val isJarType = dependency.count { char -> char == ':' } == 1
                dependencyInfo.getValue("shasums").jsonObject.entries.sortedBy { (type, _) -> type }
                    .forEach { (type, shaElement) ->
                        val jarSuffix = if (isJarType) ":jar" else ""
                        val suffix = if (type == "jar") "" else "$jarSuffix:$type"
                        val typeInfo = linkedMapOf<String, Any?>()
                        typeInfo["standard"] = commonInfo
                        typeInfo["sha"] = shaElement.toStarlarkValue()
                        allInfos[dependency + suffix] = typeInfo
                    }
            }

        lockfile.getValue("repositories").jsonObject.forEach { (repository, artifactsElement) ->
            artifactsElement.jsonArray.forEach { artifactElement ->
                val artifact = artifactElement.jsonPrimitive.content
                allInfos.getValue(artifact)["repository"] = repository
            }
        }

        lockfile.getValue("dependencies").jsonObject.entries.sortedBy { (dependency, _) -> dependency }
            .forEach { (dependency, dependenciesElement) ->
                allInfos.getValue(dependency)["dependencies"] = dependenciesElement
                    .jsonArray
                    .map { dependencyElement -> dependencyElement.jsonPrimitive.content }
                    .sorted()
            }

        return computeFinalHash(allInfos)
    }

    private fun computeFinalHash(allInfos: LinkedHashMap<String, MutableMap<String, Any?>>): Map<String, Int> {
        val finalHashes = linkedMapOf<String, Int>()
        val backupHashes = allInfos.mapValuesTo(linkedMapOf()) { (_, value) ->
            starlarkHash(starlarkRepr(value))
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
            val dependencies = allInfos
                .getValue(current)["dependencies"]
                .let { value -> value as? List<*> }
                .orEmpty()
                .filterIsInstance<String>()
            val unprocessed = dependencies.firstOrNull { dependency -> dependency in remaining }
            if (unprocessed != null) {
                stack += current
                stack += unprocessed
                remaining.remove(unprocessed)
                continue
            }
            allInfos.getValue(current)["dependency_hashes"] = dependencies
                .associateWithTo(linkedMapOf()) { dependency ->
                    finalHashes[dependency] ?: backupHashes[dependency] ?: 0
                }
            finalHashes[current] = starlarkHash(starlarkRepr(allInfos.getValue(current)))
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
        lockfile["conflict_resolution"]?.takeIf(JsonElement::isNonEmptyContainer)?.let { conflictResolution ->
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
        lockfile["skipped"]?.takeIf(JsonElement::isNonEmptyContainer)?.let { skipped ->
            lines += """  "skipped": ${jsonEncodeIndent(skipped, prefix = "  ")},"""
        }
        lines += """  "version": "3""""
        lines += "}"
        lines += ""
        return lines.joinToString("\n")
    }
}

private fun JsonElement.isNonEmptyContainer(): Boolean =
    when (this) {
        is JsonArray -> isNotEmpty()
        is JsonObject -> isNotEmpty()
        else -> true
    }

private fun String.isPomPackagingRoot(): Boolean {
    val parts = split(":")
    return parts.size == 3 && parts[2] == "pom"
}

private fun JsonElement.toStarlarkValue(): Any? {
    return when (this) {
        is JsonObject -> entries.associateTo(linkedMapOf()) { (key, value) -> key to value.toStarlarkValue() }
        is JsonArray -> map { value -> value.toStarlarkValue() }
        is JsonPrimitive -> when {
            this is JsonNull -> null
            isString -> content
            booleanOrNull != null -> booleanOrNull
            intOrNull != null -> intOrNull
            else -> content
        }

        JsonNull -> null
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

private fun starlarkRepr(value: Any?): String {
    return when (value) {
        null -> "None"
        is Boolean -> if (value) "True" else "False"
        is Int -> value.toString()
        is String -> jsonString(value)
        is List<*> -> value.joinToString(separator = ", ", prefix = "[", postfix = "]", transform = ::starlarkRepr)
        is Map<*, *> -> value.entries.joinToString(separator = ", ", prefix = "{", postfix = "}") { (key, item) ->
            "${starlarkRepr(key)}: ${starlarkRepr(item)}"
        }

        else -> value.toString()
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
