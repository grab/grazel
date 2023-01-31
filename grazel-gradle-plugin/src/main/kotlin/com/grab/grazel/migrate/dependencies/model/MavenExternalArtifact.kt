/*
 * Copyright 2022 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.migrate.dependencies.model

import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.Versioned
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.Version
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser
import org.gradle.api.internal.artifacts.result.DefaultResolvedComponentResult

internal data class MavenExternalArtifact(
    val group: String,
    val name: String,
    val version: String,
    val repository: Repository,
    val excludeRules: List<ExcludeRule>,
    val id: String = "$group:$name:$version",
    val overrideTarget: OverrideTarget? = null
) : Versioned, Comparable<MavenExternalArtifact> {
    // Declare as property to not pollute generated hashcode() equals() by data class
    lateinit var componentResult: DefaultResolvedComponentResult

    private val parsedVersion = VersionParser().transform(version)
    override fun getVersion(): Version = parsedVersion

    val shortId = "$group:$name"

    private val comparator = DefaultVersionComparator()
    override fun compareTo(other: MavenExternalArtifact) = comparator.compare(this, other)

    override fun toString() = id
}

internal data class OverrideTarget(
    val artifactShortId: String,
    val label: MavenDependency,
)

internal fun MavenExternalArtifact.mergeWith(others: List<MavenExternalArtifact>): MavenExternalArtifact {
    val current = this
    val excludeRules = (others.flatMap { it.excludeRules } + current.excludeRules).distinct()
    val overrideTarget = current.overrideTarget ?: others.map { it.overrideTarget }
        .firstOrNull { it != null }
    return current.copy(
        overrideTarget = overrideTarget,
        excludeRules = excludeRules
    ).apply {
        componentResult = current.componentResult
    }
}