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