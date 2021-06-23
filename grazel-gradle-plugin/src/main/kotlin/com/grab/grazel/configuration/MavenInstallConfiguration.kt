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

package com.grab.grazel.configuration

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.kotlin.dsl.listProperty

/**
 * Configuration for [rules_jvm_external](github.com/bazelbuild/rules_jvm_external)'s maven_install rule.
 */
data class MavenInstallConfiguration(
    private val objects: ObjectFactory,
    var resolveTimeout: Int = 600,
    var excludeArtifacts: ListProperty<String> = objects.listProperty(),
    var jetifyIncludeList: ListProperty<String> = objects.listProperty(),
    var jetifyExcludeList: ListProperty<String> = objects.listProperty()
)