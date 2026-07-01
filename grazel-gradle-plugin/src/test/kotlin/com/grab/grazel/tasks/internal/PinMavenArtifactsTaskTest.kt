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

package com.grab.grazel.tasks.internal

import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import kotlin.test.Test
import kotlin.test.assertEquals

class PinMavenArtifactsTaskTest {

    @Test
    fun `local maven fact gavs include direct pin inputs and their closure dependencies`() {
        val processor = ResolvedDependency
            .fromId("com.squareup.moshi:moshi-kotlin-codegen:1.15.0", "ksp_maven")
            .copy(
                dependencies = setOf(
                    "com.squareup:kotlinpoet:1.13.2:MavenRepo:false:null",
                    "org.jetbrains.kotlin:kotlin-stdlib:1.8.21:MavenRepo:false:null"
                )
            )

        val gavs = localMavenResolutionFactGavs(
            mapOf("ksp_maven" to listOf(processor))
        )

        assertEquals(
            setOf(
                "com.squareup.moshi:moshi-kotlin-codegen:1.15.0",
                "com.squareup:kotlinpoet:1.13.2",
                "org.jetbrains.kotlin:kotlin-stdlib:1.8.21"
            ),
            gavs
        )
    }
}
