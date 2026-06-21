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

package com.grab.grazel.gradle.dependencies

import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.migrate.dependencies.toMavenRepoName
import java.util.concurrent.ConcurrentHashMap

/** Data structure to hold information about generated maven repositories in `WORKSPACE` */
internal interface MavenInstallStore : AutoCloseable {
    /**
     * For a given variant hierarchy and `group` and `name`, the function will try to look for the
     * dependency in each of the variant hierarchy and return the first one found.
     *
     * For example, if `androidx.activity:activity` is given and it was categorized under
     * `@maven` repository then will return `@maven//:androidx_activity_activity` in form of
     * [MavenDependency]
     */
    operator fun get(
        variants: Set<String>,
        group: String,
        name: String,
        version: String? = null
    ): MavenDependency?

    operator fun set(variantRepoName: String, group: String, name: String)

    fun set(variantRepoName: String, group: String, name: String, target: MavenDependency?)

    fun set(
        variantRepoName: String,
        group: String,
        name: String,
        version: String?,
        target: MavenDependency?
    )
}

class DefaultMavenInstallStore : MavenInstallStore {

    private data class ArtifactKey(
        val variant: String,
        val group: String,
        val name: String,
        val version: String? = null,
    )

    private val cache = ConcurrentHashMap<ArtifactKey, MavenDependency>()

    override fun get(
        variants: Set<String>,
        group: String,
        name: String,
        version: String?
    ): MavenDependency? {
        fun get(variant: String, requestedVersion: String?): MavenDependency? =
            cache[ArtifactKey(variant, group, name, requestedVersion)]

        if (version != null) {
            variants.asSequence()
                .mapNotNull { variant -> get(variant, version) }
                .firstOrNull()
                ?.let { return it }

            get(DEFAULT_VARIANT, version)?.let { return it }
        }

        return variants.asSequence().mapNotNull { variant -> get(variant, null) }.firstOrNull()
            ?: get(DEFAULT_VARIANT, null)
    }

    override fun set(variantRepoName: String, group: String, name: String) {
        set(variantRepoName, group, name, target = null)
    }

    override fun set(
        variantRepoName: String,
        group: String,
        name: String,
        target: MavenDependency?
    ) {
        set(variantRepoName, group, name, version = null, target = target)
    }

    override fun set(
        variantRepoName: String,
        group: String,
        name: String,
        version: String?,
        target: MavenDependency?
    ) {
        val dependency = target ?: MavenDependency(variantRepoName.toMavenRepoName(), group, name)
        if (version != null) {
            cache[ArtifactKey(variantRepoName, group, name, version)] = dependency
        }
        cache[ArtifactKey(variantRepoName, group, name)] = dependency
    }

    override fun close() = cache.clear()
}
