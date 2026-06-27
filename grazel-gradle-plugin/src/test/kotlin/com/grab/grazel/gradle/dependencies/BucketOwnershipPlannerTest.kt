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

import com.grab.grazel.gradle.dependencies.model.ExcludeRule
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.COMPILE
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.KSP
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.variant.ANDROID_TEST_VARIANT
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.TEST_VARIANT
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.Test as TestVariantType
import org.junit.Assert.assertEquals
import org.junit.Test

class BucketOwnershipPlannerTest {

    @Test
    fun `emits default result when compile roots are empty but KSP dependencies exist`() {
        val kspDependency = dependency("com.example:processor:1.0")

        val results: List<ResolveDependenciesResult> = planner(
            metadata = metadata(
                ":app" to listOf(declaredVariant(DEFAULT_VARIANT, AndroidBuild, leaf = false))
            ),
            kspDependencies = setOf(kspDependency)
        ).plan(
            input(
                hierarchyBucketClosures = mapOf(bucket(":app", DEFAULT_VARIANT) to emptyMap()),
                reachableMainBucketNamesByProject = mapOf(":app" to setOf(DEFAULT_VARIANT))
            )
        )

        assertEquals(listOf(DEFAULT_VARIANT), results.map { result -> result.variantName })
        assertEquals(
            emptySet<ResolvedDependency>(),
            results.single().dependencies.getValue(COMPILE.name)
        )
        assertEquals(
            setOf(kspDependency),
            results.single().dependencies.getValue(KSP.name)
        )
        assertEquals(
            mapOf(":app" to setOf(DEFAULT_VARIANT)),
            results.single().reachableMainBucketsByProject
        )
    }

