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

import com.grab.grazel.gradle.dependencies.DECLARED_DEPENDENCY_REPOSITORY
import com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadata
import com.grab.grazel.gradle.dependencies.DeclaredExternalDependency
import com.grab.grazel.gradle.dependencies.DeclaredVariantDependencyMetadata
import com.grab.grazel.gradle.dependencies.ProjectDeclaredDependencyMetadata
import com.grab.grazel.gradle.dependencies.ProjectDependencyBucket
import com.grab.grazel.gradle.dependencies.model.ExcludeRule
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.variant.ANDROID_TEST_VARIANT
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.TEST_VARIANT
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.Test as TestVariantType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DependencyBucketPlacementEngineTest {

    @Test
    fun `declared main bucket variants are scoped by project path`() {
        val metadata = DeclaredDependencyMetadata(
            projects = mapOf(
                ":app" to ProjectDeclaredDependencyMetadata(
                    variants = listOf(
                        declaredMainLeaf(
                            name = "freeDebug",
                            extendsFrom = setOf(DEFAULT_VARIANT, "free", "debug"),
                            buildType = "debug",
                            productFlavors = listOf("free")
                        )
                    )
                ),
                ":test-app" to ProjectDeclaredDependencyMetadata(
                    variants = listOf(
                        declaredMainLeaf(
                            name = "freeDebug",
                            extendsFrom = setOf(DEFAULT_VARIANT, "demo", "debug"),
                            buildType = "debug",
                            productFlavors = listOf("demo")
                        )
                    )
                )
            )
        )

        assertEquals(
            listOf(
                BucketPlacementVariantInput(
                    name = "freeDebug",
                    extendsFrom = setOf(DEFAULT_VARIANT, "debug", "free"),
                    buildType = "debug",
                    productFlavors = listOf("free"),
                    leaf = true,
                    projectPath = ":app"
                )
            ),
            metadata.mainBucketVariants(":app")
        )
        assertEquals(
            listOf(
                BucketPlacementVariantInput(
                    name = "freeDebug",
                    extendsFrom = setOf(DEFAULT_VARIANT, "debug", "demo"),
                    buildType = "debug",
                    productFlavors = listOf("demo"),
                    leaf = true,
                    projectPath = ":test-app"
                )
            ),
            metadata.mainBucketVariants(":test-app")
        )
    }

    @Test
    fun `declared owner bucket does not attach to leaf when extendsFrom omits owner`() {
        val dependency = dependency("com.example:free-only:1.0")
        val metadata = DeclaredDependencyMetadata(
            projects = mapOf(
                ":app" to ProjectDeclaredDependencyMetadata(
                    variants = listOf(
                        declaredMainLeaf(
                            name = "freeDebug",
                            extendsFrom = setOf(DEFAULT_VARIANT, "debug"),
                            buildType = "debug",
                            productFlavors = listOf("free"),
                            declaredDependencyDeclarations = setOf(
                                DeclaredExternalDependency(
                                    configurationName = "freeImplementation",
                                    bucketName = "free",
                                    id = dependency.id
                                )
                            )
                        )
                    )
                )
            )
        )

        val variants = metadata.mainBucketVariants(":app")
        val plan = DependencyBucketPlacementEngine().planByProject(
            variants = metadata.mainBucketVariantsByProject(),
            hierarchyBucketClosures = metadata.collectDeclaredMainDependenciesByProjectBucket(listOf(":app")),
            leafClosures = mapOf(
                ProjectDependencyBucket(":app", "freeDebug") to mapOf(dependency.shortId to dependency)
            )
        ).getValue(":app")

        assertEquals(
            setOf(DEFAULT_VARIANT, "debug"),
            variants.single { variant -> variant.name == "freeDebug" }.extendsFrom
        )
        assertNull(plan.hierarchyBuckets["free"])
        assertEquals(
            mapOf(dependency.shortId to dependency),
            plan.leafBuckets["freeDebug"]
        )
    }

    @Test
    fun `compileOnly bucket metadata preserves project bucket ownership`() {
        val appDependency = dependency("com.example:annotations:1.0")
        val libraryDependency = dependency("com.example:annotations:2.0")
        val metadata = DeclaredDependencyMetadata(
            projects = mapOf(
                ":app" to ProjectDeclaredDependencyMetadata(
                    variants = listOf(
                        declaredMainLeaf(
                            name = "debug",
                            compileOnlyDependenciesByShortId = mapOf(
                                appDependency.shortId to appDependency
                            )
                        )
                    )
                ),
                ":lib" to ProjectDeclaredDependencyMetadata(
                    variants = listOf(
                        declaredMainLeaf(
                            name = "debug",
                            compileOnlyDependenciesByShortId = mapOf(
                                libraryDependency.shortId to libraryDependency
                            )
                        )
                    )
                )
            )
        )

        assertEquals(
            mapOf(
                ProjectDependencyBucket(":app", "debug") to mapOf(appDependency.shortId to appDependency),
                ProjectDependencyBucket(":lib", "debug") to mapOf(libraryDependency.shortId to libraryDependency)
            ),
            metadata.collectCompileOnlyDependenciesByProjectBucket(setOf(":app", ":lib"))
        )
    }

    @Test
    fun `planner partitions project-qualified bucket inputs with same names`() {
        val appDependency = dependency("com.example:app-free:1.0")
        val testAppDependency = dependency("com.example:test-app-demo:1.0")

        val plansByProject = DependencyBucketPlacementEngine().planByProject(
            variants = listOf(
                leaf("freeDebug", "debug", "free").copy(projectPath = ":app"),
                leaf("freeRelease", "release", "free").copy(projectPath = ":app"),
                leaf("paidDebug", "debug", "paid").copy(projectPath = ":app"),
                leaf("paidRelease", "release", "paid").copy(projectPath = ":app"),
                leaf("freeDebug", "debug", "demo").copy(projectPath = ":test-app"),
                leaf("freeRelease", "release", "demo").copy(projectPath = ":test-app"),
                leaf("paidDebug", "debug", "prod").copy(projectPath = ":test-app"),
                leaf("paidRelease", "release", "prod").copy(projectPath = ":test-app")
            ),
            hierarchyBucketClosures = emptyMap(),
            leafClosures = mapOf(
                ProjectDependencyBucket(":app", "freeDebug") to mapOf(appDependency.shortId to appDependency),
                ProjectDependencyBucket(":app", "freeRelease") to mapOf(appDependency.shortId to appDependency),
                ProjectDependencyBucket(":app", "paidDebug") to emptyMap(),
                ProjectDependencyBucket(":app", "paidRelease") to emptyMap(),
                ProjectDependencyBucket(":test-app", "freeDebug") to mapOf(
                    testAppDependency.shortId to testAppDependency
                ),
                ProjectDependencyBucket(":test-app", "freeRelease") to mapOf(
                    testAppDependency.shortId to testAppDependency
                ),
                ProjectDependencyBucket(":test-app", "paidDebug") to emptyMap(),
                ProjectDependencyBucket(":test-app", "paidRelease") to emptyMap()
            )
        )

        assertEquals(
            mapOf(appDependency.shortId to appDependency),
            plansByProject[":app"]?.hierarchyBuckets?.get("free")
        )
        assertNull(plansByProject[":app"]?.hierarchyBuckets?.get("demo"))
        assertEquals(
            mapOf(testAppDependency.shortId to testAppDependency),
            plansByProject[":test-app"]?.hierarchyBuckets?.get("demo")
        )
        assertNull(plansByProject[":test-app"]?.hierarchyBuckets?.get("free"))
    }

    @Test
    fun `declared composite owner bucket becomes hierarchy node for matching leaves`() {
        val dependency = dependency("com.example:combo:1.0")
        val metadata = DeclaredDependencyMetadata(
            projects = mapOf(
                ":app" to ProjectDeclaredDependencyMetadata(
                    variants = listOf(
                        declaredMainLeaf(
                            name = "gpsPaxDebug",
                            extendsFrom = setOf(DEFAULT_VARIANT, "debug", "gps", "pax"),
                            buildType = "debug",
                            productFlavors = listOf("gps", "pax"),
                            declaredDependencyDeclarations = setOf(
                                DeclaredExternalDependency(
                                    configurationName = "gpsPaxImplementation",
                                    bucketName = "gpsPax",
                                    id = dependency.id
                                )
                            )
                        ),
                        declaredMainLeaf(
                            name = "gpsOvoDebug",
                            extendsFrom = setOf(DEFAULT_VARIANT, "debug", "gps", "ovo"),
                            buildType = "debug",
                            productFlavors = listOf("gps", "ovo")
                        )
                    )
                )
            )
        )

        val variants = metadata.mainBucketVariants(":app")

        assertEquals(
            BucketPlacementVariantInput(
                name = "gpsPax",
                extendsFrom = setOf(DEFAULT_VARIANT, "gps", "pax"),
                buildType = null,
                productFlavors = listOf("gps", "pax"),
                leaf = false,
                projectPath = ":app"
            ),
            variants.single { variant -> variant.name == "gpsPax" }
        )
        assertEquals(
            setOf(DEFAULT_VARIANT, "debug", "gps", "gpsPax", "pax"),
            variants.single { variant -> variant.name == "gpsPaxDebug" }.extendsFrom
        )

        val plansByProject = DependencyBucketPlacementEngine().planByProject(
            variants = metadata.mainBucketVariantsByProject(),
            hierarchyBucketClosures = metadata.collectDeclaredMainDependenciesByProjectBucket(listOf(":app")),
            leafClosures = mapOf(
                ProjectDependencyBucket(":app", "gpsPaxDebug") to mapOf(dependency.shortId to dependency),
                ProjectDependencyBucket(":app", "gpsOvoDebug") to emptyMap()
            )
        )

        assertEquals(
            mapOf(dependency.shortId to dependency),
            plansByProject.getValue(":app").hierarchyBuckets["gpsPax"]
        )
        assertEquals(emptyMap<String, Map<String, ResolvedDependency>>(), plansByProject.getValue(":app").leafBuckets)
    }

    @Test
    fun `places same artifact with default and build type versions in nearest buckets`() {
        val defaultDependency = dependency("com.example:library:1.0")
        val debugDependency = dependency("com.example:library:2.0")

        val plan = DependencyBucketPlacementEngine().plan(
            variants = listOf(
                leaf("freeDebug", "debug", "free"),
                leaf("freeRelease", "release", "free")
            ),
            hierarchyBucketClosures = mapOf(
                DEFAULT_VARIANT to mapOf(defaultDependency.shortId to defaultDependency),
                "debug" to mapOf(debugDependency.shortId to debugDependency)
            ),
            leafClosures = mapOf(
                "freeDebug" to mapOf(debugDependency.shortId to debugDependency),
                "freeRelease" to mapOf(defaultDependency.shortId to defaultDependency)
            )
        )

        assertEquals(mapOf(defaultDependency.shortId to defaultDependency), plan.defaultBucket)
        assertEquals(mapOf(debugDependency.shortId to debugDependency), plan.hierarchyBuckets["debug"])
        assertEquals(emptyMap<String, Map<String, ResolvedDependency>>(), plan.leafBuckets)
    }

    @Test
    fun `keeps same artifact and version with different excludes in separate nearest buckets`() {
        val defaultDependency = dependency("com.example:library:1.0")
        val debugDependency = dependency("com.example:library:1.0").copy(
            excludeRules = setOf(ExcludeRule("com.example", "blocked"))
        )

        val plan = DependencyBucketPlacementEngine().plan(
            variants = listOf(
                leaf("freeDebug", "debug", "free"),
                leaf("freeRelease", "release", "free")
            ),
            hierarchyBucketClosures = mapOf(
                DEFAULT_VARIANT to mapOf(defaultDependency.shortId to defaultDependency),
                "debug" to mapOf(debugDependency.shortId to debugDependency)
            ),
            leafClosures = mapOf(
                "freeDebug" to mapOf(debugDependency.shortId to debugDependency),
                "freeRelease" to mapOf(defaultDependency.shortId to defaultDependency)
            )
        )

        assertEquals(mapOf(defaultDependency.shortId to defaultDependency), plan.defaultBucket)
        assertEquals(mapOf(debugDependency.shortId to debugDependency), plan.hierarchyBuckets["debug"])
    }

    @Test
    fun `places common flavor dependency in flavor bucket instead of leaf buckets`() {
        val flavorDependency = dependency("com.example:flavor-only:1.0")

        val plan = DependencyBucketPlacementEngine().plan(
            variants = listOf(
                leaf("freeDebug", "debug", "free"),
                leaf("freeRelease", "release", "free"),
                leaf("paidDebug", "debug", "paid")
            ),
            hierarchyBucketClosures = emptyMap(),
            leafClosures = mapOf(
                "freeDebug" to mapOf(flavorDependency.shortId to flavorDependency),
                "freeRelease" to mapOf(flavorDependency.shortId to flavorDependency),
                "paidDebug" to emptyMap()
            )
        )

        assertEquals(emptyMap<String, ResolvedDependency>(), plan.defaultBucket)
        assertEquals(mapOf(flavorDependency.shortId to flavorDependency), plan.hierarchyBuckets["free"])
        assertEquals(emptyMap<String, Map<String, ResolvedDependency>>(), plan.leafBuckets)
    }

    @Test
    fun `does not infer hierarchy buckets that are absent from extendsFrom metadata`() {
        val sharedDependency = dependency("com.example:shared:1.0")

        val plan = DependencyBucketPlacementEngine().plan(
            variants = listOf(
                leafWithParents("freeDebug", setOf(DEFAULT_VARIANT, "debug"), "debug", "free"),
                leafWithParents("freeRelease", setOf(DEFAULT_VARIANT, "release"), "release", "free"),
                leafWithParents("paidDebug", setOf(DEFAULT_VARIANT, "debug"), "debug", "paid")
            ),
            hierarchyBucketClosures = emptyMap(),
            leafClosures = mapOf(
                "freeDebug" to mapOf(sharedDependency.shortId to sharedDependency),
                "freeRelease" to mapOf(sharedDependency.shortId to sharedDependency),
                "paidDebug" to emptyMap()
            )
        )

        assertNull(plan.hierarchyBuckets["free"])
        assertEquals(
            mapOf(sharedDependency.shortId to sharedDependency),
            plan.leafBuckets["freeDebug"]
        )
        assertEquals(
            mapOf(sharedDependency.shortId to sharedDependency),
            plan.leafBuckets["freeRelease"]
        )
    }

    @Test
    fun `does not emit explicit hierarchy bucket absent from selected extendsFrom metadata`() {
        val declaredFlavorDependency = dependency("com.example:flavor-root:1.0").copy(
            repository = DECLARED_DEPENDENCY_REPOSITORY,
            dependencies = emptySet()
        )
        val selectedDependency = dependency("com.example:selected:1.0")

        val plan = DependencyBucketPlacementEngine().plan(
            variants = listOf(
                leafWithParents("freeDebug", setOf(DEFAULT_VARIANT, "debug"), "debug", "free"),
                leafWithParents("freeRelease", setOf(DEFAULT_VARIANT, "release"), "release", "free")
            ),
            hierarchyBucketClosures = mapOf(
                "free" to mapOf(declaredFlavorDependency.shortId to declaredFlavorDependency)
            ),
            leafClosures = mapOf(
                "freeDebug" to mapOf(selectedDependency.shortId to selectedDependency),
                "freeRelease" to mapOf(selectedDependency.shortId to selectedDependency)
            )
        )

        assertNull(plan.hierarchyBuckets["free"])
        assertEquals(
            listOf(CoveredDependency(DEFAULT_VARIANT, selectedDependency)),
            plan.coveredDependencies()
        )
    }

    @Test
    fun `explicit hierarchy bucket keeps inferred common leaf closure`() {
        val declaredFlavorDependency = dependency("com.example:flavor-root:1.0").copy(
            repository = "Declared",
            dependencies = emptySet(),
            excludeRules = setOf(ExcludeRule("com.example", "blocked"))
        )
        val resolvedFlavorDependency = dependency("com.example:flavor-root:1.0").copy(
            dependencies = setOf("com.example:transitive:1.0:maven:false:null")
        )
        val commonTransitiveDependency = dependency("androidx.lifecycle:lifecycle-runtime:2.8.3").copy(
            direct = false
        )

        val plan = DependencyBucketPlacementEngine().plan(
            variants = listOf(
                leaf("gpsPaxDebug", "debug", "gps", "pax"),
                leaf("gpsOvoDebug", "debug", "gps", "ovo")
            ),
            hierarchyBucketClosures = mapOf(
                "gps" to mapOf(declaredFlavorDependency.shortId to declaredFlavorDependency)
            ),
            leafClosures = mapOf(
                "gpsPaxDebug" to mapOf(
                    resolvedFlavorDependency.shortId to resolvedFlavorDependency,
                    commonTransitiveDependency.shortId to commonTransitiveDependency
                ),
                "gpsOvoDebug" to mapOf(
                    resolvedFlavorDependency.shortId to resolvedFlavorDependency,
                    commonTransitiveDependency.shortId to commonTransitiveDependency
                )
            )
        )

        assertEquals(
            mapOf(
                resolvedFlavorDependency.shortId to resolvedFlavorDependency.copy(
                    excludeRules = declaredFlavorDependency.excludeRules
                ),
                commonTransitiveDependency.shortId to commonTransitiveDependency
            ),
            plan.hierarchyBuckets["gps"]
        )
        assertEquals(emptyMap<String, Map<String, ResolvedDependency>>(), plan.leafBuckets)
    }

    @Test
    fun `declared hierarchy metadata adopts Gradle resolved leaf version`() {
        val declaredDebugDependency = dependency("com.example:library:1.0").copy(
            repository = DECLARED_DEPENDENCY_REPOSITORY,
            dependencies = emptySet(),
            excludeRules = setOf(ExcludeRule("com.example", "blocked"))
        )
        val resolvedDebugDependency = dependency("com.example:library:2.0").copy(
            dependencies = setOf("com.example:transitive:2.0:maven:false:null")
        )

        val plan = DependencyBucketPlacementEngine().plan(
            variants = listOf(
                leaf("freeDebug", "debug", "free")
            ),
            hierarchyBucketClosures = mapOf(
                "debug" to mapOf(declaredDebugDependency.shortId to declaredDebugDependency)
            ),
            leafClosures = mapOf(
                "freeDebug" to mapOf(resolvedDebugDependency.shortId to resolvedDebugDependency)
            )
        )

        assertEquals(
            mapOf(
                resolvedDebugDependency.shortId to resolvedDebugDependency.copy(
                    excludeRules = declaredDebugDependency.excludeRules
                )
            ),
            plan.hierarchyBuckets["debug"]
        )
    }

    @Test
    fun `places dependency only used by one leaf in that leaf bucket`() {
        val leafDependency = dependency("com.example:leaf-only:1.0")

        val plan = DependencyBucketPlacementEngine().plan(
            variants = listOf(
                leaf("freeDebug", "debug", "free"),
                leaf("freeRelease", "release", "free")
            ),
            hierarchyBucketClosures = emptyMap(),
            leafClosures = mapOf(
                "freeDebug" to mapOf(leafDependency.shortId to leafDependency),
                "freeRelease" to emptyMap()
            )
        )

        assertEquals(mapOf(leafDependency.shortId to leafDependency), plan.leafBuckets["freeDebug"])
        assertEquals(emptyMap<String, Map<String, ResolvedDependency>>(), plan.hierarchyBuckets)
    }

    @Test
    fun `test planner emits test hierarchy buckets without using main bucket names`() {
        val commonDependency = dependency("com.example:test-common:1.0")
        val debugDependency = dependency("com.example:debug-test:1.0")
        val freeDependency = dependency("com.example:free-test:1.0")

        val plan = DependencyBucketPlacementEngine().plan(
            variants = listOf(
                hierarchy("debugUnitTest", setOf("test")),
                leafWithParents("freeDebugUnitTest", setOf("test", "debugUnitTest"), "debug"),
                leafWithParents("paidDebugUnitTest", setOf("test", "debugUnitTest"), "debug")
            ),
            hierarchyBucketClosures = emptyMap(),
            leafClosures = mapOf(
                "freeDebugUnitTest" to mapOf(
                    commonDependency.shortId to commonDependency,
                    debugDependency.shortId to debugDependency,
                    freeDependency.shortId to freeDependency
                ),
                "paidDebugUnitTest" to mapOf(
                    commonDependency.shortId to commonDependency,
                    debugDependency.shortId to debugDependency
                )
            ),
            baseBucketName = "test"
        )

        assertEquals(emptyMap<String, ResolvedDependency>(), plan.defaultBucket)
        assertEquals(
            mapOf(
                commonDependency.shortId to commonDependency,
                debugDependency.shortId to debugDependency
            ),
            plan.hierarchyBuckets["debugUnitTest"]
        )
        assertNull(plan.hierarchyBuckets["debug"])
        assertEquals(mapOf(freeDependency.shortId to freeDependency), plan.leafBuckets["freeDebugUnitTest"])
    }

    @Test
    fun `explicit declared base bucket dependency is dropped when absent from selected leaves`() {
        val declaredDependency = dependency("com.example:android-test-helper:1.0")
            .copy(repository = DECLARED_DEPENDENCY_REPOSITORY)
        val leafDependency = dependency("com.example:leaf-only:1.0")

        val plan = DependencyBucketPlacementEngine().plan(
            variants = listOf(
                leafWithParents(
                    name = "freeDebugAndroidTest",
                    extendsFrom = setOf(ANDROID_TEST_VARIANT, "freeDebug"),
                    buildType = "debug"
                )
            ),
            hierarchyBucketClosures = mapOf(
                ANDROID_TEST_VARIANT to mapOf(declaredDependency.shortId to declaredDependency)
            ),
            leafClosures = mapOf(
                "freeDebugAndroidTest" to mapOf(leafDependency.shortId to leafDependency)
            ),
            baseBucketName = ANDROID_TEST_VARIANT
        )

        assertEquals(emptyMap<String, ResolvedDependency>(), plan.defaultBucket)
        assertEquals(
            mapOf(leafDependency.shortId to leafDependency),
            plan.leafBuckets["freeDebugAndroidTest"]
        )
    }

    @Test
    fun `test bucket variant inputs retain main parents for hierarchy graph modeling`() {
        val metadata = DeclaredDependencyMetadata(
            projects = mapOf(
                ":app" to ProjectDeclaredDependencyMetadata(
                    variants = listOf(
                        declaredVariant(
                            name = "freeDebug",
                            variantType = AndroidBuild,
                            extendsFrom = setOf(DEFAULT_VARIANT, "debug", "free"),
                            leaf = true
                        ),
                        declaredVariant(
                            name = TEST_VARIANT,
                            variantType = TestVariantType,
                            extendsFrom = setOf(DEFAULT_VARIANT),
                            leaf = false
                        ),
                        declaredVariant(
                            name = "debugUnitTest",
                            variantType = TestVariantType,
                            extendsFrom = setOf(TEST_VARIANT),
                            leaf = false
                        ),
                        declaredVariant(
                            name = "freeDebugUnitTest",
                            variantType = TestVariantType,
                            extendsFrom = setOf(TEST_VARIANT, "debugUnitTest", "freeDebug"),
                            leaf = true
                        ),
                        declaredVariant(
                            name = ANDROID_TEST_VARIANT,
                            variantType = AndroidTest,
                            extendsFrom = setOf(DEFAULT_VARIANT, TEST_VARIANT),
                            leaf = false
                        ),
                        declaredVariant(
                            name = "freeDebugAndroidTest",
                            variantType = AndroidTest,
                            extendsFrom = setOf(ANDROID_TEST_VARIANT, "freeDebug"),
                            leaf = true
                        )
                    )
                )
            )
        )

        assertEquals(
            setOf(TEST_VARIANT, "debugUnitTest", "freeDebug"),
            metadata.testBucketVariantsByProject(TestVariantType, TEST_VARIANT)
                .single { variant -> variant.name == "freeDebugUnitTest" }
                .extendsFrom
        )
        assertEquals(
            setOf(ANDROID_TEST_VARIANT, "freeDebug"),
            metadata.testBucketVariantsByProject(AndroidTest, ANDROID_TEST_VARIANT)
                .single { variant -> variant.name == "freeDebugAndroidTest" }
                .extendsFrom
        )
    }

    @Test
    fun `does not promote filtered leaf dependency to default when non default hierarchy owns it`() {
        val debugDependency = dependency("com.example:debug-only:1.0")

        val plan = DependencyBucketPlacementEngine().plan(
            variants = listOf(
                leaf("freeDebug", "debug", "free")
            ),
            hierarchyBucketClosures = mapOf(
                "debug" to mapOf(debugDependency.shortId to debugDependency)
            ),
            leafClosures = mapOf(
                "freeDebug" to mapOf(debugDependency.shortId to debugDependency)
            )
        )

        assertEquals(emptyMap<String, ResolvedDependency>(), plan.defaultBucket)
        assertEquals(mapOf(debugDependency.shortId to debugDependency), plan.hierarchyBuckets["debug"])
    }

    @Test
    fun `does not emit hierarchy dependency absent from selected descendant leaves`() {
        val productionOnlyDependency = dependency("com.example:production-only:1.0")
        val selectedLeafDependency = dependency("com.example:selected-leaf:1.0")

        val plan = DependencyBucketPlacementEngine().plan(
            variants = listOf(
                leaf("gpsMoveitDebug", "debug", "gps", "moveit"),
                leaf("hmsMoveitDebug", "debug", "hms", "moveit")
            ),
            hierarchyBucketClosures = mapOf(
                "moveit" to mapOf(productionOnlyDependency.shortId to productionOnlyDependency)
            ),
            leafClosures = mapOf(
                "gpsMoveitDebug" to mapOf(selectedLeafDependency.shortId to selectedLeafDependency),
                "hmsMoveitDebug" to emptyMap()
            )
        )

        assertNull(
            "Hierarchy deps from filtered/unselected leaves must not create a Maven repo",
            plan.hierarchyBuckets["moveit"]
        )
        assertEquals(mapOf(selectedLeafDependency.shortId to selectedLeafDependency), plan.leafBuckets["gpsMoveitDebug"])
    }

    @Test
    fun `does not emit default hierarchy dependency absent from selected leaves`() {
        val excludedDependency = dependency("com.example:excluded:1.0")
        val selectedLeafDependency = dependency("com.example:selected-leaf:1.0")

        val plan = DependencyBucketPlacementEngine().plan(
            variants = listOf(
                leaf("flavor2Debug", "debug", "flavor2")
            ),
            hierarchyBucketClosures = mapOf(
                DEFAULT_VARIANT to mapOf(excludedDependency.shortId to excludedDependency)
            ),
            leafClosures = mapOf(
                "flavor2Debug" to mapOf(selectedLeafDependency.shortId to selectedLeafDependency)
            )
        )

        assertEquals(emptyMap<String, ResolvedDependency>(), plan.defaultBucket)
        assertEquals(
            mapOf(selectedLeafDependency.shortId to selectedLeafDependency),
            plan.leafBuckets["flavor2Debug"]
        )
    }

    @Test
    fun `promotes common dependency across filtered debug leaves to debug bucket not default`() {
        val debugDependency = dependency("com.example:debug-only:1.0")
        val dependencyMap = mapOf(debugDependency.shortId to debugDependency)

        val plan = DependencyBucketPlacementEngine().plan(
            variants = listOf(
                leaf("demoFreeDebug", "debug", "demo", "free"),
                leaf("demoPaidDebug", "debug", "demo", "paid"),
                leaf("fullFreeDebug", "debug", "full", "free"),
                leaf("fullPaidDebug", "debug", "full", "paid")
            ),
            hierarchyBucketClosures = emptyMap(),
            leafClosures = mapOf(
                "demoFreeDebug" to dependencyMap,
                "demoPaidDebug" to dependencyMap,
                "fullFreeDebug" to dependencyMap,
                "fullPaidDebug" to dependencyMap
            )
        )

        assertEquals(emptyMap<String, ResolvedDependency>(), plan.defaultBucket)
        assertEquals(dependencyMap, plan.hierarchyBuckets["debug"])
        assertEquals(emptyMap<String, Map<String, ResolvedDependency>>(), plan.leafBuckets)
    }

    @Test
    fun `does not infer default bucket from a single selected leaf closure`() {
        val leafDependency = dependency("com.example:leaf-only:1.0")

        val plan = DependencyBucketPlacementEngine().plan(
            variants = listOf(
                leaf("freeDebug", "debug", "free")
            ),
            hierarchyBucketClosures = emptyMap(),
            leafClosures = mapOf(
                "freeDebug" to mapOf(leafDependency.shortId to leafDependency)
            )
        )

        assertEquals(emptyMap<String, ResolvedDependency>(), plan.defaultBucket)
        assertEquals(mapOf(leafDependency.shortId to leafDependency), plan.leafBuckets["freeDebug"])
    }

    @Test
    fun `explicit deeper hierarchy bucket wins over inferred ancestor bucket`() {
        val freeDependency = dependency("com.example:free-only:1.0")

        val plan = DependencyBucketPlacementEngine().plan(
            variants = listOf(
                hierarchy("debug", setOf(DEFAULT_VARIANT), buildType = "debug"),
                hierarchy("free", setOf("debug"), productFlavors = listOf("free")),
                leafWithParents("demoFreeDebug", setOf("free"), "debug", "free"),
                leafWithParents("fullFreeDebug", setOf("free"), "debug", "free")
            ),
            hierarchyBucketClosures = mapOf(
                "free" to mapOf(freeDependency.shortId to freeDependency)
            ),
            leafClosures = mapOf(
                "demoFreeDebug" to mapOf(freeDependency.shortId to freeDependency),
                "fullFreeDebug" to mapOf(freeDependency.shortId to freeDependency)
            )
        )

        assertNull(plan.hierarchyBuckets["debug"])
        assertEquals(mapOf(freeDependency.shortId to freeDependency), plan.hierarchyBuckets["free"])
        assertEquals(emptyMap<String, Map<String, ResolvedDependency>>(), plan.leafBuckets)
    }

    @Test
    fun `explicit deeper hierarchy bucket wins over explicit ancestor bucket`() {
        val dependency = dependency("com.example:explicit-owner:1.0")
        val dependencyMap = mapOf(dependency.shortId to dependency)

        val plan = DependencyBucketPlacementEngine().plan(
            variants = listOf(
                hierarchy("debug", setOf(DEFAULT_VARIANT), buildType = "debug"),
                hierarchy("free", setOf("debug"), productFlavors = listOf("free")),
                leafWithParents("demoFreeDebug", setOf("free"), "debug", "free"),
                leafWithParents("fullFreeDebug", setOf("free"), "debug", "free")
            ),
            hierarchyBucketClosures = mapOf(
                "debug" to dependencyMap,
                "free" to dependencyMap
            ),
            leafClosures = mapOf(
                "demoFreeDebug" to dependencyMap,
                "fullFreeDebug" to dependencyMap
            )
        )

        assertNull(plan.hierarchyBuckets["debug"])
        assertEquals(dependencyMap, plan.hierarchyBuckets["free"])
        assertEquals(emptyMap<String, Map<String, ResolvedDependency>>(), plan.leafBuckets)
    }

    @Test
    fun `leaf named hierarchy bucket is emitted as leaf bucket`() {
        val dependency = dependency("com.example:compile-only:1.0")
        val dependencyMap = mapOf(dependency.shortId to dependency)

        val plan = DependencyBucketPlacementEngine().plan(
            variants = listOf(
                leaf("freeDebug", "debug", "free")
            ),
            hierarchyBucketClosures = mapOf(
                "freeDebug" to dependencyMap
            ),
            leafClosures = emptyMap()
        )

        assertEquals(dependencyMap, plan.leafBuckets["freeDebug"])
        assertEquals(
            listOf(CoveredDependency("freeDebug", dependency)),
            plan.coveredDependencies()
        )
    }

    @Test
    fun `overlapping peer flavor buckets keep independent ownership`() {
        val dependency = dependency("com.example:flavor-overlap:1.0")
        val dependencyMap = mapOf(dependency.shortId to dependency)

        val plan = DependencyBucketPlacementEngine().plan(
            variants = listOf(
                leaf("demoFreeDebug", "debug", "demo", "free"),
                leaf("demoPaidDebug", "debug", "demo", "paid"),
                leaf("fullFreeDebug", "debug", "full", "free"),
                leaf("fullPaidDebug", "debug", "full", "paid")
            ),
            hierarchyBucketClosures = mapOf(
                "demo" to dependencyMap,
                "free" to dependencyMap,
                "full" to dependencyMap,
                "paid" to dependencyMap
            ),
            leafClosures = mapOf(
                "demoFreeDebug" to dependencyMap,
                "demoPaidDebug" to dependencyMap,
                "fullFreeDebug" to dependencyMap,
                "fullPaidDebug" to dependencyMap
            )
        )

        assertEquals(dependencyMap, plan.hierarchyBuckets["demo"])
        assertEquals(dependencyMap, plan.hierarchyBuckets["free"])
        assertEquals(dependencyMap, plan.hierarchyBuckets["full"])
        assertEquals(dependencyMap, plan.hierarchyBuckets["paid"])
        assertEquals(emptyMap<String, Map<String, ResolvedDependency>>(), plan.leafBuckets)
    }

    @Test
    fun `selected peer bucket covering all candidate leaves suppresses candidate bucket`() {
        val dependency = dependency("com.example:debug-only:1.0")
        val dependencyMap = mapOf(dependency.shortId to dependency)

        val plan = DependencyBucketPlacementEngine().plan(
            variants = listOf(
                leaf("demoFreeDebug", "debug", "demo", "free"),
                leaf("demoPaidDebug", "debug", "demo", "paid"),
                leaf("fullFreeDebug", "debug", "full", "free"),
                leaf("fullPaidDebug", "debug", "full", "paid")
            ),
            hierarchyBucketClosures = mapOf(
                "debug" to dependencyMap
            ),
            leafClosures = mapOf(
                "demoFreeDebug" to dependencyMap,
                "demoPaidDebug" to dependencyMap,
                "fullFreeDebug" to dependencyMap,
                "fullPaidDebug" to dependencyMap
            )
        )

        assertEquals(dependencyMap, plan.hierarchyBuckets["debug"])
        assertEquals(setOf("debug"), plan.hierarchyBuckets.keys)
        assertEquals(emptyMap<String, Map<String, ResolvedDependency>>(), plan.leafBuckets)
    }

    @Test
    fun `leaf main buckets are included in covered dependencies`() {
        val leafDependency = dependency("com.example:leaf-only:1.0")

        val plan = DependencyBucketPlacementEngine().plan(
            variants = listOf(
                leaf("freeDebug", "debug", "free"),
                leaf("freeRelease", "release", "free")
            ),
            hierarchyBucketClosures = emptyMap(),
            leafClosures = mapOf(
                "freeDebug" to mapOf(leafDependency.shortId to leafDependency),
                "freeRelease" to emptyMap()
            )
        )

        assertEquals(
            listOf(CoveredDependency("freeDebug", leafDependency)),
            plan.coveredDependencies()
        )
    }

    @Test
    fun `owner bucket flavors are derived from typed decomposition not substring matching`() {
        // "driver" contains "er" as a substring — the old string-matching code would incorrectly
        // include "er" as an owner flavor for the "driver" bucket because
        // bucketName.contains("er", ignoreCase = true) == true.
        // Leaf 1: two-dimension leaf — flavors ["er", "driver"], bucket "erDriver"
        // Leaf 2: single-dimension leaf — flavors ["driver"], bucket "driver"
        // Both leaves have "driver" in their candidateOwnerBucketNames, so ownerVariantFor
        // for bucketName="driver" collects matchingLeafCandidates = [leaf1, leaf2].
        // Old code: distinct flavorNames = ["driver", "er"] (sorted by length desc),
        //   "driver".contains("driver") = true, "driver".contains("er") = true
        //   → ownerFlavors = ["driver", "er"] (WRONG — "er" is not a component of "driver")
        // New code uses typed spec from candidateOwnerBucketNames → ownerFlavors = ["driver"] (CORRECT)
        val dependency = dependency("com.example:driver-only:1.0")
        val metadata = DeclaredDependencyMetadata(
            projects = mapOf(
                ":app" to ProjectDeclaredDependencyMetadata(
                    variants = listOf(
                        // Leaf with two flavor dimensions: "er" (dim1) + "driver" (dim2)
                        declaredMainLeaf(
                            name = "erDriverDebug",
                            extendsFrom = setOf(DEFAULT_VARIANT, "debug", "er", "driver"),
                            buildType = "debug",
                            productFlavors = listOf("er", "driver"),
                            declaredDependencyDeclarations = setOf(
                                DeclaredExternalDependency(
                                    configurationName = "driverImplementation",
                                    bucketName = "driver",
                                    id = dependency.id
                                )
                            )
                        ),
                        // Leaf with single flavor dimension: "driver" only
                        declaredMainLeaf(
                            name = "driverDebug",
                            extendsFrom = setOf(DEFAULT_VARIANT, "debug", "driver"),
                            buildType = "debug",
                            productFlavors = listOf("driver")
                        )
                    )
                )
            )
        )

        val variants = metadata.mainBucketVariants(":app")
        val driverOwner = variants.single { it.name == "driver" }

        // The owner bucket "driver" must only carry the typed flavor ["driver"],
        // NOT the spurious "er" that substring matching would inject.
        assertEquals(
            "owner bucket 'driver' must have exactly [driver] as productFlavors, not spurious substring matches",
            listOf("driver"),
            driverOwner.productFlavors
        )
        assertEquals(
            "owner bucket 'driver' extendsFrom must be {main, driver} — not include 'er'",
            setOf(DEFAULT_VARIANT, "driver"),
            driverOwner.extendsFrom
        )
        assertEquals(
            "owner bucket 'driver' must have null buildType",
            null,
            driverOwner.buildType
        )
    }

    @Test
    fun `combo owner bucket flavors exclude standalone flavor whose name is substring of combo name`() {
        // Reproduces the combination-path bug in candidateOwnerBucketSpecs.
        // Flavors: ["free", "demo", "freedemo"] — three distinct names across two dimensions.
        // orderedCombinations produces "freeDemo" from the subset {free, demo}.
        // The old code filters flavors via comboName.contains(f, ignoreCase=true):
        //   "freeDemo".contains("free")     = true  ✓
        //   "freeDemo".contains("demo")     = true  ✓
        //   "freeDemo".contains("freedemo") = true  ✗ (substring collision — BUG)
        // So the old code sets comboFlavors = ["free","demo","freedemo"] for the "freeDemo" bucket.
        // The fix threads the typed subset out of orderedCombinations so no string scan is needed.
        val dependency = dependency("com.example:combo-only:1.0")
        val metadata = DeclaredDependencyMetadata(
            projects = mapOf(
                ":app" to ProjectDeclaredDependencyMetadata(
                    variants = listOf(
                        // Three-flavor leaf: dim1={"free","freedemo"} dim2={"demo"}
                        // Produces a combo "freeDemo" from the subset {free, demo}.
                        declaredMainLeaf(
                            name = "freeDemoDebug",
                            extendsFrom = setOf(DEFAULT_VARIANT, "debug", "free", "demo", "freedemo"),
                            buildType = "debug",
                            productFlavors = listOf("free", "demo", "freedemo"),
                            declaredDependencyDeclarations = setOf(
                                DeclaredExternalDependency(
                                    configurationName = "freeDemoImplementation",
                                    bucketName = "freeDemo",
                                    id = dependency.id
                                )
                            )
                        )
                    )
                )
            )
        )

        val variants = metadata.mainBucketVariants(":app")
        val freeDemoOwner = variants.single { it.name == "freeDemo" }

        // The combo owner "freeDemo" must carry exactly ["free","demo"] — NOT the spurious "freedemo"
        // that the substring scan injects because "freeDemo".contains("freedemo", ignoreCase=true) == true.
        assertEquals(
            "combo owner 'freeDemo' must have productFlavors [free, demo], not spurious 'freedemo'",
            listOf("free", "demo"),
            freeDemoOwner.productFlavors
        )
        assertEquals(
            "combo owner 'freeDemo' extendsFrom must be {main, free, demo} — not include 'freedemo'",
            setOf(DEFAULT_VARIANT, "free", "demo"),
            freeDemoOwner.extendsFrom
        )
    }

    private fun leaf(
        name: String,
        buildType: String,
        vararg productFlavors: String
    ): BucketPlacementVariantInput {
        return leafWithParents(
            name = name,
            extendsFrom = (setOf(DEFAULT_VARIANT, buildType) + productFlavors).toSortedSet(),
            buildType = buildType,
            productFlavors = productFlavors.toList()
        )
    }

    private fun leafWithParents(
        name: String,
        extendsFrom: Set<String>,
        buildType: String,
        vararg productFlavors: String
    ): BucketPlacementVariantInput {
        return leafWithParents(
            name = name,
            extendsFrom = extendsFrom,
            buildType = buildType,
            productFlavors = productFlavors.toList()
        )
    }

    private fun leafWithParents(
        name: String,
        extendsFrom: Set<String>,
        buildType: String,
        productFlavors: List<String>
    ): BucketPlacementVariantInput {
        return BucketPlacementVariantInput(
            name = name,
            extendsFrom = extendsFrom.toSortedSet(),
            buildType = buildType,
            productFlavors = productFlavors,
            leaf = true
        )
    }

    private fun hierarchy(
        name: String,
        extendsFrom: Set<String>,
        buildType: String? = null,
        productFlavors: List<String> = emptyList()
    ): BucketPlacementVariantInput {
        return BucketPlacementVariantInput(
            name = name,
            extendsFrom = extendsFrom.toSortedSet(),
            buildType = buildType,
            productFlavors = productFlavors,
            leaf = false
        )
    }

    private fun dependency(id: String): ResolvedDependency {
        return ResolvedDependency.fromId(id, "maven")
    }

    private fun declaredMainLeaf(
        name: String,
        extendsFrom: Set<String> = setOf(DEFAULT_VARIANT),
        buildType: String? = null,
        productFlavors: List<String> = emptyList(),
        declaredDependencyDeclarations: Set<DeclaredExternalDependency> = emptySet(),
        compileOnlyDependenciesByShortId: Map<String, ResolvedDependency> = emptyMap()
    ): DeclaredVariantDependencyMetadata {
        return DeclaredVariantDependencyMetadata(
            name = name,
            variantType = AndroidBuild,
            extendsFrom = extendsFrom.toSortedSet(),
            variantConfigurationNames = emptySet(),
            compileConfigurationNames = emptySet(),
            runtimeConfigurationNames = emptySet(),
            kspConfigurationNames = emptySet(),
            androidLeafVariant = true,
            buildType = buildType,
            productFlavors = productFlavors,
            declaredDependencies = emptySet(),
            declaredDependencyDeclarations = declaredDependencyDeclarations,
            declaredProjectDependencies = emptySet(),
            excludeRulesByShortId = emptyMap(),
            compileOnlyBucketName = name,
            compileOnlyDependenciesByShortId = compileOnlyDependenciesByShortId
        )
    }

    private fun declaredVariant(
        name: String,
        variantType: com.grab.grazel.gradle.variant.VariantType,
        extendsFrom: Set<String>,
        leaf: Boolean
    ): DeclaredVariantDependencyMetadata {
        return DeclaredVariantDependencyMetadata(
            name = name,
            variantType = variantType,
            extendsFrom = extendsFrom.toSortedSet(),
            variantConfigurationNames = emptySet(),
            compileConfigurationNames = emptySet(),
            runtimeConfigurationNames = emptySet(),
            kspConfigurationNames = emptySet(),
            androidLeafVariant = leaf,
            buildType = null,
            productFlavors = emptyList(),
            declaredDependencies = emptySet(),
            declaredDependencyDeclarations = emptySet(),
            declaredProjectDependencies = emptySet(),
            excludeRulesByShortId = emptyMap(),
            compileOnlyBucketName = name,
            compileOnlyDependenciesByShortId = emptyMap()
        )
    }
}
