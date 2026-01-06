/*
 * Copyright 2024 Grabtaxi Holdings PTE LTD (GRAB)
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

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.property

/**
 * Additional experiments configuration
 */
data class ExperimentsExtension(private val objects: ObjectFactory) {

    /**
     * Limits the no of concurrent Gradle dependency resolution requests by establishing inter task dependencies
     * mirroring project dependency graph such that successors are always resolved first before predecessor
     * project is resolved. This is useful for large project with large dependency which can be memory intensive
     * to compute.
     *
     * Enabling this does not actually control the no of parallel requests, for that
     * please use `--max-workers` property from Gradle.
     */
    val limitDependencyResolutionParallelism: Property<Boolean> = objects
        .property<Boolean>()
        .convention(false)

    /**
     * Workaround for Bazel 7.x compatibility with rules that support the `min_sdk_version` attribute.
     *
     * When enabled, explicitly sets `min_sdk_version = 0` on `android_binary` and
     * `android_instrumentation_binary` targets. This prevents the `--min_sdk_version` flag from
     * being passed to dexmerger actions, which would cause build failures on Bazel 7.x where
     * dexmerger does not recognize this flag.
     *
     * Background: Bazel 8 introduced forwarding `min_sdk_version` to dexmerger actions via
     * `--min_sdk_version` flag (only when value > 0). Rules updated for Bazel 8 compatibility
     * may generate this attribute, but Bazel 7.x dexmerger rejects it as an unknown flag.
     * Setting the value to 0 suppresses flag generation while still allowing the attribute
     * to be present in BUILD files.
     *
     * This workaround should be removed once the project fully migrates to Bazel 8.
     *
     * @see <a href="https://github.com/bazelbuild/bazel/commit/6a1ee8984f1b893c32689ee6aef543e00e462205">Bazel commit</a>
     */
    val minSdkVersionWorkaround: Property<Boolean> = objects
        .property<Boolean>()
        .convention(false)
}