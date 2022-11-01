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
import com.grab.grazel.bazel.starlark.load
import com.grab.grazel.bazel.starlark.quote
import com.grab.grazel.extension.DaggerExtension

internal const val DAGGER_GROUP = "com.google.dagger"

fun StatementsBuilder.daggerWorkspaceRules(
    daggerExt: DaggerExtension
) {
    val tag = "DAGGER_TAG"
    val sha256 = "DAGGER_SHA"

    tag eq daggerExt.tag.quote()
    sha256 eq daggerExt.sha.quote()
    httpArchive(
        name = "dagger",
        stripPrefix = """"dagger-dagger-%s" % $tag""",
        sha256 = sha256,
        url = """"https://github.com/google/dagger/archive/dagger-%s.zip" % $tag"""
    )
}

internal const val DAGGER_REPOSITORIES = "DAGGER_REPOSITORIES"
internal const val DAGGER_ARTIFACTS = "DAGGER_ARTIFACTS"

fun StatementsBuilder.loadDaggerArtifactsAndRepositories() {
    load("@dagger//:workspace_defs.bzl", DAGGER_ARTIFACTS, DAGGER_REPOSITORIES)
}

fun StatementsBuilder.daggerBuildRules() {
    load("@dagger//:workspace_defs.bzl", "dagger_rules")
    add("dagger_rules()")
}