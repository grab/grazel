/*
 * Copyright 2026 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.gradle.variant

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Executable proof that the shadow-extraction divergent state is unconstructible:
 * `reachableCompressedTargetSuffixes` draws only from `variantToSuffix.values`, and this
 * invariant guarantees every such suffix is a `targetsBySuffix` key. Therefore a non-empty
 * reachable-suffix set can never filter `targetsBySuffix` down to empty — the extractor's
 * old `takeUnless(isEmpty)` fallback was dead code, deleted by the selectData refactor.
 * If this test ever fails, that deletion is no longer safe.
 */
class VariantCompressionResultInvariantTest {

    @Test
    fun `construction rejects variant mappings pointing at absent target suffixes`() {
        assertThrows(IllegalArgumentException::class.java) {
            VariantCompressionResult(
                targetsBySuffix = emptyMap(),
                variantToSuffix = mapOf("paidDebug" to "-debug"),
                expandedBuildTypes = emptySet()
            )
        }
    }
}
