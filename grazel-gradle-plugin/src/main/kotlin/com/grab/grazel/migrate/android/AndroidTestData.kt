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
 * Implements AndroidData interface for consistency with other Android targets.
 *
 * Fields inherited from AndroidData:
 * @param name The name of the test target
 * @param srcs List of source file patterns for the test
 * @param resourceSets Set of resource sets (constructed from resourceFiles and assets)
 * @param resValuesData Resource values data (not used for tests, empty default)
 * @param manifestFile Manifest file (not used for tests, null)
 * @param customPackage The custom package name for the test
 * @param packageName The package of the app under test (same as targetPackage)
 * @param buildConfigData Build config data (not used for tests, empty default)
 * @param deps List of dependencies required by the test
 * @param plugins List of plugins (not used for tests, empty)
 * @param compose Whether Jetpack Compose is enabled for this test
 * @param databinding Whether data binding is enabled (not used for tests, false)
 * @param tags List of tags for the test (e.g., "manual", "no-sandbox")
 * @param lintConfigData Lint configuration (not used for tests, empty default)
 *
 * Test-specific fields:
 * @param associates List of associated library targets (allows test to access app internals)
 * @param instruments The target being instrumented/tested (external project reference)
 * @param targetPackage The package of the app under test (also mapped to packageName)
 * @param testInstrumentationRunner The fully qualified class name of the test runner
 * @param manifestValues Key-value pairs to be injected into the AndroidManifest.xml
 * @param debugKey Optional debug key for signing the test APK
 * @param resources List of Java/Kotlin test resource file patterns (src/test/resources)
 * @param resourceFiles List of Android resource file patterns (res/layout, res/values, etc.)
 * @param resourceStripPrefix Optional prefix to strip from resource paths
 * @param assets List of asset file patterns
 */
internal data class AndroidTestData(
    override val name: String,
    override val srcs: List<String>,
    override val resourceSets: Set<BazelSourceSet>,
    override val resValuesData: ResValuesData = ResValuesData(),
    override val manifestFile: String? = null,
    override val customPackage: String,
    override val packageName: String, // Same as targetPackage for tests
    override val buildConfigData: BuildConfigData = BuildConfigData(),
    override val deps: List<BazelDependency>,
    override val plugins: List<BazelDependency> = emptyList(),
    override val compose: Boolean,
    override val databinding: Boolean = false,
    override val tags: List<String>,
    override val lintConfigData: LintConfigData = LintConfigData(),
    // Test-specific fields
    val associates: List<BazelDependency>,
    val instruments: BazelDependency,
    val targetPackage: String, // Kept for clarity, same as packageName
    val testInstrumentationRunner: String,
    val manifestValues: Map<String, String?>,
    val debugKey: String?,
    val resources: List<String>, // Java/Kotlin resources
    val resourceFiles: List<String>, // Android resources
    val resourceStripPrefix: String?,
    val assets: List<String>
) : AndroidData

