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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StarlarkReprTest {

    @Test
    fun `renders starlark repr using rje compatible json string escaping`() {
        val value = StarlarkValue.DictValue(
            linkedMapOf(
                "none" to StarlarkValue.None,
                "bool" to StarlarkValue.Bool(true),
                "list" to StarlarkValue.ListValue(
                    listOf(
                        StarlarkValue.StringValue("quote\"slash\\tab\t"),
                        StarlarkValue.IntValue(7)
                    )
                )
            )
        )

        assertThat(StarlarkRepr.render(value))
            .isEqualTo("""{"none": None, "bool": True, "list": ["quote\"slash\\tab\t", 7]}""")
    }

    @Test
    fun `hash delegates to java string hash code for rje compatibility`() {
        assertThat(StarlarkRepr.hash("rje-hash-input"))
            .isEqualTo("rje-hash-input".hashCode())
    }
}
