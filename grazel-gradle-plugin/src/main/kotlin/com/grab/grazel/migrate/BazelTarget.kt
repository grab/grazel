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

package com.grab.grazel.migrate

import com.grab.grazel.bazel.rules.Visibility
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.StatementsBuilder
import org.gradle.api.Project


interface BazelTarget {
    val name: String
    fun statements(builder: StatementsBuilder)
}

fun BazelTarget.toBazelDependency(): BazelDependency {
    return BazelDependency.StringDependency(":$name")
}

interface BazelBuildTarget : BazelTarget {
    val srcs: List<String>
    val deps: List<BazelDependency>
    val visibility: Visibility
    val tags: List<String>
}


interface TargetBuilder {
    fun build(project: Project): List<BazelTarget>
    fun canHandle(project: Project): Boolean

    fun sortOrder(): Int = Int.MAX_VALUE
}
