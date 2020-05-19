/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.migrate.android

import com.grab.grazel.bazel.rules.Multidex
import com.grab.grazel.bazel.rules.Visibility
import com.grab.grazel.bazel.rules.androidBinary
import com.grab.grazel.bazel.rules.crashlyticsAndroidLibrary
import com.grab.grazel.bazel.rules.googleServicesXml
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.Statement
import com.grab.grazel.bazel.starlark.asString
import com.grab.grazel.bazel.starlark.statements
import com.grab.grazel.migrate.BazelBuildTarget

internal data class AndroidBinaryTarget(
    override val name: String,
    override val visibility: Visibility = Visibility.Public,
    override val deps: List<BazelDependency>,
    override val srcs: List<String>,
    val crunchPng: Boolean = false,
    val packageName: String,
    val dexShards: Int? = null,
    val debugKey: String? = null,
    val multidex: Multidex = Multidex.Off,
    val incrementalDexing: Boolean = false,
    val res: List<String>,
    val extraRes: List<ResourceSet> = emptyList(),
    val manifest: String? = null,
    val manifestValues: Map<String, String?> = mapOf(),
    val enableDataBinding: Boolean = false,
    val assetsGlob: List<String> = emptyList(),
    val assetsDir: String? = null,
    val buildId: String? = null,
    val googleServicesJson: String?,
    val hasCrashlytics: Boolean
) : BazelBuildTarget {
    override fun statements(): List<Statement> = statements {
        var resourceFiles = buildResources(res, extraRes, name)
        var finalDeps = deps
        if (googleServicesJson != null) {
            val googleServicesXmlRes = googleServicesXml(
                packageName = packageName,
                googleServicesJson = googleServicesJson
            )
            resourceFiles += googleServicesXmlRes
            if (hasCrashlytics && buildId != null) {
                finalDeps += crashlyticsAndroidLibrary(
                    packageName = packageName,
                    buildId = buildId,
                    resourceFiles = googleServicesXmlRes.asString()
                )
            }
        }

        androidBinary(
            name = name,
            crunchPng = crunchPng,
            multidex = multidex,
            debugKey = debugKey,
            dexShards = dexShards,
            visibility = visibility,
            incrementalDexing = incrementalDexing,
            enableDataBinding = enableDataBinding,
            packageName = packageName,
            srcsGlob = srcs,
            manifest = manifest,
            manifestValues = manifestValues,
            resources = resourceFiles,
            deps = finalDeps,
            assetsGlob = assetsGlob,
            assetsDir = assetsDir
        )
    }
}