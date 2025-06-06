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
import com.grab.grazel.bazel.starlark.BazelDependency

internal data class BazelSourceSet(
    val name: String,
    val res: String?,
    val assets: String?,
    val manifest: String?,
) {
    val isEmpty: Boolean = res == null && assets == null && manifest == null
    val hasResources: Boolean = res != null || assets != null
}

internal interface AndroidData {
    val name: String
    val srcs: List<String>
    val resourceSets: Set<BazelSourceSet>
    val resValuesData: ResValuesData
    val manifestFile: String?

    // Custom package used for detecting Java/Kotlin sources root
    val customPackage: String

    // Actual application package name of the library
    val packageName: String
    val buildConfigData: BuildConfigData
    val deps: List<BazelDependency>
    val plugins: List<BazelDependency>
    val compose: Boolean
    val databinding: Boolean
    val tags: List<String>
    val lintConfigData: LintConfigData
}

internal data class AndroidLibraryData(
    override val name: String,
    override val srcs: List<String> = emptyList(),
    override val resourceSets: Set<BazelSourceSet> = emptySet(),
    override val resValuesData: ResValuesData = ResValuesData(),
    override val manifestFile: String? = null,
    override val customPackage: String,
    override val packageName: String,
    override val buildConfigData: BuildConfigData = BuildConfigData(),
    override val deps: List<BazelDependency> = emptyList(),
    override val plugins: List<BazelDependency> = emptyList(),
    override val databinding: Boolean = false,
    override val compose: Boolean = false,
    override val tags: List<String> = emptyList(),
    override val lintConfigData: LintConfigData,
) : AndroidData

internal data class AndroidBinaryData(
    override val name: String,
    override val srcs: List<String> = emptyList(),
    override val resourceSets: Set<BazelSourceSet> = emptySet(),
    override val resValuesData: ResValuesData = ResValuesData(),
    override val manifestFile: String? = null,
    override val customPackage: String,
    override val packageName: String,
    override val buildConfigData: BuildConfigData = BuildConfigData(),
    override val deps: List<BazelDependency> = emptyList(),
    override val plugins: List<BazelDependency> = emptyList(),
    override val databinding: Boolean = false,
    override val compose: Boolean = false,
    override val tags: List<String> = emptyList(),
    override val lintConfigData: LintConfigData,
    val manifestValues: Map<String, String?> = emptyMap(),
    val resConfigs: Set<String> = emptySet(),
    val multidex: Multidex = Multidex.Native,
    val dexShards: Int? = null,
    val incrementalDexing: Boolean = true,
    val debugKey: String? = null,
    val hasCrashlytics: Boolean = false,
) : AndroidData