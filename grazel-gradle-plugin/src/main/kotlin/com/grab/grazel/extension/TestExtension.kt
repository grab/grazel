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

package com.grab.grazel.extension

import com.grab.grazel.bazel.rules.BazelRepositoryRule
import com.grab.grazel.bazel.rules.GitRepositoryRule
import com.grab.grazel.bazel.rules.HttpArchiveRule
import groovy.lang.Closure


private const val DEFAULT_ROBOLECTRIC_VERSION = "4.4"
private const val ROBOLECTRIC = "robolectric"
private const val DEFAULT_ROBOLECTRIC_STRIP_PREFIX = "robolectric-bazel-%s"
private const val DEFAULT_ROBOLECTRIC_URL =
    "https://github.com/robolectric/robolectric-bazel/archive/%s.tar.gz"

private val DEFAULT_ROBOLECTRIC_ARCHIVE = HttpArchiveRule(
    name = ROBOLECTRIC,
    stripPrefix = String.format(DEFAULT_ROBOLECTRIC_STRIP_PREFIX, DEFAULT_ROBOLECTRIC_VERSION),
    url = String.format(DEFAULT_ROBOLECTRIC_URL, DEFAULT_ROBOLECTRIC_VERSION)
)

data class RobolectricExtension(
    var repository: BazelRepositoryRule = DEFAULT_ROBOLECTRIC_ARCHIVE,
    var version: String = DEFAULT_ROBOLECTRIC_VERSION
) {

    fun gitRepository(closure: Closure<*>) {
        repository = GitRepositoryRule(name = ROBOLECTRIC)
        closure.delegate = repository
        closure.call()
    }

    fun gitRepository(builder: GitRepositoryRule.() -> Unit) {
        repository = GitRepositoryRule(name = ROBOLECTRIC).apply(builder)
    }

    fun httpArchiveRepository(closure: Closure<*>) {
        repository = DEFAULT_ROBOLECTRIC_ARCHIVE
        closure.delegate = repository
        closure.call()
    }

    fun httpArchiveRepository(builder: HttpArchiveRule.() -> Unit) {
        repository = DEFAULT_ROBOLECTRIC_ARCHIVE.apply(builder)
    }
}

data class TestExtension(
    var enableTestMigration: Boolean = false,
    var enabledTransitiveReduction: Boolean = false,
    var detectSourceSets: Boolean = false,
)

