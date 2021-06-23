/*
 * Copyright 2021 Grabtaxi Holdings PTE LTE (GRAB)
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