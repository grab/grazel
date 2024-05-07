/*
 * Copyright 2024 Grabtaxi Holdings PTE LTD (GRAB)
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

import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.StarlarkMapEntry
import com.grab.grazel.bazel.starlark.quote
import java.util.Locale

data class LintConfigData(
    val enabled: Boolean = true,
    val lintConfig: BazelDependency? = null,
    val baselinePath: String? = null,
    val lintChecks: List<BazelDependency>? = null
) {
    val merged: List<StarlarkMapEntry> = listOf(
        StarlarkMapEntry(
            name = "enabled",
            value = enabled.toString().capitalize(Locale.ROOT),
            quoteKeys = true,
            quoteValues = false
        ),
        StarlarkMapEntry(
            name = "config",
            value = lintConfig?.toString(),
            quoteKeys = true,
            quoteValues = true
        ),
        StarlarkMapEntry(
            name = "baseline",
            value = baselinePath,
            quoteKeys = true,
            quoteValues = true
        ),
        StarlarkMapEntry(
            name = "lint_checks",
            value = if (lintChecks != null) {
                "[${lintChecks.joinToString(",", transform = BazelDependency::quote)}]"
            } else {
                null
            },
            quoteKeys = true,
            quoteValues = false
        )
    ).filter { it.value != null }
}