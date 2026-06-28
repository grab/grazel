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

import com.grab.grazel.bazel.TestSize
import com.grab.grazel.bazel.starlark.BazelDependency.ProjectDependency
import com.grab.grazel.bazel.starlark.BazelDependency.StringDependency
import com.grab.grazel.buildProject
import com.grab.grazel.migrate.android.AndroidInstrumentationBinaryData
import com.grab.grazel.migrate.android.AndroidUnitTestData
import com.grab.grazel.migrate.kotlin.UnitTestData
import org.junit.Assert.assertEquals
import org.junit.Test

class TargetReferenceFactsDataMappingTest {

    @Test
    fun `Android unit test facts include associate project references`() {
        val rootProject = buildProject("root")
        val associateProject = buildProject("support", rootProject)

        val facts = AndroidUnitTestData(
            name = "app_debug_test",
            srcs = emptyList(),
            additionalSrcSets = emptyList(),
            deps = emptyList(),
            tags = emptyList(),
            customPackage = "com.grab.app",
            associates = listOf(
                ProjectDependency(associateProject, suffix = "_debug"),
                StringDependency("//shared/testkit:shared_testkit_debug")
            ),
            resources = emptyList(),
            compose = false,
            testSize = TestSize.MEDIUM
        ).referenceFacts()

        assertEquals(
            mapOf(
                ":shared:testkit" to setOf("shared_testkit_debug"),
                ":support" to setOf("support_debug")
            ),
            facts.projectTargets
        )
    }

    @Test
    fun `Android instrumentation facts include associates and instrumented target`() {
        val rootProject = buildProject("root")
        val appProject = buildProject("app", rootProject)
        val associateProject = buildProject("android-test-support", rootProject)

        val facts = AndroidInstrumentationBinaryData(
            associates = listOf(ProjectDependency(associateProject, suffix = "_debug")),
            customPackage = "com.grab.app.test",
            targetPackage = "com.grab.app",
            deps = emptyList(),
            instruments = ProjectDependency(appProject, suffix = "_debug"),
            name = "app_debug_android_test",
            resourceFiles = emptyList(),
            resources = emptyList(),
            srcs = emptyList(),
            tags = emptyList()
        ).referenceFacts()

        assertEquals(
            mapOf(
                ":android-test-support" to setOf("android-test-support_debug"),
                ":app" to setOf("app_debug")
            ),
            facts.projectTargets
        )
    }

    @Test
    fun `Kotlin unit test facts include associate project references`() {
        val rootProject = buildProject("root")
        val associateProject = buildProject("jvm-support", rootProject)

        val facts = UnitTestData(
            name = "jvm_test",
            srcs = emptyList(),
            additionalSrcSets = emptyList(),
            deps = emptyList(),
            tags = emptyList(),
            associates = listOf(ProjectDependency(associateProject, suffix = "_test")),
            testSize = TestSize.MEDIUM,
            hasAndroidJarDep = false
        ).referenceFacts()

        assertEquals(
            mapOf(":jvm-support" to setOf("jvm-support_test")),
            facts.projectTargets
        )
    }
}
