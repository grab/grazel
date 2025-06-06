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

import com.grab.grazel.bazel.TestSize
import com.grab.grazel.bazel.starlark.BazelDependency

data class AndroidUnitTestData(
    val name: String,
    val srcs: List<String>,
    val additionalSrcSets: List<String>,
    val deps: List<BazelDependency>,
    val tags: List<String>,
    val customPackage: String,
    val associates: List<BazelDependency>,
    val resources: List<String>,
    val compose: Boolean,
    val testSize: TestSize = TestSize.MEDIUM
)

internal fun AndroidUnitTestData.toUnitTestTarget() = AndroidUnitTestTarget(
    name = name,
    srcs = srcs,
    additionalSrcSets = additionalSrcSets,
    deps = deps,
    associates = associates,
    customPackage = customPackage,
    resources = resources,
    tags = tags,
    compose = compose,
    testSize = testSize,
)
