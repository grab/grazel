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

import com.android.build.api.variant.Variant
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.internal.api.BaseVariantImpl
import com.android.builder.internal.ClassFieldImpl
import com.android.builder.model.ClassField
import com.grab.grazel.bazel.starlark.quote
import com.grab.grazel.gradle.isAndroidApplication
import com.grab.grazel.util.fieldValue
import org.gradle.api.Project

internal data class BuildConfigData(
    val packageName: String? = null,
    val strings: Map<String, String> = emptyMap(),
    val booleans: Map<String, String> = emptyMap(),
    val ints: Map<String, String> = emptyMap(),
    val longs: Map<String, String> = emptyMap()
)

internal fun BaseExtension.extractBuildConfig(
    project: Project,
    variant: BaseVariant
): BuildConfigData {
    val packageName = defaultConfig.applicationId
    val buildConfigFields: Map<String, ClassField> = (
        variant.buildType?.buildConfigFields ?: emptyMap()
        ) +
        defaultConfig.buildConfigFields.toMap() +
        project.androidBinaryBuildConfigFields(this) +
        variant.extractBuildConfigWithVariantApi()
    val buildConfigTypeMap = buildConfigFields
        .asSequence()
        .map { it.value }
        .groupBy(
            keySelector = { it.type },
            valueTransform = { it.name to it.value }
        ).mapValues { it.value.toMap() }
        .withDefault { emptyMap() }
    return BuildConfigData(
        packageName = packageName,
        strings = buildConfigTypeMap.getValue("String"),
        booleans = buildConfigTypeMap.getValue("boolean"),
        ints = buildConfigTypeMap.getValue("int"),
        longs = buildConfigTypeMap.getValue("long")
    )
}

private const val VERSION_CODE = "VERSION_CODE"
private const val VERSION_NAME = "VERSION_NAME"

/**
 * Android binary target alone might have extra properties like VERSION_NAME and VERSION_CODE, this function extracts
 * them if the given project is a android binary target
 */
private fun Project.androidBinaryBuildConfigFields(
    extension: BaseExtension
): Map<String, ClassField> = if (isAndroidApplication) {
    val versionCode = extension.defaultConfig.versionCode
    val versionName = extension.defaultConfig.versionName
    mapOf(
        VERSION_CODE to ClassFieldImpl("int", VERSION_CODE, versionCode.toString()),
        VERSION_NAME to ClassFieldImpl("String", VERSION_NAME, versionName.toString().quote())
    )
} else emptyMap()

/**
 * Build config modifications done via the new Variant APIs are not reflected in the [BaseVariant]
 * API and at the same time, new Variants API in AGP i.e `androidComponents {}` extension does not
 * expose extensions currently to read properties. This is unfortunate and until an API is exposed
 * at least as read only property we have to rely on reflection to access the new Variant API.
 */
private fun BaseVariant.extractBuildConfigWithVariantApi(): Map<String, ClassField> {
    try {
        val variantImpl = this as? BaseVariantImpl
        return variantImpl
            ?.fieldValue<Variant>("component")
            ?.buildConfigFields?.get()
            ?.mapValues { (key, value) -> ClassFieldImpl(value.type, key, value.value.toString()) }
            ?: emptyMap()
    } catch (ignored: Exception) {
    }
    return emptyMap()
}