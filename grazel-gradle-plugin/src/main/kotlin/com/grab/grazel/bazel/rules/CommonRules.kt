/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.bazel.rules

import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.bazel.starlark.function
import com.grab.grazel.bazel.starlark.load
import com.grab.grazel.bazel.starlark.quote

const val GRAB_BAZEL_COMMON = "grab_bazel_common"
const val GRAB_BAZEL_COMMON_ARTIFACTS = "GRAB_BAZEL_COMMON_ARTIFACTS"

fun StatementsBuilder.workspace(name: String) {
    function("workspace") {
        "name" eq name
            .replace("-", "_")
            .replace(" ", "_")
            .quote()
    }
}

enum class Visibility(val rule: String) {
    Public("//visibility:public")
}

fun StatementsBuilder.loadBazelCommonArtifacts(bazelCommonRepoName: String) {
    load("@$bazelCommonRepoName//:workspace_defs.bzl", "GRAB_BAZEL_COMMON_ARTIFACTS")
}

fun StatementsBuilder.registerToolchain(toolchain: String) {
    function("register_toolchains", toolchain.quote())
}