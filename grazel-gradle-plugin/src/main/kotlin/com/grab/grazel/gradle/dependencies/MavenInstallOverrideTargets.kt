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

import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency

/**
 * Merges two sources of `maven_install` override targets: extension-configured overrides (only kept
 * if [artifacts] actually contains that short ID — an override for an artifact not in this repo
 * would be meaningless) and overrides already attached to artifacts by
 * [MavenInstallRootArtifacts]'s owner-promotion logic ([ResolvedDependency.overrideTarget]). A
 * result is dropped when its label is an exact self-reference back to [owningMavenRepoName]
 * ([isExactSelfOverride]) — otherwise a promoted artifact could end up pointing a maven_install repo
 * at itself, which `rules_jvm_external` cannot resolve.
 */
internal fun calculateMavenInstallOverrideTargets(
    artifacts: List<ResolvedDependency>,
    owningMavenRepoName: String,
    configuredOverrideTargets: Map<String, String> = emptyMap()
): Map<String, String> {
    val artifactsShortIdMap = artifacts.groupBy(ResolvedDependency::shortId)
    val overridesFromExtension = configuredOverrideTargets
        .toList()
        .filter { (shortId, _) -> shortId in artifactsShortIdMap }
    val overridesFromArtifacts = artifacts
        .asSequence()
        .mapNotNull(ResolvedDependency::overrideTarget)
        .map { overrideTarget -> overrideTarget.artifactShortId to overrideTarget.label.toString() }
    return (overridesFromArtifacts + overridesFromExtension)
        .filterNot { (shortId, label) -> label.isExactSelfOverride(shortId, owningMavenRepoName) }
        .sortedBy { it.toString() }
        .toMap()
}

private fun String.isExactSelfOverride(shortId: String, owningMavenRepoName: String): Boolean {
    val ownLabel = MavenDependency.fromShortId(shortId, owningMavenRepoName).toString()
    return this == ownLabel
}
