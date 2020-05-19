/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.bazel.starlark

import java.io.PrintWriter

open class ArrayStatement(open vararg val elements: Assignee) : Assignee {
    override fun write(level: Int, writer: PrintWriter) {
        writer.println("[")
        for (element in elements) {
            indent(level + 1, writer)
            element.write(level + 1, writer)
            writer.write(", \n")
        }
        indent(level, writer)
        writer.print("]")
    }
}

fun array(vararg elements: Assignee) = ArrayStatement(*elements)

fun array(vararg elements: String) = ArrayStatement(*elements.map { StringStatement(it) }.toTypedArray())

fun array(list: Collection<String>): ArrayStatement = array(*list.toTypedArray())