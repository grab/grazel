package com.grab.grazel.bazel.starlark

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class StatementWriterTest {

    @Test
    fun `writeToFile removes trailing separator newlines`() {
        val outputFile = File.createTempFile("starlark-writer", ".bzl")
        try {
            statements {
                filegroup(name = "debug-keystore", srcs = listOf("debug.keystore"))
            }.writeToFile(outputFile)

            assertEquals(
                """
                filegroup(
                  name = "debug-keystore",
                  srcs = [
                    "debug.keystore",
                  ],
                  visibility = [
                    "//visibility:public",
                  ]
                )
                """.trimIndent() + "\n",
                outputFile.readText()
            )
        } finally {
            outputFile.delete()
        }
    }
}
