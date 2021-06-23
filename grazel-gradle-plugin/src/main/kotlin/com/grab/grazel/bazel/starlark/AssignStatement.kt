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

import com.grab.grazel.bazel.starlark.AssignmentOp.EQUAL
import java.io.PrintWriter

/**
 * Marker interface to denote that a statement is assignable, i.e goes to left of the statement preceding an [AssignmentOp]
 */
interface Assignable : Statement, IsEmpty

/**
 * Marker interface to denote that a statement is assignee, i.e goes to right of the statement succeeding an [AssignmentOp]
 */
interface Assignee : Statement

//TODO(arun) Make this functional interface
interface AssigneeBuilder {
    fun build(): Assignee
}

inline fun assigneeBuilder(crossinline builder: () -> Assignee) = object : AssigneeBuilder {
    override fun build(): Assignee = builder()
}

enum class AssignmentOp(val op: String) {
    EQUAL("="),
    COLON(":")
}

open class AssignStatement(
    private val key: Assignable,
    private val value: Assignee,
    private val assignmentOp: AssignmentOp = EQUAL
) : Statement {
    override fun write(level: Int, writer: PrintWriter) {
        indent(level, writer)
        if (!key.isEmpty()) {
            key.write(0, writer)
            writer.print(" ${assignmentOp.op} ")
        }
        value.write(0, writer)
    }
}

class StringStatement(
    private val string: String,
    private val newLine: Boolean = false
) : Assignable, Assignee {

    override fun write(level: Int, writer: PrintWriter) {
        indent(level, writer)
        writer.print(string)
        if (newLine) {
            writer.println()
        }
    }

    override fun isEmpty() = string.trim().isEmpty()
}

object NewLineStatement : Statement {
    override fun write(level: Int, writer: PrintWriter) {
        writer.println()
    }
}

class SimpleAssignStatement(
    key: String,
    value: String,
    assignmentOp: AssignmentOp = EQUAL
) : AssignStatement(
    key = StringStatement(key),
    value = StringStatement(value),
    assignmentOp = assignmentOp
)

class StringKeyAssignStatement(
    key: String,
    value: Assignee,
    assignmentOp: AssignmentOp = EQUAL
) : AssignStatement(
    key = StringStatement(key),
    value = value,
    assignmentOp = assignmentOp
)

fun noArgAssign(
    value: Assignee,
    assignmentOp: AssignmentOp = EQUAL
) = StringKeyAssignStatement("", value, assignmentOp)

fun noArgAssign(
    value: String,
    assignmentOp: AssignmentOp = EQUAL
) = StringKeyAssignStatement("", StringStatement(value), assignmentOp)

interface AssignmentBuilder {
    infix fun String.eq(value: String)
    infix fun String.eq(value: Any) = this eq value.toString()
    infix fun String.eq(assignee: Assignee)
    infix fun String.eq(strings: List<String>)
    infix fun String.eq(assigneeBuilder: AssigneeBuilder) {
        this eq assigneeBuilder.build()
    }

    /**
     * A common pattern is to not add statements if a list is empty. This function only executes `block` if the given list
     * is not empty.
     */
    fun List<*>.notEmpty(block: () -> Unit) {
        if (isNotEmpty()) block()
    }
}

class DefaultAssignmentBuilder(private val assignmentOp: AssignmentOp = EQUAL) : AssignmentBuilder {

    private val mutableAssignments = mutableListOf<AssignStatement>()

    /**
     * List of [AssignStatement] built by this builder
     */
    val assignments get() = mutableAssignments.toList()

    override infix fun String.eq(value: String) {
        mutableAssignments += SimpleAssignStatement(this, value, assignmentOp)
    }

    override infix fun String.eq(assignee: Assignee) {
        mutableAssignments += StringKeyAssignStatement(this, assignee, assignmentOp)
    }

    override infix fun String.eq(strings: List<String>) {
        this eq array(strings)
    }
}

fun assignments(
    assignmentOp: AssignmentOp = EQUAL,
    assignmentBuilder: AssignmentBuilder.() -> Unit = {}
): List<AssignStatement> {
    return DefaultAssignmentBuilder(assignmentOp).apply(assignmentBuilder).assignments
}

fun List<Statement>.toAssignee() = StringStatement(asString())