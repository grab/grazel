package com.grab.grazel.util

import kotlin.test.assertEquals
import kotlin.test.assertFails

fun assertErrorMessage(description: String, error: String, block: () -> Unit) {
    val e = assertFails(description, block)
    assertEquals(error, e.message, description)
}