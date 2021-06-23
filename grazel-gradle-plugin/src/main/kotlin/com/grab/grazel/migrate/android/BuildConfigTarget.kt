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

package com.grab.grazel.migrate.android

import com.grab.grazel.bazel.rules.buildConfig
import com.grab.grazel.bazel.starlark.statements
import com.grab.grazel.migrate.BazelTarget

data class BuildConfigTarget(
    override val name: String,
    val packageName: String,
    val strings: Map<String, String> = emptyMap(),
    val booleans: Map<String, String> = emptyMap(),
    val ints: Map<String, String> = emptyMap(),
    val longs: Map<String, String> = emptyMap()
) : BazelTarget {
    override fun statements() = statements {
        buildConfig(
            name = name,
            packageName = packageName,
            strings = strings,
            booleans = booleans,
            ints = ints,
            longs = longs
        )
    }
}