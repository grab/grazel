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

package com.grab.grazel.bazel.rules

import com.grab.grazel.bazel.starlark.Assignee
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.LintConfigs
import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.bazel.starlark.array
import com.grab.grazel.bazel.starlark.asString
import com.grab.grazel.bazel.starlark.glob
import com.grab.grazel.bazel.starlark.load
import com.grab.grazel.bazel.starlark.quote
import com.grab.grazel.bazel.starlark.toObject
import com.grab.grazel.migrate.android.BuildConfigData
import com.grab.grazel.migrate.android.ResValuesData

fun StatementsBuilder.androidSdkRepository(
    name: String = "androidsdk",
    apiLevel: Int? = null,
    buildToolsVersion: String? = null
) {
    rule("android_sdk_repository") {
        "name" `=` name.quote
        apiLevel?.let {
            "api_level" `=` apiLevel
        }
        buildToolsVersion?.let {
            "build_tools_version" `=` buildToolsVersion.quote
        }
    }
}

fun StatementsBuilder.androidNdkRepository(
    name: String = "androidndk",
    path: String? = null,
    ndkApiLevel: Int? = null
) {
    rule("android_ndk_repository") {
        "name" `=` name.quote
        path?.let {
            "path" `=` path.quote
        }
        ndkApiLevel?.let {
            "api_level" `=` ndkApiLevel
        }
    }
}

fun StatementsBuilder.buildConfig(
    name: String,
    packageName: String,
    strings: Map<String, String> = emptyMap(),
    booleans: Map<String, String> = emptyMap(),
    ints: Map<String, String> = emptyMap(),
    longs: Map<String, String> = emptyMap()
) {
    load("@$GRAB_BAZEL_COMMON//tools/build_config:build_config.bzl", "build_config")
    rule("build_config") {
        "name" `=` name.quote
        "package_name" `=` packageName.quote

        if (strings.isNotEmpty()) {
            "strings" `=` strings.mapKeys { it.key.quote }.toObject()
        }

        if (booleans.isNotEmpty()) {
            "booleans" `=` booleans
                .mapKeys { it.key.quote }
                .mapValues { it.value.quote }
                .toObject()
        }

        if (ints.isNotEmpty()) {
            "ints" `=` ints.mapKeys { it.key.quote }.toObject()
        }

        if (longs.isNotEmpty()) {
            "longs" `=` longs.mapKeys { it.key.quote }.toObject()
        }
    }
}

fun StatementsBuilder.loadResValue() {
    load("@$GRAB_BAZEL_COMMON//tools/res_value:res_value.bzl", "res_value")
}

fun resValue(
    name: String,
    strings: Map<String, String>
) = Assignee {
    rule("res_value") {
        "name" `=` name.quote
        "strings" `=` strings.mapKeys { it.key.quote }
            .mapValues { it.value.quote }
            .toObject()
    }
}

enum class Multidex {
    Native,
    Legacy,
    ManualMainDex,
    Off
}

