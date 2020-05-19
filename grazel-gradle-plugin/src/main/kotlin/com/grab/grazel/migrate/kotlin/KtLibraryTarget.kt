/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.migrate.kotlin

import com.grab.grazel.bazel.rules.KotlinProjectType
import com.grab.grazel.bazel.rules.Visibility
import com.grab.grazel.bazel.rules.ktLibrary
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.Statement
import com.grab.grazel.bazel.starlark.statements
import com.grab.grazel.migrate.BazelBuildTarget
import com.grab.grazel.migrate.android.ResourceSet
import com.grab.grazel.migrate.android.buildResources

internal data class KtLibraryTarget(
    override val name: String,
    override val deps: List<BazelDependency>,
    override val srcs: List<String>,
    override val visibility: Visibility = Visibility.Public,
    val projectName: String = name,
    val kotlinProjectType: KotlinProjectType = KotlinProjectType.Jvm,
    val packageName: String? = null,
    val res: List<String>,
    val extraRes: List<ResourceSet> = emptyList(),
    val manifest: String? = null,
    val plugins: List<BazelDependency> = emptyList(),
    val assetsGlob: List<String> = emptyList(),
    val assetsDir: String? = null,
    val tags: List<String> = emptyList()
) : BazelBuildTarget {

    override fun statements(): List<Statement> = statements {
        val resourceFiles = buildResources(res, extraRes, projectName)
        ktLibrary(
            name = name,
            kotlinProjectType = kotlinProjectType,
            packageName = packageName,
            visibility = visibility,
            srcsGlob = srcs,
            manifest = manifest,
            resourceFiles = resourceFiles,
            deps = deps,
            plugins = plugins,
            assetsGlob = assetsGlob,
            assetsDir = assetsDir,
            tags = tags
        )
    }
}