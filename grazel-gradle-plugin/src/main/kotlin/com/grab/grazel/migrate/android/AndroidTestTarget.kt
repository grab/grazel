/*
 * Copyright 2022 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.migrate.android

import com.grab.grazel.bazel.rules.Visibility
import com.grab.grazel.bazel.rules.androidInstrumentationBinary
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.migrate.BazelBuildTarget

/**
 * Represents a Bazel build target for com.android.test modules.
 *
 * This target generates android_instrumentation_binary rules for test modules
 * that are migrated from Gradle's com.android.test plugin.
 */

internal data class AndroidTestTarget(
    override val name: String,
    override val srcs: List<String> = emptyList(),
    override val deps: List<BazelDependency>,
    override val tags: List<String> = emptyList(),
    override val visibility: Visibility = Visibility.Public,
    override val enableDataBinding: Boolean = false,
    override val enableCompose: Boolean = false,
    override val projectName: String = name,
    override val resourceSets: Set<BazelSourceSet> = emptySet(),
    override val resValuesData: ResValuesData = ResValuesData(),
    override val buildConfigData: BuildConfigData = BuildConfigData(),
    override val packageName: String,
    override val manifest: String? = null,
    override val assetsGlob: List<String> = emptyList(),
    override val assetsDir: String? = null,
    override val sortKey: String = "2$name",
    override val lintConfigData: LintConfigData? = null,
    // Test-specific fields
    val associates: List<BazelDependency>,
    val instruments: BazelDependency,
    val customPackage: String,
    val targetPackage: String,
    val testInstrumentationRunner: String,
    val manifestValues: Map<String, String?>,
    val debugKey: String?,
    val resources: List<String>,
    val resourceFiles: List<String>,
    val resourceStripPrefix: String?,
) : AndroidTarget {
    override fun statements(builder: StatementsBuilder) = builder {
        androidInstrumentationBinary(
            name = name,
            srcsGlob = srcs,
            // Include associates in deps instead of passing separately to avoid
            // "Dependencies on .jar artifacts are not allowed" error in some Bazel configurations.
            // The app library dependency is needed for resource linking.
            deps = deps + associates,
            customPackage = customPackage,
            targetPackage = targetPackage,
            debugKey = debugKey,
            instruments = instruments,
            manifestValues = manifestValues,
            resources = resources,
            resourceStripPrefix = resourceStripPrefix,
            resourceFiles = buildResFiles(resourceFiles),
            testInstrumentationRunner = testInstrumentationRunner,
            enableCompose = enableCompose,
        )
    }
}