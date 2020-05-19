/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.migrate.android

import com.android.build.gradle.BaseExtension
import com.android.builder.internal.ClassFieldImpl
import com.android.builder.model.ClassField
import com.grab.grazel.bazel.starlark.quote
import com.grab.grazel.gradle.AndroidBuildVariantDataSource
import com.grab.grazel.gradle.isAndroidApplication
import org.gradle.api.Project

internal data class BuildConfigData(
    val strings: Map<String, String> = emptyMap(),
    val booleans: Map<String, String> = emptyMap(),
    val ints: Map<String, String> = emptyMap(),
    val longs: Map<String, String> = emptyMap()
)

internal fun BaseExtension.extractBuildConfig(
    project: Project,
    androidBuildVariantDataSource: AndroidBuildVariantDataSource
): BuildConfigData {
    val buildConfigFields: Map<String, ClassField> = (androidBuildVariantDataSource
        .getMigratableVariants(project)
        .firstOrNull()?.buildType?.buildConfigFields
        ?: emptyMap()) +
            defaultConfig.buildConfigFields.toMap() +
            project.androidBinaryBuildConfigFields(this)
    val buildConfigTypeMap = buildConfigFields
        .asSequence()
        .map { it.value }
        .groupBy(
            keySelector = { it.type },
            valueTransform = { it.name to it.value }
        ).mapValues { it.value.toMap() }
        .withDefault { emptyMap() }
    return BuildConfigData(
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
    // TODO Should we check flavors too?
    mapOf(
        VERSION_CODE to ClassFieldImpl("int", VERSION_CODE, versionCode.toString()),
        VERSION_NAME to ClassFieldImpl("String", VERSION_NAME, versionName.toString().quote())
    )
} else emptyMap()
