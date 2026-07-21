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

package com.grab.grazel.android.sample

import com.grab.grazel.android.sample.testutil.TestUtil
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises the `testImplementation project(":sample-android-test-util")` edge so the
 * `android_unit_test` target emits a `project(...)` dependency on the test-util library,
 * guarding regeneration of a `BUILD.bazel` for a module reachable only via test scope.
 *
 * `TestUtil` itself depends on `sample-android-test-util-dep` (`implementation
 * project(":sample-android-test-util-dep")`), so this also exercises the second hop of the
 * chain - a module reachable from nowhere but a reachable-only-via-test-scope module.
 */
class TestUtilUsageTest {

    @Test
    fun usesTestUtilFixture() {
        // fixtureId() routes through Moshi (the test-util's maven dependency), so the value is
        // JSON-encoded - i.e. wrapped in quotes.
        assertEquals("\"test-util-test-util-dep\"", TestUtil.fixtureId())
    }
}
