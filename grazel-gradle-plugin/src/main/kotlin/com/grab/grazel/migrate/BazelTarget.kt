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

package com.grab.grazel.migrate

import com.grab.grazel.bazel.rules.Visibility
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.Statement
import org.gradle.api.Project


interface BazelTarget {
    val name: String
    fun statements(): List<Statement>
}

fun BazelTarget.toBazelDependency(): BazelDependency {
    return BazelDependency.StringDependency(":$name")
}

interface BazelBuildTarget : BazelTarget {
    val deps: List<BazelDependency>
    val srcs: List<String>
    val visibility: Visibility
}


interface TargetBuilder {
    fun build(project: Project): List<BazelTarget>
    fun canHandle(project: Project): Boolean
}
