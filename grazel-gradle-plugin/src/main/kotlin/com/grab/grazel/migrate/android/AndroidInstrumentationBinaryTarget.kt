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
import com.grab.grazel.bazel.starlark.Statement
import com.grab.grazel.bazel.starlark.statements
import com.grab.grazel.migrate.BazelBuildTarget

internal data class AndroidInstrumentationBinaryTarget(
    override val name: String,
    override val deps: List<BazelDependency>,
    override val srcs: List<String>,
    override val tags: List<String> = emptyList(),
    override val visibility: Visibility = Visibility.Public,
    val associates: List<BazelDependency> = emptyList(),
    val customPackage: String,
    val targetPackage: String,
    val debugKey: String? = null,
    val instruments: BazelDependency,
    val manifestValues: Map<String, String?> = mapOf(),
    val resources: List<String>,
    val resourceStripPrefix: String? = null,
    val resourceFiles: List<String>,
    val customResourceSets: List<ResourceSet> = emptyList(),
    val testInstrumentationRunner: String? = null,
) : BazelBuildTarget {

    override fun statements(): List<Statement> = statements {
        val resFiles = buildResources(name, resourceFiles, customResourceSets)
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
            resourceFiles = resFiles,
            testInstrumentationRunner = testInstrumentationRunner,
        )
    }
}
