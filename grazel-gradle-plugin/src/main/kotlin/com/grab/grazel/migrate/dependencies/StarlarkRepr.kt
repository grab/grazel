/*
 * Copyright 2026 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.migrate.dependencies

internal sealed interface StarlarkValue {
    object None : StarlarkValue
    data class Bool(val value: Boolean) : StarlarkValue
    data class IntValue(val value: Int) : StarlarkValue
    data class StringValue(val value: String) : StarlarkValue
    data class ListValue(val values: List<StarlarkValue>) : StarlarkValue
    data class DictValue(val entries: Map<String, StarlarkValue>) : StarlarkValue
}

internal object StarlarkRepr {
    fun render(value: StarlarkValue): String {
        return when (value) {
            StarlarkValue.None -> "None"
            is StarlarkValue.Bool -> if (value.value) "True" else "False"
            is StarlarkValue.IntValue -> value.value.toString()
            is StarlarkValue.StringValue -> rjeJsonString(value.value)
            is StarlarkValue.ListValue -> value.values.joinToString(
                separator = ", ",
                prefix = "[",
                postfix = "]",
                transform = ::render
            )

            is StarlarkValue.DictValue -> value.entries.entries.joinToString(
                separator = ", ",
                prefix = "{",
                postfix = "}"
            ) { (key, item) ->
                "${rjeJsonString(key)}: ${render(item)}"
            }
        }
    }

    fun hash(value: String): Int = value.hashCode()
}

internal fun rjeJsonString(value: String): String {
    return buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\r' -> append("\\r")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                else -> {
                    if (char.code in 0x00..0x1F) {
                        append("\\x")
                        append(char.code.toString(16).padStart(2, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
        append('"')
    }
}
