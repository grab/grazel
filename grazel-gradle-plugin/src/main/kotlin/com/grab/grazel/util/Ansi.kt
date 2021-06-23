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