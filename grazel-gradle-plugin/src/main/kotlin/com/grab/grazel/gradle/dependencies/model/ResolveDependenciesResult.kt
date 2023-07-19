/*
 * Copyright 2023 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.gradle.dependencies.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.gradle.api.file.RegularFile
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.Versioned
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.Version
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser
import java.io.File

@Serializable
data class ResolveDependenciesResult(
    val variantName: String,
    val dependencies: Map<String, Set<ResolvedDependency>> = HashMap()
) {
    companion object {
        fun fromJson(json: RegularFile) = fromJson(json.asFile)
        fun fromJson(json: File) = json
            .inputStream()
            .buffered().use { stream -> Json.decodeFromStream<ResolveDependenciesResult>(stream) }
    }
}

@Serializable
data class ResolvedDependency(
    val id: String,
    val version: String,
    val shortId: String,
    val direct: Boolean,
    val dependencies: Set<String>,
    val excludeRules: Set<ExcludeRule>,
    val repository: String
) : Comparable<ResolvedDependency> {
    override fun compareTo(other: ResolvedDependency) = id.compareTo(other.id)

    companion object {
        fun from(dependencyNotation: String): ResolvedDependency {
            val (group, name, version, repository) = dependencyNotation.split(":")
            val shortId = "$group:$name"
            return ResolvedDependency(
                id = "$group:$name:$version",
                version = version,
                shortId = shortId,
                direct = false,
                dependencies = emptySet(),
                excludeRules = emptySet(),
                repository = repository
            )
        }
    }
}

/**
 * Unwrap [ResolvedDependency] such that it contains all its dependencies in the form of
 * [ResolvedDependency]
 */
val ResolvedDependency.allDependencies: Set<ResolvedDependency>
    get() = buildSet {
        add(this@allDependencies.copy(dependencies = emptySet()))
        addAll(dependencies.map { dependency -> ResolvedDependency.from(dependency) })
    }


class VersionInfo(val version: String) : Versioned, Comparable<VersionInfo> {
    private val parsedVersion = VersionParser().transform(version)
    override fun getVersion(): Version = parsedVersion
    private val comparator = DefaultVersionComparator()
    override fun compareTo(other: VersionInfo) = comparator.compare(this, other)
}

val ResolvedDependency.versionInfo get() = VersionInfo(version = version)