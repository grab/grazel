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

internal data class MavenInstallLockfileArtifactKey(
    val group: String,
    val artifact: String,
    val extension: String,
) {
    val isPomPackagingRoot: Boolean
        get() = extension == "pom"

    /**
     * Reproduces rules_jvm_external's own key suffix convention for per-shasum-type hash entries in
     * [RulesJvmExternalLockfileHasher]. A `jar` shasum contributes no suffix (it's the artifact's
     * primary/default hash), while any other classifier (e.g. `sources`, `javadoc`) appends
     * `:jar:<type>` when the artifact's own extension is `jar`, or just `:<type>` otherwise. This
     * must byte-match RJE's internal key scheme exactly, since these suffixed keys become dependency
     * hash lookup keys that feed the final Starlark-hash computation.
     */
    fun resolvedArtifactHashSuffix(shasumType: String): String {
        if (shasumType == "jar") return ""
        val artifactTypePrefix = if (extension == "jar") ":jar" else ""
        return "$artifactTypePrefix:$shasumType"
    }

    companion object {
        fun parse(value: String): MavenInstallLockfileArtifactKey {
            val parts = value.split(":")
            require(parts.size in 2..3) { "Unexpected maven_install artifact key: $value" }
            return MavenInstallLockfileArtifactKey(
                group = parts[0],
                artifact = parts[1],
                extension = parts.getOrNull(2)?.takeIf(String::isNotBlank) ?: "jar"
            )
        }
    }
}
