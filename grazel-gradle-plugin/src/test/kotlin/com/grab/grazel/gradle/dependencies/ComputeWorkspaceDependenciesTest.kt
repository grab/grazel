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

package com.grab.grazel.gradle.dependencies

import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.gradle.dependencies.model.ExcludeRule
import com.grab.grazel.gradle.dependencies.model.OverrideTarget
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.COMPILE
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.KSP
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import org.junit.Assert.assertEquals
import org.junit.Test

class ComputeWorkspaceDependenciesTest {

    @Test
    fun `keeps child bucket dependency when same artifact has different version than default`() {
        val defaultDependency = dependency("com.example:library:1.0", "maven")
        val debugDependency = dependency("com.example:library:2.0", "debug_maven")

        val workspaceDependencies = ComputeWorkspaceDependencies().computeFromResults(
            listOf(
                result("default", defaultDependency),
                result("debug", debugDependency)
            )
        )

        val defaultDeps = workspaceDependencies.variantDeps.getValue("default")
        val debugDeps = workspaceDependencies.variantDeps.getValue("debug")

        assertEquals(listOf("com.example:library:1.0"), defaultDeps.map { it.id })
        assertEquals(listOf("com.example:library:2.0"), debugDeps.map { it.id })
    }

    @Test
    fun `removes child bucket dependency when only repository differs from default`() {
        val defaultDependency = dependency("com.example:library:1.0", "maven")
        val debugDependency = dependency("com.example:library:1.0", "debug_maven")

        val workspaceDependencies = ComputeWorkspaceDependencies().computeFromResults(
            listOf(
                result("default", defaultDependency),
                result("debug", debugDependency)
            )
        )

        val defaultDeps = workspaceDependencies.variantDeps.getValue("default")

        assertEquals(listOf("maven"), defaultDeps.map { it.repository })
        assertEquals(emptyList<ResolvedDependency>(), workspaceDependencies.variantDeps["debug"].orEmpty())
    }

    @Test
    fun `keeps child declared direct dependency when default resolved classpath also contains it`() {
        val defaultDependency = dependency("com.example:library:1.0", "maven")
        val debugDependency = dependency("com.example:library:1.0", "Declared")

        val workspaceDependencies = ComputeWorkspaceDependencies().computeFromResults(
            listOf(
                result("default", defaultDependency),
                result("debug", debugDependency)
            )
        )

        val defaultDeps = workspaceDependencies.variantDeps.getValue("default")
        val debugDeps = workspaceDependencies.variantDeps.getValue("debug")

        assertEquals(listOf("com.example:library:1.0"), defaultDeps.map { it.id })
        assertEquals(listOf("com.example:library:1.0"), debugDeps.map { it.id })
        assertEquals(listOf("Declared"), debugDeps.map { it.repository })
    }

    @Test
    fun `removes child declared direct dependency when default declared bucket already owns it`() {
        val defaultDependency = dependency("com.example:library:1.0", "Declared")
        val debugDependency = dependency("com.example:library:1.0", "Declared")

        val workspaceDependencies = ComputeWorkspaceDependencies().computeFromResults(
            listOf(
                result("default", defaultDependency),
                result("debug", debugDependency)
            )
        )

        val defaultDeps = workspaceDependencies.variantDeps.getValue("default")

        assertEquals(listOf("com.example:library:1.0"), defaultDeps.map { it.id })
        assertEquals(emptyList<ResolvedDependency>(), workspaceDependencies.variantDeps["debug"].orEmpty())
    }

    @Test
    fun `keeps non default dependency when default bucket is empty`() {
        val freeDependency = dependency("com.example:free-only:1.0", "free_maven")

        val workspaceDependencies = ComputeWorkspaceDependencies().computeFromResults(
            listOf(
                result("default"),
                result("free", freeDependency)
            )
        )

        val defaultDeps = workspaceDependencies.variantDeps.getValue("default")
        val freeDeps = workspaceDependencies.variantDeps.getValue("free")

        assertEquals(emptyList<ResolvedDependency>(), defaultDeps)
        assertEquals(listOf("com.example:free-only:1.0"), freeDeps.map { it.id })
    }

