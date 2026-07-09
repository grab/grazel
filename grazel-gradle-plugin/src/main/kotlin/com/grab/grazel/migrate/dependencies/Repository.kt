/*
 * Copyright 2023 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.migrate.dependencies

import com.grab.grazel.gradle.variant.DEFAULT_VARIANT

/** Name of the aggregated `maven_install` repository that the default variant maps to. */
const val BASE_MAVEN_REPO = "maven"

/** Label prefix of a compile-filter tag pointing at the aggregated `@maven` repository. */
const val MAVEN_COMPILE_FILTER_TAG_PREFIX = "@$BASE_MAVEN_REPO//:"

fun String.toMavenRepoName() = when (this) {
    DEFAULT_VARIANT -> BASE_MAVEN_REPO
    else -> replace("([a-z])([A-Z]+)".toRegex(), "\$1_\$2")
        .toLowerCase() + "_maven"
}

fun String.toMaterializedMavenRepoName() = when {
    this == BASE_MAVEN_REPO || endsWith("_maven") -> this
    else -> toMavenRepoName()
}
