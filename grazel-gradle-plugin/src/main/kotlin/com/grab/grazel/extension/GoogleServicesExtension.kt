/*
 * Copyright 2021 Grabtaxi Holdings PTE LTD (GRAB)
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

internal const val TOOLS_ANDROID = "tools_android"
internal const val TOOLS_ANDROID_COMMIT = "58d67fd54a3b7f5f1e6ddfa865442db23a60e1b6"
internal const val TOOLS_ANDROID_SHA =
    "a192553d52a42df306437a8166fc6b5ec043282ac4f72e96999ae845ece6812f"

internal val TOOLS_ANDROID_REPOSITORY = HttpArchiveRule(
    name = TOOLS_ANDROID,
    sha256 = TOOLS_ANDROID_SHA,
    stripPrefix = "tools_android-$TOOLS_ANDROID_COMMIT",
    url = """https://github.com/bazelbuild/tools_android/archive/$TOOLS_ANDROID_COMMIT.tar.gz"""
)

data class GoogleServicesExtension(
    val crashlytics: CrashlyticsExtension = CrashlyticsExtension(),
    var repository: BazelRepositoryRule = TOOLS_ANDROID_REPOSITORY,
) {
    fun crashlytics(block: CrashlyticsExtension.() -> Unit) {
        block(crashlytics)
    }

    fun crashlytics(closure: Closure<*>) {
        closure.delegate = crashlytics
        closure.call()
    }

    fun gitRepository(closure: Closure<*>) {
        repository = GitRepositoryRule(name = repository.name, remote = "")
        closure.delegate = repository
        closure.call()
    }

    fun gitRepository(builder: GitRepositoryRule.() -> Unit) {
        repository = GitRepositoryRule(name = repository.name, remote = "").apply(builder)
    }

    /**
     * Configure an HTTP Archive for `tools_android`.
     *
     * @param closure closure called with default value set to
     *     [TOOLS_ANDROID_REPOSITORY]
     */
    fun httpArchiveRepository(closure: Closure<*>) {
        repository = TOOLS_ANDROID_REPOSITORY
        closure.delegate = repository
        closure.call()
    }

    /**
     * Configure an HTTP Archive for `tools_android`.
     *
     * @param builder Builder called with default value of
     *     [TOOLS_ANDROID_REPOSITORY]
     */
    fun httpArchiveRepository(builder: HttpArchiveRule.() -> Unit) {
        repository = TOOLS_ANDROID_REPOSITORY.apply(builder)
    }
}

data class CrashlyticsExtension(
    var buildId: String = "042cb4d8-56f8-41a0-916a-9da28e94d1bc" // Default build id
)