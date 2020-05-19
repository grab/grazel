/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.bazel.rules

import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.bazel.starlark.load

fun StatementsBuilder.androidExtensionsRules() {
    load(
        "@$GRAB_BAZEL_COMMON//tools/parcelize:parcelize.bzl",
        "parcelize_rules"
    )
    add("parcelize_rules()")
}

internal val KOTLIN_PARCELIZE_TARGET = BazelDependency.StringDependency("//:parcelize")
