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
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.JvmVariant
import com.grab.grazel.gradle.variant.Variant
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.JvmBuild
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

class AggregatedDependencyResolverTest {

    @Test
    fun `keeps child bucket dependency when covered dependency has same short id but different version`() {
        val defaultDependency = dependency("com.example:library:1.0")
        val debugDependency = dependency("com.example:library:2.0")
        val childBucket = mapOf(debugDependency.shortId to debugDependency)

        val filteredBucket = childBucket.withoutDependenciesCoveredBy(
            listOf(covered("default", defaultDependency))
        )

        assertEquals(childBucket, filteredBucket)
    }

    @Test
    fun `removes child bucket dependency when direct parent dependency has same identity`() {
        val debugDependency = dependency("com.example:library:1.0")
        val flavorDependency = dependency("com.example:library:1.0")
        val childBucket = mapOf(flavorDependency.shortId to flavorDependency)

        val filteredBucket = childBucket.withoutDependenciesCoveredBy(
            listOf(covered("debug", debugDependency))
        )

        assertEquals(emptyMap<String, ResolvedDependency>(), filteredBucket)
    }

    @Test
    fun `keeps child bucket dependency as override carrier when only transitive closure differs`() {
        val debugDependency = dependency("com.example:library:1.0")
        val flavorDependency = dependency("com.example:library:1.0").copy(
            dependencies = setOf("com.example:transitive:1.0:maven:false:null")
        )
        val childBucket = mapOf(flavorDependency.shortId to flavorDependency)

        val filteredBucket = childBucket.withoutDependenciesCoveredBy(
            listOf(covered("debug", debugDependency))
        )

        assertEquals(
            mapOf(
                flavorDependency.shortId to flavorDependency.copy(
                    overrideTarget = OverrideTarget(
                        artifactShortId = flavorDependency.shortId,
                        label = MavenDependency(
                            repo = "debug_maven",
                            group = "com.example",
                            name = "library"
                        )
                    )
                )
            ),
            filteredBucket
        )
    }

    @Test
    fun `keeps child bucket dependency when same identity parent dependency is only transitive`() {
        val transitiveDefaultDependency = dependency("com.example:library:1.0").copy(direct = false)
        val directChildDependency = dependency("com.example:library:1.0")
        val childBucket = mapOf(directChildDependency.shortId to directChildDependency)

        val filteredBucket = childBucket.withoutDependenciesCoveredBy(
            listOf(covered("default", transitiveDefaultDependency))
        )

        assertEquals(childBucket, filteredBucket)
    }

    @Test
    fun `keeps test bucket dependency when direct main dependency has same short id but different version`() {
        val mainDependency = dependency("com.example:library:1.0")
        val testDependency = dependency("com.example:library:2.0")
        val testBucket = mapOf(testDependency.shortId to testDependency)

        val filteredBucket = testBucket.withoutDependenciesCoveredByDirectDependencies(
            listOf(covered("default", mainDependency))
        )

        assertEquals(testBucket, filteredBucket)
    }

    @Test
    fun `does not intersect same short id with different bucket owner identity`() {
        val defaultDependency = dependency("com.example:library:1.0")
        val debugDependency = dependency("com.example:library:2.0")

        val intersection = intersectByBucketOwner(
            listOf(
                mapOf(defaultDependency.shortId to defaultDependency),
                mapOf(debugDependency.shortId to debugDependency)
            )
        )

        assertEquals(emptyMap<String, ResolvedDependency>(), intersection)
    }

    @Test
    fun `merges duplicate dependency metadata while keeping max version representative`() {
        val excludeRule = ExcludeRule("com.example", "blocked")
        val lowerVersionWithExclude = dependency("com.example:library:1.0").copy(
            dependencies = setOf("com.example:lower-transitive:1.0:maven:false:null"),
            excludeRules = setOf(excludeRule)
        )
        val higherVersionWithoutExclude = dependency("com.example:library:2.0").copy(
            dependencies = setOf("com.example:higher-transitive:2.0:maven:false:null")
        )

        val merged = mergeDependencyMetadataByMaxVersion(
            higherVersionWithoutExclude,
            lowerVersionWithExclude
        )

        assertEquals("2.0", merged.version)
        assertEquals(higherVersionWithoutExclude.dependencies, merged.dependencies)
        assertEquals(setOf(excludeRule), merged.excludeRules)
    }

