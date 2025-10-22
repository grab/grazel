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

package com.grab.grazel.migrate.android

import com.grab.grazel.bazel.starlark.BazelDependency

/**
 * Data class representing an Android instrumentation test target (android_local_test).
 *
 * This is used for migrating com.android.test modules to Bazel android_local_test rules.
 *
 * @property name The name of the test target
 * @property srcs List of source file patterns for the test
 * @property deps List of dependencies required by the test
 * @property instruments The target being instrumented/tested (external project reference)
 * @property customPackage The custom package name for the test
 * @property targetPackage The package of the app under test
 * @property testInstrumentationRunner The fully qualified class name of the test runner
 * @property manifestValues Key-value pairs to be injected into the AndroidManifest.xml
 * @property debugKey Optional debug key for signing the test APK
 * @property resources List of resource file patterns
 * @property assets List of asset file patterns
 * @property tags List of tags for the test (e.g., "manual", "no-sandbox")
 * @property visibility List of visibility declarations for the target
 */
data class AndroidTestData(
    val name: String,
    val srcs: List<String>,
    val deps: List<BazelDependency>,
    val instruments: BazelDependency,
    val customPackage: String,
    val targetPackage: String,
    val testInstrumentationRunner: String,
    val manifestValues: Map<String, String>,
    val debugKey: String?,
    val resources: List<String>,
    val assets: List<String>,
    val tags: List<String>,
    val visibility: List<String>
)