internal fun StatementsBuilder.androidBinary(
    name: String,
    crunchPng: Boolean = false,
    customPackage: String,
    dexShards: Int? = null,
    debugKey: String? = null,
    multidex: Multidex = Multidex.Off,
    incrementalDexing: Boolean = false,
    manifest: String? = null,
    srcsGlob: List<String> = emptyList(),
    manifestValues: Map<String, String?> = mapOf(),
    enableDataBinding: Boolean = false,
    enableCompose: Boolean = false,
    visibility: Visibility = Visibility.Public,
    resourceFiles: List<Assignee> = emptyList(),
    resources: Assignee? = null,
    resValuesData: ResValuesData,
    deps: List<BazelDependency>,
    assetsGlob: List<String> = emptyList(),
    assetsDir: String? = null,
    buildConfigData: BuildConfigData,
    lintConfigs: LintConfigs? = null,
) {
    load("@$GRAB_BAZEL_COMMON//rules:defs.bzl", "android_binary")
    rule("android_binary") {
        "name" `=` name.quote
        "crunch_png" `=` crunchPng.toString().capitalize()
        "custom_package" `=` customPackage.quote
        "incremental_dexing" `=` incrementalDexing.toString().capitalize()
        dexShards?.let { "dex_shards" `=` dexShards }
        debugKey?.let { "debug_key" `=` debugKey.quote }
        "multidex" `=` multidex.name.toLowerCase().quote
        manifest?.let { "manifest" `=` manifest.quote }
        "manifest_values" `=` manifestValues.toObject(quoteKeys = true, quoteValues = true)
        srcsGlob.notEmpty {
            "srcs" `=` glob(srcsGlob.quote)
        }
        "visibility" `=` array(visibility.rule.quote)
        if (enableDataBinding) {
            "enable_data_binding" `=` enableDataBinding.toString().capitalize()
        }
        if (enableCompose) {
            "enable_compose" `=` enableCompose.toString().capitalize()
        }
        resourceFiles.notEmpty {
            "resource_files" `=` resourceFiles.joinToString(
                separator = " + ",
                transform = Assignee::asString
            )
        }
        resources?.let { "resources" `=` resources }
        deps.notEmpty {
            "deps" `=` array(deps.map(BazelDependency::toString).quote)
        }
        assetsDir?.let {
            "assets" `=` glob(assetsGlob.quote)
            "assets_dir" `=` assetsDir.quote
        }
        if (!buildConfigData.isEmpty) {
            "build_config" `=` buildConfigData.merged.toObject(quoteKeys = true)
        }
        if (!resValuesData.isEmpty) {
            "res_values" `=` resValuesData.merged.toObject(quoteKeys = true, quoteValues = true)
        }

        if (lintConfigs?.merged?.isNotEmpty() == true) {
            "lint_options" `=` lintConfigs.merged.toObject(quoteKeys = true, quoteValues = true)
        }
    }
}

internal fun StatementsBuilder.androidLibrary(
    name: String,
    packageName: String,
    manifest: String? = null,
    srcsGlob: List<String> = emptyList(),
    visibility: Visibility = Visibility.Public,
    resources: Assignee? = null,
    resourceFiles: List<Assignee> = emptyList(),
    enableDataBinding: Boolean = false,
    enableCompose: Boolean = false,
    deps: List<BazelDependency>,
    tags: List<String> = emptyList(),
    assetsGlob: List<String> = emptyList(),
    assetsDir: String? = null,
    resValuesData: ResValuesData,
    buildConfigData: BuildConfigData,
    lintConfigs: LintConfigs?
) {
    load("@$GRAB_BAZEL_COMMON//rules:defs.bzl", "android_library")
    rule("android_library") {
        "name" `=` name.quote
        "custom_package" `=` packageName.quote
        manifest?.let { "manifest" `=` manifest.quote }
        srcsGlob.notEmpty {
            "srcs" `=` glob(srcsGlob.map(String::quote))
        }
        "visibility" `=` array(visibility.rule.quote)
        resourceFiles.notEmpty {
            "resource_files" `=` resourceFiles.joinToString(
                separator = " + ",
                transform = Assignee::asString
            )
        }
        resources?.let { "resources" `=` resources }
        deps.notEmpty {
            "deps" `=` array(deps.map(BazelDependency::toString).map(String::quote))
        }
        if (enableDataBinding) {
            "enable_data_binding" `=` enableDataBinding.toString().capitalize()
        }
        if (enableCompose) {
            "enable_compose" `=` enableCompose.toString().capitalize()
        }
        tags.notEmpty {
            "tags" `=` array(tags.map(String::quote))
        }
        assetsDir?.let {
            "assets" `=` glob(assetsGlob.quote)
            "assets_dir" `=` assetsDir.quote
        }
        if (!buildConfigData.isEmpty) {
            "build_config" `=` buildConfigData.merged.toObject(quoteKeys = true)
        }
        if (!resValuesData.isEmpty) {
            "res_values" `=` resValuesData.merged.toObject(quoteKeys = true, quoteValues = true)
        }

        if (lintConfigs?.merged?.isNotEmpty() == true) {
            "lint_options" `=` lintConfigs.merged.toObject(quoteKeys = true, quoteValues = true)
        }
    }
}

internal const val DATABINDING_GROUP = "androidx.databinding"
internal const val ANDROIDX_GROUP = "androidx.annotation"
internal const val ANNOTATION_ARTIFACT = "annotation"

fun StatementsBuilder.loadCustomRes() {
    load("@$GRAB_BAZEL_COMMON//tools/custom_res:custom_res.bzl", "custom_res")
}

