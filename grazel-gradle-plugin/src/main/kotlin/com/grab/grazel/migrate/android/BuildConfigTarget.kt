/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.migrate.android

import com.grab.grazel.bazel.rules.buildConfig
import com.grab.grazel.bazel.starlark.statements
import com.grab.grazel.migrate.BazelTarget

data class BuildConfigTarget(
    override val name: String,
    val packageName: String,
    val strings: Map<String, String> = emptyMap(),
    val booleans: Map<String, String> = emptyMap(),
    val ints: Map<String, String> = emptyMap(),
    val longs: Map<String, String> = emptyMap()
) : BazelTarget {
    override fun statements() = statements {
        buildConfig(
            name = name,
            packageName = packageName,
            strings = strings,
            booleans = booleans,
            ints = ints,
            longs = longs
        )
    }
}