    @Test
    fun `merges project scoped main buckets by resolved max version`() {
        val appDependency = dependency("com.example:library:1.0")
        val testAppDependency = dependency("com.example:library:2.0")

        val results: List<ResolveDependenciesResult> = planner(
            metadata = metadata(
                ":app" to listOf(
                    declaredVariant(DEFAULT_VARIANT, AndroidBuild, leaf = false),
                    declaredVariant("debug", AndroidBuild, leaf = true, buildType = "debug")
                ),
                ":test-app" to listOf(
                    declaredVariant(DEFAULT_VARIANT, AndroidBuild, leaf = false),
                    declaredVariant("debug", AndroidBuild, leaf = true, buildType = "debug")
                )
            )
        ).plan(
            input(
                hierarchyBucketClosures = mapOf(
                    bucket(":app", "debug") to deps(appDependency),
                    bucket(":test-app", "debug") to deps(testAppDependency)
                )
            )
        )

        assertEquals(
            listOf("com.example:library:2.0"),
            results.single { result -> result.variantName == "debug" }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
    }

    @Test
    fun `test bucket keeps explicitly declared direct dependency with distinct excludes`() {
        val mainExclude = ExcludeRule(group = "com.example", artifact = "main-blocked")
        val testExclude = ExcludeRule(group = "com.example", artifact = "test-blocked")
        val mainDependency = dependency("com.example:library:1.0").copy(
            excludeRules = setOf(mainExclude)
        )
        val testDependency = dependency("com.example:library:1.0").copy(
            excludeRules = setOf(testExclude)
        )
        val declaredTestDependency = declaredDependency("com.example:library:1.0").copy(
            excludeRules = setOf(testExclude)
        )

        val results: List<ResolveDependenciesResult> = planner(
            metadata = metadata(
                ":app" to listOf(
                    declaredVariant(DEFAULT_VARIANT, AndroidBuild, leaf = false),
                    declaredVariant("debug", AndroidBuild, leaf = true, buildType = "debug"),
                    declaredVariant(TEST_VARIANT, TestVariantType, leaf = false),
                    declaredVariant(
                        "debugUnitTest",
                        TestVariantType,
                        leaf = true,
                        extendsFrom = setOf(TEST_VARIANT, "debug")
                    )
                )
            )
        ).plan(
            input(
                hierarchyBucketClosures = mapOf(bucket(":app", DEFAULT_VARIANT) to deps(mainDependency)),
                leafUnitTestClosures = mapOf(bucket(":app", "debug") to deps(testDependency)),
                testHierarchyBucketClosures = mapOf(bucket(":app", TEST_VARIANT) to deps(declaredTestDependency)),
                declaredTestDependenciesByBucket = mapOf(bucket(":app", TEST_VARIANT) to deps(declaredTestDependency))
            )
        )

        assertEquals(
            listOf("com.example:library:1.0"),
            results.single { result -> result.variantName == TEST_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
        assertEquals(
            setOf(testExclude),
            results.single { result -> result.variantName == TEST_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .single()
                .excludeRules
        )
    }

    @Test
    fun `result order keeps main buckets before test androidTest and lint`() {
        val defaultDependency = dependency("com.example:default-lib:1.0")
        val debugDependency = dependency("com.example:debug-lib:1.0")
        val leafDependency = dependency("com.example:leaf-lib:1.0")
        val testDependency = dependency("com.example:test-lib:1.0")
        val androidTestDependency = dependency("com.example:android-test-lib:1.0")
        val lintDependency = dependency("com.example:lint-lib:1.0")

        val results: List<ResolveDependenciesResult> = planner(
            metadata = metadata(
                ":app" to listOf(
                    declaredVariant(DEFAULT_VARIANT, AndroidBuild, leaf = false),
                    declaredVariant("debug", AndroidBuild, leaf = false),
                    declaredVariant("freeDebug", AndroidBuild, leaf = true, buildType = "debug"),
                    declaredVariant(TEST_VARIANT, TestVariantType, leaf = false),
                    declaredVariant(
                        "freeDebugUnitTest",
                        TestVariantType,
                        leaf = true,
                        extendsFrom = setOf(TEST_VARIANT, "freeDebug")
                    )
                )
            )
        ).plan(
            input(
                hierarchyBucketClosures = mapOf(
                    bucket(":app", DEFAULT_VARIANT) to deps(defaultDependency),
                    bucket(":app", "debug") to deps(debugDependency)
                ),
                leafClosures = mapOf(bucket(":app", "freeDebug") to deps(defaultDependency, debugDependency, leafDependency)),
                leafUnitTestClosures = mapOf(bucket(":app", "freeDebug") to deps(testDependency)),
                leafAndroidTestClosures = mapOf(bucket(":app", "freeDebug") to deps(androidTestDependency)),
                lintDeps = deps(lintDependency)
            )
        )

        assertEquals(
            listOf(DEFAULT_VARIANT, "freeDebug", "freeDebugUnitTest", ANDROID_TEST_VARIANT, "lint"),
            results.map { result -> result.variantName }
        )
    }

    private fun planner(
        metadata: DeclaredDependencyMetadata,
        kspDependencies: Set<ResolvedDependency> = emptySet()
    ): BucketOwnershipPlanner {
        return BucketOwnershipPlanner(
            declaredDependencyMetadata = metadata,
            precomputedKspDependencies = kspDependencies
        )
    }

    private fun input(
        leafClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> = emptyMap(),
        leafUnitTestClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> = emptyMap(),
        leafAndroidTestClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> = emptyMap(),
        hierarchyBucketClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> = emptyMap(),
        testHierarchyBucketClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> = emptyMap(),
        reachableMainBucketNamesByProject: Map<String, Set<String>> = emptyMap(),
        lintDeps: Map<String, ResolvedDependency> = emptyMap(),
        declaredTestDependenciesByBucket: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> = emptyMap()
    ): OwnershipPlannerInput {
        return OwnershipPlannerInput(
            leafClosures = leafClosures,
            leafUnitTestClosures = leafUnitTestClosures,
            leafAndroidTestClosures = leafAndroidTestClosures,
            hierarchyBucketClosures = hierarchyBucketClosures,
            testHierarchyBucketClosures = testHierarchyBucketClosures,
            reachableMainBucketNamesByProject = reachableMainBucketNamesByProject,
            lintDeps = lintDeps,
            declaredTestDependenciesByBucket = declaredTestDependenciesByBucket
        )
    }

    private fun metadata(
        vararg projects: Pair<String, List<DeclaredVariantDependencyMetadata>>
    ): DeclaredDependencyMetadata {
        return DeclaredDependencyMetadata(
            projects = projects.associate { (projectPath, variants) ->
                projectPath to ProjectDeclaredDependencyMetadata(variants = variants)
            }
        )
    }

    private fun declaredVariant(
        name: String,
        variantType: VariantType,
        leaf: Boolean,
        buildType: String? = null,
        extendsFrom: Set<String>? = null
    ): DeclaredVariantDependencyMetadata {
        return DeclaredVariantDependencyMetadata(
            name = name,
            variantType = variantType,
            extendsFrom = extendsFrom ?: if (name == DEFAULT_VARIANT) emptySet() else setOf(DEFAULT_VARIANT),
            variantConfigurationNames = emptySet(),
            compileConfigurationNames = emptySet(),
            runtimeConfigurationNames = emptySet(),
            kspConfigurationNames = emptySet(),
            androidLeafVariant = leaf,
            buildType = buildType,
            productFlavors = emptyList(),
            declaredDependencies = emptySet(),
            declaredDependencyDeclarations = emptySet(),
            declaredProjectDependencies = emptySet(),
            excludeRulesByShortId = emptyMap(),
            compileOnlyBucketName = name,
            compileOnlyDependenciesByShortId = emptyMap()
        )
    }

    private fun bucket(projectPath: String, bucketName: String): ProjectDependencyBucket {
        return ProjectDependencyBucket(projectPath, bucketName)
    }

    private fun deps(vararg dependencies: ResolvedDependency): Map<String, ResolvedDependency> {
        return dependencies.associateBy(ResolvedDependency::shortId)
    }

    private fun dependency(id: String): ResolvedDependency {
        return ResolvedDependency.fromId(id, "maven")
    }

    private fun declaredDependency(id: String): ResolvedDependency {
        return ResolvedDependency.fromId(id, DECLARED_DEPENDENCY_REPOSITORY)
    }
}
