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