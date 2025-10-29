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
 * Data class representing an Android instrumentation test target (android_instrumentation_binary).
 *
 * This is used for migrating com.android.test modules to Bazel android_instrumentation_binary rules.
 *
 * @param name The name of the test target
 * @param srcs List of source file patterns for the test
 * @param deps List of dependencies required by the test
 * @param associates List of associated library targets (allows test to access app internals)
 * @param instruments The target being instrumented/tested (external project reference)
 * @param customPackage The custom package name for the test
 * @param targetPackage The package of the app under test
 * @param testInstrumentationRunner The fully qualified class name of the test runner
 * @param manifestValues Key-value pairs to be injected into the AndroidManifest.xml
 * @param debugKey Optional debug key for signing the test APK
 * @param resources List of Java/Kotlin test resource file patterns (src/test/resources)
 * @param resourceFiles List of Android resource file patterns (res/layout, res/values, etc.)
 * @param resourceStripPrefix Optional prefix to strip from resource paths
 * @param assets List of asset file patterns
 * @param compose Whether Jetpack Compose is enabled for this test
 * @param tags List of tags for the test (e.g., "manual", "no-sandbox")
 * @param visibility List of visibility declarations for the target
 */
data class AndroidTestData(
    val name: String,
    val srcs: List<String>,
    val deps: List<BazelDependency>,
    val associates: List<BazelDependency>,
    val instruments: BazelDependency,
    val customPackage: String,
    val targetPackage: String,
    val testInstrumentationRunner: String,
    val manifestValues: Map<String, String?>,
    val debugKey: String?,
    val resources: List<String>,
    val resourceFiles: List<String>,
    val resourceStripPrefix: String?,
    val assets: List<String>,
    val compose: Boolean,
    val tags: List<String>,
    val visibility: List<String>
)
