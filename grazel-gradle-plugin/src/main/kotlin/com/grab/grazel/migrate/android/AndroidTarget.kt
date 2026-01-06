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
    val enableCompose: Boolean
    val projectName: String
    val resourceSets: Set<BazelSourceSet>
    val resValuesData: ResValuesData
    val buildConfigData: BuildConfigData
    val packageName: String
    val manifest: String?
    val assetsGlob: List<String>
    val assetsDir: String?
    val lintConfigData: LintConfigData?
}

internal data class AndroidLibraryTarget(
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
    override val sortKey: String = "0$name",
    override val lintConfigData: LintConfigData? = null,
    val plugins: List<BazelDependency> = emptyList(),
) : AndroidTarget {
    override fun statements(builder: StatementsBuilder) = builder {
        androidLibrary(
            name = name,
            packageName = packageName,
            manifest = manifest,
            enableDataBinding = enableDataBinding,
            enableCompose = enableCompose,
            srcsGlob = srcs,
            resorceSets = buildResources(resourceSets),
            visibility = visibility,
            deps = deps,
            plugins = plugins,
            tags = tags,
            assetsGlob = assetsGlob,
            assetsDir = assetsDir,
            buildConfigData = buildConfigData,
            resValuesData = resValuesData,
            lintConfigData = lintConfigData,
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
    override val enableCompose: Boolean = false,
    override val projectName: String = name,
    override val resourceSets: Set<BazelSourceSet> = emptySet(),
    override val resValuesData: ResValuesData = ResValuesData(),
    override val buildConfigData: BuildConfigData = BuildConfigData(),
    override val packageName: String,
    override val manifest: String? = null,
    override val assetsGlob: List<String> = emptyList(),
    override val assetsDir: String? = null,
    override val sortKey: String = "0$name",
    override val lintConfigData: LintConfigData? = null,
    val crunchPng: Boolean = false,
    val multidex: Multidex = Multidex.Native,
    val debug: Boolean = true,
    val debugKey: String? = null,
    val dexShards: Int? = null,
    val manifestValues: Map<String, String?> = mapOf(),
    val resConfigFilters: Set<String> = emptySet(),
    val customPackage: String,
    val incrementalDexing: Boolean = false,
    val minSdkVersion: Int? = null,
    val plugins: List<BazelDependency> = emptyList(),
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
            enableCompose = enableCompose,
            customPackage = customPackage,
            srcsGlob = srcs,
            manifest = manifest,
            manifestValues = manifestValues,
            resConfigFilters = resConfigFilters,
            resourceSets = buildResources(resourceSets),
            resValuesData = resValuesData,
            deps = deps,
            plugins = plugins,
            assetsGlob = assetsGlob,
            buildConfigData = buildConfigData,
            assetsDir = assetsDir,
            lintConfigData = lintConfigData,
            minSdkVersion = minSdkVersion,
        )
    }
}