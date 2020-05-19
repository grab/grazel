/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.migrate.android

import com.grab.grazel.bazel.rules.resValue
import com.grab.grazel.bazel.starlark.statements
import com.grab.grazel.migrate.BazelTarget

data class ResValueTarget(
    override val name: String,
    val packageName: String,
    val manifest: String,
    val strings: Map<String, String>
) : BazelTarget {
    override fun statements() = statements {
        resValue(
            name = name,
            packageName = packageName,
            manifest = manifest,
            strings = strings
        )
    }
}