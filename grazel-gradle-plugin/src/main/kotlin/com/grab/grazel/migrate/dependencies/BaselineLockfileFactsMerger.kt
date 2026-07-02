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

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object BaselineLockfileFactsMerger {

    fun merge(
        currentLockfile: RulesJvmExternalLockfile,
        baselineLockfile: RulesJvmExternalLockfile,
    ): RulesJvmExternalLockfile {
        val artifacts = mergeArtifactFacts(
            currentArtifacts = currentLockfile.artifacts,
            baselineArtifacts = baselineLockfile.artifacts
        )
        val skipped = mergedSkippedArtifacts(
            currentSkipped = currentLockfile.skipped,
            currentArtifactNames = currentLockfile.artifactNames,
            baselineSkipped = baselineLockfile.skipped,
            baselineArtifactNames = baselineLockfile.artifactNames
        )
        return currentLockfile.copy(
            artifacts = JsonObject(artifacts),
            skipped = skipped
        )
    }

    private fun mergeArtifactFacts(
        currentArtifacts: JsonObject,
        baselineArtifacts: JsonObject,
    ): LinkedHashMap<String, JsonElement> {
        return currentArtifacts.entries.associateTo(linkedMapOf()) { (artifact, artifactInfoElement) ->
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
    }

    private fun mergedSkippedArtifacts(
        currentSkipped: JsonArray?,
        currentArtifactNames: Set<String>,
        baselineSkipped: JsonArray?,
        baselineArtifactNames: Set<String>,
    ): JsonArray? {
        val currentSkippedArtifacts = artifactNames(currentSkipped)
        val skippedBaselineArtifacts = currentSkippedArtifacts
            .filter { skipped -> skipped in baselineArtifactNames }
            .filterNot { skipped -> skipped in currentArtifactNames }
            .toSortedSet()
        check(skippedBaselineArtifacts.isEmpty()) {
            "Local Maven reconstruction skipped artifacts that existed in the baseline: " +
                skippedBaselineArtifacts.joinToString()
        }

        val currentSkippedForMerge = currentSkippedArtifacts
            .filterNot { skipped -> skipped in baselineArtifactNames }
        val skipped = (artifactNames(baselineSkipped) + currentSkippedForMerge)
            .toSortedSet()
            .map(::JsonPrimitive)
        return skipped.takeIf { artifacts -> artifacts.isNotEmpty() }?.let(::JsonArray)
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

    private fun artifactNames(skippedArtifacts: JsonArray?): List<String> =
        skippedArtifacts?.map { skipped -> skipped.jsonPrimitive.content }.orEmpty()
}
