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

import com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadata
import com.grab.grazel.gradle.dependencies.DeclaredProjectDependency
import com.grab.grazel.gradle.dependencies.DeclaredProjectType
import com.grab.grazel.gradle.dependencies.DeclaredVariantDependencyMetadata
import com.grab.grazel.gradle.dependencies.ProjectDeclaredDependencyMetadata
import com.grab.grazel.gradle.dependencies.ProjectDependencyBucket
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.VariantType
import org.junit.Assert.assertEquals
import org.junit.Test

class MainReachabilityTrackerTest {

    private fun variant(
        name: String = DEFAULT_VARIANT,
        declaredProjectDependencies: Set<DeclaredProjectDependency> = emptySet()
    ): DeclaredVariantDependencyMetadata = DeclaredVariantDependencyMetadata(
        name = name,
        variantType = VariantType.AndroidBuild,
        extendsFrom = emptySet(),
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
        excludeRulesByShortId = emptyMap(),
        compileOnlyBucketName = DEFAULT_VARIANT,
        compileOnlyDependenciesByShortId = emptyMap()
    )

    private fun projectDependency(
        targetProjectPath: String,
        excludedShortIds: Set<String> = emptySet()
    ): DeclaredProjectDependency = DeclaredProjectDependency(
        configurationName = "implementation",
        targetProjectPath = targetProjectPath,
        targetConfiguration = "default",
        excludedShortIds = excludedShortIds
    )

    private fun metadataFor(
        projects: Map<String, ProjectDeclaredDependencyMetadata>
    ): DeclaredDependencyMetadata = DeclaredDependencyMetadata(projects = projects)

    @Test
    fun `computeScope collects transitive reachable projects and stops on cycles`() {
        // :app -> :a -> :b, and :b -> :a (cycle).
        val metadata = metadataFor(
            mapOf(
                ":app" to ProjectDeclaredDependencyMetadata(
                    variants = listOf(variant(declaredProjectDependencies = setOf(projectDependency(":a"))))
                ),
                ":a" to ProjectDeclaredDependencyMetadata(
                    variants = listOf(variant(declaredProjectDependencies = setOf(projectDependency(":b"))))
                ),
                ":b" to ProjectDeclaredDependencyMetadata(
                    variants = listOf(variant(declaredProjectDependencies = setOf(projectDependency(":a"))))
                )
            )
        )
        val tracker = MainReachabilityTracker(metadata, listOf(":app", ":a", ":b"))

        val scope = tracker.computeScope(
            projectPath = ":app",
            variantNames = setOf(DEFAULT_VARIANT),
            selectedOnly = false
        )

        assertEquals(setOf(":app", ":a", ":b"), scope.reachableProjectPaths)
    }

    @Test
    fun `filterExcludedByEveryReachableRoot keeps a dep excluded on only one reachable edge`() {
        val metadata = metadataFor(
            mapOf(
                ":app" to ProjectDeclaredDependencyMetadata(
                    variants = listOf(variant(declaredProjectDependencies = setOf(projectDependency(":lib"))))
                ),
                ":app2" to ProjectDeclaredDependencyMetadata(
                    variants = listOf(variant(declaredProjectDependencies = setOf(projectDependency(":lib"))))
                ),
                ":lib" to ProjectDeclaredDependencyMetadata(variants = listOf(variant()))
            )
        )
        val tracker = MainReachabilityTracker(metadata, listOf(":app", ":app2", ":lib"))

        // Two roots reach :lib. Fold both scopes via recordMainRoot as collectRootClosures would.
        val excludingScope = MainProjectEdgeScope(
            reachableProjectPaths = setOf(":app", ":lib"),
            reachableBucketNamesByProject = emptyMap(),
            excludedShortIdsByTargetProject = mapOf(":lib" to setOf("group:artifact"))
        )
        val nonExcludingScope = MainProjectEdgeScope(
            reachableProjectPaths = setOf(":app2", ":lib"),
            reachableBucketNamesByProject = emptyMap(),
            excludedShortIdsByTargetProject = emptyMap()
        )
        tracker.recordMainRoot(sampleMainHierarchyMetadata(":app"), excludingScope)
        tracker.recordMainRoot(sampleMainHierarchyMetadata(":app2"), nonExcludingScope)

        val dependenciesByBucket = mapOf(
            ProjectDependencyBucket(":lib", DEFAULT_VARIANT) to mapOf(
                "group:artifact" to sampleResolvedDependency("group:artifact")
            )
        )

        val filtered = tracker.filterExcludedByEveryReachableRoot(dependenciesByBucket)

        assertEquals(
            setOf("group:artifact"),
            filtered.getValue(ProjectDependencyBucket(":lib", DEFAULT_VARIANT)).keys
        )
    }

