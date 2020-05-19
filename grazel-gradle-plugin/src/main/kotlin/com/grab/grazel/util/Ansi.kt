/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.util

import com.grab.grazel.util.AnsiColor.CYAN
import com.grab.grazel.util.AnsiColor.GREEN
import com.grab.grazel.util.AnsiColor.PURPLE
import com.grab.grazel.util.AnsiColor.RED
import com.grab.grazel.util.AnsiColor.RESET
import com.grab.grazel.util.AnsiColor.WHITE
import com.grab.grazel.util.AnsiColor.YELLOW

private enum class AnsiColor(val value: String) {
    RESET("\u001B[0m"),
    RED("\u001B[31m"),
    GREEN("\u001B[32m"),
    YELLOW("\u001B[33m"),
    PURPLE("\u001B[35m"),
    CYAN("\u001B[36m"),
    WHITE("\u001B[37m")
}

private fun String.ansiWrap(ansiColor: AnsiColor) = ansiColor.value + this + RESET.value

internal val String.ansiRed get() = ansiWrap(RED)
internal val String.ansiGreen get() = ansiWrap(GREEN)
internal val String.ansiYellow get() = ansiWrap(YELLOW)
internal val String.ansiPurple get() = ansiWrap(PURPLE)
internal val String.ansiCyan get() = ansiWrap(CYAN)
internal val String.ansiWhite get() = ansiWrap(WHITE)