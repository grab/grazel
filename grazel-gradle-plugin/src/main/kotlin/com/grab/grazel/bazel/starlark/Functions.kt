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

package com.grab.grazel.bazel.starlark

import com.grab.grazel.bazel.rules.Visibility
import com.grab.grazel.bazel.rules.rule
import java.io.PrintWriter

data class FunctionStatement(
    val name: String,
    private val params: List<Assignee>,
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
    Assignments(assignmentBuilder = assignmentBuilder),
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
    add(FunctionStatement(name = name, params = args.map(String::quote).map { noArgAssign(it) }))
}

fun StatementsBuilder.load(bzlFile: String, vararg symbols: String) {
    loadStrategy.load(this, bzlFile, *symbols)
}

/**
 * Load statement with option to alias imported symbol via `assignmentBuilder`
 *
 * Eg:
 * ```
 * load("@maven//:defs.bzl", default_pinned_maven_install = "pinned_maven_install")
 * ```
 */
fun StatementsBuilder.load(bzlFile: String, assignmentBuilder: AssignmentBuilder.() -> Unit = {}) {
    val symbolImports = Assignments(assignmentBuilder = assignmentBuilder)
    loadStrategy.load(this, bzlFile, symbolImports.asString())
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

fun StatementsBuilder.filegroup(
    name: String,
    srcs: List<String>,
    visibility: Visibility = Visibility.Public
) {
    rule("filegroup") {
        "name" `=` name.quote
        "srcs" `=` array(srcs.quote)
        "visibility" `=` array(visibility.rule.quote)
    }
}
