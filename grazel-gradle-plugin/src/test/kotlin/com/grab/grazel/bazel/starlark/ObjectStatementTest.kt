package com.grab.grazel.bazel.starlark

import com.grab.grazel.util.truth
import org.junit.Test

class ObjectStatementTest {


    @Test
    fun `assert Map toObject returns dict`() {
        val items = mapOf("x" to "y", "a" to "b")
        items.toObject()
            .asString()
            .truth()
            .isEqualTo(
                """{
    x : y,
    a : b,
  }"""
            )

        items.toObject(quoteKeys = true)
            .asString()
            .truth()
            .isEqualTo(
                """{
    "x" : y,
    "a" : b,
  }"""
            )

        items.toObject(quoteKeys = true, quoteValues = true)
            .asString()
            .truth()
            .isEqualTo(
                """{
    "x" : "y",
    "a" : "b",
  }"""
            )
    }

    @Test
    fun `assert Map toObject() is able to recursively expand to dicts of dicts`() {
        val items = mapOf(
            "x" to mapOf("a" to "b"),
            "y" to mapOf("t" to mapOf("y" to "p")),
            "z" to mapOf("t" to mapOf("n" to mapOf("y" to "p")))
        )
        items.toObject()
            .asString()
            .truth()
            .isEqualTo(
                """{
    x : {
    a : b,
  },
    y : {
    t : {
    y : p,
  },
  },
    z : {
    t : {
    n : {
    y : p,
  },
  },
  },
  }"""
            )
    }
}