    @Test
    fun `removes child direct dependency after preserving child transitive closure`() {
        val defaultDependency = dependency("com.example:library:1.0", "maven")
        val debugDependency = dependency(
            id = "com.example:library:1.0",
            repository = "maven",
            dependencies = setOf("com.example:transitive:1.0:maven:false:null")
        )

        val workspaceDependencies = ComputeWorkspaceDependencies().computeFromResults(
            listOf(
                result("default", defaultDependency),
                result("debug", debugDependency)
            )
        )

        val defaultDeps = workspaceDependencies.variantDeps.getValue("default")
        val debugDeps = workspaceDependencies.variantDeps.getValue("debug")

        assertEquals(listOf("com.example:library:1.0"), defaultDeps.map { it.id })
        assertEquals(listOf("com.example:transitive:1.0"), debugDeps.map { it.id })
        assertEquals(
            setOf("com.example:transitive"),
            workspaceDependencies.transitiveClasspath.getValue("com.example:library")
        )
    }

    @Test
    fun `keeps child direct dependency when default only has same transitive dependency`() {
        val defaultDependency = dependency("com.example:library:1.0", "maven").copy(direct = false)
        val androidTestDependency = dependency("com.example:library:1.0", "maven")

        val workspaceDependencies = ComputeWorkspaceDependencies().computeFromResults(
            listOf(
                result("default", defaultDependency),
                result("androidTest", androidTestDependency)
            )
        )

        val defaultDeps = workspaceDependencies.variantDeps.getValue("default")
        val androidTestDeps = workspaceDependencies.variantDeps.getValue("androidTest")

        assertEquals(listOf("com.example:library:1.0"), defaultDeps.map { it.id })
        assertEquals(listOf("com.example:library:1.0"), androidTestDeps.map { it.id })
    }

    @Test
    fun `removes child direct dependency covered by default even when jetifier source differs`() {
        val defaultDependency = dependency("androidx.test.espresso:espresso-core:3.5.1", "Google")
            .copy(jetifierSource = "com.android.support.test.espresso:espresso-core")
        val androidTestDependency = dependency("androidx.test.espresso:espresso-core:3.5.1", "Google")

        val workspaceDependencies = ComputeWorkspaceDependencies().computeFromResults(
            listOf(
                result("default", defaultDependency),
                result("androidTest", androidTestDependency)
            )
        )

        assertEquals(
            listOf("androidx.test.espresso:espresso-core:3.5.1"),
            workspaceDependencies.variantDeps.getValue("default").map { it.id }
        )
        assertEquals(
            emptyList<ResolvedDependency>(),
            workspaceDependencies.variantDeps["androidTest"].orEmpty()
        )
    }

    @Test
    fun `keeps child direct dependency with different excludes after preserving child transitive closure`() {
        val defaultDependency = dependency("com.example:root:1.0", "maven")
        val debugDependency = dependency(
            id = "com.example:root:1.0",
            repository = "debug_maven",
            dependencies = setOf("com.example:debug-transitive:1.0:maven:false:null")
        ).copy(
            excludeRules = setOf(ExcludeRule("com.example", "excluded-from-debug"))
        )

        val workspaceDependencies = ComputeWorkspaceDependencies().computeFromResults(
            listOf(
                result("default", defaultDependency),
                result("debug", debugDependency)
            )
        )

        val debugDeps = workspaceDependencies.variantDeps.getValue("debug")

        assertEquals(
            listOf("com.example:root:1.0"),
            workspaceDependencies.variantDeps.getValue("default").map { it.id }
        )
        assertEquals(
            listOf("com.example:debug-transitive:1.0", "com.example:root:1.0"),
            debugDeps.map { it.id }
        )
        assertEquals(
            setOf(ExcludeRule("com.example", "excluded-from-debug")),
            debugDeps.single { it.shortId == "com.example:root" }.excludeRules
        )
        assertEquals(
            setOf("com.example:debug-transitive"),
            workspaceDependencies.transitiveClasspath.getValue("com.example:root")
        )
    }

    @Test
    fun `removes child direct dependency that already resolves through default override target`() {
        val defaultDependency = dependency("androidx.test:runner:1.5.2", "Google")
            .copy(jetifierSource = "com.android.support.test:runner")
        val androidTestDependency = dependency("androidx.test:runner:1.5.2", "Google")
            .copy(
                jetifierSource = "com.android.support.test:runner",
                overrideTarget = OverrideTarget(
                    artifactShortId = "androidx.test:runner",
                    label = MavenDependency(group = "androidx.test", name = "runner")
                )
            )

        val workspaceDependencies = ComputeWorkspaceDependencies().computeFromResults(
            listOf(
                result("default", defaultDependency),
                result("androidTest", androidTestDependency)
            )
        )

        assertEquals(
            listOf("androidx.test:runner:1.5.2"),
            workspaceDependencies.variantDeps.getValue("default").map { it.id }
        )
        assertEquals(
            emptyList<ResolvedDependency>(),
            workspaceDependencies.variantDeps["androidTest"].orEmpty()
        )
    }

