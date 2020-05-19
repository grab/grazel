/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
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
