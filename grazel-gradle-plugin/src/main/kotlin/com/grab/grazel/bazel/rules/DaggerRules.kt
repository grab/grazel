/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.bazel.rules

import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.bazel.starlark.load
import com.grab.grazel.bazel.starlark.quote

internal const val DAGGER_GROUP = "com.google.dagger"

fun StatementsBuilder.daggerWorkspaceRules(
    daggerTag: String = "2.28.1",
    daggerSha: String = "9e69ab2f9a47e0f74e71fe49098bea908c528aa02fa0c5995334447b310d0cdd"
) {
    val tag = "DAGGER_TAG"
    val sha256 = "DAGGER_SHA"

    tag eq daggerTag.quote()
    sha256 eq daggerSha.quote()
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