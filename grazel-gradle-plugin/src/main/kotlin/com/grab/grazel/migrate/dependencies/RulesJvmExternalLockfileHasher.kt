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

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object RulesJvmExternalLockfileHasher {

    fun inputArtifactsHashWithRepositories(
        inputArtifactsHash: JsonObject,
        canonicalRepositoryInputs: List<String>,
    ): JsonObject {
        return JsonObject(
            inputArtifactsHash.toMutableMap().apply {
                this["repositories"] = JsonPrimitive(repositoryInputsHash(canonicalRepositoryInputs))
            }
        )
    }

    fun resolvedArtifactsHash(lockfile: RulesJvmExternalLockfile): JsonObject {
        return JsonObject(
            resolvedArtifactHashes(lockfile).mapValuesTo(linkedMapOf()) { (_, hash) -> JsonPrimitive(hash) }
        )
    }

    private fun repositoryInputsHash(canonicalRepositoryInputs: List<String>): Int =
        starlarkHash(
            StarlarkValue.ListValue(
                canonicalRepositoryInputs.sorted().map(StarlarkValue::StringValue)
            )
        )

    private fun resolvedArtifactHashes(lockfile: RulesJvmExternalLockfile): Map<String, Int> {
        val allInfos = linkedMapOf<String, ResolvedArtifactHashInfo>()
        lockfile.artifacts.entries.sortedBy { (dependency, _) -> dependency }
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

        lockfile.repositories.forEach { (repository, artifactsElement) ->
            artifactsElement.jsonArray.forEach { artifactElement ->
                val artifact = artifactElement.jsonPrimitive.content
                allInfos.getValue(artifact).repository = repository
            }
        }

        lockfile.dependencies.entries.sortedBy { (dependency, _) -> dependency }
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
            starlarkHash(value.toStarlarkFields())
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
            finalHashes[current] = starlarkHash(currentInfo.toStarlarkFields())
        }
        return finalHashes.toSortedMap()
    }

    private fun starlarkHash(value: StarlarkValue): Int =
        StarlarkRepr.hash(StarlarkRepr.render(value))

    private fun starlarkValue(element: JsonElement): StarlarkValue {
        return when (element) {
            is JsonObject -> StarlarkValue.DictValue(
                element.entries.associateTo(linkedMapOf()) { (key, value) -> key to starlarkValue(value) }
            )

            is JsonArray -> StarlarkValue.ListValue(
                element.map { value -> starlarkValue(value) }
            )

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
}

private data class ResolvedArtifactHashInfo(
    val standard: Map<String, StarlarkValue>,
    val sha: StarlarkValue,
    var repository: String? = null,
    var dependencies: List<String>? = null,
    var dependencyHashes: Map<String, Int>? = null,
) {
    fun toStarlarkFields(): StarlarkValue.DictValue {
        val fields = linkedMapOf(
            "standard" to StarlarkValue.DictValue(standard),
            "sha" to sha
        )
        repository?.let { value -> fields["repository"] = StarlarkValue.StringValue(value) }
        dependencies?.let { value ->
            fields["dependencies"] = StarlarkValue.ListValue(value.map(StarlarkValue::StringValue))
        }
        dependencyHashes?.let { value ->
            fields["dependency_hashes"] = StarlarkValue.DictValue(
                value.mapValuesTo(linkedMapOf()) { (_, hash) -> StarlarkValue.IntValue(hash) }
            )
        }
        return StarlarkValue.DictValue(fields)
    }
}
