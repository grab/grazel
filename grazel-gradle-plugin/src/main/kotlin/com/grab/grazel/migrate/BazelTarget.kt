/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.migrate

import com.grab.grazel.bazel.rules.Visibility
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.Statement
import org.gradle.api.Project


interface BazelTarget {
    val name: String
    fun statements(): List<Statement>
}

fun BazelTarget.toBazelDependency(): BazelDependency {
    return BazelDependency.StringDependency(":$name")
}

interface BazelBuildTarget : BazelTarget {
    val deps: List<BazelDependency>
    val srcs: List<String>
    val visibility: Visibility
}


interface TargetBuilder {
    fun build(project: Project): List<BazelTarget>
    fun canHandle(project: Project): Boolean
}
