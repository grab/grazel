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

package com.grab.grazel.sample

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi

/**
 * Data class to test KSP code generation with Moshi in kotlin_library.
 */
@JsonClass(generateAdapter = true)
data class KotlinConfig(
    val name: String,
    val version: Int,
    val enabled: Boolean = true
)

public class HelloWorld {
    /**
     * Verify KSP code generation by using the generated Moshi adapter.
     * This will fail to compile if KSP doesn't generate KotlinConfigJsonAdapter.
     */
    fun verifyKspCodeGeneration(): String {
        val moshi = Moshi.Builder().build()
        val adapter = moshi.adapter(KotlinConfig::class.java)
        return adapter.toJson(KotlinConfig("test", 1))
    }
}