    @Test
    fun `filterExcludedByEveryReachableRoot drops a dep excluded on every reachable edge`() {
        val metadata = metadataFor(
            mapOf(
                ":app" to ProjectDeclaredDependencyMetadata(
                    variants = listOf(variant(declaredProjectDependencies = setOf(projectDependency(":lib"))))
                ),
                ":app2" to ProjectDeclaredDependencyMetadata(
                    variants = listOf(variant(declaredProjectDependencies = setOf(projectDependency(":lib"))))
                ),
                ":lib" to ProjectDeclaredDependencyMetadata(variants = listOf(variant()))
            )
        )
        val tracker = MainReachabilityTracker(metadata, listOf(":app", ":app2", ":lib"))

        val firstExcludingScope = MainProjectEdgeScope(
            reachableProjectPaths = setOf(":app", ":lib"),
            reachableBucketNamesByProject = emptyMap(),
            excludedShortIdsByTargetProject = mapOf(":lib" to setOf("group:artifact"))
        )
        val secondExcludingScope = MainProjectEdgeScope(
            reachableProjectPaths = setOf(":app2", ":lib"),
            reachableBucketNamesByProject = emptyMap(),
            excludedShortIdsByTargetProject = mapOf(":lib" to setOf("group:artifact"))
        )
        tracker.recordMainRoot(sampleMainHierarchyMetadata(":app"), firstExcludingScope)
        tracker.recordMainRoot(sampleMainHierarchyMetadata(":app2"), secondExcludingScope)

        val dependenciesByBucket = mapOf(
            ProjectDependencyBucket(":lib", DEFAULT_VARIANT) to mapOf(
                "group:artifact" to sampleResolvedDependency("group:artifact")
            )
        )

        val filtered = tracker.filterExcludedByEveryReachableRoot(dependenciesByBucket)

        assertEquals(
            emptySet<String>(),
            filtered.getValue(ProjectDependencyBucket(":lib", DEFAULT_VARIANT)).keys
        )
    }

    @Test
    fun `selectedMainVariantHierarchyNames falls back to default when known`() {
        val metadataWithDefault = metadataFor(
            mapOf(
                ":lib" to ProjectDeclaredDependencyMetadata(
                    projectType = DeclaredProjectType.OTHER,
                    variants = listOf(variant(name = DEFAULT_VARIANT))
                )
            )
        )
        val trackerWithDefault = MainReachabilityTracker(metadataWithDefault, listOf(":lib"))
        assertEquals(
            setOf(DEFAULT_VARIANT),
            trackerWithDefault.selectedMainVariantHierarchyNames(":lib", "unrelatedDisplayName")
        )

        val metadataWithoutDefault = metadataFor(
            mapOf(
                ":lib" to ProjectDeclaredDependencyMetadata(
                    projectType = DeclaredProjectType.OTHER,
                    variants = listOf(variant(name = "custom"))
                )
            )
        )
        val trackerWithoutDefault = MainReachabilityTracker(metadataWithoutDefault, listOf(":lib"))
        assertEquals(
            emptySet<String>(),
            trackerWithoutDefault.selectedMainVariantHierarchyNames(":lib", "unrelatedDisplayName")
        )
    }

    private fun sampleMainHierarchyMetadata(
        projectPath: String
    ) = com.grab.grazel.gradle.dependencies.AggregatedDependencyRootMetadata(
        projectPath = projectPath,
        kind = com.grab.grazel.gradle.dependencies.AggregatedDependencyRootKind.MAIN_HIERARCHY,
        configurationName = "runtimeClasspath"
    )

    private fun sampleResolvedDependency(shortId: String) = ResolvedDependency(
        id = "$shortId:1.0",
        shortId = shortId,
        version = "1.0",
        direct = true,
        dependencies = sortedSetOf(),
        excludeRules = emptySet(),
        repository = "central",
        requiresJetifier = false
    )
}
