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

package com.grab.grazel.build.properties

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import java.util.Properties


internal const val LOCAL_PROPERTIES = "local.properties"

public val Project.localProperties: Provider<Properties>
    get() = provider {
        rootProject
            .file(LOCAL_PROPERTIES)
            .inputStream()
            .use { stream -> Properties().apply { load(stream) } }
    }

public fun Project.localProperty(key: String): Provider<String?> =
    localProperties.map { it.getProperty(key) }

public fun Project.properties(key: String): Provider<String> = providers.gradleProperty(key)
public fun Project.environment(key: String): Provider<String> = providers.environmentVariable(key)

public fun Project.localOrEnvProperty(key: String): Provider<String?> =
    localProperty(key).orElse(environment(key))
