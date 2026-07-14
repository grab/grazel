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

    fun set(
        variantRepoName: String,
        group: String,
        name: String,
        version: String?,
        target: MavenDependency?,
        direct: Boolean
    )

    fun getAllInRepo(repo: String): Set<MavenDependency>
}

class DefaultMavenInstallStore : MavenInstallStore {

    private data class ArtifactKey(
        val variant: String,
        val group: String,
        val name: String,
        val version: String? = null,
    )

    private data class StoredDependency(
        val dependency: MavenDependency,
        val direct: Boolean
    )

    private val cache = ConcurrentHashMap<ArtifactKey, StoredDependency>()
    private val dependenciesByRepo = ConcurrentHashMap<String, MutableSet<MavenDependency>>()

    override fun get(
        variants: Set<String>,
        group: String,
        name: String,
        version: String?
    ): MavenDependency? {
        fun variantsWithDefault(): Sequence<String> = sequence {
            yieldAll(variants)
            if (DEFAULT_VARIANT !in variants) {
                yield(DEFAULT_VARIANT)
            }
        }

        fun get(variant: String, requestedVersion: String?): StoredDependency? =
            cache[ArtifactKey(variant, group, name, requestedVersion)]

        fun select(candidates: Sequence<StoredDependency>): MavenDependency? {
            var firstIndirectCandidate: MavenDependency? = null
            candidates.forEach { candidate ->
                if (candidate.direct) {
                    return candidate.dependency
                }
                if (firstIndirectCandidate == null) {
                    firstIndirectCandidate = candidate.dependency
                }
            }
            return firstIndirectCandidate
        }

        if (version != null) {
            select(
                variantsWithDefault()
                    .mapNotNull { variant -> get(variant, version) }
            )?.let { return it }
        }

        return select(
            variantsWithDefault()
                .mapNotNull { variant -> get(variant, null) }
        )
    }

    override fun set(
        variantRepoName: String,
        group: String,
        name: String,
        version: String?,
        target: MavenDependency?,
        direct: Boolean
    ) {
        val repoName = variantRepoName.toMaterializedMavenRepoName()
        val dependency = target ?: MavenDependency(repoName, group, name)
        val storedDependency = StoredDependency(dependency, direct)
        dependenciesByRepo
            .getOrPut(repoName) { sortedSetOf() }
            .add(dependency)

        fun put(key: ArtifactKey) {
            cache.merge(key, storedDependency) { existing, candidate ->
                when {
                    existing.direct && !candidate.direct -> existing
                    else -> candidate
                }
            }
        }

        if (version != null) {
            put(ArtifactKey(variantRepoName, group, name, version))
        }
        put(ArtifactKey(variantRepoName, group, name))
    }

    override fun getAllInRepo(repo: String): Set<MavenDependency> =
        dependenciesByRepo[repo]?.toSortedSet() ?: emptySet()

    override fun close() {
        cache.clear()
        dependenciesByRepo.clear()
    }
}
