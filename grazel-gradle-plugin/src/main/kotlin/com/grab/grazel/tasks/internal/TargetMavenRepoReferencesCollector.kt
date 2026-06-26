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

package com.grab.grazel.tasks.internal

import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.migrate.BazelBuildTarget
import com.grab.grazel.migrate.BazelPluginTarget
import com.grab.grazel.migrate.BazelTarget

internal object TargetMavenRepoReferencesCollector {
    private val tagLabelPattern = Regex("""^@([A-Za-z0-9_]+_maven|maven)//:""")

    fun fromTargets(targets: Iterable<BazelTarget>): Set<String> {
        return targets
            .asSequence()
            .flatMap { target ->
                sequenceOf(
                    (target as? BazelBuildTarget)
                        ?.deps
                        .orEmpty()
                        .asSequence()
                        .filterIsInstance<MavenDependency>()
                        .map(MavenDependency::repo),
                    (target as? BazelPluginTarget)
                        ?.plugins
                        .orEmpty()
                        .asSequence()
                        .filterIsInstance<MavenDependency>()
                        .map(MavenDependency::repo),
                    (target as? BazelBuildTarget)
                        ?.tags
                        .orEmpty()
                        .asSequence()
                        .mapNotNull { tag -> tag.mavenRepoFromTag() }
                ).flatten()
            }
            .toSortedSet()
    }

    private fun String.mavenRepoFromTag(): String? =
        tagLabelPattern.find(this)?.groupValues?.get(1)
}
