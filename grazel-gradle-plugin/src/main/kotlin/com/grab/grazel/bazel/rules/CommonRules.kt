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

import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.bazel.starlark.add
import com.grab.grazel.bazel.starlark.array
import com.grab.grazel.bazel.starlark.function
import com.grab.grazel.bazel.starlark.load
import com.grab.grazel.bazel.starlark.quote
import com.grab.grazel.extension.PreBazelCommonArchive

const val GRAB_BAZEL_COMMON = "grab_bazel_common"
const val GRAB_BAZEL_COMMON_ARTIFACTS = "GRAB_BAZEL_COMMON_ARTIFACTS"

fun StatementsBuilder.workspace(name: String) {
    function("workspace") {
        "name" `=` name
            .replace("-", "_")
            .replace(" ", "_")
            .quote
    }
}

enum class Visibility(val rule: String) {
    Public("//visibility:public")
}

fun StatementsBuilder.loadBazelCommonArtifacts(bazelCommonRepoName: String) {
    load("@$bazelCommonRepoName//:workspace_defs.bzl", "GRAB_BAZEL_COMMON_ARTIFACTS")
}

fun StatementsBuilder.registerToolchain(toolchain: String) {
    function("register_toolchains", quote = true, toolchain.quote)
}

fun StatementsBuilder.bazelCommonRepository(
    repositoryRule: GitRepositoryRule,
    buildifierVersion: String,
    pinnedMavenInstall: Boolean = true,
    additionalCoursierOptions: List<String> = listOf("--parallel", "12"),
) {
    add(repositoryRule)
    val bazelCommonRepoName = repositoryRule.name
    bazelCommonDependencies(bazelCommonRepoName)
    bazelCommonDepsInit(bazelCommonRepoName)
    bazelCommonInitialize(
        bazelCommonRepoName,
        buildifierVersion,
        pinnedMavenInstall,
        additionalCoursierOptions,
    )
    pinBazelCommonArtifacts(bazelCommonRepoName)
}

fun StatementsBuilder.pinBazelCommonArtifacts(bazelCommonRepoName: String) {
    load("@$bazelCommonRepoName//rules:maven.bzl", "pin_bazel_common_dependencies")
    function("pin_bazel_common_dependencies")
}

fun StatementsBuilder.bazelCommonDependencies(bazelCommonRepoName: String) {
    load("@${bazelCommonRepoName}//rules:repositories.bzl", "bazel_common_dependencies")
    function("bazel_common_dependencies")
}

fun StatementsBuilder.bazelCommonDepsInit(bazelCommonRepoName: String) {
    load("@${bazelCommonRepoName}//rules:deps_init.bzl", "bazel_common_deps_init")
    function("bazel_common_deps_init")
}

fun StatementsBuilder.bazelCommonInitialize(
    bazelCommonRepoName: String,
    buildifierVersion: String,
    pinnedMavenInstall: Boolean = true,
    additionalCoursierOptions: List<String> = listOf("--parallel", "12"),
) {
    load("@${bazelCommonRepoName}//rules:setup.bzl", "bazel_common_setup")
    function("bazel_common_setup") {
        "patched_android_tools" `=` "True"
        "buildifier_version" `=` buildifierVersion.quote
        "pinned_maven_install" `=` if (pinnedMavenInstall) "True" else "False"
        if (additionalCoursierOptions.isNotEmpty()) {
            "additional_coursier_options" `=` array(additionalCoursierOptions.quote)
        }
    }
}

fun StatementsBuilder.preBazelCommonArchives(archives: List<PreBazelCommonArchive>) {
    archives.forEach { archive ->
        add(HttpArchiveRule(
            name = archive.name,
            url = archive.url,
            urls = archive.urls,
            sha256 = archive.sha256,
            stripPrefix = archive.stripPrefix,
            patches = archive.patches,
            patchArgs = archive.patchArgs
        ))
    }
}
