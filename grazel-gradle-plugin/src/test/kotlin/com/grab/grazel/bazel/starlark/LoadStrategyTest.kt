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
        val statements = testStatements(loadStrategy = Inline())
        assertEquals(8, statements.size)
        assertTrue("Load statements are added inline") {
            arrayOf(0, 4).all { index ->
                statements[index].let { it is FunctionStatement && it.name == "load" }
            }
        }
    }


    @Test
    fun `assert no duplicates are added for INLINE strategy`() {
        val statements = statements(Inline()) {
            load(":test.bzl", "test_lib")
            load(":test.bzl", "test_lib")
            load(":test.bzl", "test_lib", "test_bin")
        }
        assertEquals(
            """
                load(":test.bzl",  "test_lib")
                
                load(":test.bzl",  "test_bin")
                
                
                """.trimIndent(),
            statements.asString(),
            "No duplicates are added for inline strategy"
        )
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

    @Test
    fun `assert load statements are sorted based on file name`() {
        val statements = statements {
            load(":test.bzl", "d")
            statements {
                load(":test.bzl", "a", "t")
                statements {
                    load(":hello.bzl", "y", "u")
                }
            }
        }
        assertEquals(
            expected = statements.asString(),
            actual = """load(":hello.bzl",  "y",  "u")
load(":test.bzl",  "d",  "a",  "t")

""".trimIndent(),
            message = "Load statements are sorted based on file name"
        )
    }

    @Test
    fun `assert aliases load statements are handled with load stategy`() {
        val statements = statements {
            load(":test.bzl") {
                "pinned_maven_install" `=` "pinned_maven_install".quote
            }
            statements {
                load(":test.bzl") {
                    "pinned_maven_install" `=` "pinned_maven_install".quote
                }
            }
        }
        assertEquals(
            expected = statements.asString(),
            actual = """load(":test.bzl",  "pinned_maven_install = "pinned_maven_install"")
""",
            message = "Load statement aliases are handled with load strategy"
        )
    }
}

