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