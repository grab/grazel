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
import com.grab.grazel.bazel.starlark.BazelDependency
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

internal fun ResourceSet.entryGlob(builder: StatementsBuilder) = builder.glob(listOf(entry.quote()))

internal data class AndroidLibraryData(
    val name: String,
    val srcs: List<String>,
    val res: List<String>,
    val assets: List<String>,
    val assetsDir: String?,
    val manifestFile: String?,
    /**
     * Custom package used for detecting Java/Kotlin sources root
     */
    val customPackage: String,
    /**
     * Actual application package name of the library
     */
    val packageName: String,
    val buildConfigData: BuildConfigData = BuildConfigData(),
    val resValues: ResValues,
    val extraRes: List<ResourceSet> = emptyList(),
    val deps: List<BazelDependency> = emptyList(),
    val plugins: List<BazelDependency> = emptyList(),
    val hasDatabinding: Boolean = false,
    val tags: List<String> = emptyList(),
)


internal fun AndroidLibraryData.toKtLibraryTarget(): BazelBuildTarget? = when {
    srcs.isNotEmpty() || hasDatabinding -> KtLibraryTarget(
        name = name,
        kotlinProjectType = KotlinProjectType.Android(hasDatabinding = hasDatabinding),
        packageName = packageName,
        srcs = srcs,
        manifest = manifestFile,
        res = res,
        resValues = resValues,
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
 * @param resValues Gradle's res_value values
 * @param customResourceSets The custom resource folders add as Gradle
 *     resource set
 * @param targetName The name of the target
 * @return List of `Assignee` to be used in `resource_files`
 */
internal fun StatementsBuilder.buildResources(
    resources: List<String>,
    resValues: ResValues,
    customResourceSets: List<ResourceSet>,
    targetName: String
): List<Assignee> {

    val localResources = resources.map { glob(array(it.quote())) }

    val customResources = if (customResourceSets.isNotEmpty()) {
        loadCustomRes()
        customResourceSets
            .map { extraResSet ->
                customRes(targetName, extraResSet.folderName, extraResSet.entryGlob(this))
            }
    } else emptyList()

    val generatedResValues = if (resValues.exists()) {
        loadResValue()
        listOf(resValue("$targetName-res-value", resValues.stringValues))
    } else emptyList()

    return localResources + customResources + generatedResValues
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