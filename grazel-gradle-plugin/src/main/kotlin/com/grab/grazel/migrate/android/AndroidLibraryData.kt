/*
 * Copyright 2021 Grabtaxi Holdings PTE LTE (GRAB)
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

import com.android.builder.core.DefaultApiVersion
import com.grab.grazel.bazel.rules.customRes
import com.grab.grazel.bazel.rules.loadCustomRes
import com.grab.grazel.bazel.starlark.Assignee
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.bazel.starlark.array
import com.grab.grazel.bazel.starlark.glob
import com.grab.grazel.bazel.starlark.quote

/**
 * Light weight data structure to hold details about custom resource set
 *
 * @param folderName The root folder name of the custom resource set. Eg: res-debug.
 * @param entry The parsed entry in this folder. Eg: `src/main/res/`
 */
internal data class ResourceSet(
    val folderName: String,
    val entry: String
)

internal fun ResourceSet.entryGlob(builder: StatementsBuilder) = builder.glob(listOf(entry.quote()))

internal data class AndroidLibraryData(
    val name: String,
    val srcs: List<String>,
    val res: List<String>,
    val assets: List<String>,
    val assetsDir: String?,
    val manifestFile: String?,
    val packageName: String,
    val buildConfigData: BuildConfigData = BuildConfigData(),
    val resValues: ResValues,
    val extraRes: List<ResourceSet> = emptyList(),
    val deps: List<BazelDependency> = emptyList(),
    val plugins: List<BazelDependency> = emptyList(),
    val hasDatabinding: Boolean = false
)


/**
 * Calculate resources for Android targets
 *
 * @param res resource list come from Android project
 * @param extraRes // todo please help to provide more info
 * @param targetName The name of the target
 *
 * @return List of `Assignee` to be used in `resource_files`
 */
internal fun StatementsBuilder.buildResources(
    res: List<String>,
    extraRes: List<ResourceSet>,
    targetName: String
): List<Assignee> {
    if (extraRes.isNotEmpty()) loadCustomRes()
    return res.map { glob(array(it.quote())) } +
            extraRes.map { extraResSet ->
                customRes(targetName, extraResSet.folderName, extraResSet.entryGlob(this))
            }
}

/**
 * Calculate an Android Project's compileSdkVersion from `AppExtension`
 *
 * @param compileSdkVersion The compileSdkVersion from `BaseExtension`.
 * @return The api level. `null` if not found.
 *
 *@see `SdkVersionInfo`
 */
internal fun parseCompileSdkVersion(compileSdkVersion: String?): Int? {
    return if (compileSdkVersion != null) {
        // Match formats `android-30`
        if ("android-\\d\\d".toRegex() matches compileSdkVersion) {
            return compileSdkVersion.split("-").last().toInt()
        }
        // Fallback to querying from AGP Apis
        DefaultApiVersion.create(compileSdkVersion).apiLevel
    } else null
}