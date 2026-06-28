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

import com.grab.grazel.gradle.dependencies.TargetReferenceFactsCollector
import com.grab.grazel.gradle.dependencies.merged
import com.grab.grazel.gradle.dependencies.model.TargetMavenRepoReferences
import com.grab.grazel.gradle.dependencies.toTargetMavenRepoReferences
import com.grab.grazel.migrate.BazelBuildTarget
import com.grab.grazel.migrate.BazelPluginTarget
import com.grab.grazel.migrate.BazelTarget
import com.grab.grazel.migrate.android.AndroidTestTarget
import com.grab.grazel.migrate.android.AndroidTarget
import com.grab.grazel.migrate.android.LintConfigData
import com.grab.grazel.migrate.kotlin.KotlinLibraryTarget

internal object TargetMavenRepoReferencesCollector {
    fun fromTargets(targets: Iterable<BazelTarget>): TargetMavenRepoReferences =
        targets
            .map { target ->
                TargetReferenceFactsCollector.from(
                    deps = (target as? BazelBuildTarget)?.deps.orEmpty(),
                    tags = (target as? BazelBuildTarget)?.tags.orEmpty(),
                    plugins = (target as? BazelPluginTarget)?.plugins.orEmpty(),
                    lintChecks = target.lintConfigData()?.lintChecks.orEmpty(),
                    associates = (target as? AndroidTestTarget)?.associates.orEmpty(),
                    instruments = (target as? AndroidTestTarget)?.instruments
                )
            }
            .merged()
            .toTargetMavenRepoReferences()

    private fun BazelTarget.lintConfigData(): LintConfigData? =
        when (this) {
            is AndroidTarget -> lintConfigData
            is KotlinLibraryTarget -> lintConfigData
            else -> null
        }
}
