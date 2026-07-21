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

package com.grab.grazel.android.sample.testutil

import com.squareup.moshi.Moshi

/**
 * Test-only fixture consumed by `sample-android-library`'s unit test via
 * `testImplementation project(":sample-android-test-util")`.
 *
 * This module is reachable from nowhere else in the sample graph, guarding the
 * referenced-but-unreached generation path: its own `BUILD.bazel` must be generated
 * because the sample lib's `android_unit_test` depends on it.
 */
object TestUtil {
    fun fixtureId(): String = "test-util"

    /** Exercises the module's maven dependency so it resolves for a referenced-but-unreached module. */
    fun serialize(value: String): String =
        Moshi.Builder().build().adapter(String::class.java).toJson(value)
}
