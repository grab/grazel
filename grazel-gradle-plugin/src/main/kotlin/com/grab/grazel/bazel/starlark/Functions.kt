/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.bazel.starlark

import com.grab.grazel.bazel.rules.Visibility
import com.grab.grazel.bazel.rules.rule
import java.io.PrintWriter

class FunctionStatement(
    val name: String,
    private val params: List<AssignStatement>,
    private val multilineParams: Boolean = false
) : Assignee {
    override fun write(level: Int, writer: PrintWriter) {
        indent(level, writer)
        writer.write("$name(")
        if (multilineParams) {
            writer.println()
        }
        params.forEachIndexed { index, parameters ->
            if (index == 0 && !multilineParams) {
                parameters.write(level, writer)
            } else {
                parameters.write(level + 1, writer)
            }
            if (index != params.size - 1) {
                writer.write(",")
            }
            if (multilineParams) {
                writer.println()
            }
        }
        indent(level, writer)
        writer.println(")")
    }
}

fun function(
    name: String,
    multilineParams: Boolean = false,
    assignmentBuilder: AssignmentBuilder.() -> Unit = {}
) = FunctionStatement(
    name,
    assignments(assignmentBuilder = assignmentBuilder),
    multilineParams
)

fun StatementsBuilder.function(
    name: String,
    multilineParams: Boolean = false,
    assignmentBuilder: AssignmentBuilder.() -> Unit = {}
) {
    add(com.grab.grazel.bazel.starlark.function(name, multilineParams, assignmentBuilder))
}

fun StatementsBuilder.function(name: String, vararg args: String) {
    add(FunctionStatement(name = name, params = args.map(String::quote).map(::noArgAssign)))
}

/**
 * //TODO Implement collecting all load statements and adding them to top of the file.
 */
fun StatementsBuilder.load(vararg args: String) {
    function("load", *args)
}

fun StatementsBuilder.load(args: List<String>) {
    load(*args.toTypedArray())
}

@Suppress("unused")
fun StatementsBuilder.glob(include: ArrayStatement): FunctionStatement {
    val multilineParams = include.elements.size > 2
    return FunctionStatement(
        name = "glob",
        multilineParams = multilineParams,
        params = listOf(noArgAssign(include))
    )
}

@Suppress("unused")
fun StatementsBuilder.glob(items: Collection<String>) = glob(array(items))

fun StatementsBuilder.filegroup(name: String, srcs: List<String>, visibility: Visibility = Visibility.Public) {
    rule("filegroup") {
        "name" eq name.quote()
        "srcs" eq array(srcs.quote)
        "visibility" eq array(visibility.rule.quote())
    }
}