    @Test
    fun `keeps default dependency when non default hierarchy has same short id with different identity`() {
        val defaultDependency = dependency("com.example:library:1.0")
        val debugDependency = dependency("com.example:library:2.0")
        val defaultBucket = mapOf(defaultDependency.shortId to defaultDependency)

        val filteredBucket = defaultBucket.withoutDependenciesOwnedByNonDefaultHierarchy(
            hierarchyDefaultDeps = emptyMap(),
            nonDefaultHierarchyDependencies = listOf(debugDependency)
        )

        assertEquals(defaultBucket, filteredBucket)
    }

    @Test
    fun `removes default dependency when same owner exists only in non default hierarchy`() {
        val defaultDependency = dependency("com.example:library:1.0")
        val debugDependency = dependency("com.example:library:1.0")
        val defaultBucket = mapOf(defaultDependency.shortId to defaultDependency)

        val filteredBucket = defaultBucket.withoutDependenciesOwnedByNonDefaultHierarchy(
            hierarchyDefaultDeps = emptyMap(),
            nonDefaultHierarchyDependencies = listOf(debugDependency)
        )

        assertEquals(emptyMap<String, ResolvedDependency>(), filteredBucket)
    }

    @Test
    fun `does not apply child only excludes to parent configuration metadata`() {
        val project = ProjectBuilder.builder().build()
        val implementation = project.configurations.create("implementation")
        val debugImplementation = project.configurations.create("debugImplementation")
        val debugRuntimeClasspath = project.configurations.create("debugRuntimeClasspath").apply {
            extendsFrom(implementation, debugImplementation)
        }

        project.dependencies.add("implementation", "com.example:library:1.0")
        val debugDependency = project.dependencies.add(
            "debugImplementation",
            "com.example:library:1.0"
        ) as ExternalModuleDependency
        debugDependency.exclude(mapOf("group" to "com.example", "module" to "blocked"))

        assertEquals(emptyMap<String, Set<ExcludeRule>>(), implementation.extractExcludeRulesByShortId())
        assertEquals(
            mapOf("com.example:library" to setOf(ExcludeRule("com.example", "blocked"))),
            debugRuntimeClasspath.extractExcludeRulesByShortId()
        )
    }

    @Test
    fun `declared exclude metadata ignores inherited classpath dependencies`() {
        val project = ProjectBuilder.builder().build()
        val implementation = project.configurations.create("implementation")
        val debugImplementation = project.configurations.create("debugImplementation")
        val debugRuntimeClasspath = project.configurations.create("debugRuntimeClasspath").apply {
            extendsFrom(implementation, debugImplementation)
        }

        val mainDependency = project.dependencies.add(
            "implementation",
            "com.example:library:1.0"
        ) as ExternalModuleDependency
        mainDependency.exclude(mapOf("group" to "com.example", "module" to "main-blocked"))
        val debugDependency = project.dependencies.add(
            "debugImplementation",
            "com.example:library:1.0"
        ) as ExternalModuleDependency
        debugDependency.exclude(mapOf("group" to "com.example", "module" to "debug-blocked"))
        val classpathDependency = project.dependencies.add(
            "debugRuntimeClasspath",
            "com.example:library:1.0"
        ) as ExternalModuleDependency
        classpathDependency.exclude(mapOf("group" to "com.example", "module" to "classpath-blocked"))

        assertEquals(emptyMap<String, Set<ExcludeRule>>(), debugRuntimeClasspath.extractDeclaredExcludeRulesByShortId())
        assertEquals(
            mapOf("com.example:library" to setOf(ExcludeRule("com.example", "debug-blocked"))),
            listOf(debugRuntimeClasspath, debugImplementation).extractDeclaredExcludeRulesByShortId()
        )
    }

    @Test
    fun `declared exclude metadata skips dependencies without group`() {
        val project = ProjectBuilder.builder().build()
        val implementation = project.configurations.create("implementation")
        val blankGroupDependency = project.dependencies.add(
            "implementation",
            project.dependencies.create(mapOf("group" to "", "name" to "library", "version" to "1.0"))
        ) as ExternalModuleDependency
        blankGroupDependency.exclude(mapOf("group" to "com.example", "module" to "blocked"))

        assertEquals(emptyMap<String, Set<ExcludeRule>>(), implementation.extractDeclaredExcludeRulesByShortId())
    }

