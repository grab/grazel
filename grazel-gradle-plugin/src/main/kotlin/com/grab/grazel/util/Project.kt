/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.util

import org.gradle.api.Project
import java.io.FileInputStream
import java.util.*

internal fun Project.localProperties(): Properties {
    val localPropertiesFile = project.rootProject.file("local.properties")
    val localProperties = Properties()
    if (localPropertiesFile.exists()) {
        FileInputStream(localPropertiesFile).use(localProperties::load)
    }
    return localProperties
}

internal fun Project.booleanProperty(
    name: String,
    localProperties: Properties = Properties()
): Boolean {
    val local = localProperties.getOrDefault(name, "false").toString().toBoolean()
    val gradleProperty = findProperty(name)?.toString()?.toBoolean() ?: false
    return local || gradleProperty
}