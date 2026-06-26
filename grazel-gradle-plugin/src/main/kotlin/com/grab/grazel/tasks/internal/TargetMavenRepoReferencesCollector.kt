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

import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.bazel.starlark.BazelDependency.ProjectDependency
import com.grab.grazel.bazel.starlark.BazelDependency.StringDependency
import com.grab.grazel.gradle.dependencies.model.TargetMavenRepoReferences
import com.grab.grazel.migrate.BazelBuildTarget
import com.grab.grazel.migrate.BazelPluginTarget
import com.grab.grazel.migrate.BazelTarget
import com.grab.grazel.migrate.android.AndroidTestTarget
import com.grab.grazel.migrate.android.AndroidTarget
import com.grab.grazel.migrate.android.LintConfigData
import com.grab.grazel.migrate.kotlin.KotlinLibraryTarget

internal object TargetMavenRepoReferencesCollector {
    private val tagLabelPattern = Regex("""^@([A-Za-z0-9_]+_maven|maven)//:""")
    private val projectLabelPattern = Regex("""^//([^:]+):([^:]+)$""")

    fun fromTargets(targets: Iterable<BazelTarget>): TargetMavenRepoReferences {
        val targetList = targets.toList()
        return TargetMavenRepoReferences(
            repoNames = mavenReposFromTargets(targetList),
            projectPaths = projectPathsFromTargets(targetList),
            projectTargets = projectTargetsFromTargets(targetList)
        )
    }

    private fun mavenReposFromTargets(targets: Iterable<BazelTarget>): Set<String> =
        targets
            .asSequence()
            .flatMap { target ->
                sequenceOf(
                    target
                        .dependencies()
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

    private fun projectPathsFromTargets(targets: Iterable<BazelTarget>): Set<String> =
        targets
            .asSequence()
            .flatMap { target -> target.dependencies() }
            .mapNotNull { dependency -> dependency.projectReference()?.first }
            .toSortedSet()

    private fun projectTargetsFromTargets(targets: Iterable<BazelTarget>): Map<String, Set<String>> =
        targets
            .asSequence()
            .flatMap { target -> target.dependencies() }
            .mapNotNull { dependency -> dependency.projectReference() }
            .groupBy(
                keySelector = { (projectPath, _) -> projectPath },
                valueTransform = { (_, targetName) -> targetName }
            )
            .mapValues { (_, targetNames) -> targetNames.toSortedSet() }
            .toSortedMap()

    private fun BazelTarget.dependencies(): Sequence<BazelDependency> {
        return sequenceOf(
            (this as? BazelBuildTarget)?.deps.orEmpty().asSequence(),
            (this as? BazelPluginTarget)?.plugins.orEmpty().asSequence(),
            (this as? AndroidTestTarget)?.associates.orEmpty().asSequence(),
            (this as? AndroidTestTarget)?.instruments?.let { sequenceOf(it) }.orEmpty(),
            lintConfigData()?.lintChecks.orEmpty().asSequence()
        ).flatten()
    }

    private fun BazelTarget.lintConfigData(): LintConfigData? =
        when (this) {
            is AndroidTarget -> lintConfigData
            is KotlinLibraryTarget -> lintConfigData
            else -> null
        }

    private fun String.mavenRepoFromTag(): String? =
        tagLabelPattern.find(this)?.groupValues?.get(1)

    private fun ProjectDependency.targetName(): String =
        "$prefix${dependencyProject.name}$suffix"

    private fun BazelDependency.projectReference(): Pair<String, String>? =
        when (this) {
            is ProjectDependency -> dependencyProject.path to targetName()
            is StringDependency -> string.projectReference()
            else -> null
        }

    private fun String.projectReference(): Pair<String, String>? {
        val match = projectLabelPattern.find(this) ?: return null
        val projectPath = ":${match.groupValues[1].replace("/", ":")}"
        val targetName = match.groupValues[2]
        return projectPath to targetName
    }
}
