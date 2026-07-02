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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

internal object PomPackagingSkipNormalizer {

    fun requireNoPomPackagingArtifactsWithoutBaseline(lockfile: RulesJvmExternalLockfile) {
        val pomPackagingArtifacts = lockfile.artifactNames.filter(::isPomPackagingRoot)
        check(pomPackagingArtifacts.isEmpty()) {
            "Local Maven reconstruction requires a baseline lockfile before it can safely " +
                "classify POM-packaging artifacts: ${pomPackagingArtifacts.joinToString()}"
        }
    }

    fun normalize(
        lockfile: RulesJvmExternalLockfile,
        baselineArtifactNames: Set<String>,
    ): RulesJvmExternalLockfile {
        val pomPackagingArtifacts = lockfile.artifactNames
            .filter(::isPomPackagingRoot)
            .filterNot { artifact -> artifact in baselineArtifactNames }
        if (pomPackagingArtifacts.isEmpty()) return lockfile

        val existingSkipped = lockfile.skipped
            ?.map { skipped -> skipped.jsonPrimitive.content }
            .orEmpty()
        val skipped = (existingSkipped + pomPackagingArtifacts)
            .toSortedSet()
            .map(::JsonPrimitive)
        return lockfile.copy(skipped = JsonArray(skipped))
    }

    private fun isPomPackagingRoot(artifactKey: String): Boolean {
        return MavenInstallLockfileArtifactKey.parse(artifactKey).isPomPackagingRoot
    }
}
