/*
 * Copyright 2021 Grabtaxi Holdings PTE LTD (GRAB)
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
    path: String? = null,
    ndkApiLevel: Int? = null
) {
    rule("android_ndk_repository") {
        "name" eq name.quote()
        path?.let {
            "path" eq path.quote()
        }
        ndkApiLevel?.let {
            "api_level" eq ndkApiLevel
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

fun StatementsBuilder.loadResValue() {
    load("@$GRAB_BAZEL_COMMON//tools/res_value:res_value.bzl", "res_value")
}

fun resValue(
    name: String,
    strings: Map<String, String>
) = Assignee {
    rule("res_value") {
        "name" eq name.quote()
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
            "resource_files" eq resources.joinToString(
                separator = " + ",
                transform = Assignee::asString
            )
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
            "resource_files" eq resourceFiles.joinToString(
                separator = " + ",
                transform = Assignee::asString
            )
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

internal const val DATABINDING_GROUP = "androidx.databinding"
internal const val ANDROIDX_GROUP = "androidx.annotation"
internal const val ANNOTATION_ARTIFACT = "annotation"
internal val DATABINDING_ARTIFACTS by lazy {
    val version = "7.1.2" // TODO(arun) Infer this from AGP
    listOf(
        MavenArtifact(DATABINDING_GROUP, "databinding-adapters", version),
        MavenArtifact(DATABINDING_GROUP, "databinding-compiler", version),
        MavenArtifact(DATABINDING_GROUP, "databinding-common", version),
        MavenArtifact(DATABINDING_GROUP, "databinding-runtime", version),
        MavenArtifact(DATABINDING_GROUP, "viewbinding", version),
        MavenArtifact(ANDROIDX_GROUP, ANNOTATION_ARTIFACT, "1.1.0")
    )
}

fun StatementsBuilder.androidToolsRepository(commit: String? = null, remote: String) {
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
): Assignee = Assignee {
    rule("custom_res") {
        "target" eq target.quote()
        "dir_name" eq dirName.quote()
        "resource_files" eq resourceFiles
    }
}

fun StatementsBuilder.grabAndroidLocalTest(
    name: String,
    customPackage: String,
    srcs: List<String> = emptyList(),
    additionalSrcSets: List<String> = emptyList(),
    srcsGlob: List<String> = emptyList(),
    visibility: Visibility = Visibility.Public,
    deps: List<BazelDependency> = emptyList(),
    associates: List<BazelDependency> = emptyList(),
    plugins: List<BazelDependency> = emptyList(),
    tags: List<String> = emptyList(),
    resourcesGlob: List<String> = emptyList(),
) {
    load("@grab_bazel_common//tools/test:test.bzl", "grab_android_local_test")

    rule("grab_android_local_test") {
        "name" eq name.quote()
        "custom_package" eq customPackage.quote()
        srcs.notEmpty {
            "srcs" eq srcs.map(String::quote)
        }
        additionalSrcSets.notEmpty {
            "additional_src_sets" eq additionalSrcSets.map(String::quote)
        }
        srcsGlob.notEmpty {
            "srcs" eq glob(srcsGlob.map(String::quote))
        }
        "visibility" eq array(visibility.rule.quote())
        associates.notEmpty {
            "associates" eq array(associates.map(BazelDependency::toString).map(String::quote))
        }
        deps.notEmpty {
            "deps" eq array(deps.map(BazelDependency::toString).map(String::quote))
        }
        plugins.notEmpty {
            "plugins" eq array(plugins.map(BazelDependency::toString).map(String::quote))
        }
        tags.notEmpty {
            "tags" eq array(tags.map(String::quote))
        }
        resourcesGlob.notEmpty {
            "resources" eq glob(resourcesGlob.map(String::quote))
        }
    }
}