    @Test
    fun `uses owner project excludes without applying root or sibling excludes`() {
        val shortId = "com.example:library"
        val rootRule = ExcludeRule("com.example", "root-blocked")
        val ownerRule = ExcludeRule("com.example", "owner-blocked")
        val siblingRule = ExcludeRule("com.example", "sibling-blocked")

        val excludeRules = mapOf(
            ":lib" to ProjectExcludeRules(
                bucketRulesByShortId = mapOf(shortId to setOf(ownerRule)),
                variantRulesByName = emptyMap(),
                variantHierarchyNamesByName = emptyMap()
            ),
            ":sibling" to ProjectExcludeRules(
                bucketRulesByShortId = mapOf(shortId to setOf(siblingRule)),
                variantRulesByName = emptyMap(),
                variantHierarchyNamesByName = emptyMap()
            )
        )

        assertEquals(
            setOf(ownerRule),
            excludeRules.excludeRulesFor(
                rootProjectPath = ":app",
                rootExcludeRulesByShortId = mapOf(shortId to setOf(rootRule)),
                ownerProjectPath = ":lib",
                ownerProjectVariantDisplayName = null,
                shortId = shortId
            )
        )
    }

    @Test
    fun `uses root excludes for root owned or unknown owner dependencies`() {
        val shortId = "com.example:library"
        val rootRule = ExcludeRule("com.example", "root-blocked")
        val ownerRule = ExcludeRule("com.example", "owner-blocked")

        val excludeRules = mapOf(
            ":app" to ProjectExcludeRules(
                bucketRulesByShortId = mapOf(shortId to setOf(ownerRule)),
                variantRulesByName = emptyMap(),
                variantHierarchyNamesByName = emptyMap()
            )
        )
        val rootExcludeRules = mapOf(shortId to setOf(rootRule))

        assertEquals(
            setOf(ownerRule),
            excludeRules.excludeRulesFor(
                rootProjectPath = ":app",
                rootExcludeRulesByShortId = rootExcludeRules,
                ownerProjectPath = ":app",
                ownerProjectVariantDisplayName = null,
                shortId = shortId
            )
        )
        assertEquals(
            setOf(rootRule),
            excludeRules.excludeRulesFor(
                rootProjectPath = ":app",
                rootExcludeRulesByShortId = rootExcludeRules,
                ownerProjectPath = null,
                ownerProjectVariantDisplayName = null,
                shortId = shortId
            )
        )
    }

    @Test
    fun `prefers scoped root owner excludes over extended classpath fallback`() {
        val shortId = "com.example:library"
        val mainRule = ExcludeRule("com.example", "main-blocked")
        val androidTestRule = ExcludeRule("com.example", "android-test-blocked")

        val excludeRules = mapOf(
            ":app" to ProjectExcludeRules(
                bucketRulesByShortId = mapOf(shortId to setOf(androidTestRule)),
                variantRulesByName = emptyMap(),
                variantHierarchyNamesByName = emptyMap()
            )
        )
        val extendedClasspathRules = mapOf(shortId to setOf(mainRule, androidTestRule))

        assertEquals(
            setOf(androidTestRule),
            excludeRules.excludeRulesFor(
                rootProjectPath = ":app",
                rootExcludeRulesByShortId = extendedClasspathRules,
                ownerProjectPath = ":app",
                ownerProjectVariantDisplayName = null,
                shortId = shortId
            )
        )
    }

    @Test
    fun `uses selected project variant excludes instead of app bucket sibling excludes`() {
        val shortId = "com.example:library"
        val freeRule = ExcludeRule("com.example", "free-blocked")
        val paidRule = ExcludeRule("com.example", "paid-blocked")

        val excludeRules = mapOf(
            ":lib" to ProjectExcludeRules(
                bucketRulesByShortId = mapOf(shortId to setOf(freeRule)),
                variantRulesByName = mapOf(
                    "paidDebug" to mapOf(shortId to setOf(paidRule))
                ),
                variantHierarchyNamesByName = mapOf(
                    "paidDebug" to setOf("paidDebug", "paid", "debug", "default"),
                    "freeDebug" to setOf("freeDebug", "free", "debug", "default")
                )
            )
        )

        assertEquals(
            setOf(paidRule),
            excludeRules.excludeRulesFor(
                rootProjectPath = ":app",
                rootExcludeRulesByShortId = emptyMap(),
                ownerProjectPath = ":lib",
                ownerProjectVariantDisplayName = "paidDebugRuntimeElements",
                shortId = shortId
            )
        )
    }

