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
    override val deps: List<BazelDependency>,
    override val srcs: List<String>,
    override val tags: List<String>,
    override val visibility: Visibility,
    override val sortKey: String = "2$name",
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
    val assets: List<String>,
    val compose: Boolean,
) : BazelBuildTarget {

    override fun statements(builder: StatementsBuilder) = builder {
        androidInstrumentationBinary(
            name = name,
            srcsGlob = srcs,
            deps = deps,
            associates = associates,
            customPackage = customPackage,
            targetPackage = targetPackage,
            debugKey = debugKey,
            instruments = instruments,
            manifestValues = manifestValues,
            resources = resources,
            resourceStripPrefix = resourceStripPrefix,
            resourceFiles = buildResFiles(resourceFiles),
            testInstrumentationRunner = testInstrumentationRunner,
            enableCompose = compose,
        )
    }
}

/**
 * Converts AndroidTestData to AndroidTestTarget.
 */
internal fun AndroidTestData.toTarget() = AndroidTestTarget(
    name = name,
    deps = deps,
    srcs = srcs,
    tags = tags,
    visibility = Visibility.Public, // Always use public visibility
    associates = associates,
    instruments = instruments,
    customPackage = customPackage,
    targetPackage = targetPackage,
    testInstrumentationRunner = testInstrumentationRunner,
    manifestValues = manifestValues,
    debugKey = debugKey,
    resources = resources,
    resourceFiles = resourceFiles,
    resourceStripPrefix = resourceStripPrefix,
    assets = assets,
    compose = compose,
)
