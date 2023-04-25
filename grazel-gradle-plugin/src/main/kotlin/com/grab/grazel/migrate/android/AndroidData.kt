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

internal interface AndroidData {
    val name: String
    val srcs: List<String>
    val res: List<String>
    val resValuesData: ResValuesData
    val assets: List<String>
    val assetsDir: String?
    val manifestFile: String?

    // Custom package used for detecting Java/Kotlin sources root
    val customPackage: String

    // Actual application package name of the library
    val packageName: String
    val buildConfigData: BuildConfigData
    val deps: List<BazelDependency>
    val plugins: List<BazelDependency>
    val databinding: Boolean
    val tags: List<String>
}

internal data class AndroidLibraryData(
    override val name: String,
    override val srcs: List<String> = emptyList(),
    override val res: List<String> = emptyList(),
    override val resValuesData: ResValuesData = ResValuesData(),
    override val assets: List<String> = emptyList(),
    override val assetsDir: String? = null,
    override val manifestFile: String? = null,
    override val customPackage: String,
    override val packageName: String,
    override val buildConfigData: BuildConfigData = BuildConfigData(),
    override val deps: List<BazelDependency> = emptyList(),
    override val plugins: List<BazelDependency> = emptyList(),
    override val databinding: Boolean = false,
    override val tags: List<String> = emptyList()
) : AndroidData

internal data class AndroidBinaryData(
    override val name: String,
    override val srcs: List<String> = emptyList(),
    override val res: List<String> = emptyList(),
    override val resValuesData: ResValuesData = ResValuesData(),
    override val assets: List<String> = emptyList(),
    override val assetsDir: String? = null,
    override val manifestFile: String? = null,
    override val customPackage: String,
    override val packageName: String,
    override val buildConfigData: BuildConfigData = BuildConfigData(),
    override val deps: List<BazelDependency> = emptyList(),
    override val plugins: List<BazelDependency> = emptyList(),
    override val databinding: Boolean = false,
    override val tags: List<String> = emptyList(),
    val manifestValues: Map<String, String?> = emptyMap(),
    val multidex: Multidex = Multidex.Native,
    val dexShards: Int? = null,
    val incrementalDexing: Boolean = true,
    val debugKey: String? = null,
    val hasCrashlytics: Boolean = false,
) : AndroidData