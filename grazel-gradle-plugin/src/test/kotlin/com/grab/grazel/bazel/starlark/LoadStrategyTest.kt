package com.grab.grazel.bazel.starlark

import com.grab.grazel.bazel.rules.rule
import com.grab.grazel.bazel.starlark.LoadStrategy.Inline
import com.grab.grazel.bazel.starlark.LoadStrategy.Top
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoadStrategyTest {

    private fun testStatements(loadStrategy: LoadStrategy) = statements(loadStrategy) {
        load(":test.bzl", "test_lib")
        rule("test_lib") {
            "name" `=` "test"
        }
        load(":test.bzl", "test_bin")
        rule("test_bin") {
            "name" `=` "test"
        }
    }

    @Test
    fun `assert inline load statements are added for INLINE strategy`() {
        val statements = testStatements(loadStrategy = Inline)
        assertEquals(8, statements.size)
        assertTrue("Load statements are added inline") {
            arrayOf(0, 4).all { index ->
                statements[index].let { it is FunctionStatement && it.name == "load" }
            }
        }
    }

    @Test
    fun `assert load statements are added at the top for TOP strategy`() {
        val statements = testStatements(loadStrategy = Top())
        assertEquals(5, statements.size)
        assertTrue("Load statements are added at the top") {
            statements[0].let { it is FunctionStatement && it.name == "load" }
        }
        assertTrue("Related symbols from the same file are merged") {
            statements[0].asString().contains("""load(":test.bzl",  "test_lib",  "test_bin")""")
        }
        assertTrue("Load statements are not present in inline") {
            statements.filterIsInstance<FunctionStatement>().filter { it.name == "load" }.size == 1
        }
    }

    @Test
    fun `assert nested statements call carry the load strategy context in TOP strategy`() {
        val statements = statements {
            load(":test.bzl", "test_lib")
            statements {
                load(":test.bzl", "test_bin")
            }
        }
        assertEquals(1, statements.size)
        assertTrue("Related symbols from the same file are merged") {
            statements.asString().contains("""load(":test.bzl",  "test_lib",  "test_bin")""")
        }
    }
}

