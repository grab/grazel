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

package com.grab.grazel.bazel.rules

import com.grab.grazel.bazel.starlark.AssignmentBuilder
import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.bazel.starlark.function

/**
 * Contract for all types of Bazel rules,
 */
interface BazelRule {
    var name: String

    /**
     * Write the contents of the Rule to the given `StatementsBuilder`.
     *
     * @receiver The `StatementsBuilder` instance to which the contents must be written to.
     */
    fun StatementsBuilder.addRule()

    fun addTo(statementsBuilder: StatementsBuilder) {
        statementsBuilder.addRule()
    }
}

fun StatementsBuilder.rule(name: String, assignmentBuilder: AssignmentBuilder.() -> Unit = {}) {
    function(name, true, assignmentBuilder)
}
