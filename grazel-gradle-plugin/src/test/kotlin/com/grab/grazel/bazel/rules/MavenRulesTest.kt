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

package com.grab.grazel.bazel.rules

import com.grab.grazel.bazel.starlark.LoadStrategy
import com.grab.grazel.bazel.starlark.asString
import com.grab.grazel.bazel.starlark.statements
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class MavenRulesTest {
    @Test
    fun `assert unpinned maven install does not call pinned maven install`() {
        val workspace = statements(loadStrategy = LoadStrategy.Inline()) {
            mavenInstall(
                name = "debug_maven",
                rulesJvmExternalName = "rules_jvm_external",
                artifacts = setOf(
                    MavenInstallArtifact.SimpleArtifact("com.example:debug-only:1.0")
                ),
                artifactPinning = true,
                mavenInstallJson = "debug_maven_install.json",
                mavenInstallJsonEnabled = false,
            )
        }.asString()

        assertTrue("#maven_install_json" in workspace)
        assertFalse("debug_maven_pinned_maven_install()" in workspace)
    }

    @Test
    fun `assert pinned maven install calls pinned maven install when json is enabled`() {
        val workspace = statements(loadStrategy = LoadStrategy.Inline()) {
            mavenInstall(
                name = "debug_maven",
                rulesJvmExternalName = "rules_jvm_external",
                artifacts = setOf(
                    MavenInstallArtifact.SimpleArtifact("com.example:debug-only:1.0")
                ),
                artifactPinning = true,
                mavenInstallJson = "debug_maven_install.json",
                mavenInstallJsonEnabled = true,
            )
        }.asString()

        assertTrue("maven_install_json" in workspace)
        assertTrue("debug_maven_pinned_maven_install()" in workspace)
    }
}
