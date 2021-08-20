/*
 * Copyright 2021 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.bazel.rules

import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.bazel.starlark.load

internal const val FORMAT_ROBOLECTRIC_ARTIFACT = "org.robolectric:robolectric:%s"


fun StatementsBuilder.robolectricWorkspaceRules(repository: BazelRepositoryRule) {
    repository.addTo(this)
    load("@robolectric//bazel:robolectric.bzl", "robolectric_repositories")
    add("robolectric_repositories()")
}