/*
 * Copyright 2025 Grabtaxi Holdings PTE LTD (GRAB)
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

import com.grab.grazel.bazel.TestSize
import org.gradle.api.model.ObjectFactory

/**
 * Data class containing test metrics used to determine test size.
 *
 * @property targetName The name of the test target
 * @property testsCount The total number of test cases/methods (@Test) in a
 *    test target
 * @property testFileCount The total number of test files/classes in a test
 *    target
 */
data class TestData(
    val targetName: String,
    val testsCount: Int,
    val testFileCount: Int
)

/**
 * Extension to configure test related settings.
 *
 * @property testSizeProvider Function that takes [TestData] and returns a
 *    [TestSize]. Used to calculate the `size` attribute for test targets
 *    in Bazel based on test metrics like number of test cases. Defaults to
 *    MEDIUM size for all tests.
 */
class TestExtension(
    val objects: ObjectFactory,
    var testSizeProvider: (TestData) -> TestSize = { TestSize.MEDIUM },
)