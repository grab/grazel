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
    /**
     * Reproduces Starlark's `repr()` text format (`True`/`False`/`None`, `[...]`/`{...}` literal
     * syntax with `, ` separators) exactly, because the resulting string - not the [StarlarkValue]
     * structure - is what gets hashed via [String.hashCode] in [hash] to emulate Bazel/RJE's own
     * repr-based hashing of Starlark values. The string format is therefore load-bearing rather
     * than cosmetic: any deviation (extra/missing space, different separator) changes the hash even
     * though the represented value is unchanged.
     */
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

/**
 * String escaping matching RJE/Bazel's specific conventions, not standard JSON escaping: control
 * characters below 0x20 (other than \r, \n, \t) are emitted as `\xHH` rather than JSON's
 * six-hex-digit backslash-u (`u00HH`) form.
 * This function feeds both the rendered maven_install.json ([RulesJvmExternalLockfileRenderer]) and
 * Starlark reprs ([StarlarkRepr.render]) that get hashed, so using standard backslash-u-style escaping
 * here would produce byte-different output/hashes from what rules_jvm_external itself generates for
 * any string containing such characters.
 */
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
