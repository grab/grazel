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

package com.grab.grazel.gradle.dependencies.resolution

import com.grab.grazel.gradle.dependencies.AggregatedDependencyRoot
import com.grab.grazel.gradle.dependencies.AggregatedDependencyRootKind
import com.grab.grazel.gradle.dependencies.AggregatedDependencyRootMetadata
import com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadata
import com.grab.grazel.gradle.dependencies.DeclaredProjectDependency
import com.grab.grazel.gradle.dependencies.DeclaredVariantDependencyMetadata
import com.grab.grazel.gradle.dependencies.ProjectDeclaredDependencyMetadata
import com.grab.grazel.gradle.dependencies.model.ExcludeRule
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.variant.ANDROID_TEST_VARIANT
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.TEST_VARIANT
import com.grab.grazel.gradle.variant.VariantType
import com.nhaarman.mockito_kotlin.mock
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootContributionComputerTest {

    private fun variant(
        name: String,
        variantType: VariantType = VariantType.AndroidBuild,
        extendsFrom: Set<String> = emptySet(),
        declaredProjectDependencies: Set<DeclaredProjectDependency> = emptySet(),
        excludeRulesByShortId: Map<String, Set<ExcludeRule>> = emptyMap(),
    ): DeclaredVariantDependencyMetadata = DeclaredVariantDependencyMetadata(
        name = name,
        variantType = variantType,
        extendsFrom = extendsFrom,
        variantConfigurationNames = emptySet(),
        compileConfigurationNames = emptySet(),
        runtimeConfigurationNames = emptySet(),
        kspConfigurationNames = emptySet(),
        androidLeafVariant = false,
        buildType = null,
        productFlavors = emptyList(),
        declaredDependencies = emptySet(),
        declaredDependencyDeclarations = emptySet(),
        declaredProjectDependencies = declaredProjectDependencies,
        excludeRulesByShortId = excludeRulesByShortId,
        compileOnlyBucketName = DEFAULT_VARIANT,
        compileOnlyDependenciesByShortId = emptyMap()
    )

    private fun projectDependency(targetProjectPath: String): DeclaredProjectDependency =
        DeclaredProjectDependency(
            configurationName = "implementation",
            targetProjectPath = targetProjectPath,
            targetConfiguration = "default",
            excludedShortIds = emptySet()
        )

    private fun rootMetadata(
        projectPath: String,
        kind: AggregatedDependencyRootKind,
        bucketName: String? = null,
        leafName: String? = null,
        variantNames: Set<String> = emptySet(),
        variantType: VariantType? = null,
        targetBuckets: Set<String> = emptySet(),
    ) = AggregatedDependencyRootMetadata(
        projectPath = projectPath,
        kind = kind,
        configurationName = "runtimeClasspath",
        bucketName = bucketName,
        leafName = leafName,
        variantNames = variantNames,
        variantType = variantType,
        targetBuckets = targetBuckets
    )

    private fun aggregatedRoot(metadata: AggregatedDependencyRootMetadata): AggregatedDependencyRoot =
        AggregatedDependencyRoot(root = mock<ResolvedComponentResult>(), metadata = metadata)

    private fun sampleDependency(shortId: String): ResolvedDependency = ResolvedDependency(
        id = "$shortId:1.0",
        shortId = shortId,
        version = "1.0",
        direct = true,
        dependencies = sortedSetOf(),
        excludeRules = emptySet(),
        repository = "central",
        requiresJetifier = false
    )

    /** Records every invocation so assertions can inspect what a per-kind branch actually passed. */
    private class RecordingResolver(private val outcome: RootVisitOutcome) {
        data class Invocation(
            val excludeRulesByProjectPath: Map<String, com.grab.grazel.gradle.dependencies.ProjectExcludeRules>,
            val projectEdgeExcludedShortIdsByTargetProject: Map<String, Set<String>>,
            val reachableBucketNamesForProject: ((String, String?) -> Set<String>)?,
        )

        val invocations = mutableListOf<Invocation>()

        val resolve: (
            AggregatedDependencyRoot,
            Map<String, com.grab.grazel.gradle.dependencies.ProjectExcludeRules>,
            Map<String, Set<String>>,
            ((String, String?) -> Set<String>)?
        ) -> RootVisitOutcome = { _, excludeRulesByProjectPath, projectEdgeExcludedShortIdsByTargetProject, reachableBucketNamesForProject ->
            invocations.add(
                Invocation(
                    excludeRulesByProjectPath,
                    projectEdgeExcludedShortIdsByTargetProject,
                    reachableBucketNamesForProject
                )
            )
            outcome
        }
    }

    @Test
    fun `main hierarchy root seeds reachability and routes to hierarchy buckets`() {
        val declaredMetadata = DeclaredDependencyMetadata(
            projects = mapOf(
                ":app" to ProjectDeclaredDependencyMetadata(
                    variants = listOf(
                        variant(
                            name = DEFAULT_VARIANT,
                            declaredProjectDependencies = setOf(projectDependency(":lib"))
                        )
                    )
                ),
                ":lib" to ProjectDeclaredDependencyMetadata(variants = listOf(variant(name = DEFAULT_VARIANT)))
            )
        )
        val tracker = MainReachabilityTracker(declaredMetadata, listOf(":app", ":lib"))
        val fixedOutcome = RootVisitOutcome(
            dependencies = mapOf("g:a" to sampleDependency("g:a")),
            reachableProjectPaths = setOf(":lib"),
            reachableBucketNamesByProject = mapOf(":lib" to setOf(DEFAULT_VARIANT))
        )
        val resolver = RecordingResolver(fixedOutcome)
        val computer = RootContributionComputer(tracker, declaredMetadata, resolver.resolve)

        val root = aggregatedRoot(
            rootMetadata(
                projectPath = ":app",
                kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                bucketName = DEFAULT_VARIANT,
                variantNames = setOf(DEFAULT_VARIANT),
                variantType = VariantType.AndroidBuild,
                targetBuckets = setOf(DEFAULT_VARIANT, TEST_VARIANT)
            )
        )

        val contribution = computer.compute(root)

        // Reachability must already be folded into the tracker by the time compute() returns.
        assertEquals(setOf(":app", ":lib"), tracker.reachableMainProjectPaths)
        assertEquals(setOf(":app", ":lib"), contribution.scope?.reachableProjectPaths)
        assertEquals(fixedOutcome, contribution.outcome)
        assertEquals(
            listOf(
                BucketRouting(BucketTarget.HIERARCHY, DEFAULT_VARIANT),
                BucketRouting(BucketTarget.TEST_HIERARCHY, TEST_VARIANT)
            ),
            contribution.routing
        )
        assertNull(contribution.lintClosure)
        assertTrue(contribution.seedsBinaryRoot)
    }

    @Test
    fun `main leaf root routes test and androidTest bucket names to leaf test maps`() {
        val declaredMetadata = DeclaredDependencyMetadata(
            projects = mapOf(":lib" to ProjectDeclaredDependencyMetadata(variants = listOf(variant(name = "flavorX"))))
        )
        val tracker = MainReachabilityTracker(declaredMetadata, listOf(":lib"))
        val fixedOutcome = RootVisitOutcome(
            dependencies = mapOf("g:a" to sampleDependency("g:a")),
            reachableProjectPaths = emptySet(),
            reachableBucketNamesByProject = emptyMap()
        )
        val resolver = RecordingResolver(fixedOutcome)
        val computer = RootContributionComputer(tracker, declaredMetadata, resolver.resolve)

        val root = aggregatedRoot(
            rootMetadata(
                projectPath = ":lib",
                kind = AggregatedDependencyRootKind.MAIN_LEAF,
                bucketName = "flavorX",
                leafName = "flavorX",
                variantNames = setOf("flavorX"),
                variantType = VariantType.AndroidBuild,
                targetBuckets = setOf(DEFAULT_VARIANT, TEST_VARIANT, ANDROID_TEST_VARIANT)
            )
        )

        val contribution = computer.compute(root)

        assertEquals(
            listOf(
                BucketRouting(BucketTarget.HIERARCHY, DEFAULT_VARIANT),
                BucketRouting(BucketTarget.LEAF_UNIT_TEST, "flavorX"),
                BucketRouting(BucketTarget.LEAF_ANDROID_TEST, "flavorX"),
            ),
            contribution.routing
        )
        assertTrue(contribution.seedsBinaryRoot)
        assertNull(contribution.lintClosure)
    }

    @Test
    fun `lint root produces lint closure and no reachability scope`() {
        val declaredMetadata = DeclaredDependencyMetadata(
            projects = mapOf(":app" to ProjectDeclaredDependencyMetadata(variants = listOf(variant(name = DEFAULT_VARIANT))))
        )
        val tracker = MainReachabilityTracker(declaredMetadata, listOf(":app"))
        val fixedOutcome = RootVisitOutcome(
            dependencies = mapOf("g:lint" to sampleDependency("g:lint")),
            reachableProjectPaths = setOf(":other"),
            reachableBucketNamesByProject = emptyMap()
        )
        val resolver = RecordingResolver(fixedOutcome)
        val computer = RootContributionComputer(tracker, declaredMetadata, resolver.resolve)

        val root = aggregatedRoot(
            rootMetadata(projectPath = ":app", kind = AggregatedDependencyRootKind.LINT)
        )

        val contribution = computer.compute(root)

        assertEquals(fixedOutcome.dependencies, contribution.lintClosure)
        assertTrue(contribution.routing.isEmpty())
        assertNull(contribution.scope)
        assertFalse(contribution.seedsBinaryRoot)
        // LINT never seeds tracker state.
        assertTrue(tracker.reachableMainProjectPaths.isEmpty())
        val invocation = resolver.invocations.single()
        assertTrue(invocation.excludeRulesByProjectPath.isEmpty())
        assertTrue(invocation.projectEdgeExcludedShortIdsByTargetProject.isEmpty())
        assertNull(invocation.reachableBucketNamesForProject)
    }

    @Test
    fun `unit test root uses Test exclude rules and routes to leaf unit test`() {
        val leafVariant = variant(name = "flavorX")
        val testVariant = variant(
            name = "flavorXTest",
            variantType = VariantType.Test,
            extendsFrom = setOf("flavorX"),
            excludeRulesByShortId = mapOf("group:unit-only" to setOf(ExcludeRule("group", "unit-only")))
        )
        val androidTestVariant = variant(
            name = "flavorXAndroidTest",
            variantType = VariantType.AndroidTest,
            extendsFrom = setOf("flavorX"),
            excludeRulesByShortId = mapOf("group:android-only" to setOf(ExcludeRule("group", "android-only")))
        )
        val declaredMetadata = DeclaredDependencyMetadata(
            projects = mapOf(
                ":lib" to ProjectDeclaredDependencyMetadata(
                    variants = listOf(leafVariant, testVariant, androidTestVariant)
                )
            )
        )
        val tracker = MainReachabilityTracker(declaredMetadata, listOf(":lib"))
        val fixedOutcome = RootVisitOutcome(
            dependencies = mapOf("g:a" to sampleDependency("g:a")),
            reachableProjectPaths = emptySet(),
            reachableBucketNamesByProject = emptyMap()
        )
        val resolver = RecordingResolver(fixedOutcome)
        val computer = RootContributionComputer(tracker, declaredMetadata, resolver.resolve)

        val root = aggregatedRoot(
            rootMetadata(
                projectPath = ":lib",
                kind = AggregatedDependencyRootKind.UNIT_TEST,
                bucketName = "flavorX",
                leafName = "flavorX",
                variantNames = setOf("flavorX")
            )
        )

        val contribution = computer.compute(root)

        assertEquals(listOf(BucketRouting(BucketTarget.LEAF_UNIT_TEST, "flavorX")), contribution.routing)
        assertNull(contribution.scope)
        assertNull(contribution.lintClosure)
        assertFalse(contribution.seedsBinaryRoot)

        val invocation = resolver.invocations.single()
        val libExcludeRules = invocation.excludeRulesByProjectPath.getValue(":lib")
        assertEquals(setOf("group:unit-only"), libExcludeRules.bucketRulesByShortId.keys)
    }
}