    @Test
    fun `keeps override carrier while preserving child transitive closure`() {
        val defaultDependency = dependency("com.example:root:1.0", "maven")
        val debugDependency = dependency("com.example:library:1.0", "debug_maven")
        val flavorDependency = dependency(
            id = "com.example:library:1.0",
            repository = "debug_maven",
            dependencies = setOf("com.example:transitive:1.0:maven:false:null")
        ).copy(
            overrideTarget = OverrideTarget(
                artifactShortId = "com.example:library",
                label = MavenDependency(
                    repo = "debug_maven",
                    group = "com.example",
                    name = "library"
                )
            )
        )

        val workspaceDependencies = ComputeWorkspaceDependencies().computeFromResults(
            listOf(
                result("default", defaultDependency),
                result("debug", debugDependency),
                result("free", flavorDependency)
            )
        )

        val freeDeps = workspaceDependencies.variantDeps.getValue("free")

        assertEquals(
            listOf("com.example:library:1.0", "com.example:transitive:1.0"),
            freeDeps.map { it.id }
        )
        assertEquals(
            OverrideTarget(
                artifactShortId = "com.example:library",
                label = MavenDependency(
                    repo = "debug_maven",
                    group = "com.example",
                    name = "library"
                )
            ),
            freeDeps.single { it.shortId == "com.example:library" }.overrideTarget
        )
        assertEquals(
            setOf("com.example:transitive"),
            workspaceDependencies.transitiveClasspath.getValue("com.example:library")
        )
    }

    @Test
    fun `keeps variant scoped transitive classpath for shared direct root`() {
        val defaultDependency = dependency("com.example:root:1.0", "maven")
        val debugDependency = dependency(
            id = "com.example:shared-root:1.0",
            repository = "maven",
            dependencies = setOf("com.example:debug-carrier:1.0:maven:false:null")
        )
        val androidTestDependency = dependency(
            id = "com.example:shared-root:1.0",
            repository = "maven",
            dependencies = setOf("com.example:android-test-carrier:1.0:maven:false:null")
        )

        val workspaceDependencies = ComputeWorkspaceDependencies().computeFromResults(
            listOf(
                result("default", defaultDependency),
                result("debug", debugDependency),
                result("androidTest", androidTestDependency)
            )
        )

        assertEquals(
            setOf("com.example:debug-carrier"),
            workspaceDependencies.variantTransitiveClasspath
                .getValue("debug")
                .getValue("com.example:shared-root")
        )
        assertEquals(
            setOf("com.example:android-test-carrier"),
            workspaceDependencies.variantTransitiveClasspath
                .getValue("androidTest")
                .getValue("com.example:shared-root")
        )
        assertEquals(
            setOf("com.example:android-test-carrier", "com.example:debug-carrier"),
            workspaceDependencies.transitiveClasspath.getValue("com.example:shared-root")
        )
    }

    @Test
    fun `uses default selected version for lower child transitive dependency`() {
        val defaultDependency = dependency("com.example:annotations:1.1", "maven").copy(direct = false)
        val lintDependency = dependency(
            id = "com.example:lint-checks:1.0",
            repository = "lint_maven",
            dependencies = setOf("com.example:annotations:1.0:maven:false:null")
        )

        val workspaceDependencies = ComputeWorkspaceDependencies().computeFromResults(
            listOf(
                result("default", defaultDependency),
                result("lint", lintDependency)
            )
        )

        val lintDeps = workspaceDependencies.variantDeps.getValue("lint")

        assertEquals(
            listOf("com.example:annotations:1.1", "com.example:lint-checks:1.0"),
            lintDeps.map { it.id }
        )
        assertEquals(
            OverrideTarget(
                artifactShortId = "com.example:annotations",
                label = MavenDependency(group = "com.example", name = "annotations")
            ),
            lintDeps.single { it.shortId == "com.example:annotations" }.overrideTarget
        )
    }

    private fun result(
        variantName: String,
        vararg dependencies: ResolvedDependency
    ): ResolveDependenciesResult {
        return ResolveDependenciesResult(
            variantName = variantName,
            dependencies = mapOf(
                COMPILE.name to dependencies.toSet(),
                KSP.name to emptySet()
            )
        )
    }

    private fun dependency(
        id: String,
        repository: String,
        dependencies: Set<String> = emptySet()
    ): ResolvedDependency {
        return ResolvedDependency.fromId(id, repository).copy(dependencies = dependencies)
    }
}
