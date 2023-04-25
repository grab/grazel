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

package com.grab.grazel.migrate.android

import com.grab.grazel.bazel.rules.Multidex
import com.grab.grazel.bazel.rules.Visibility
import com.grab.grazel.bazel.rules.androidBinary
import com.grab.grazel.bazel.rules.androidLibrary
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.migrate.BazelBuildTarget

internal interface AndroidTarget : BazelBuildTarget {
    val enableDataBinding: Boolean
    val projectName: String
    val resDirs: List<String>
    val resValuesData: ResValuesData
    val buildConfigData: BuildConfigData
    val packageName: String
    val manifest: String?
    val assetsGlob: List<String>
    val assetsDir: String?
}

internal data class AndroidLibraryTarget(
    override val name: String,
    override val srcs: List<String> = emptyList(),
    override val deps: List<BazelDependency>,
    override val tags: List<String> = emptyList(),
    override val visibility: Visibility = Visibility.Public,
    override val enableDataBinding: Boolean = false,
    override val projectName: String = name,
    override val resDirs: List<String>,
    override val resValuesData: ResValuesData = ResValuesData(),
    override val buildConfigData: BuildConfigData = BuildConfigData(),
    override val packageName: String,
    override val manifest: String? = null,
    override val assetsGlob: List<String> = emptyList(),
    override val assetsDir: String? = null
) : AndroidTarget {
    override fun statements(builder: StatementsBuilder) = builder {
        androidLibrary(
            name = name,
            packageName = packageName,
            manifest = manifest,
            enableDataBinding = enableDataBinding,
            srcsGlob = srcs,
            resources = buildResources(resDirs),
            visibility = visibility,
            deps = deps,
            tags = tags,
            assetsGlob = assetsGlob,
            assetsDir = assetsDir,
            buildConfigData = buildConfigData,
            resValuesData = resValuesData
        )
    }
}

internal data class AndroidBinaryTarget(
    override val name: String,
    override val srcs: List<String> = emptyList(),
    override val deps: List<BazelDependency>,
    override val tags: List<String> = emptyList(),
    override val visibility: Visibility = Visibility.Public,
    override val enableDataBinding: Boolean = false,
    override val projectName: String = name,
    override val resDirs: List<String>,
    override val resValuesData: ResValuesData = ResValuesData(),
    override val buildConfigData: BuildConfigData = BuildConfigData(),
    override val packageName: String,
    override val manifest: String? = null,
    override val assetsGlob: List<String> = emptyList(),
    override val assetsDir: String? = null,
    val crunchPng: Boolean = false,
    val multidex: Multidex = Multidex.Native,
    val debug: Boolean = true,
    val debugKey: String? = null,
    val dexShards: Int? = null,
    val manifestValues: Map<String, String?> = mapOf(),
    val customPackage: String,
    val incrementalDexing: Boolean = false,
) : AndroidTarget {
    override fun statements(builder: StatementsBuilder) = builder {
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
            resources = buildResources(resDirs),
            resValuesData = resValuesData,
            deps = deps,
            assetsGlob = assetsGlob,
            buildConfigData = buildConfigData,
            assetsDir = assetsDir,
        )
    }
}