    @Test
    fun `does not fall back to app bucket sibling excludes when selected project variant has no matching excludes`() {
        val shortId = "com.example:library"
        val freeRule = ExcludeRule("com.example", "free-blocked")

        val excludeRules = mapOf(
            ":lib" to ProjectExcludeRules(
                bucketRulesByShortId = mapOf(shortId to setOf(freeRule)),
                variantRulesByName = emptyMap(),
                variantHierarchyNamesByName = mapOf(
                    "paidDebug" to setOf("paidDebug", "paid", "debug", "default")
                )
            )
        )

        assertEquals(
            emptySet<ExcludeRule>(),
            excludeRules.excludeRulesFor(
                rootProjectPath = ":app",
                rootExcludeRulesByShortId = emptyMap(),
                ownerProjectPath = ":lib",
                ownerProjectVariantDisplayName = "paidDebugRuntimeElements",
                shortId = shortId
            )
        )
    }

    @Test
    fun `matches selected project variant display name using longest variant prefix`() {
        assertEquals(
            setOf("paidDebug", "paid", "debug", "default"),
            selectedVariantHierarchyNames(
                displayName = "paidDebugRuntimeElements",
                variantHierarchyNamesByName = mapOf(
                    "paid" to setOf("paid", "default"),
                    "paidDebug" to setOf("paidDebug", "paid", "debug", "default")
                )
            )
        )
    }

    @Test
    fun `maps jvm main selected variant display name to default excludes`() {
        val shortId = "com.example:library"
        val mainRule = ExcludeRule("com.example", "main-blocked")
        val testRule = ExcludeRule("com.example", "test-blocked")

        val rules = ProjectExcludeRules(
            bucketRulesByShortId = mapOf(shortId to setOf(mainRule, testRule)),
            variantRulesByName = mapOf(
                DEFAULT_VARIANT to mapOf(shortId to setOf(mainRule)),
                "test" to mapOf(shortId to setOf(testRule))
            ),
            variantHierarchyNamesByName = mapOf(
                DEFAULT_VARIANT to setOf(DEFAULT_VARIANT),
                "test" to setOf("test")
            )
        )

        assertEquals(
            setOf(mainRule),
            rules.rulesFor(
                shortId = shortId,
                selectedVariantDisplayName = "runtimeElements"
            )
        )
    }

    @Test
    fun `collects jvm owner excludes when root bucket asks for android build metadata`() {
        val rootProject = ProjectBuilder.builder().withName("root").build()
        val jvmProject = ProjectBuilder.builder()
            .withName("lib")
            .withParent(rootProject)
            .build()
        val implementation = jvmProject.configurations.create("implementation")
        jvmProject.configurations.create("compileClasspath").extendsFrom(implementation)
        jvmProject.configurations.create("runtimeClasspath").extendsFrom(implementation)
        val dependency = jvmProject.dependencies.add(
            "implementation",
            "com.example:library:1.0"
        ) as ExternalModuleDependency
        dependency.exclude(mapOf("group" to "com.example", "module" to "blocked"))

        val rulesByProjectPath = DeclaredDependencyMetadataCollector().collectExcludeRulesByProjectPath(
            variantsByProject = mapOf(jvmProject to listOf(JvmVariant(jvmProject, JvmBuild))),
            variantTypes = setOf(AndroidBuild),
            variantNames = setOf(DEFAULT_VARIANT)
        )

        assertEquals(
            setOf(ExcludeRule("com.example", "blocked")),
            rulesByProjectPath[":lib"]?.rulesFor(
                shortId = "com.example:library",
                selectedVariantDisplayName = null
            )
        )
    }

    @Test
    fun `skips declared compileOnly dependencies without group`() {
        val project = ProjectBuilder.builder().withName("lib").build()
        project.configurations.create("compileOnly")
        project.dependencies.add(
            "compileOnly",
            project.dependencies.create(mapOf("name" to "library", "version" to "1.0"))
        )

        val depsByBucket = DeclaredDependencyMetadataCollector()
            .collectCompileOnlyDependenciesByBucket(
                variantsByProject = mapOf(project to listOf(JvmVariant(project, JvmBuild))),
                projects = listOf(project)
            )

        assertEquals(emptyMap<String, Map<String, ResolvedDependency>>(), depsByBucket)
    }

    private fun dependency(id: String): ResolvedDependency {
        return ResolvedDependency.fromId(id, "maven")
    }

    private fun covered(bucketName: String, dependency: ResolvedDependency): CoveredDependency {
        return CoveredDependency(bucketName, dependency)
    }

}
