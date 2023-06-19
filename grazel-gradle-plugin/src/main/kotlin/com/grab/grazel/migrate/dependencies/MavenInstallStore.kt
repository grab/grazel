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

package com.grab.grazel.migrate.dependencies

import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data structure to hold information about generated maven repositories in `WORKSPACE`
 */
internal interface MavenInstallStore {
    /**
     * For a given variant hierarchy and `group` and `name`, the function will try to look
     * for the dependency in each of the variant hierarchy and return the first one found.
     *
     * For example, if `androidx.activity:activity` is given and it was categorized
     * under `@maven` repository then will return `@maven//:androidx_activity_activity`
     * in form of [MavenDependency]
     */
    operator fun get(variants: Set<String>, group: String, name: String): MavenDependency?

    operator fun set(variantRepoName: String, group: String, name: String)

    val size: Int
}

@Singleton
class DefaultMavenInstallStore
@Inject
constructor() : MavenInstallStore {

    private data class ArtifactKey(
        val variant: String,
        val group: String,
        val name: String,
    )

    private val map = ConcurrentHashMap<ArtifactKey, String>()

    override val size: Int get() = map.size

    override fun get(variants: Set<String>, group: String, name: String): MavenDependency {
        fun get(repo: String): MavenDependency? =
            if (map.containsKey(ArtifactKey(repo, group, name))) {
                MavenDependency(repo, group, name)
            } else null

        return variants.asSequence().mapNotNull { variant ->
            val repoName = variant.toMavenRepoName()
            get(repoName)
        }.firstOrNull() ?: get("maven") ?: run {
            // When no dependency is found in the index, assume @maven. This could be incorrect
            // but makes for easier testing
            MavenDependency(group = group, name = name)
        }
    }

    override fun set(variantRepoName: String, group: String, name: String) {
        map[ArtifactKey(variantRepoName, group, name)] = variantRepoName
    }
}