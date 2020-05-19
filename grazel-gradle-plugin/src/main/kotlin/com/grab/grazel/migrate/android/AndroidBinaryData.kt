/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.migrate.android

import com.grab.grazel.bazel.rules.Multidex
import com.grab.grazel.bazel.starlark.BazelDependency

internal data class AndroidBinaryData(
    val name: String,
    val manifestValues: Map<String, String?>,
    val deps: List<BazelDependency>,
    val multidex: Multidex,
    val dexShards: Int? = null,
    val debugKey: String? = null,
    val buildId: String? = null,
    val googleServicesJson: String? = null,
    val hasCrashlytics: Boolean = false,
    val hasDatabinding: Boolean = false
)