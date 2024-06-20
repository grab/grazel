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
import java.util.Locale

class DetektConfigData(
    val enabled: Boolean? = null,
    val baseline: String? = null,
    val configs: List<BazelDependency.FileDependency> = emptyList(),
    val debug: Boolean? = null,
    val parallel: Boolean? = null,
    val allRules: Boolean? = null,// activate all available (even unstable) rules.
    val buildUponDefaultConfig: Boolean? = null,
    val disableDefaultRuleSets: Boolean? = null,
    val autoCorrect: Boolean? = null,
    val detektChecks: List<BazelDependency>? = null,
) {

    val merged: List<StarlarkMapEntry> = listOf(
        StarlarkMapEntry(
            name = "enabled",
            value = enabled?.toString()?.capitalize(Locale.ROOT),
            quoteKeys = false,
            quoteValues = false
        ),
        StarlarkMapEntry(
            name = "baseline",
            value = baseline,
            quoteKeys = false,
            quoteValues = true
        ),
        StarlarkMapEntry(
            name = "cfgs",
            value = configs(),
            quoteKeys = false,
            quoteValues = false
        ),
        StarlarkMapEntry(
            name = "debug",
            value = debug?.toString()?.capitalize(Locale.ROOT),
            quoteKeys = false,
            quoteValues = false
        ),
        StarlarkMapEntry(
            name = "parallel",
            value = parallel?.toString()?.capitalize(Locale.ROOT),
            quoteKeys = false,
            quoteValues = false
        ),
        StarlarkMapEntry(
            name = "all_rules",
            value = allRules?.toString()?.capitalize(Locale.ROOT),
            quoteKeys = false,
            quoteValues = false
        ),
        StarlarkMapEntry(
            name = "build_upon_default_config",
            value = buildUponDefaultConfig?.toString()?.capitalize(Locale.ROOT),
            quoteKeys = false,
            quoteValues = false
        ),
        StarlarkMapEntry(
            name = "disable_default_rule_sets",
            value = disableDefaultRuleSets?.toString()?.capitalize(Locale.ROOT),
            quoteKeys = false,
            quoteValues = false
        ),
        StarlarkMapEntry(
            name = "auto_correct",
            value = autoCorrect?.toString()?.capitalize(Locale.ROOT),
            quoteKeys = false,
            quoteValues = false
        ),
        StarlarkMapEntry(
            name = "detekt_checks",
            value = detektPlugins(),
            quoteKeys = false,
            quoteValues = false
        )
    ).filter { it.value != null }

    private fun configs() = if (configs.isNotEmpty()) {
        "[" + configs.joinToString { "\"$it\"" } + "]"
    } else {
        null
    }

    private fun detektPlugins(): String? {
        if (detektChecks.isNullOrEmpty()) return null
        return "[" + detektChecks.joinToString(",") {
            if (it is BazelDependency.StringDependency) {
                "\"//$it\""
            } else {
                "\"${it}\""
            }
        } + "]"
    }
}