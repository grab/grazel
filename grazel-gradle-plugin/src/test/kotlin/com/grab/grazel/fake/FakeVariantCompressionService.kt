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

package com.grab.grazel.fake

import com.grab.grazel.gradle.variant.VariantCompressionResult
import com.grab.grazel.gradle.variant.DefaultVariantCompressionService
import com.grab.grazel.gradle.variant.VariantCompressionService

/**
 * A test fake for [VariantCompressionService] that stores pre-configured compression results.
 *
 * Usage:
 * ```kotlin
 * val fakeService = FakeVariantCompressionService()
 * fakeService.register(":app", compressionResult)
 * val provider = project.provider { fakeService }
 * ```
 */
internal class FakeVariantCompressionService(
    private val results: MutableMap<String, VariantCompressionResult> = mutableMapOf()
) : DefaultVariantCompressionService() {

    override fun register(projectPath: String, result: VariantCompressionResult) {
        results[projectPath] = result
    }

    override fun get(projectPath: String): VariantCompressionResult? {
        return results[projectPath]
    }

    override fun isRegistered(projectPath: String): Boolean {
        return projectPath in results
    }

    override fun close() {
        results.clear()
    }

    override fun getParameters(): VariantCompressionService.Params {
        throw UnsupportedOperationException("Not needed for tests")
    }
}
