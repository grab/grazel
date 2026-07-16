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

package com.grab.grazel.gradle.dependencies.bucket

import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.gradle.dependencies.DECLARED_DEPENDENCY_REPOSITORY
import com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadata
import com.grab.grazel.gradle.dependencies.DeclaredVariantDependencyMetadata
import com.grab.grazel.gradle.dependencies.ProjectDeclaredDependencyMetadata
import com.grab.grazel.gradle.dependencies.ProjectDependencyBucket
import com.grab.grazel.gradle.dependencies.model.ExcludeRule
import com.grab.grazel.gradle.dependencies.model.OverrideTarget
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.COMPILE
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.KSP
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.variant.ANDROID_TEST_VARIANT
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.TEST_VARIANT
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
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
    fun `unit test base bucket drops dependency inherited from each selected main leaf`() {
        val sharedMainDependency = dependency("com.example:shared-main:1.0")
        val unitTestOnlyDependency = dependency("com.example:unit-test-only:1.0")

        val results: List<ResolveDependenciesResult> = planner(
            metadata = metadata(
                ":app" to listOf(
                    declaredVariant(DEFAULT_VARIANT, AndroidBuild, leaf = false),
                    declaredVariant(
                        "freeDebug",
                        AndroidBuild,
                        leaf = true,
                        buildType = "debug",
                        extendsFrom = setOf(DEFAULT_VARIANT, "debug", "free")
                    ),
                    declaredVariant(
                        "paidRelease",
                        AndroidBuild,
                        leaf = true,
                        buildType = "release",
                        extendsFrom = setOf(DEFAULT_VARIANT, "release", "paid")
                    ),
                    declaredVariant(TEST_VARIANT, TestVariantType, leaf = false),
                    declaredVariant(
                        "freeDebugUnitTest",
                        TestVariantType,
                        leaf = true,
                        extendsFrom = setOf(TEST_VARIANT, "freeDebug")
                    ),
                    declaredVariant(
                        "paidReleaseUnitTest",
                        TestVariantType,
                        leaf = true,
                        extendsFrom = setOf(TEST_VARIANT, "paidRelease")
                    )
                )
            )
        ).plan(
            input(
                hierarchyBucketClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(sharedMainDependency),
                    bucket(":app", "paidRelease") to deps(sharedMainDependency)
                ),
                leafClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(sharedMainDependency),
                    bucket(":app", "paidRelease") to deps(sharedMainDependency)
                ),
                leafUnitTestClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(sharedMainDependency, unitTestOnlyDependency),
                    bucket(":app", "paidRelease") to deps(sharedMainDependency, unitTestOnlyDependency)
                )
            )
        )

        assertEquals(
            compileSummary(results),
            listOf("com.example:unit-test-only:1.0"),
            compileIdsFor(results, TEST_VARIANT)
        )
    }

    @Test
    fun `android test base bucket drops dependency inherited from each selected main leaf`() {
        val sharedMainDependency = dependency("com.example:shared-main:1.0")
        val androidTestOnlyDependency = dependency("com.example:android-test-only:1.0")

        val results: List<ResolveDependenciesResult> = planner(
            metadata = metadata(
                ":app" to listOf(
                    declaredVariant(DEFAULT_VARIANT, AndroidBuild, leaf = false),
                    declaredVariant(
                        "freeDebug",
                        AndroidBuild,
                        leaf = true,
                        buildType = "debug",
                        extendsFrom = setOf(DEFAULT_VARIANT, "debug", "free")
                    ),
                    declaredVariant(
                        "paidRelease",
                        AndroidBuild,
                        leaf = true,
                        buildType = "release",
                        extendsFrom = setOf(DEFAULT_VARIANT, "release", "paid")
                    ),
                    declaredVariant(
                        ANDROID_TEST_VARIANT,
                        AndroidTest,
                        leaf = false,
                        extendsFrom = setOf(DEFAULT_VARIANT, TEST_VARIANT)
                    ),
                    declaredVariant(
                        "freeDebugAndroidTest",
                        AndroidTest,
                        leaf = true,
                        extendsFrom = setOf(ANDROID_TEST_VARIANT, TEST_VARIANT, "freeDebug")
                    ),
                    declaredVariant(
                        "paidReleaseAndroidTest",
                        AndroidTest,
                        leaf = true,
                        extendsFrom = setOf(ANDROID_TEST_VARIANT, TEST_VARIANT, "paidRelease")
                    )
                )
            )
        ).plan(
            input(
                hierarchyBucketClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(sharedMainDependency),
                    bucket(":app", "paidRelease") to deps(sharedMainDependency)
                ),
                leafClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(sharedMainDependency),
                    bucket(":app", "paidRelease") to deps(sharedMainDependency)
                ),
                leafAndroidTestClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(sharedMainDependency, androidTestOnlyDependency),
                    bucket(":app", "paidRelease") to deps(sharedMainDependency, androidTestOnlyDependency)
                )
            )
        )

        assertEquals(
            compileSummary(results),
            listOf("com.example:android-test-only:1.0"),
            compileIdsFor(results, ANDROID_TEST_VARIANT)
        )
    }

    @Test
    fun `android test base bucket keeps dependency when selected main leaves resolve different version`() {
        val sharedMainDependency = dependency("com.example:shared:1.0")
        val androidTestDependency = dependency("com.example:shared:2.0")

        val results: List<ResolveDependenciesResult> = planner(
            metadata = metadata(
                ":app" to listOf(
                    declaredVariant(DEFAULT_VARIANT, AndroidBuild, leaf = false),
                    declaredVariant(
                        "freeDebug",
                        AndroidBuild,
                        leaf = true,
                        buildType = "debug",
                        extendsFrom = setOf(DEFAULT_VARIANT, "debug", "free")
                    ),
                    declaredVariant(
                        "paidRelease",
                        AndroidBuild,
                        leaf = true,
                        buildType = "release",
                        extendsFrom = setOf(DEFAULT_VARIANT, "release", "paid")
                    ),
                    declaredVariant(
                        ANDROID_TEST_VARIANT,
                        AndroidTest,
                        leaf = false,
                        extendsFrom = setOf(DEFAULT_VARIANT, TEST_VARIANT)
                    ),
                    declaredVariant(
                        "freeDebugAndroidTest",
                        AndroidTest,
                        leaf = true,
                        extendsFrom = setOf(ANDROID_TEST_VARIANT, TEST_VARIANT, "freeDebug")
                    ),
                    declaredVariant(
                        "paidReleaseAndroidTest",
                        AndroidTest,
                        leaf = true,
                        extendsFrom = setOf(ANDROID_TEST_VARIANT, TEST_VARIANT, "paidRelease")
                    )
                )
            )
        ).plan(
            input(
                hierarchyBucketClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(sharedMainDependency),
                    bucket(":app", "paidRelease") to deps(sharedMainDependency)
                ),
                leafClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(sharedMainDependency),
                    bucket(":app", "paidRelease") to deps(sharedMainDependency)
                ),
                leafAndroidTestClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(androidTestDependency),
                    bucket(":app", "paidRelease") to deps(androidTestDependency)
                )
            )
        )

        assertEquals(
            compileSummary(results),
            listOf("com.example:shared:2.0"),
            compileIdsFor(results, ANDROID_TEST_VARIANT)
        )
    }

    @Test
    fun `android test base bucket keeps direct root when main owner does not cover its closure`() {
        val sharedMainDependency = dependency("com.example:shared:1.0")
        val androidTestDependency = dependency("com.example:shared:1.0").copy(
            dependencies = setOf("com.example:android-test-child:1.0")
        )

        val results: List<ResolveDependenciesResult> = planner(
            metadata = metadata(
                ":app" to listOf(
                    declaredVariant(DEFAULT_VARIANT, AndroidBuild, leaf = false),
                    declaredVariant(
                        "freeDebug",
                        AndroidBuild,
                        leaf = true,
                        buildType = "debug",
                        extendsFrom = setOf(DEFAULT_VARIANT, "debug", "free")
                    ),
                    declaredVariant(
                        "paidRelease",
                        AndroidBuild,
                        leaf = true,
                        buildType = "release",
                        extendsFrom = setOf(DEFAULT_VARIANT, "release", "paid")
                    ),
                    declaredVariant(
                        ANDROID_TEST_VARIANT,
                        AndroidTest,
                        leaf = false,
                        extendsFrom = setOf(DEFAULT_VARIANT, TEST_VARIANT)
                    ),
                    declaredVariant(
                        "freeDebugAndroidTest",
                        AndroidTest,
                        leaf = true,
                        extendsFrom = setOf(ANDROID_TEST_VARIANT, TEST_VARIANT, "freeDebug")
                    ),
                    declaredVariant(
                        "paidReleaseAndroidTest",
                        AndroidTest,
                        leaf = true,
                        extendsFrom = setOf(ANDROID_TEST_VARIANT, TEST_VARIANT, "paidRelease")
                    )
                )
            )
        ).plan(
            input(
                hierarchyBucketClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(sharedMainDependency),
                    bucket(":app", "paidRelease") to deps(sharedMainDependency)
                ),
                leafClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(sharedMainDependency),
                    bucket(":app", "paidRelease") to deps(sharedMainDependency)
                ),
                leafAndroidTestClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(androidTestDependency),
                    bucket(":app", "paidRelease") to deps(androidTestDependency)
                )
            )
        )

        val androidTestDeps = compileDepsFor(results, ANDROID_TEST_VARIANT)
        assertEquals(
            compileSummary(results),
            listOf("com.example:shared:1.0"),
            androidTestDeps.map(ResolvedDependency::id)
        )
        assertEquals(
            setOf("com.example:android-test-child:1.0"),
            androidTestDeps.single().dependencies
        )
    }

    @Test
    fun `android test base bucket drops main root when scoped sibling carries extra closure`() {
        val childDependencyId = "com.example:android-test-child:1.0"
        val sharedMainDependency = dependency("com.example:shared:1.0")
        val androidTestDependency = dependency("com.example:shared:1.0").copy(
            dependencies = setOf(childDependencyId)
        )
        val androidTestCarrierDependency = dependency("com.example:android-test-carrier:1.0").copy(
            dependencies = setOf(childDependencyId)
        )

        val results: List<ResolveDependenciesResult> = planner(
            metadata = metadata(
                ":app" to listOf(
                    declaredVariant(DEFAULT_VARIANT, AndroidBuild, leaf = false),
                    declaredVariant(
                        "freeDebug",
                        AndroidBuild,
                        leaf = true,
                        buildType = "debug",
                        extendsFrom = setOf(DEFAULT_VARIANT, "debug", "free")
                    ),
                    declaredVariant(
                        "paidRelease",
                        AndroidBuild,
                        leaf = true,
                        buildType = "release",
                        extendsFrom = setOf(DEFAULT_VARIANT, "release", "paid")
                    ),
                    declaredVariant(
                        ANDROID_TEST_VARIANT,
                        AndroidTest,
                        leaf = false,
                        extendsFrom = setOf(DEFAULT_VARIANT, TEST_VARIANT)
                    ),
                    declaredVariant(
                        "freeDebugAndroidTest",
                        AndroidTest,
                        leaf = true,
                        extendsFrom = setOf(ANDROID_TEST_VARIANT, TEST_VARIANT, "freeDebug")
                    ),
                    declaredVariant(
                        "paidReleaseAndroidTest",
                        AndroidTest,
                        leaf = true,
                        extendsFrom = setOf(ANDROID_TEST_VARIANT, TEST_VARIANT, "paidRelease")
                    )
                )
            )
        ).plan(
            input(
                hierarchyBucketClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(sharedMainDependency),
                    bucket(":app", "paidRelease") to deps(sharedMainDependency)
                ),
                leafClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(sharedMainDependency),
                    bucket(":app", "paidRelease") to deps(sharedMainDependency)
                ),
                leafAndroidTestClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(androidTestDependency, androidTestCarrierDependency),
                    bucket(":app", "paidRelease") to deps(androidTestDependency, androidTestCarrierDependency)
                )
            )
        )

        val androidTestDeps = compileDepsFor(results, ANDROID_TEST_VARIANT)
        assertEquals(
            compileSummary(results),
            listOf("com.example:android-test-carrier:1.0"),
            androidTestDeps.map(ResolvedDependency::id)
        )
        assertEquals(
            setOf(childDependencyId),
            androidTestDeps.single().dependencies
        )
    }

    @Test
    fun `android test base bucket drops main root when scoped sibling root carries extra closure`() {
        val childDependencyNotation = "com.example:android-test-child:1.0:maven:false:null"
        val sharedMainDependency = dependency("com.example:shared:1.0")
        val androidTestDependency = dependency("com.example:shared:1.0").copy(
            dependencies = setOf(childDependencyNotation)
        )
        val androidTestChildDependency = dependency("com.example:android-test-child:1.0")

        val results: List<ResolveDependenciesResult> = planner(
            metadata = metadata(
                ":app" to listOf(
                    declaredVariant(DEFAULT_VARIANT, AndroidBuild, leaf = false),
                    declaredVariant(
                        "freeDebug",
                        AndroidBuild,
                        leaf = true,
                        buildType = "debug",
                        extendsFrom = setOf(DEFAULT_VARIANT, "debug", "free")
                    ),
                    declaredVariant(
                        "paidRelease",
                        AndroidBuild,
                        leaf = true,
                        buildType = "release",
                        extendsFrom = setOf(DEFAULT_VARIANT, "release", "paid")
                    ),
                    declaredVariant(
                        ANDROID_TEST_VARIANT,
                        AndroidTest,
                        leaf = false,
                        extendsFrom = setOf(DEFAULT_VARIANT, TEST_VARIANT)
                    ),
                    declaredVariant(
                        "freeDebugAndroidTest",
                        AndroidTest,
                        leaf = true,
                        extendsFrom = setOf(ANDROID_TEST_VARIANT, TEST_VARIANT, "freeDebug")
                    ),
                    declaredVariant(
                        "paidReleaseAndroidTest",
                        AndroidTest,
                        leaf = true,
                        extendsFrom = setOf(ANDROID_TEST_VARIANT, TEST_VARIANT, "paidRelease")
                    )
                )
            )
        ).plan(
            input(
                hierarchyBucketClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(sharedMainDependency),
                    bucket(":app", "paidRelease") to deps(sharedMainDependency)
                ),
                leafClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(sharedMainDependency),
                    bucket(":app", "paidRelease") to deps(sharedMainDependency)
                ),
                leafAndroidTestClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(androidTestDependency, androidTestChildDependency),
                    bucket(":app", "paidRelease") to deps(androidTestDependency, androidTestChildDependency)
                )
            )
        )

        val androidTestDeps = compileDepsFor(results, ANDROID_TEST_VARIANT)
        assertEquals(
            compileSummary(results),
            listOf("com.example:android-test-child:1.0"),
            androidTestDeps.map(ResolvedDependency::id)
        )
        assertEquals(
            emptySet<String>(),
            androidTestDeps.single().dependencies
        )
    }

    @Test
    fun `android test base bucket drops main root when merged scoped sibling root carries extra closure`() {
        val childDependencyNotation = "com.example:android-test-child:1.0:maven:false:null"
        val sharedMainDependency = dependency("com.example:shared:1.0")
        val androidTestDependency = dependency("com.example:shared:1.0").copy(
            dependencies = setOf(childDependencyNotation)
        )
        val androidTestChildDependency = dependency("com.example:android-test-child:1.0")

        val results: List<ResolveDependenciesResult> = planner(
            metadata = metadata(
                ":app" to androidAndAndroidTestVariants(),
                ":test-fixture" to androidAndAndroidTestVariants()
            )
        ).plan(
            input(
                hierarchyBucketClosures = mapOf(
                    bucket(":app", DEFAULT_VARIANT) to deps(sharedMainDependency)
                ),
                leafAndroidTestClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(androidTestDependency),
                    bucket(":app", "paidRelease") to deps(androidTestDependency),
                    bucket(":test-fixture", "freeDebug") to deps(androidTestChildDependency),
                    bucket(":test-fixture", "paidRelease") to deps(androidTestChildDependency)
                )
            )
        )

        val androidTestDeps = compileDepsFor(results, ANDROID_TEST_VARIANT)
        assertEquals(
            compileSummary(results),
            listOf("com.example:android-test-child:1.0"),
            androidTestDeps.map(ResolvedDependency::id)
        )
        assertEquals(
            emptySet<String>(),
            androidTestDeps.single().dependencies
        )
    }

    @Test
    fun `android test base bucket drops inherited root when only override target differs`() {
        val mainDependency = dependency("com.example:shared:1.0")
        val androidTestDependency = dependency("com.example:shared:1.0").copy(
            overrideTarget = OverrideTarget(
                artifactShortId = "com.example:shared",
                label = MavenDependency(
                    group = "com.example",
                    name = "shared"
                )
            )
        )

        val results: List<ResolveDependenciesResult> = planner(
            metadata = metadata(
                ":app" to listOf(
                    declaredVariant(DEFAULT_VARIANT, AndroidBuild, leaf = false),
                    declaredVariant(
                        "freeDebug",
                        AndroidBuild,
                        leaf = true,
                        buildType = "debug",
                        extendsFrom = setOf(DEFAULT_VARIANT, "debug", "free")
                    ),
                    declaredVariant(
                        "paidRelease",
                        AndroidBuild,
                        leaf = true,
                        buildType = "release",
                        extendsFrom = setOf(DEFAULT_VARIANT, "release", "paid")
                    ),
                    declaredVariant(
                        ANDROID_TEST_VARIANT,
                        AndroidTest,
                        leaf = false,
                        extendsFrom = setOf(DEFAULT_VARIANT, TEST_VARIANT)
                    ),
                    declaredVariant(
                        "freeDebugAndroidTest",
                        AndroidTest,
                        leaf = true,
                        extendsFrom = setOf(ANDROID_TEST_VARIANT, TEST_VARIANT, "freeDebug")
                    ),
                    declaredVariant(
                        "paidReleaseAndroidTest",
                        AndroidTest,
                        leaf = true,
                        extendsFrom = setOf(ANDROID_TEST_VARIANT, TEST_VARIANT, "paidRelease")
                    )
                )
            )
        ).plan(
            input(
                hierarchyBucketClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(mainDependency),
                    bucket(":app", "paidRelease") to deps(mainDependency)
                ),
                leafClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(mainDependency),
                    bucket(":app", "paidRelease") to deps(mainDependency)
                ),
                leafAndroidTestClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(androidTestDependency),
                    bucket(":app", "paidRelease") to deps(androidTestDependency)
                )
            )
        )

        assertEquals(emptyList<String>(), compileIdsFor(results, ANDROID_TEST_VARIANT))
    }

    @Test
    fun `unit test shared build type bucket emits typed output bucket`() {
        val debugTestDependency = dependency("com.example:debug-test-only:1.0")

        val results: List<ResolveDependenciesResult> = planner(
            metadata = metadata(
                ":app" to listOf(
                    declaredVariant(DEFAULT_VARIANT, AndroidBuild, leaf = false),
                    declaredVariant("debug", AndroidBuild, leaf = false, buildType = "debug"),
                    declaredVariant(
                        "freeDebug",
                        AndroidBuild,
                        leaf = true,
                        buildType = "debug",
                        extendsFrom = setOf(DEFAULT_VARIANT, "debug", "free")
                    ),
                    declaredVariant(
                        "paidDebug",
                        AndroidBuild,
                        leaf = true,
                        buildType = "debug",
                        extendsFrom = setOf(DEFAULT_VARIANT, "debug", "paid")
                    ),
                    declaredVariant(TEST_VARIANT, TestVariantType, leaf = false),
                    declaredVariant(
                        "debugUnitTest",
                        TestVariantType,
                        leaf = false,
                        extendsFrom = setOf(TEST_VARIANT, "debug")
                    ),
                    declaredVariant(
                        "freeDebugUnitTest",
                        TestVariantType,
                        leaf = true,
                        extendsFrom = setOf("debugUnitTest", "freeDebug")
                    ),
                    declaredVariant(
                        "paidDebugUnitTest",
                        TestVariantType,
                        leaf = true,
                        extendsFrom = setOf("debugUnitTest", "paidDebug")
                    )
                )
            )
        ).plan(
            input(
                leafUnitTestClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(debugTestDependency),
                    bucket(":app", "paidDebug") to deps(debugTestDependency)
                )
            )
        )

        assertEquals(
            compileSummary(results),
            listOf("com.example:debug-test-only:1.0"),
            compileIdsFor(results, "debugUnitTest")
        )
        assertEquals(emptyList<String>(), compileIdsFor(results, TEST_VARIANT))
        assertEquals(emptyList<String>(), compileIdsFor(results, "debug"))
    }

    @Test
    fun `unit test bucket projection uses variant type instead of rendered suffix`() {
        val debugTestDependency = dependency("com.example:debug-test-only:1.0")

        val results: List<ResolveDependenciesResult> = planner(
            metadata = metadata(
                ":app" to listOf(
                    declaredVariant(DEFAULT_VARIANT, AndroidBuild, leaf = false),
                    declaredVariant("debug", AndroidBuild, leaf = false, buildType = "debug"),
                    declaredVariant(
                        "freeDebug",
                        AndroidBuild,
                        leaf = true,
                        buildType = "debug",
                        extendsFrom = setOf(DEFAULT_VARIANT, "debug", "free")
                    ),
                    declaredVariant(
                        "paidDebug",
                        AndroidBuild,
                        leaf = true,
                        buildType = "debug",
                        extendsFrom = setOf(DEFAULT_VARIANT, "debug", "paid")
                    ),
                    declaredVariant(TEST_VARIANT, TestVariantType, leaf = false),
                    declaredVariant(
                        "debugSpec",
                        TestVariantType,
                        leaf = false,
                        extendsFrom = setOf(TEST_VARIANT, "debug")
                    ),
                    declaredVariant(
                        "freeDebugSpec",
                        TestVariantType,
                        leaf = true,
                        extendsFrom = setOf("debugSpec", "freeDebug")
                    ),
                    declaredVariant(
                        "paidDebugSpec",
                        TestVariantType,
                        leaf = true,
                        extendsFrom = setOf("debugSpec", "paidDebug")
                    )
                )
            )
        ).plan(
            input(
                leafUnitTestClosures = mapOf(
                    bucket(":app", "freeDebug") to deps(debugTestDependency),
                    bucket(":app", "paidDebug") to deps(debugTestDependency)
                )
            )
        )

        assertEquals(
            compileSummary(results),
            listOf("com.example:debug-test-only:1.0"),
            compileIdsFor(results, "debugSpec")
        )
        assertEquals(emptyList<String>(), compileIdsFor(results, TEST_VARIANT))
        assertEquals(emptyList<String>(), compileIdsFor(results, "debugSpecUnitTest"))
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

    private fun compileIdsFor(
        results: List<ResolveDependenciesResult>,
        variantName: String
    ): List<String> {
        return compileDepsFor(results, variantName)
            .map(ResolvedDependency::id)
    }

    private fun compileDepsFor(
        results: List<ResolveDependenciesResult>,
        variantName: String
    ): List<ResolvedDependency> {
        return results.singleOrNull { result -> result.variantName == variantName }
            ?.dependencies
            ?.getValue(COMPILE.name)
            ?.toList()
            .orEmpty()
    }

    private fun compileSummary(results: List<ResolveDependenciesResult>): String {
        return results.joinToString(separator = "; ") { result ->
            "${result.variantName}=" +
                result.dependencies.getValue(COMPILE.name).map(ResolvedDependency::id)
        }
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

    private fun androidAndAndroidTestVariants(): List<DeclaredVariantDependencyMetadata> {
        return listOf(
            declaredVariant(DEFAULT_VARIANT, AndroidBuild, leaf = false),
            declaredVariant(
                "freeDebug",
                AndroidBuild,
                leaf = true,
                buildType = "debug",
                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "free")
            ),
            declaredVariant(
                "paidRelease",
                AndroidBuild,
                leaf = true,
                buildType = "release",
                extendsFrom = setOf(DEFAULT_VARIANT, "release", "paid")
            ),
            declaredVariant(
                ANDROID_TEST_VARIANT,
                AndroidTest,
                leaf = false,
                extendsFrom = setOf(DEFAULT_VARIANT, TEST_VARIANT)
            ),
            declaredVariant(
                "freeDebugAndroidTest",
                AndroidTest,
                leaf = true,
                extendsFrom = setOf(ANDROID_TEST_VARIANT, TEST_VARIANT, "freeDebug")
            ),
            declaredVariant(
                "paidReleaseAndroidTest",
                AndroidTest,
                leaf = true,
                extendsFrom = setOf(ANDROID_TEST_VARIANT, TEST_VARIANT, "paidRelease")
            )
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
