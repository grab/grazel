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

/**
 * Reconciles a freshly-reconstructed lockfile (rewritten from proxy URLs back to canonical, see
 * [MavenLockfileRepositoryUrlRewriter]) against the baseline lockfile that shipped before local
 * Maven resolution ran. The goal is byte-identical RJE output: reconstruction is a best-effort
 * re-derivation, so wherever an artifact's facts already match the baseline in every field except
 * `shasums`, we assert ([requireSameShasums]) that reconstruction also produced the *same* shasum
 * and otherwise keep the current entry unchanged - there is no substitution of baseline values.
 * A mismatch there means reconstruction silently resolved a different artifact and must fail
 * loudly rather than emit a lockfile that diffs from what rules_jvm_external itself would
 * produce. (The skipped-array merge in [mergedSkippedArtifacts] is what actually reconciles
 * differing output between current and baseline.)
 */
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

    /**
     * If reconstruction "skipped" (failed to resolve) an artifact that the baseline lockfile
     * actually resolved, that is a regression, not a legitimate skip - so it fails hard via [check]
     * rather than silently carrying the skip forward. Conversely, artifacts the baseline already
     * skipped are always preserved. The invariant-violation set is names from
     * [currentSkippedArtifacts] that are in "baseline artifacts" (i.e. baseline actually resolved
     * them) and not in "current artifacts" (i.e. current didn't resolve them either, so the skip
     * wasn't superseded by a real resolution) - the `filter`/`filterNot` pair are independent
     * predicates over disjoint sets, so their order doesn't matter. Results are deduplicated via
     * [toSortedSet] because the two skip lists may overlap and RJE lockfiles expect a stable,
     * sorted skipped array.
     */
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
