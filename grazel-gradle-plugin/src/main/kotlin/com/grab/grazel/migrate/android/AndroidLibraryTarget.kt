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


