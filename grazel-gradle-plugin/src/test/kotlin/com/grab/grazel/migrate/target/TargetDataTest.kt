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

package com.grab.grazel.migrate.target

import com.grab.grazel.bazel.starlark.BazelDependency.ProjectDependency
import com.grab.grazel.buildProject
import com.grab.grazel.fake.FakeVariant
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.migrate.android.AndroidBinaryData
import com.grab.grazel.migrate.android.AndroidLibraryData
import com.grab.grazel.migrate.android.LintConfigData
import org.junit.Assert.assertEquals
import org.junit.Test

class TargetDataTest {

    @Test
    fun `android library target data maps deps into reference facts`() {
        val rootProject = buildProject("root")
        val depProject = buildProject("lib-dep", rootProject)

        val facts = AndroidLibraryTargetData(
            data = AndroidLibraryData(
                name = "mylib",
                customPackage = "com.example",
                packageName = "com.example",
                deps = listOf(ProjectDependency(depProject, suffix = "-debug")),
                lintConfigData = LintConfigData()
            )
        ).referenceFacts()

        assertEquals(mapOf(":lib-dep" to setOf("lib-dep-debug")), facts.projectTargets)
    }

    @Test
    fun `android binary target data merges library and binary deps`() {
        val rootProject = buildProject("root")
        val libDep = buildProject("from-library", rootProject)
        val binDep = buildProject("from-binary", rootProject)

        val facts = AndroidBinaryVariantTargetData(
            matchedVariant = MatchedVariant(
                variantName = "debug",
                flavors = emptySet(),
                buildType = "debug",
                variant = FakeVariant("debug")
            ),
            libraryData = AndroidLibraryData(
                name = "mylib",
                customPackage = "com.example",
                packageName = "com.example",
                deps = listOf(ProjectDependency(libDep, suffix = "-debug")),
                lintConfigData = LintConfigData()
            ),
            binaryData = AndroidBinaryData(
                name = "myapp",
                customPackage = "com.example",
                packageName = "com.example",
                deps = listOf(ProjectDependency(binDep, suffix = "-debug")),
                lintConfigData = LintConfigData()
            )
        ).referenceFacts()

        assertEquals(
            mapOf(
                ":from-library" to setOf("from-library-debug"),
                ":from-binary" to setOf("from-binary-debug")
            ),
            facts.projectTargets
        )
    }
}
