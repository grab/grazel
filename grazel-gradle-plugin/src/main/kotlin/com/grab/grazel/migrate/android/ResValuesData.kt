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

import com.android.build.gradle.BaseExtension
import com.android.builder.model.Version.ANDROID_GRADLE_PLUGIN_VERSION
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.util.merge

data class ResValuesData(
    val stringValues: Map<String, String> = emptyMap()
) {
    val isEmpty = stringValues.isEmpty()
    val merged: Map<String, Map<String, String>> by lazy {
        buildMap {
            put(STRINGS, stringValues)
        }
    }

    companion object {
        const val STRINGS = "strings"
    }
}

internal fun BaseExtension.extractResValue(
    matchedVariant: MatchedVariant
): ResValuesData {
    val default = defaultConfig.resValues.mapValues { it.value.value }
    val buildTypes = matchedVariant.variant.buildType.resValues.mapValues { it.value.value }
    val flavors = matchedVariant.variant.productFlavors
        .map { flavor -> flavor.resValues.mapValues { it.value.value } }
        .merge { prev, next -> prev + next }
    return ResValuesData(
        stringValues = (default + buildTypes + flavors).mapKeys { getKeyValue(it.key) }
    )
}

/**
 * Example:
 * constraint: AGP 7.2.2
 * input: string/generated_value
 * output: generated_value
 *
 * constraint: AGP 7.1.2
 * input: generated_value
 * output: generated_value
 */
private fun getKeyValue(key: String): String {
    val agpVersion = ANDROID_GRADLE_PLUGIN_VERSION.split(".")
    val majorVersion = agpVersion[0].toInt()
    val minorVersion = agpVersion[1].toInt()
    return if (majorVersion >= 7 && minorVersion >= 2) {
        key.split("/").last()
    } else {
        key
    }
}
