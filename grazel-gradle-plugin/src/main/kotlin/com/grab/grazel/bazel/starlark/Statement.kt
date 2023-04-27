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

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.TreeMap

internal const val INDENT = 2

interface Statement {

    fun write(level: Int, writer: PrintWriter)

    fun indent(
        level: Int,
        writer: PrintWriter
    ): PrintWriter {
        writer.print(" ".repeat(level * INDENT))
        return writer
    }
}

interface IsEmpty {
    fun isEmpty(): Boolean
}

private val String.isQuoted get() = startsWith("\"") && endsWith("\"")

val Any.quote: String
    get() {
        val stringValue = toString()
        return if (!stringValue.isQuoted) "\"" + toString() + "\"" else stringValue
    }

/**
 * Quotes each [Iterable]'s items with `quote`.
 */
val <T : Any> Collection<T>.quote: Collection<String> get() = map { it.quote }

private typealias SymbolMap = MutableMap< /*Bzl File*/ String, /*Symbols*/ Set<String>>

/**
 * Represents various types of symbol loading strategies
 */
sealed class LoadStrategy(
    open val importedSymbols: SymbolMap
) {

    // TODO(arun) migrate to a context receiver on StatementsBuilder
    abstract fun load(
        builder: StatementsBuilder,
        bzlFile: String,
        vararg symbols: String
    )

    /**
     * Allows to query result of loading strategy as list of [Statement]s if applicable.
     */
    abstract fun results(): List<Statement>

    /**
     * Load strategy where symbols are imported as needed eg. WORKSPACE
     */
    data class Inline(
        override val importedSymbols: SymbolMap = HashMap()
    ) : LoadStrategy(importedSymbols) {

        override fun load(
            builder: StatementsBuilder,
            bzlFile: String,
            vararg symbols: String
        ) {
            val prevImportedSymbols = importedSymbols.getOrDefault(bzlFile, emptySet())
            symbols.filter { it !in prevImportedSymbols }.let { newSymbols ->
                if (newSymbols.isNotEmpty()) {
                    builder.function(
                        name = "load",
                        args = buildList {
                            add(bzlFile)
                            addAll(newSymbols)
                        }.toTypedArray()
                    )
                }
            }
            importedSymbols[bzlFile] = (prevImportedSymbols + symbols).toSet()
        }

        /**
         * Nothing to return as load statements are inlined
         */
        override fun results(): List<Statement> = emptyList()
    }

    /**
     * Load strategy where symbols are preferred to be in top of the file. eg. BUILD.bazel
     */
    data class Top(
        override val importedSymbols: SymbolMap = TreeMap()
    ) : LoadStrategy(importedSymbols) {

        override fun load(
            builder: StatementsBuilder,
            bzlFile: String,
            vararg symbols: String
        ) {
            // Instead of adding `load` statements inline, collect all symbols
            val prevImportedSymbols = importedSymbols.getOrDefault(bzlFile, emptySet())
            importedSymbols[bzlFile] = (prevImportedSymbols + symbols).toSet()
        }

        override fun results() = importedSymbols.map { (bzlFile: String, symbols: Set<String>) ->
            FunctionStatement(
                name = "load",
                params = buildList {
                    add(bzlFile)
                    addAll(symbols)
                }.quote.map(::noArgAssign)
            )
        }
    }
}

class StatementsBuilder(
    val loadStrategy: LoadStrategy = LoadStrategy.Top(),
) : AssignmentBuilder {
    private val mutableStatements = mutableListOf<Statement>()

    val statements: List<Statement>
        get() = loadStrategy.results() + mutableStatements.toList()

    fun add(statement: Statement) {
        mutableStatements += statement
        addNewLine()
    }

    fun add(statements: List<Statement>) {
        mutableStatements.addAll(statements)
        addNewLine()
    }

    operator fun invoke(builder: StatementsBuilder.() -> Unit) = builder.invoke(this)

    fun statements(builder: StatementsBuilder.() -> Unit) {
        builder.invoke(this)
    }

    fun newLine() {
        addNewLine()
    }

    private fun addNewLine() {
        mutableStatements += NewLineStatement
    }

    fun add(statement: String) {
        add(statement.toStatement())
    }

    override fun String.`=`(value: String) {
        val key = this
        add(Assignments { key `=` value })
    }

    override fun String.`=`(assignee: Assignee) {
        val key = this
        add(Assignments { key `=` assignee })
    }

    override fun String.`=`(strings: List<String>) {
        val key = this
        add(Assignments { key `=` strings })
    }
}

fun statements(
    loadStrategy: LoadStrategy = LoadStrategy.Top(),
    builder: StatementsBuilder.() -> Unit
): List<Statement> {
    return StatementsBuilder(loadStrategy = loadStrategy).apply(builder).statements
}

fun List<Statement>.asString(): String {
    val sw = StringWriter()
    val pw = PrintWriter(sw)
    forEach { it.write(0, pw) }
    return sw.toString()
}

fun List<Statement>.asAssignee(): Assignee = asString().toStatement()

fun Statement.asString(): String {
    val sw = StringWriter()
    val pw = PrintWriter(sw)
    write(0, pw)
    return sw.toString()
}

fun List<Statement>.writeToFile(file: File) {
    PrintWriter(file).use { printWriter ->
        forEach { statement -> statement.write(0, printWriter) }
        printWriter.flush()
    }
}