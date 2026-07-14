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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal class MavenLockfileRepositoryUrlRewriter(
    repositoryRewrite: MavenInstallRepositoryRewrite,
) {
    private val proxyToCanonicalUrl = repositoryRewrite.proxyToCanonicalUrl
    private val proxyPrefixesByDescendingLength = proxyToCanonicalUrl.keys.sortedByDescending(String::length)

    /**
     * Walks every section of the lockfile that can contain a repository URL, including object keys
     * (the `repositories` map is keyed by repository URL, and `services`/`packages` maps may be too)
     * - not just string values - since [rewriteJson] rewrites both. Each field is listed explicitly
     * rather than reflectively because [RulesJvmExternalLockfile] fields that aren't touched here
     * would silently retain stale proxy URLs in the emitted maven_install.json; any new field added
     * to the lockfile model that can carry a repository URL must be added to this list too.
     */
    fun rewrite(lockfile: RulesJvmExternalLockfile): RulesJvmExternalLockfile {
        return lockfile.copy(
            inputArtifactsHash = rewriteJson(lockfile.inputArtifactsHash).jsonObject,
            resolvedArtifactsHash = rewriteJson(lockfile.resolvedArtifactsHash).jsonObject,
            conflictResolution = lockfile.conflictResolution?.let(::rewriteJson),
            artifacts = rewriteJson(lockfile.artifacts).jsonObject,
            dependencies = rewriteJson(lockfile.dependencies).jsonObject,
            packages = rewriteJson(lockfile.packages).jsonObject,
            repositories = rewriteJson(lockfile.repositories).jsonObject,
            services = rewriteJson(lockfile.services).jsonObject,
            skipped = lockfile.skipped?.let { skipped -> rewriteJson(skipped).jsonArray },
        )
    }

    private fun rewriteJson(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> JsonObject(
                element.entries.associateTo(linkedMapOf()) { (key, value) ->
                    rewriteUrl(key) to rewriteJson(value)
                }
            )

            is JsonArray -> JsonArray(element.map(::rewriteJson))
            is JsonPrimitive -> if (element.isString) {
                JsonPrimitive(rewriteUrl(element.content))
            } else {
                element
            }

            JsonNull -> JsonNull
        }
    }

    /**
     * Proxy prefixes are checked longest-first ([proxyPrefixesByDescendingLength]) so that when one
     * proxy URL is a prefix of another (e.g. a repo-group proxy nested under a broader mirror
     * proxy), the more specific match wins - checking shortest-first would let a broader prefix
     * mask a more specific proxy mapping and rewrite to the wrong canonical repository. Trailing
     * slashes are normalized in [lockfileRepositoryPrefix] because rules_jvm_external's own
     * lockfiles are sensitive to exact URL formatting; leaving a canonical URL without a trailing
     * slash while the original proxy one had it (or vice versa) would produce a lockfile RJE
     * considers changed even though the effective repository is the same.
     */
    private fun rewriteUrl(value: String): String {
        val proxyPrefix = proxyPrefixesByDescendingLength
            .firstOrNull { proxyUrl -> value.startsWith(proxyUrl) }
            ?: return value
        return proxyToCanonicalUrl
            .getValue(proxyPrefix)
            .let(::lockfileRepositoryPrefix) + value.removePrefix(proxyPrefix)
    }

    private fun lockfileRepositoryPrefix(repositoryUrl: String): String =
        repositoryUrl.trimEnd('/') + "/"
}
