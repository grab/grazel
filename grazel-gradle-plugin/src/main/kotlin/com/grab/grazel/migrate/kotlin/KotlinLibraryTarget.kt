/*
 * Copyright 2022 Grabtaxi Holdings PTE LTD (GRAB)
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

import com.grab.grazel.bazel.rules.Visibility
import com.grab.grazel.bazel.rules.ktLibrary
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.migrate.BazelBuildTarget
import com.grab.grazel.migrate.android.ResValuesData
import com.grab.grazel.migrate.android.ResourceSet
import com.grab.grazel.migrate.android.buildResources

internal data class KotlinLibraryTarget(
    override val name: String,
    override val srcs: List<String>,
    override val deps: List<BazelDependency>,
    override val visibility: Visibility = Visibility.Public,
    override val tags: List<String> = emptyList(),
    val projectName: String = name,
    val packageName: String? = null,
    val res: List<String>,
    val resValuesData: ResValuesData = ResValuesData(),
    val customResourceSets: List<ResourceSet> = emptyList(),
    val manifest: String? = null,
    val plugins: List<BazelDependency> = emptyList(),
    val assetsGlob: List<String> = emptyList(),
    val assetsDir: String? = null,
) : BazelBuildTarget {

    override fun statements(builder: StatementsBuilder) = builder {
        val resourceFiles = buildResources(projectName, res, customResourceSets, resValuesData)
        ktLibrary(
            name = name,
            packageName = packageName,
            srcsGlob = srcs,
            visibility = visibility,
            deps = deps,
            resourceFiles = resourceFiles,
            manifest = manifest,
            plugins = plugins,
            assetsGlob = assetsGlob,
            assetsDir = assetsDir,
            tags = tags
        )
    }
}