/*
 * Copyright 2023 Grabtaxi Holdings PTE LTD (GRAB)
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
 * Extension to configure hybrid builds.
 */
data class HybridExtension(
    private val objects: ObjectFactory,
    /**
     * When set to true, will attempt to do hybrid build by running bazel build on all available
     * targets during configuration phase and then replace gradle project dependencies with bazel
     * build artifacts.
     *
     * Note: Not optimized for performance and only used for ensuring correctness.
     */
    val enabled: Property<Boolean> = objects
        .property<Boolean>()
        .convention(false)
)