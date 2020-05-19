/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
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