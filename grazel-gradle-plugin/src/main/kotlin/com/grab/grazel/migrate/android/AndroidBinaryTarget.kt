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

import com.grab.grazel.bazel.rules.Multidex
import com.grab.grazel.bazel.rules.Visibility
import com.grab.grazel.bazel.rules.androidBinary
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.Statement
import com.grab.grazel.bazel.starlark.statements
import com.grab.grazel.migrate.BazelBuildTarget

internal data class AndroidBinaryTarget(
    override val name: String,
    override val visibility: Visibility = Visibility.Public,
    override val deps: List<BazelDependency>,
    override val srcs: List<String>,
    override val tags: List<String> = emptyList(),
    val crunchPng: Boolean = false,
    val customPackage: String,
    val dexShards: Int? = null,
    val debugKey: String? = null,
    val multidex: Multidex = Multidex.Off,
    val incrementalDexing: Boolean = false,
    val res: List<String>,
    val resValues: ResValues = ResValues(),
    val customResourceSets: List<ResourceSet> = emptyList(),
    val manifest: String? = null,
    val manifestValues: Map<String, String?> = mapOf(),
    val enableDataBinding: Boolean = false,
    val assetsGlob: List<String> = emptyList(),
    val assetsDir: String? = null,
) : BazelBuildTarget {
    override fun statements(): List<Statement> = statements {
        val resourceFiles = buildResources(res, resValues, customResourceSets, name)

        androidBinary(
            name = name,
            crunchPng = crunchPng,
            multidex = multidex,
            debugKey = debugKey,
            dexShards = dexShards,
            visibility = visibility,
            incrementalDexing = incrementalDexing,
            enableDataBinding = enableDataBinding,
            customPackage = customPackage,
            srcsGlob = srcs,
            manifest = manifest,
            manifestValues = manifestValues,
            resources = resourceFiles,
            deps = deps,
            assetsGlob = assetsGlob,
            assetsDir = assetsDir
        )
    }
}