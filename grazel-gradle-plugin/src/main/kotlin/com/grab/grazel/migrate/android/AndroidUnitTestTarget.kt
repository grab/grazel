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

package com.grab.grazel.migrate.android

import com.grab.grazel.bazel.rules.Visibility
import com.grab.grazel.bazel.rules.grabAndroidLocalTest
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.migrate.BazelBuildTarget

internal data class AndroidUnitTestTarget(
    override val name: String,
    override val srcs: List<String> = emptyList(),
    override val deps: List<BazelDependency>,
    override val tags: List<String> = emptyList(),
    override val visibility: Visibility = Visibility.Public,
    val associates: List<BazelDependency> = emptyList(),
    val customPackage: String,
    val resources: List<String> = emptyList(),
    val additionalSrcSets: List<String> = emptyList(),
) : BazelBuildTarget {
    override fun statements(builder: StatementsBuilder) = builder {
        if (srcs.isNotEmpty()) {
            grabAndroidLocalTest(
                name = name,
                deps = deps,
                visibility = visibility,
                srcsGlob = srcs,
                associates = associates,
                tags = tags,
                customPackage = customPackage,
                resourcesGlob = resources,
                additionalSrcSets = additionalSrcSets,
            )
        }
    }
}


