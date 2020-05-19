/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.migrate.android

import com.grab.grazel.bazel.rules.Visibility
import com.grab.grazel.bazel.rules.androidLibrary
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.statements
import com.grab.grazel.migrate.BazelBuildTarget

internal data class AndroidLibraryTarget(
    override val name: String,
    override val deps: List<BazelDependency>,
    override val srcs: List<String> = emptyList(),
    override val visibility: Visibility = Visibility.Public,
    val enableDataBinding: Boolean = false,
    val projectName: String = name,
    val res: List<String>,
    val extraRes: List<ResourceSet> = emptyList(),
    val packageName: String,
    val manifest: String? = null,
    val assetsGlob: List<String> = emptyList(),
    val assetsDir: String? = null
) : BazelBuildTarget {
    override fun statements() = statements {
        val resourceFiles = buildResources(res, extraRes, projectName)
        androidLibrary(
            name = name,
            packageName = packageName,
            manifest = manifest,
            enableDataBinding = enableDataBinding,
            srcsGlob = srcs,
            resourceFiles = resourceFiles,
            visibility = visibility,
            deps = deps,
            assetsGlob = assetsGlob,
            assetsDir = assetsDir
        )
    }
}


