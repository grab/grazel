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

package com.grab.grazel.migrate.android

import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.asString
import com.grab.grazel.bazel.starlark.statements
import com.grab.grazel.util.truth
import org.junit.Test

class AndroidTestTargetTest {

    @Test
    fun `emits tags for standalone android test target`() {
        val target = AndroidTestTarget(
            name = "sample-test-debug",
            srcs = listOf("src/main/java/**/*.kt"),
            deps = listOf(
                BazelDependency.MavenDependency(
                    group = "androidx.test",
                    name = "runner"
                )
            ),
            tags = listOf(
                "@maven//:androidx_test_runner",
                "@self//sample-test-debug"
            ),
            packageName = "com.example.app",
            associates = emptyList(),
            instruments = BazelDependency.StringDependency("//app:app-debug"),
            customPackage = "com.example.app.tests",
            targetPackage = "com.example.app",
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner",
            manifestValues = emptyMap(),
            debugKey = null,
            resources = emptyList(),
            resourceFiles = emptyList(),
            resourceStripPrefix = null,
        )

        val generated = statements {
            target.statements(this)
        }.asString()

        generated.truth {
            contains("tags = [")
            contains("@maven//:androidx_test_runner")
            contains("@self//sample-test-debug")
        }
    }
}
