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