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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val INPUT_ARTIFACTS_HASH_KEY = "__INPUT_ARTIFACTS_HASH"
internal const val RESOLVED_ARTIFACTS_HASH_KEY = "__RESOLVED_ARTIFACTS_HASH"
private const val CONFLICT_RESOLUTION_KEY = "conflict_resolution"
private const val ARTIFACTS_KEY = "artifacts"
private const val DEPENDENCIES_KEY = "dependencies"
private const val M2LOCAL_KEY = "m2local"
private const val PACKAGES_KEY = "packages"
private const val REPOSITORIES_KEY = "repositories"
private const val SERVICES_KEY = "services"
private const val SKIPPED_KEY = "skipped"
private const val VERSION_KEY = "version"

internal data class RulesJvmExternalLockfile(
    val inputArtifactsHash: JsonObject,
    val resolvedArtifactsHash: JsonObject,
    val conflictResolution: JsonElement? = null,
    val artifacts: JsonObject,
    val dependencies: JsonObject,
    val m2local: Boolean = false,
    val packages: JsonObject,
    val repositories: JsonObject,
    val services: JsonObject,
    val skipped: JsonArray? = null,
    val version: String = "3"
) {
    val artifactNames: Set<String> get() = artifacts.keys
}

internal object RulesJvmExternalLockfileParser {
    fun parse(lockfileContents: String): RulesJvmExternalLockfile {
        return fromJsonObject(Json.parseToJsonElement(lockfileContents).jsonObject)
    }

    fun fromJsonObject(lockfile: JsonObject): RulesJvmExternalLockfile {
        return RulesJvmExternalLockfile(
            inputArtifactsHash = lockfile.getValue(INPUT_ARTIFACTS_HASH_KEY).jsonObject,
            resolvedArtifactsHash = lockfile.getValue(RESOLVED_ARTIFACTS_HASH_KEY).jsonObject,
            conflictResolution = lockfile[CONFLICT_RESOLUTION_KEY],
            artifacts = lockfile.getValue(ARTIFACTS_KEY).jsonObject,
            dependencies = lockfile.getValue(DEPENDENCIES_KEY).jsonObject,
            m2local = lockfile[M2LOCAL_KEY]?.jsonPrimitive?.booleanOrNull == true,
            packages = lockfile.getValue(PACKAGES_KEY).jsonObject,
            repositories = lockfile.getValue(REPOSITORIES_KEY).jsonObject,
            services = lockfile.getValue(SERVICES_KEY).jsonObject,
            skipped = lockfile[SKIPPED_KEY]?.jsonArray,
            version = lockfile[VERSION_KEY]?.jsonPrimitive?.content ?: "3"
        )
    }
}
