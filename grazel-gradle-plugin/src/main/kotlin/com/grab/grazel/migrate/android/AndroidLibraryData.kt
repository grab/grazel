/*
 * Copyright 2022 Grabtaxi Holdings PTE LTD (GRAB)
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

import com.android.builder.core.DefaultApiVersion
import com.grab.grazel.bazel.starlark.Assignee
import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.bazel.starlark.array
import com.grab.grazel.bazel.starlark.glob
import com.grab.grazel.bazel.starlark.quote
import com.grab.grazel.bazel.starlark.toObject

/**
 * Calculate resources for Android targets
 *
 * @param resources resource list come from Android project
 *     resource set
 * @return List of `Assignee` to be used in `resource_files`
 */
internal fun StatementsBuilder.buildResFiles(
    resources: List<String>,
): List<Assignee> {
    return resources.map { glob(array(it.quote)) }
}

internal fun buildResources(
    resDirs: List<String>,
) = if (resDirs.isEmpty()) null else
    Assignee {
        add(
            statement = resDirs
                .groupBy { it }
                .mapValues { emptyMap<String, String>() }
                .toObject(quoteKeys = true, quoteValues = true, allowEmpty = true)
        )
    }

/**
 * Calculate an Android Project's compileSdkVersion from `AppExtension`
 *
 * @param compileSdkVersion The compileSdkVersion from `BaseExtension`.
 * @return The api level. `null` if not found.
 * @see `SdkVersionInfo`
 */
internal fun parseCompileSdkVersion(compileSdkVersion: String?): Int? {
    return if (compileSdkVersion != null) {
        // Match formats `android-30`
        if ("android-\\d\\d".toRegex() matches compileSdkVersion) {
            return compileSdkVersion.split("-").last().toInt()
        }
        // Fallback to querying from AGP Apis
        DefaultApiVersion.create(compileSdkVersion).apiLevel
    } else null
}