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

import com.android.builder.core.DefaultApiVersion
import com.grab.grazel.bazel.rules.KotlinProjectType
import com.grab.grazel.bazel.rules.customRes
import com.grab.grazel.bazel.rules.loadCustomRes
import com.grab.grazel.bazel.rules.loadResValue
import com.grab.grazel.bazel.rules.resValue
import com.grab.grazel.bazel.starlark.Assignee
import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.bazel.starlark.array
import com.grab.grazel.bazel.starlark.glob
import com.grab.grazel.bazel.starlark.quote
import com.grab.grazel.migrate.BazelBuildTarget
import com.grab.grazel.migrate.kotlin.KtLibraryTarget

/**
 * Lightweight data structure to hold details about custom resource set
 *
 * @param folderName The root folder name of the custom resource set.
 *     Eg: res-debug.
 * @param entry The parsed entry in this folder. Eg: `src/main/res/`
 */
internal data class ResourceSet(
    val folderName: String,
    val entry: String
)

internal fun ResourceSet.entryGlob(builder: StatementsBuilder) = builder.glob(listOf(entry.quote))

internal fun AndroidLibraryData.toKtLibraryTarget(): BazelBuildTarget? = when {
    srcs.isNotEmpty() || databinding -> KtLibraryTarget(
        name = name,
        kotlinProjectType = KotlinProjectType.Android(hasDatabinding = databinding),
        packageName = packageName,
        srcs = srcs,
        manifest = manifestFile,
        res = res,
        resValuesData = resValuesData,
        customResourceSets = extraRes,
        deps = deps,
        plugins = plugins,
        assetsGlob = assets,
        assetsDir = assetsDir,
        tags = tags
    )

    srcs.isEmpty() && res.isNotEmpty() -> AndroidLibraryTarget(
        name = name,
        packageName = packageName,
        manifest = manifestFile,
        projectName = name,
        res = res,
        customResourceSets = extraRes,
        deps = deps,
        assetsGlob = assets,
        tags = tags,
        assetsDir = assetsDir
    )

    else -> null
}

internal fun AndroidLibraryData.toBuildConfigTarget() = BuildConfigTarget(
    name = "$name-build-config",
    packageName = customPackage,
    strings = buildConfigData.strings,
    booleans = buildConfigData.booleans,
    ints = buildConfigData.ints,
    longs = buildConfigData.longs
)


/**
 * Calculate resources for Android targets
 *
 * @param resources resource list come from Android project
 * @param resValuesData Gradle's res_value values
 * @param customResourceSets The custom resource folders add as Gradle
 *     resource set
 * @param targetName The name of the target
 * @return List of `Assignee` to be used in `resource_files`
 */
internal fun StatementsBuilder.buildResources(
    targetName: String,
    resources: List<String>,
    customResourceSets: List<ResourceSet>,
    resValuesData: ResValuesData = ResValuesData()
): List<Assignee> {

    val localResources = resources.map { glob(array(it.quote)) }

    val customResources = if (customResourceSets.isNotEmpty()) {
        loadCustomRes()
        customResourceSets
            .map { extraResSet ->
                customRes(targetName, extraResSet.folderName, extraResSet.entryGlob(this))
            }
    } else emptyList()

    if (!resValuesData.isEmpty) {
        loadResValue()
        listOf(resValue("$targetName-res-value", resValuesData.stringValues))
    } else emptyList()

    return localResources + customResources
}

/**
 * Calculate an Android Project's compileSdkVersion from `AppExtension`
 *
 * @param compileSdkVersion The compileSdkVersion from `BaseExtension`.
 * @return The api level. `null` if not found.
 * @see `SdkVersionInfo`
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