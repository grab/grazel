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

import com.grab.grazel.bazel.rules.GRAB_BAZEL_COMMON
import com.grab.grazel.bazel.rules.GitRepositoryRule
import groovy.lang.Closure

class BazelCommonConfiguration(
    var repository: GitRepositoryRule = GitRepositoryRule(
        name = GRAB_BAZEL_COMMON
    )
) {
    fun gitRepository(closure: Closure<*>) {
        closure.delegate = repository
        closure.call()
    }

    fun gitRepository(builder: GitRepositoryRule.() -> Unit) {
        builder(repository)
    }
}