fun customRes(
    target: String,
    dirName: String,
    resourceFiles: Assignee
): Assignee = Assignee {
    rule("custom_res") {
        "target" `=` target.quote
        "dir_name" `=` dirName.quote
        "resource_files" `=` resourceFiles
    }
}

fun StatementsBuilder.androidUnitTest(
    name: String,
    customPackage: String,
    srcs: List<String> = emptyList(),
    additionalSrcSets: List<String> = emptyList(),
    srcsGlob: List<String> = emptyList(),
    visibility: Visibility = Visibility.Public,
    deps: List<BazelDependency> = emptyList(),
    associates: List<BazelDependency> = emptyList(),
    plugins: List<BazelDependency> = emptyList(),
    enableCompose: Boolean = false,
    tags: List<String> = emptyList(),
    resourcesGlob: List<String> = emptyList(),
) {
    load("@$GRAB_BAZEL_COMMON//rules:defs.bzl", "android_unit_test")

    rule("android_unit_test") {
        "name" `=` name.quote
        "custom_package" `=` customPackage.quote
        srcs.notEmpty {
            "srcs" `=` srcs.map(String::quote)
        }
        additionalSrcSets.notEmpty {
            "additional_src_sets" `=` additionalSrcSets.map(String::quote)
        }
        srcsGlob.notEmpty {
            "srcs" `=` glob(srcsGlob.map(String::quote))
        }
        "visibility" `=` array(visibility.rule.quote)
        associates.notEmpty {
            "associates" `=` array(associates.map(BazelDependency::toString).map(String::quote))
        }
        deps.notEmpty {
            "deps" `=` array(deps.map(BazelDependency::toString).map(String::quote))
        }
        if (enableCompose) {
            "enable_compose" `=` enableCompose.toString().capitalize()
        }
        plugins.notEmpty {
            "plugins" `=` array(plugins.map(BazelDependency::toString).map(String::quote))
        }
        tags.notEmpty {
            "tags" `=` array(tags.map(String::quote))
        }
        resourcesGlob.notEmpty {
            "resources" `=` glob(resourcesGlob.map(String::quote))
        }
    }
}

fun StatementsBuilder.androidInstrumentationBinary(
    name: String,
    srcsGlob: List<String> = emptyList(),
    deps: List<BazelDependency>,
    associates: List<BazelDependency> = emptyList(),
    customPackage: String,
    targetPackage: String,
    debugKey: String? = null,
    instruments: BazelDependency,
    manifestValues: Map<String, String?> = mapOf(),
    resources: List<String> = emptyList(),
    resourceStripPrefix: String? = null,
    resourceFiles: List<Assignee> = emptyList(),
    testInstrumentationRunner: String? = null,
    enableCompose: Boolean = false,
) {
    load(
        "@$GRAB_BAZEL_COMMON//rules:defs.bzl",
        "android_instrumentation_binary"
    )
    rule("android_instrumentation_binary") {
        "name" `=` name.quote
        associates.notEmpty {
            "associates" `=` array(associates.map(BazelDependency::toString).map(String::quote))
        }
        "custom_package" `=` customPackage.quote
        "target_package" `=` targetPackage.quote
        debugKey?.let { "debug_key" `=` debugKey.quote }
        deps.notEmpty {
            "deps" `=` array(deps.map(BazelDependency::toString).map(String::quote))
        }
        "instruments" `=` instruments.toString().quote
        manifestValues.notEmpty {
            "manifest_values" `=` manifestValues.toObject(
                quoteKeys = true,
                quoteValues = true
            )
        }
        resources.notEmpty {
            "resources" `=` glob(resources.quote)
        }
        resourceStripPrefix?.let {
            "resource_strip_prefix" `=` it.quote
        }
        resourceFiles.notEmpty {
            "resource_files" `=` resourceFiles.joinToString(
                separator = " + ",
                transform = Assignee::asString
            )
        }
        srcsGlob.notEmpty {
            "srcs" `=` glob(srcsGlob.quote)
        }
        testInstrumentationRunner?.let {
            "test_instrumentation_runner" `=` it.quote
        }

        if (enableCompose) {
            "enable_compose" `=` enableCompose.toString().capitalize()
        }
    }
}
