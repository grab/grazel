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

package com.grab.grazel.bazel.rules

import com.grab.grazel.bazel.starlark.Assignee
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.bazel.starlark.array
import com.grab.grazel.bazel.starlark.asString
import com.grab.grazel.bazel.starlark.function
import com.grab.grazel.bazel.starlark.glob
import com.grab.grazel.bazel.starlark.load
import com.grab.grazel.bazel.starlark.quote
import com.grab.grazel.bazel.starlark.statements
import com.grab.grazel.bazel.starlark.toAssignee
import com.grab.grazel.bazel.starlark.toObject
import com.grab.grazel.gradle.dependencies.MavenArtifact

fun StatementsBuilder.androidSdkRepository(
    name: String = "androidsdk",
    apiLevel: Int? = null,
    buildToolsVersion: String? = null
) {
    rule("android_sdk_repository") {
        "name" eq name.quote()
        apiLevel?.let {
            "api_level" eq apiLevel
        }
        buildToolsVersion?.let {
            "build_tools_version" eq buildToolsVersion.quote()
        }
    }
}

fun StatementsBuilder.androidNdkRepository(
    name: String = "androidndk",
    path: String? = null
) {
    rule("android_ndk_repository") {
        "name" eq name.quote()
        path?.let {
            "path" eq path.quote()
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
        "name" eq name.quote()
        "package_name" eq packageName.quote()

        if (strings.isNotEmpty()) {
            "strings" eq strings.mapKeys { it.key.quote() }.toObject()
        }

        if (booleans.isNotEmpty()) {
            "booleans" eq booleans
                .mapKeys { it.key.quote() }
                .mapValues { it.value.quote() }
                .toObject()
        }

        if (ints.isNotEmpty()) {
            "ints" eq ints.mapKeys { it.key.quote() }.toObject()
        }

        if (longs.isNotEmpty()) {
            "longs" eq longs.mapKeys { it.key.quote() }.toObject()
        }
    }
}

fun StatementsBuilder.resValue(
    name: String,
    packageName: String,
    manifest: String,
    strings: Map<String, String>
) {
    load("@$GRAB_BAZEL_COMMON//tools/res_value:res_value.bzl", "res_value")
    rule("res_value") {
        "name" eq name.quote()
        "custom_package" eq packageName.quote()
        "manifest" eq manifest.quote()
        "strings" eq strings.mapKeys { it.key.quote() }
            .mapValues { it.value.quote() }
            .toObject()
    }
}

enum class Multidex {
    Native,
    Legacy,
    ManualMainDex,
    Off
}

fun StatementsBuilder.androidBinary(
    name: String,
    crunchPng: Boolean = false,
    packageName: String,
    dexShards: Int? = null,
    debugKey: String? = null,
    multidex: Multidex = Multidex.Off,
    incrementalDexing: Boolean = false,
    manifest: String? = null,
    srcsGlob: List<String> = emptyList(),
    manifestValues: Map<String, String?> = mapOf(),
    enableDataBinding: Boolean = false,
    visibility: Visibility = Visibility.Public,
    resources: List<Assignee> = emptyList(),
    deps: List<BazelDependency>,
    assetsGlob: List<String> = emptyList(),
    assetsDir: String? = null
) {

    rule("android_binary") {
        "name" eq name.quote()
        "crunch_png" eq crunchPng.toString().capitalize()
        "custom_package" eq packageName.quote()
        "incremental_dexing" eq incrementalDexing.toString().capitalize()
        dexShards?.let { "dex_shards" eq dexShards }
        debugKey?.let { "debug_key" eq debugKey.quote() }
        "multidex" eq multidex.name.toLowerCase().quote()
        manifest?.let { "manifest" eq manifest.quote() }
        "manifest_values" eq manifestValues.toObject(
            quoteKeys = true,
            quoteValues = true
        )
        srcsGlob.notEmpty {
            "srcs" eq glob(srcsGlob.quote)
        }
        "visibility" eq array(visibility.rule.quote())
        if (enableDataBinding) {
            "enable_data_binding" eq enableDataBinding.toString().capitalize()
        }
        resources.notEmpty {
            "resource_files" eq resources.joinToString(separator = " + ", transform = Assignee::asString)
        }
        deps.notEmpty {
            "deps" eq array(deps.map(BazelDependency::toString).quote)
        }
        assetsDir?.let {
            "assets" eq glob(assetsGlob.quote)
            "assets_dir" eq assetsDir.quote()
        }
    }
}

fun StatementsBuilder.androidLibrary(
    name: String,
    packageName: String,
    manifest: String? = null,
    srcsGlob: List<String> = emptyList(),
    visibility: Visibility = Visibility.Public,
    resourceFiles: List<Assignee> = emptyList(),
    enableDataBinding: Boolean = false,
    deps: List<BazelDependency>,
    assetsGlob: List<String> = emptyList(),
    assetsDir: String? = null
) {
    rule("android_library") {
        "name" eq name.quote()
        "custom_package" eq packageName.quote()
        manifest?.let { "manifest" eq manifest.quote() }
        srcsGlob.notEmpty {
            "srcs" eq glob(srcsGlob.map(String::quote))
        }
        "visibility" eq array(visibility.rule.quote())
        resourceFiles.notEmpty {
            "resource_files" eq resourceFiles.joinToString(separator = " + ", transform = Assignee::asString)
        }
        deps.notEmpty {
            "deps" eq array(deps.map(BazelDependency::toString).map(String::quote))
        }
        if (enableDataBinding) {
            "enable_data_binding" eq enableDataBinding.toString().capitalize()
        }
        assetsDir?.let {
            "assets" eq glob(assetsGlob.quote)
            "assets_dir" eq assetsDir.quote()
        }
    }
}

internal val DATABINDING_GROUP = "androidx.databinding"
internal val ANDROIDX_GROUP = "androidx.annotation"
internal val ANNOTATION_ARTIFACT = "annotation"
internal val DATABINDING_ARTIFACTS by lazy {
    val version = "3.4.2"
    listOf(
        MavenArtifact(DATABINDING_GROUP, "databinding-adapters", version),
        MavenArtifact(DATABINDING_GROUP, "databinding-compiler", version),
        MavenArtifact(DATABINDING_GROUP, "databinding-common", version),
        MavenArtifact(DATABINDING_GROUP, "databinding-runtime", version),
        MavenArtifact(ANDROIDX_GROUP, ANNOTATION_ARTIFACT, "1.1.0")
    )
}

fun StatementsBuilder.androidToolsRepository(commit: String? = null, remote : String) {
    load("@grab_bazel_common//:workspace_defs.bzl", "android_tools")
    function("android_tools") {
        commit?.let { "commit" eq commit.quote() }
        "remote" eq remote.quote()
    }
}

fun StatementsBuilder.loadCustomRes() {
    load("@grab_bazel_common//tools/custom_res:custom_res.bzl", "custom_res")
}

fun customRes(
    target: String,
    dirName: String,
    resourceFiles: Assignee
): Assignee = statements {
    rule("custom_res") {
        "target" eq target.quote()
        "dir_name" eq dirName.quote()
        "resource_files" eq resourceFiles
    }
}.toAssignee()