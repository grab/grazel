/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.migrate.kotlin

import com.grab.grazel.bazel.starlark.BazelDependency

data class KotlinProjectData(
    val name: String,
    val srcs: List<String>,
    val res: List<String>,
    val deps: List<BazelDependency>
)