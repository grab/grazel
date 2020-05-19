/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.bazel.starlark

import com.grab.grazel.bazel.starlark.AssignmentOp.COLON
import java.io.PrintWriter

class ObjectStatement(private val args: List<AssignStatement>) : Assignee {
    override fun write(level: Int, writer: PrintWriter) {
        writer.println("{")
        args.forEach { element ->
            indent(level + 1, writer)
            element.write(level + 1, writer)
            writer.write(",")
            writer.println()
        }
        indent(level + 1, writer)
        writer.print("}")
    }
}

fun obj(assignmentBuilder: AssignmentBuilder.() -> Unit = {}) = ObjectStatement(
    assignments(COLON, assignmentBuilder)
)

@Suppress("unused")
fun StatementsBuilder.obj(
    assignmentBuilder: AssignmentBuilder.() -> Unit = {}
) = com.grab.grazel.bazel.starlark.obj(assignmentBuilder)

/**
 * Converts the given `Map` to bazel struct
 *
 * @param quoteKeys Whether the keys should be wrapped with quotes in generated code.
 * @param quoteValues Whether the values should be wrapped with quotes in generated code.
 */
fun Map<String, Any?>.toObject(
    quoteKeys: Boolean = false,
    quoteValues: Boolean = false
) = obj {
    filterValues { it != null }
        .forEach { (orgKey, orgValue) ->
            val key = if (quoteKeys) orgKey.quote() else orgKey
            val value = if (quoteValues) orgValue!!.quote() else orgValue!!
            key eq value
        }
}