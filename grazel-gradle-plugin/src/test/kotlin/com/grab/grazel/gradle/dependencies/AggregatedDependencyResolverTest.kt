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
import com.grab.grazel.fake.FakeAttributeContainer
import com.grab.grazel.fake.fakeComponentResult
import com.grab.grazel.gradle.dependencies.model.ExcludeRule
import com.grab.grazel.gradle.dependencies.model.OverrideTarget
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.COMPILE
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.KSP
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.variant.ANDROID_TEST_VARIANT
import com.grab.grazel.gradle.variant.TEST_VARIANT
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.JvmVariant
import com.grab.grazel.gradle.variant.Variant
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.JvmBuild
import com.grab.grazel.gradle.variant.VariantType.Test as TestVariantType
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.result.DefaultResolvedDependencyResult
import org.gradle.api.internal.artifacts.result.DefaultResolvedVariantResult
import org.gradle.internal.DisplayName
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.component.external.model.DefaultModuleComponentSelector
import org.gradle.internal.component.external.model.ImmutableCapabilities
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

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
    fun `removes child direct dependency when parent direct dependency roots superset closure`() {
        val debugDependency = dependency("com.example:library:1.0").copy(
            dependencies = setOf(
                "com.example:first:1.0:maven:false:null",
                "com.example:second:1.0:maven:false:null"
            )
        )
        val flavorDependency = dependency("com.example:library:1.0").copy(
            dependencies = setOf("com.example:first:1.0:maven:false:null")
        )
        val childBucket = mapOf(flavorDependency.shortId to flavorDependency)

        val filteredBucket = childBucket.withoutDependenciesCoveredBy(
            listOf(covered("debug", debugDependency))
        )

        assertEquals(emptyMap<String, ResolvedDependency>(), filteredBucket)
    }

    @Test
    fun `removes child declared placeholder when resolved parent roots superset closure`() {
        val defaultDependency = dependency("com.example:library:1.0").copy(
            dependencies = setOf("com.example:first:1.0:maven:false:null"),
            repository = "maven"
        )
        val debugDependency = dependency("com.example:library:1.0").copy(
            dependencies = emptySet(),
            repository = DECLARED_DEPENDENCY_REPOSITORY
        )
        val childBucket = mapOf(debugDependency.shortId to debugDependency)

        val filteredBucket = childBucket.withoutDependenciesCoveredBy(
            listOf(covered("default", defaultDependency))
        )

        assertEquals(emptyMap<String, ResolvedDependency>(), filteredBucket)
    }

    @Test
    fun `keeps child declared placeholder when exclude rules differ from resolved parent`() {
        val defaultDependency = dependency("com.example:library:1.0").copy(
            dependencies = setOf("com.example:first:1.0:maven:false:null"),
            excludeRules = setOf(ExcludeRule(group = "com.example", artifact = "second")),
            repository = "maven"
        )
        val debugDependency = dependency("com.example:library:1.0").copy(
            dependencies = emptySet(),
            excludeRules = setOf(ExcludeRule(group = "com.example", artifact = "first")),
            repository = DECLARED_DEPENDENCY_REPOSITORY
        )
        val childBucket = mapOf(debugDependency.shortId to debugDependency)

        val filteredBucket = childBucket.withoutDependenciesCoveredBy(
            listOf(covered("default", defaultDependency))
        )

        assertEquals(childBucket, filteredBucket)
    }

    @Test
    fun `global hierarchy merge removes declared placeholder covered by default bucket from another project`() {
        val sharedDependency = fakeComponentResult(
            group = "com.example",
            name = "shared",
            version = "1.0",
            isProject = false
        )
        val appDefaultRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(sharedDependency)
        }
        val appDebugRoot = fakeComponentResult(projectPath = ":app")

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredProjectDependencies = setOf("implementation->:lib::[]")
                            ),
                            declaredVariant(
                                name = "debug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                declaredProjectDependencies = setOf("implementation->:lib::[]")
                            )
                        )
                    ),
                    ":lib" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.OTHER,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false
                            ),
                            declaredVariant(
                                name = "debug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                declaredDependencies = setOf("com.example:shared:1.0")
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appDefaultRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild,
                    traverseProjectNodes = false,
                    directDependencyShortIds = setOf("com.example:shared")
                ),
                root(
                    component = appDebugRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = "debug",
                    variantType = AndroidBuild,
                    traverseProjectNodes = false
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:shared:1.0"),
            results.single { result -> result.variantName == DEFAULT_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
        assertNull(results.singleOrNull { result -> result.variantName == "debug" })
    }

    @Test
    fun `global hierarchy merge keeps declared placeholder when default excludes differ`() {
        val mainExcludeRule = ExcludeRule("com.example", "main-blocked")
        val debugExcludeRule = ExcludeRule("com.example", "debug-blocked")
        val sharedDependency = fakeComponentResult(
            group = "com.example",
            name = "shared",
            version = "1.0",
            isProject = false
        )
        val appDefaultRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(sharedDependency)
        }
        val appDebugRoot = fakeComponentResult(projectPath = ":app")

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredProjectDependencies = setOf("implementation->:lib::[]")
                            ),
                            declaredVariant(
                                name = "debug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                declaredProjectDependencies = setOf("implementation->:lib::[]")
                            )
                        )
                    ),
                    ":lib" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.OTHER,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false
                            ),
                            declaredVariant(
                                name = "debug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                declaredDependencies = setOf("com.example:shared:1.0"),
                                excludeRulesByShortId = mapOf(
                                    "com.example:shared" to setOf(debugExcludeRule)
                                )
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appDefaultRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild,
                    traverseProjectNodes = false,
                    directDependencyShortIds = setOf("com.example:shared"),
                    rootExcludeRulesByShortId = mapOf(
                        "com.example:shared" to setOf(mainExcludeRule)
                    )
                ),
                root(
                    component = appDebugRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = "debug",
                    variantType = AndroidBuild,
                    traverseProjectNodes = false
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:shared:1.0"),
            results.single { result -> result.variantName == "debug" }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
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

        val filteredBucket = testBucket.withoutDependenciesCoveredBy(
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
    fun `merges duplicate dependency metadata while dropping one sided excludes`() {
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
        assertEquals(emptySet<ExcludeRule>(), merged.excludeRules)
    }

    @Test
    fun `intersects duplicate dependency excludes while keeping max version representative`() {
        val commonExclude = ExcludeRule("com.example", "common")
        val firstOnlyExclude = ExcludeRule("com.example", "first-only")
        val secondOnlyExclude = ExcludeRule("com.example", "second-only")
        val firstDependency = dependency("com.example:library:1.0").copy(
            excludeRules = setOf(commonExclude, firstOnlyExclude)
        )
        val secondDependency = dependency("com.example:library:1.0").copy(
            excludeRules = setOf(commonExclude, secondOnlyExclude)
        )

        val merged = mergeDependencyMetadataByMaxVersion(firstDependency, secondDependency)

        assertEquals(setOf(commonExclude), merged.excludeRules)
    }

    @Test
    fun `merges same version declared metadata without replacing resolved owner`() {
        val excludeRule = ExcludeRule("com.example", "blocked")
        val resolvedDependency = dependency("com.example:library:1.0").copy(
            dependencies = setOf("com.example:transitive:1.0:maven:false:null"),
            repository = "maven"
        )
        val declaredDependency = dependency("com.example:library:1.0").copy(
            repository = DECLARED_DEPENDENCY_REPOSITORY,
            excludeRules = setOf(excludeRule)
        )

        val merged = mergeDependencyMetadataByMaxVersion(
            resolvedDependency,
            declaredDependency
        )

        assertEquals("maven", merged.repository)
        assertEquals(resolvedDependency.dependencies, merged.dependencies)
        assertEquals(setOf(excludeRule), merged.excludeRules)
    }

    @Test
    fun `merges higher version declared metadata without replacing resolved version`() {
        val excludeRule = ExcludeRule("com.example", "blocked")
        val resolvedDependency = dependency("com.example:library:1.5").copy(
            dependencies = setOf("com.example:transitive:1.5:maven:false:null"),
            repository = "maven"
        )
        val declaredDependency = dependency("com.example:library:2.0").copy(
            repository = DECLARED_DEPENDENCY_REPOSITORY,
            excludeRules = setOf(excludeRule)
        )

        val merged = mergeDependencyMetadataByMaxVersion(
            resolvedDependency,
            declaredDependency
        )

        assertEquals("1.5", merged.version)
        assertEquals("maven", merged.repository)
        assertEquals(resolvedDependency.dependencies, merged.dependencies)
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
    fun `classpath exclude metadata drops child only exclude when inherited parent keeps dependency`() {
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
            emptyMap<String, Set<ExcludeRule>>(),
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
    fun `root exclude metadata is keyed in stable short id order`() {
        val project = ProjectBuilder.builder().build()
        val implementation = project.configurations.create("implementation")
        val laterDependency = project.dependencies.add(
            "implementation",
            "com.zed:later:1.0"
        ) as ExternalModuleDependency
        laterDependency.exclude(mapOf("group" to "com.example", "module" to "later-blocked"))
        val earlierDependency = project.dependencies.add(
            "implementation",
            "com.alpha:earlier:1.0"
        ) as ExternalModuleDependency
        earlierDependency.exclude(mapOf("group" to "com.example", "module" to "earlier-blocked"))

        assertEquals(
            listOf("com.alpha:earlier", "com.zed:later"),
            implementation.extractExcludeRulesByShortId().keys.toList()
        )
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
    fun `intersects selected project variant hierarchy excludes`() {
        val shortId = "com.example:library"
        val commonRule = ExcludeRule("com.example", "common-blocked")
        val paidRule = ExcludeRule("com.example", "paid-blocked")
        val debugRule = ExcludeRule("com.example", "debug-blocked")

        val excludeRules = mapOf(
            ":lib" to ProjectExcludeRules(
                bucketRulesByShortId = emptyMap(),
                variantRulesByName = mapOf(
                    "paidDebug" to mapOf(shortId to setOf(commonRule, paidRule)),
                    "debug" to mapOf(shortId to setOf(commonRule, debugRule))
                ),
                variantHierarchyNamesByName = mapOf(
                    "paidDebug" to setOf("paidDebug", "paid", "debug", "default")
                )
            )
        )

        assertEquals(
            setOf(commonRule),
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

    @Test
    fun `emits default result when compile roots are empty but KSP dependencies exist`() {
        val kspDependency = dependency("com.example:ksp-processor:1.0")
        val emptyRoot = fakeComponentResult(projectPath = ":app")

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = declaredAppMetadata(),
            precomputedKspDependencies = setOf(kspDependency),
            workspaceDependencyRoots = listOf(
                root(
                    component = emptyRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild
                )
            )
        ).resolve()

        val defaultResult = results.single { result -> result.variantName == DEFAULT_VARIANT }

        assertEquals(emptySet<ResolvedDependency>(), defaultResult.dependencies.getValue(COMPILE.name))
        assertEquals(setOf(kspDependency), defaultResult.dependencies.getValue(KSP.name))
    }

    @Test
    fun `fails task when aggregated root resolution fails`() {
        val failingRoot = failingRootComponent()

        try {
            AggregatedDependencyResolver(
                logger = ProjectBuilder.builder().build().logger,
                declaredDependencyMetadata = declaredAppMetadata(),
                workspaceDependencyRoots = listOf(
                    root(
                        component = failingRoot,
                        projectPath = ":app",
                        kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                        bucketName = DEFAULT_VARIANT,
                        variantType = AndroidBuild
                    )
                )
            ).resolve()
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("Failed to resolve aggregated root"))
            return
        }

        error("Expected aggregated root resolution failure to fail the resolver")
    }

    @Test
    fun `test bucket reuses identical default dependency owned by another project`() {
        val sharedDependency = fakeComponentResult(
            group = "com.example",
            name = "shared",
            version = "1.0",
            isProject = false
        )
        val appRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(sharedDependency)
        }
        val testAppRoot = fakeComponentResult(projectPath = ":test-app") {
            addDependencyTo(sharedDependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false
                            )
                        )
                    ),
                    ":test-app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = TEST_VARIANT,
                                variantType = TestVariantType,
                                leaf = false
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild
                ),
                root(
                    component = testAppRoot,
                    projectPath = ":test-app",
                    kind = AggregatedDependencyRootKind.UNIT_TEST,
                    bucketName = DEFAULT_VARIANT,
                    leafName = DEFAULT_VARIANT,
                    variantType = TestVariantType
                )
            )
        ).resolve()

        assertNull(results.singleOrNull { result -> result.variantName == TEST_VARIANT })
    }

    @Test
    fun `test bucket removes transitive dependency already rooted by main bucket`() {
        val sharedTransitiveDependency = fakeComponentResult(
            group = "com.example",
            name = "shared-transitive",
            version = "1.0",
            isProject = false
        )
        val mainDependency = fakeComponentResult(
            group = "com.example",
            name = "main",
            version = "1.0",
            isProject = false
        ) {
            addDependencyTo(sharedTransitiveDependency)
        }
        val testDependency = fakeComponentResult(
            group = "com.example",
            name = "test-helper",
            version = "1.0",
            isProject = false
        ) {
            addDependencyTo(sharedTransitiveDependency)
        }
        val appRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(mainDependency)
        }
        val testRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(testDependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false
                            ),
                            declaredVariant(
                                name = TEST_VARIANT,
                                variantType = TestVariantType,
                                leaf = false
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild,
                    traverseProjectNodes = false,
                    directDependencyShortIds = setOf("com.example:main")
                ),
                root(
                    component = testRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.UNIT_TEST,
                    bucketName = DEFAULT_VARIANT,
                    leafName = DEFAULT_VARIANT,
                    variantType = TestVariantType,
                    traverseProjectNodes = false,
                    directDependencyShortIds = setOf("com.example:test-helper")
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:test-helper:1.0"),
            results.single { result -> result.variantName == TEST_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
        assertNull(
            results.single { result -> result.variantName == TEST_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .single()
                .overrideTarget
        )
    }

    @Test
    fun `test bucket inherits main direct dependency when only excludes differ and test did not declare it`() {
        val mainExcludeRule = ExcludeRule("com.example", "blocked")
        val inheritedDependency = fakeComponentResult(
            group = "com.example",
            name = "library",
            version = "1.0",
            isProject = false
        )
        val testOnlyDependency = fakeComponentResult(
            group = "com.example",
            name = "test-helper",
            version = "1.0",
            isProject = false
        )
        val appRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(inheritedDependency)
        }
        val testRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(inheritedDependency)
            addDependencyTo(testOnlyDependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false,
                                excludeRulesByShortId = mapOf(
                                    "com.example:library" to setOf(mainExcludeRule)
                                )
                            ),
                            declaredVariant(
                                name = TEST_VARIANT,
                                variantType = TestVariantType,
                                leaf = false,
                                declaredDependencies = setOf("com.example:test-helper:1.0")
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild,
                    directDependencyShortIds = setOf("com.example:library"),
                    traverseProjectNodes = false,
                    rootExcludeRulesByShortId = mapOf(
                        "com.example:library" to setOf(mainExcludeRule)
                    )
                ),
                root(
                    component = testRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.UNIT_TEST,
                    bucketName = DEFAULT_VARIANT,
                    leafName = DEFAULT_VARIANT,
                    variantType = TestVariantType,
                    directDependencyShortIds = setOf("com.example:library", "com.example:test-helper"),
                    traverseProjectNodes = false
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:test-helper:1.0"),
            results.single { result -> result.variantName == TEST_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
    }

    @Test
    fun `test bucket keeps explicitly declared direct dependency with distinct excludes`() {
        val mainExcludeRule = ExcludeRule("com.example", "main-blocked")
        val testExcludeRule = ExcludeRule("com.example", "test-blocked")
        val dependency = fakeComponentResult(
            group = "com.example",
            name = "library",
            version = "1.0",
            isProject = false
        )
        val appRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(dependency)
        }
        val testRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(dependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false,
                                excludeRulesByShortId = mapOf(
                                    "com.example:library" to setOf(mainExcludeRule)
                                )
                            ),
                            declaredVariant(
                                name = TEST_VARIANT,
                                variantType = TestVariantType,
                                leaf = false,
                                declaredDependencies = setOf("com.example:library:1.0"),
                                excludeRulesByShortId = mapOf(
                                    "com.example:library" to setOf(testExcludeRule)
                                )
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild,
                    directDependencyShortIds = setOf("com.example:library"),
                    traverseProjectNodes = false,
                    rootExcludeRulesByShortId = mapOf(
                        "com.example:library" to setOf(mainExcludeRule)
                    )
                ),
                root(
                    component = testRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.UNIT_TEST,
                    bucketName = DEFAULT_VARIANT,
                    leafName = DEFAULT_VARIANT,
                    variantType = TestVariantType,
                    directDependencyShortIds = setOf("com.example:library"),
                    traverseProjectNodes = false,
                    rootExcludeRulesByShortId = mapOf(
                        "com.example:library" to setOf(testExcludeRule)
                    )
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:library:1.0"),
            results.single { result -> result.variantName == TEST_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
        assertEquals(
            setOf(testExcludeRule),
            results.single { result -> result.variantName == TEST_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .single()
                .excludeRules
        )
    }

    @Test
    fun `test bucket keeps declared dependency covered only by non visible main bucket`() {
        val dependency = fakeComponentResult(
            group = "com.example",
            name = "library",
            version = "1.0",
            isProject = false
        )
        val paxRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(dependency)
        }
        val testRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(dependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false
                            ),
                            declaredVariant(
                                name = "pax",
                                variantType = AndroidBuild,
                                leaf = false
                            ),
                            declaredVariant(
                                name = TEST_VARIANT,
                                variantType = TestVariantType,
                                leaf = false,
                                declaredDependencies = setOf("com.example:library:1.0")
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = paxRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "pax",
                    leafName = "pax",
                    variantType = AndroidBuild,
                    directDependencyShortIds = setOf("com.example:library"),
                    traverseProjectNodes = false
                ),
                root(
                    component = testRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.UNIT_TEST,
                    bucketName = DEFAULT_VARIANT,
                    leafName = DEFAULT_VARIANT,
                    variantType = TestVariantType,
                    directDependencyShortIds = setOf("com.example:library"),
                    traverseProjectNodes = false
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:library:1.0"),
            results.single { result -> result.variantName == TEST_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
        assertEquals(
            listOf("com.example:library:1.0"),
            results.single { result -> result.variantName == "pax" }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
    }

    @Test
    fun `test compile only metadata is inherited from visible main bucket`() {
        val sharedDependency = fakeComponentResult(
            group = "com.example",
            name = "shared",
            version = "1.0",
            isProject = false
        )
        val appRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(sharedDependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false
                            ),
                            declaredVariant(
                                name = TEST_VARIANT,
                                variantType = TestVariantType,
                                leaf = false,
                                compileOnlyDependenciesByShortId = mapOf(
                                    "com.example:shared" to ResolvedDependency.fromId(
                                        "com.example:shared:1.0",
                                        DECLARED_DEPENDENCY_REPOSITORY
                                    )
                                )
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild
                )
            )
        ).resolve()

        assertNull(results.singleOrNull { result -> result.variantName == TEST_VARIANT })
    }

    @Test
    fun `unit test classpaths emit planned test hierarchy buckets`() {
        val commonTestDependency = fakeComponentResult(
            group = "com.example",
            name = "test-common",
            version = "1.0",
            isProject = false
        )
        val debugTestDependency = fakeComponentResult(
            group = "com.example",
            name = "debug-test",
            version = "1.0",
            isProject = false
        )
        val freeTestDependency = fakeComponentResult(
            group = "com.example",
            name = "free-test",
            version = "1.0",
            isProject = false
        )
        val paidTestDependency = fakeComponentResult(
            group = "com.example",
            name = "paid-test",
            version = "1.0",
            isProject = false
        )
        val freeMainRoot = fakeComponentResult(projectPath = ":app")
        val paidMainRoot = fakeComponentResult(projectPath = ":app")
        val broadUnitTestRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(commonTestDependency)
        }
        val freeUnitTestRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(commonTestDependency)
            addDependencyTo(debugTestDependency)
            addDependencyTo(freeTestDependency)
        }
        val paidUnitTestRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(commonTestDependency)
            addDependencyTo(debugTestDependency)
            addDependencyTo(paidTestDependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = "freeDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("free"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "free")
                            ),
                            declaredVariant(
                                name = "paidDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("paid"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "paid")
                            ),
                            declaredVariant(
                                name = TEST_VARIANT,
                                variantType = TestVariantType,
                                leaf = false,
                                extendsFrom = setOf(DEFAULT_VARIANT)
                            ),
                            declaredVariant(
                                name = "debugUnitTest",
                                variantType = TestVariantType,
                                leaf = false,
                                extendsFrom = setOf(TEST_VARIANT)
                            ),
                            declaredVariant(
                                name = "freeDebugUnitTest",
                                variantType = TestVariantType,
                                leaf = true,
                                extendsFrom = setOf(TEST_VARIANT, "debugUnitTest", "freeDebug")
                            ),
                            declaredVariant(
                                name = "paidDebugUnitTest",
                                variantType = TestVariantType,
                                leaf = true,
                                extendsFrom = setOf(TEST_VARIANT, "debugUnitTest", "paidDebug")
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = freeMainRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "freeDebug",
                    leafName = "freeDebug",
                    variantType = AndroidBuild
                ),
                root(
                    component = paidMainRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "paidDebug",
                    leafName = "paidDebug",
                    variantType = AndroidBuild
                ),
                root(
                    component = broadUnitTestRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.TEST_HIERARCHY,
                    bucketName = TEST_VARIANT,
                    variantType = TestVariantType
                ),
                root(
                    component = freeUnitTestRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.UNIT_TEST,
                    bucketName = "freeDebug",
                    leafName = "freeDebug",
                    variantType = TestVariantType
                ),
                root(
                    component = paidUnitTestRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.UNIT_TEST,
                    bucketName = "paidDebug",
                    leafName = "paidDebug",
                    variantType = TestVariantType
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:test-common:1.0"),
            results.single { result -> result.variantName == TEST_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
        assertEquals(
            listOf("com.example:debug-test:1.0"),
            results.single { result -> result.variantName == "debugUnitTest" }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
        assertEquals(
            listOf("com.example:free-test:1.0"),
            results.single { result -> result.variantName == "freeDebugUnitTest" }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
        assertEquals(
            listOf("com.example:paid-test:1.0"),
            results.single { result -> result.variantName == "paidDebugUnitTest" }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
        assertNull(results.singleOrNull { result -> result.variantName == "debug" })
    }

    @Test
    fun `declared unit test dependencies from non app modules are added to test bucket`() {
        val mainDependency = fakeComponentResult(
            group = "com.example",
            name = "main",
            version = "1.0",
            isProject = false
        )
        val libProject = fakeComponentResult(
            isProject = true,
            projectPath = ":lib"
        ) {
            addDependencyTo(mainDependency)
        }
        val appRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(libProject, selectedVariantDisplayName = "runtimeElements")
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false
                            )
                        )
                    ),
                    ":lib" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.OTHER,
                        variants = listOf(
                            declaredVariant(
                                name = TEST_VARIANT,
                                variantType = TestVariantType,
                                leaf = false,
                                declaredDependencies = setOf("com.example:test-helper:1.0")
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:test-helper:1.0"),
            results.single { result -> result.variantName == TEST_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
    }

    @Test
    fun `declared android test dependencies from non app modules use planned androidTest bucket`() {
        val mainDependency = fakeComponentResult(
            group = "com.example",
            name = "main",
            version = "1.0",
            isProject = false
        )
        val libProject = fakeComponentResult(
            isProject = true,
            projectPath = ":lib"
        ) {
            addDependencyTo(mainDependency)
        }
        val appRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(libProject)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false
                            )
                        )
                    ),
                    ":lib" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.OTHER,
                        variants = listOf(
                            declaredVariant(
                                name = "freeDebugAndroidTest",
                                variantType = AndroidTest,
                                leaf = true,
                                declaredDependencies = setOf("com.example:android-test-helper:1.0")
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:android-test-helper:1.0"),
            results.single { result -> result.variantName == "freeDebugAndroidTest" }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
        assertNull(results.singleOrNull { result -> result.variantName == ANDROID_TEST_VARIANT })
    }

    @Test
    fun `app base android test declarations are not emitted when absent from selected leaf closure`() {
        val appRoot = fakeComponentResult(projectPath = ":app")
        val androidTestRoot = fakeComponentResult(projectPath = ":app")

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false
                            ),
                            declaredVariant(
                                name = "gpsPaxDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("gps", "pax"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "gps", "pax")
                            ),
                            declaredVariant(
                                name = ANDROID_TEST_VARIANT,
                                variantType = AndroidTest,
                                leaf = false,
                                declaredDependencies = setOf("com.example:android-test-helper:1.0"),
                                extendsFrom = setOf(DEFAULT_VARIANT, TEST_VARIANT)
                            ),
                            declaredVariant(
                                name = "gpsPaxDebugAndroidTest",
                                variantType = AndroidTest,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("gps", "pax"),
                                extendsFrom = setOf(
                                    ANDROID_TEST_VARIANT,
                                    "gpsPaxDebug",
                                    DEFAULT_VARIANT,
                                    TEST_VARIANT
                                )
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "gpsPaxDebug",
                    leafName = "gpsPaxDebug",
                    variantType = AndroidBuild
                ),
                root(
                    component = androidTestRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.ANDROID_TEST,
                    bucketName = "gpsPaxDebug",
                    leafName = "gpsPaxDebug",
                    variantType = AndroidTest
                )
            )
        ).resolve()

        assertNull(results.singleOrNull { result -> result.variantName == ANDROID_TEST_VARIANT })
    }

    @Test
    fun `android test roots mark reached project main buckets reachable`() {
        val libDependency = fakeComponentResult(
            group = "com.example",
            name = "lib-main",
            version = "1.0",
            isProject = false
        )
        val uiTestsProject = fakeComponentResult(
            isProject = true,
            projectPath = ":ui-tests"
        ) {
            addDependencyTo(libDependency)
        }
        val appRoot = fakeComponentResult(projectPath = ":app")
        val androidTestRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(uiTestsProject, selectedVariantDisplayName = "debugRuntimeElements")
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false
                            ),
                            declaredVariant(
                                name = "gpsPaxDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("gps", "pax"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "gps", "pax")
                            ),
                            declaredVariant(
                                name = "gpsPaxDebugAndroidTest",
                                variantType = AndroidTest,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("gps", "pax"),
                                extendsFrom = setOf(
                                    DEFAULT_VARIANT,
                                    ANDROID_TEST_VARIANT,
                                    "gpsPaxDebug",
                                    "debug",
                                    "gps",
                                    "pax"
                                )
                            )
                        )
                    ),
                    ":ui-tests" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.OTHER,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false
                            ),
                            declaredVariant(
                                name = "debug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                extendsFrom = setOf(DEFAULT_VARIANT)
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "gpsPaxDebug",
                    leafName = "gpsPaxDebug",
                    variantType = AndroidBuild,
                    variantNames = setOf(DEFAULT_VARIANT, "debug", "gps", "pax", "gpsPaxDebug")
                ),
                root(
                    component = androidTestRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.ANDROID_TEST,
                    bucketName = "gpsPaxDebug",
                    leafName = "gpsPaxDebug",
                    variantType = AndroidTest,
                    variantNames = setOf(DEFAULT_VARIANT, "debug", "gps", "pax", "gpsPaxDebug")
                )
            )
        ).resolve()

        assertEquals(
            setOf(DEFAULT_VARIANT, "debug"),
            results.first()
                .reachableMainBucketsByProject
                .getValue(":ui-tests")
        )
    }

    @Test
    fun `app base android test declarations are emitted with multiple selected android test leaves`() {
        val androidTestDependency = fakeComponentResult(
            group = "com.example",
            name = "android-test-helper",
            version = "1.0",
            isProject = false
        )
        val appGpsPaxDebugRoot = fakeComponentResult(projectPath = ":app")
        val appGpsOvoDebugRoot = fakeComponentResult(projectPath = ":app")
        val gpsPaxAndroidTestRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(androidTestDependency)
        }
        val gpsOvoAndroidTestRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(androidTestDependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false
                            ),
                            declaredVariant(
                                name = "debug",
                                variantType = AndroidBuild,
                                leaf = false,
                                extendsFrom = setOf(DEFAULT_VARIANT)
                            ),
                            declaredVariant(
                                name = "gps",
                                variantType = AndroidBuild,
                                leaf = false,
                                extendsFrom = setOf(DEFAULT_VARIANT)
                            ),
                            declaredVariant(
                                name = "ovo",
                                variantType = AndroidBuild,
                                leaf = false,
                                extendsFrom = setOf(DEFAULT_VARIANT)
                            ),
                            declaredVariant(
                                name = "pax",
                                variantType = AndroidBuild,
                                leaf = false,
                                extendsFrom = setOf(DEFAULT_VARIANT)
                            ),
                            declaredVariant(
                                name = "gpsOvoDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("gps", "ovo"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "gps", "ovo")
                            ),
                            declaredVariant(
                                name = "gpsPaxDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("gps", "pax"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "gps", "pax")
                            ),
                            declaredVariant(
                                name = TEST_VARIANT,
                                variantType = TestVariantType,
                                leaf = false,
                                extendsFrom = setOf(DEFAULT_VARIANT)
                            ),
                            declaredVariant(
                                name = ANDROID_TEST_VARIANT,
                                variantType = AndroidTest,
                                leaf = false,
                                declaredDependencies = setOf("com.example:android-test-helper:1.0"),
                                extendsFrom = setOf(DEFAULT_VARIANT, TEST_VARIANT)
                            ),
                            declaredVariant(
                                name = "debugAndroidTest",
                                variantType = AndroidTest,
                                leaf = false,
                                extendsFrom = setOf(ANDROID_TEST_VARIANT, "debug", DEFAULT_VARIANT, TEST_VARIANT)
                            ),
                            declaredVariant(
                                name = "gpsAndroidTest",
                                variantType = AndroidTest,
                                leaf = false,
                                extendsFrom = setOf(ANDROID_TEST_VARIANT, "gps", DEFAULT_VARIANT, TEST_VARIANT)
                            ),
                            declaredVariant(
                                name = "ovoAndroidTest",
                                variantType = AndroidTest,
                                leaf = false,
                                extendsFrom = setOf(ANDROID_TEST_VARIANT, "ovo", DEFAULT_VARIANT, TEST_VARIANT)
                            ),
                            declaredVariant(
                                name = "paxAndroidTest",
                                variantType = AndroidTest,
                                leaf = false,
                                extendsFrom = setOf(ANDROID_TEST_VARIANT, "pax", DEFAULT_VARIANT, TEST_VARIANT)
                            ),
                            declaredVariant(
                                name = "gpsOvoDebugAndroidTest",
                                variantType = AndroidTest,
                                leaf = true,
                                extendsFrom = setOf(
                                    ANDROID_TEST_VARIANT,
                                    "debug",
                                    "debugAndroidTest",
                                    DEFAULT_VARIANT,
                                    "gps",
                                    "gpsAndroidTest",
                                    "gpsOvoDebug",
                                    "ovo",
                                    "ovoAndroidTest",
                                    TEST_VARIANT
                                )
                            ),
                            declaredVariant(
                                name = "gpsPaxDebugAndroidTest",
                                variantType = AndroidTest,
                                leaf = true,
                                extendsFrom = setOf(
                                    ANDROID_TEST_VARIANT,
                                    "debug",
                                    "debugAndroidTest",
                                    DEFAULT_VARIANT,
                                    "gps",
                                    "gpsAndroidTest",
                                    "gpsPaxDebug",
                                    "pax",
                                    "paxAndroidTest",
                                    TEST_VARIANT
                                )
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appGpsPaxDebugRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "gpsPaxDebug",
                    leafName = "gpsPaxDebug",
                    variantType = AndroidBuild
                ),
                root(
                    component = appGpsOvoDebugRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "gpsOvoDebug",
                    leafName = "gpsOvoDebug",
                    variantType = AndroidBuild
                ),
                root(
                    component = gpsPaxAndroidTestRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.ANDROID_TEST,
                    bucketName = "gpsPaxDebug",
                    leafName = "gpsPaxDebug",
                    variantType = AndroidTest
                ),
                root(
                    component = gpsOvoAndroidTestRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.ANDROID_TEST,
                    bucketName = "gpsOvoDebug",
                    leafName = "gpsOvoDebug",
                    variantType = AndroidTest
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:android-test-helper:1.0"),
            results.single { result -> result.variantName == ANDROID_TEST_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
    }

    @Test
    fun `app base android test declarations are not overwritten by base named leaf buckets`() {
        val androidTestDependency = fakeComponentResult(
            group = "com.example",
            name = "android-test-helper",
            version = "1.0",
            isProject = false
        )
        val unrelatedAndroidTestDependency = fakeComponentResult(
            group = "com.example",
            name = "unrelated-android-test-helper",
            version = "1.0",
            isProject = false
        )
        val appRoot = fakeComponentResult(projectPath = ":app")
        val selectedAndroidTestRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(androidTestDependency)
        }
        val baseNamedAndroidTestRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(unrelatedAndroidTestDependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false
                            ),
                            declaredVariant(
                                name = "gpsPaxDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("gps", "pax"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "gps", "pax")
                            ),
                            declaredVariant(
                                name = TEST_VARIANT,
                                variantType = TestVariantType,
                                leaf = false,
                                extendsFrom = setOf(DEFAULT_VARIANT)
                            ),
                            declaredVariant(
                                name = ANDROID_TEST_VARIANT,
                                variantType = AndroidTest,
                                leaf = false,
                                declaredDependencies = setOf("com.example:android-test-helper:1.0"),
                                extendsFrom = setOf(DEFAULT_VARIANT, TEST_VARIANT)
                            ),
                            declaredVariant(
                                name = "gpsPaxDebugAndroidTest",
                                variantType = AndroidTest,
                                leaf = true,
                                extendsFrom = setOf(
                                    ANDROID_TEST_VARIANT,
                                    "gpsPaxDebug",
                                    DEFAULT_VARIANT,
                                    TEST_VARIANT
                                )
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "gpsPaxDebug",
                    leafName = "gpsPaxDebug",
                    variantType = AndroidBuild
                ),
                root(
                    component = selectedAndroidTestRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.ANDROID_TEST,
                    bucketName = "gpsPaxDebug",
                    leafName = "gpsPaxDebug",
                    variantType = AndroidTest
                ),
                root(
                    component = baseNamedAndroidTestRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.ANDROID_TEST,
                    bucketName = ANDROID_TEST_VARIANT,
                    leafName = ANDROID_TEST_VARIANT,
                    variantType = AndroidTest
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:android-test-helper:1.0"),
            results.single { result -> result.variantName == ANDROID_TEST_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
    }

    @Test
    fun `explicit app android test declaration is inherited from main when main owns same resolved dependency`() {
        val sharedDependency = fakeComponentResult(
            group = "com.example",
            name = "shared",
            version = "1.0",
            isProject = false
        )
        val appRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(sharedDependency)
        }
        val androidTestRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(sharedDependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false
                            ),
                            declaredVariant(
                                name = ANDROID_TEST_VARIANT,
                                variantType = AndroidTest,
                                leaf = false,
                                declaredDependencies = setOf("com.example:shared:1.0"),
                                extendsFrom = setOf(DEFAULT_VARIANT, TEST_VARIANT)
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild
                ),
                root(
                    component = androidTestRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.ANDROID_TEST,
                    bucketName = DEFAULT_VARIANT,
                    leafName = DEFAULT_VARIANT,
                    variantType = AndroidTest
                )
            )
        ).resolve()

        assertNull(results.singleOrNull { result -> result.variantName == ANDROID_TEST_VARIANT })
    }

    @Test
    fun `explicit app android test declaration is emitted when resolved dependency differs from main`() {
        val mainSharedDependency = fakeComponentResult(
            group = "com.example",
            name = "shared",
            version = "1.0",
            isProject = false
        )
        val androidTestSharedDependency = fakeComponentResult(
            group = "com.example",
            name = "shared",
            version = "2.0",
            isProject = false
        )
        val appRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(mainSharedDependency)
        }
        val androidTestRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(androidTestSharedDependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false
                            ),
                            declaredVariant(
                                name = ANDROID_TEST_VARIANT,
                                variantType = AndroidTest,
                                leaf = false,
                                declaredDependencies = setOf("com.example:shared:2.0"),
                                extendsFrom = setOf(DEFAULT_VARIANT, TEST_VARIANT)
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild
                ),
                root(
                    component = androidTestRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.ANDROID_TEST,
                    bucketName = DEFAULT_VARIANT,
                    leafName = DEFAULT_VARIANT,
                    variantType = AndroidTest
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:shared:2.0"),
            results.single { result -> result.variantName == ANDROID_TEST_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
    }

    @Test
    fun `android test bucket inherits direct dependency already owned by test bucket`() {
        val sharedTestDependency = fakeComponentResult(
            group = "com.example",
            name = "shared-test-helper",
            version = "1.0",
            isProject = false
        )
        val appRoot = fakeComponentResult(projectPath = ":app")
        val unitTestRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(sharedTestDependency)
        }
        val androidTestRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(sharedTestDependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false
                            ),
                            declaredVariant(
                                name = TEST_VARIANT,
                                variantType = TestVariantType,
                                leaf = false,
                                extendsFrom = setOf(DEFAULT_VARIANT)
                            ),
                            declaredVariant(
                                name = ANDROID_TEST_VARIANT,
                                variantType = AndroidTest,
                                leaf = false,
                                extendsFrom = setOf(DEFAULT_VARIANT, TEST_VARIANT)
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild
                ),
                root(
                    component = unitTestRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.UNIT_TEST,
                    bucketName = DEFAULT_VARIANT,
                    leafName = DEFAULT_VARIANT,
                    variantType = TestVariantType
                ),
                root(
                    component = androidTestRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.ANDROID_TEST,
                    bucketName = DEFAULT_VARIANT,
                    leafName = DEFAULT_VARIANT,
                    variantType = AndroidTest
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:shared-test-helper:1.0"),
            results.single { result -> result.variantName == TEST_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
        assertNull(results.singleOrNull { result -> result.variantName == ANDROID_TEST_VARIANT })
    }

    @Test
    fun `android test bucket reuses identical default dependency owned by another project`() {
        val sharedDependency = fakeComponentResult(
            group = "com.example",
            name = "shared-main",
            version = "1.0",
            isProject = false
        )
        val appRoot = fakeComponentResult(projectPath = ":app")
        val libraryRoot = fakeComponentResult(projectPath = ":library") {
            addDependencyTo(sharedDependency)
        }
        val appAndroidTestRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(sharedDependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false
                            ),
                            declaredVariant(
                                name = ANDROID_TEST_VARIANT,
                                variantType = AndroidTest,
                                leaf = false,
                                extendsFrom = setOf(DEFAULT_VARIANT, TEST_VARIANT)
                            )
                        )
                    ),
                    ":library" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.OTHER,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild
                ),
                root(
                    component = libraryRoot,
                    projectPath = ":library",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild
                ),
                root(
                    component = appAndroidTestRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.ANDROID_TEST,
                    bucketName = DEFAULT_VARIANT,
                    leafName = DEFAULT_VARIANT,
                    variantType = AndroidTest
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:shared-main:1.0"),
            results.single { result -> result.variantName == DEFAULT_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
        assertNull(results.singleOrNull { result -> result.variantName == ANDROID_TEST_VARIANT })
    }

    @Test
    fun `android test global bucket keeps declared exclude from contributing project`() {
        val sharedDependency = fakeComponentResult(
            group = "com.example",
            name = "shared",
            version = "1.0",
            isProject = false
        )
        val appAndroidTestRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(sharedDependency)
        }
        val appRoot = fakeComponentResult(projectPath = ":app")
        val libraryAndroidTestRoot = fakeComponentResult(projectPath = ":library") {
            addDependencyTo(sharedDependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = ANDROID_TEST_VARIANT,
                                variantType = AndroidTest,
                                leaf = false,
                                declaredDependencies = setOf("com.example:shared:1.0"),
                                excludeRulesByShortId = mapOf(
                                    "com.example:shared" to setOf(ExcludeRule("com.example", "blocked"))
                                ),
                                extendsFrom = setOf(DEFAULT_VARIANT, TEST_VARIANT)
                            ),
                            declaredVariant(
                                name = "debugAndroidTest",
                                variantType = AndroidTest,
                                leaf = true,
                                buildType = "debug",
                                extendsFrom = setOf(ANDROID_TEST_VARIANT, "debug", DEFAULT_VARIANT, TEST_VARIANT)
                            )
                        )
                    ),
                    ":library" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.OTHER,
                        variants = listOf(
                            declaredVariant(
                                name = ANDROID_TEST_VARIANT,
                                variantType = AndroidTest,
                                leaf = false,
                                extendsFrom = setOf(DEFAULT_VARIANT, TEST_VARIANT)
                            ),
                            declaredVariant(
                                name = "debugAndroidTest",
                                variantType = AndroidTest,
                                leaf = true,
                                buildType = "debug",
                                extendsFrom = setOf(ANDROID_TEST_VARIANT, "debug", DEFAULT_VARIANT, TEST_VARIANT)
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild
                ),
                root(
                    component = appAndroidTestRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.ANDROID_TEST,
                    bucketName = "debugAndroidTest",
                    leafName = "debugAndroidTest",
                    variantType = AndroidTest,
                    variantNames = setOf("debugAndroidTest", ANDROID_TEST_VARIANT, "debug", DEFAULT_VARIANT, TEST_VARIANT)
                ),
                root(
                    component = libraryAndroidTestRoot,
                    projectPath = ":library",
                    kind = AggregatedDependencyRootKind.ANDROID_TEST,
                    bucketName = "debugAndroidTest",
                    leafName = "debugAndroidTest",
                    variantType = AndroidTest,
                    variantNames = setOf("debugAndroidTest", ANDROID_TEST_VARIANT, "debug", DEFAULT_VARIANT, TEST_VARIANT)
                )
            )
        ).resolve()

        val dependency = results.single { result -> result.variantName == ANDROID_TEST_VARIANT }
            .dependencies
            .getValue(COMPILE.name)
            .single()
        assertEquals("com.example:shared:1.0", dependency.id)
        assertEquals(setOf(ExcludeRule("com.example", "blocked")), dependency.excludeRules)
    }

    @Test
    fun `declared main dependencies from non app modules are added to main bucket`() {
        val mainDependency = fakeComponentResult(
            group = "com.example",
            name = "main",
            version = "1.0",
            isProject = false
        )
        val appRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(mainDependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredProjectDependencies = setOf("implementation->:lib::[]")
                            )
                        )
                    ),
                    ":lib" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.OTHER,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredDependencies = setOf("com.example:main:1.0")
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:main:1.0"),
            results.single { result -> result.variantName == DEFAULT_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
    }

    @Test
    fun `declared main dependencies from non app modules are limited to selected project variant`() {
        val selectedDependency = fakeComponentResult(
            group = "com.example",
            name = "selected",
            version = "1.0",
            isProject = false
        )
        val libProject = fakeComponentResult(
            isProject = true,
            projectPath = ":lib"
        ) {
            addDependencyTo(selectedDependency)
        }
        val appRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(libProject, selectedVariantDisplayName = "paidDebugRuntimeElements")
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = "flavor2Debug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("flavor2"),
                                declaredProjectDependencies = setOf("implementation->:lib::[]"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "flavor2")
                            )
                        )
                    ),
                    ":lib" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.OTHER,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false
                            ),
                            declaredVariant(
                                name = "debug",
                                variantType = AndroidBuild,
                                leaf = false,
                                extendsFrom = setOf(DEFAULT_VARIANT)
                            ),
                            declaredVariant(
                                name = "paid",
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredDependencies = setOf("com.example:paid-only:1.0"),
                                extendsFrom = setOf(DEFAULT_VARIANT)
                            ),
                            declaredVariant(
                                name = "free",
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredDependencies = setOf("com.example:free-only:1.0"),
                                extendsFrom = setOf(DEFAULT_VARIANT)
                            ),
                            declaredVariant(
                                name = "paidDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("paid"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "paid")
                            ),
                            declaredVariant(
                                name = "freeDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("free"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "free")
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "flavor2Debug",
                    leafName = "flavor2Debug",
                    variantType = AndroidBuild
                )
            )
        ).resolve()

        assertTrue(
            results.any { result ->
                result.dependencies.getValue(COMPILE.name).any { dependency ->
                    dependency.shortId == "com.example:paid-only"
                }
            }
        )
        assertTrue(
            "Selected fallback resolution should not emit declarations from unselected sibling variants",
            results.none { result ->
                result.dependencies.getValue(COMPILE.name).any { dependency ->
                    dependency.shortId == "com.example:free-only"
                }
            }
        )
    }

    @Test
    fun `project dependency edge excludes remove matching owner project dependencies`() {
        val blockedDependency = fakeComponentResult(
            group = "com.example",
            name = "blocked",
            version = "1.0",
            isProject = false
        )
        val libProject = fakeComponentResult(
            isProject = true,
            projectPath = ":lib"
        ) {
            addDependencyTo(blockedDependency)
        }
        val appRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(libProject, selectedVariantDisplayName = "runtimeElements")
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredProjectDependencies = setOf(
                                    "implementation->:lib::[com.example:blocked]"
                                )
                            )
                        )
                    ),
                    ":lib" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.OTHER,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredDependencies = setOf("com.example:blocked:1.0")
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild
                )
            )
        ).resolve()

        assertTrue(
            results.none { result ->
                result.dependencies.getValue(COMPILE.name).any { dependency ->
                    dependency.shortId == "com.example:blocked"
                }
            }
        )
    }

    @Test
    fun `project dependency edge excludes do not remove dependency needed by another root`() {
        val blockedDependency = fakeComponentResult(
            group = "com.example",
            name = "blocked",
            version = "1.0",
            isProject = false
        )
        val libProject = fakeComponentResult(
            isProject = true,
            projectPath = ":lib"
        ) {
            addDependencyTo(blockedDependency)
        }
        val excludingAppRoot = fakeComponentResult(projectPath = ":excluding-app") {
            addDependencyTo(libProject, selectedVariantDisplayName = "runtimeElements")
        }
        val includingAppRoot = fakeComponentResult(projectPath = ":including-app") {
            addDependencyTo(libProject, selectedVariantDisplayName = "runtimeElements")
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":excluding-app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredProjectDependencies = setOf(
                                    "implementation->:lib::[com.example:blocked]"
                                )
                            )
                        )
                    ),
                    ":including-app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredProjectDependencies = setOf("implementation->:lib::[]")
                            )
                        )
                    ),
                    ":lib" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.OTHER,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredDependencies = setOf("com.example:blocked:1.0")
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = excludingAppRoot,
                    projectPath = ":excluding-app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild
                ),
                root(
                    component = includingAppRoot,
                    projectPath = ":including-app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:blocked:1.0"),
            results.single { result -> result.variantName == DEFAULT_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
    }

    @Test
    fun `project dependency cycle does not apply unselected root variant excludes`() {
        val blockedDependency = fakeComponentResult(
            group = "com.example",
            name = "blocked",
            version = "1.0",
            isProject = false
        )
        val libProject = fakeComponentResult(
            isProject = true,
            projectPath = ":lib"
        ) {
            addDependencyTo(blockedDependency)
        }
        val appRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(libProject, selectedVariantDisplayName = "runtimeElements")
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredProjectDependencies = setOf("implementation->:lib::[]")
                            ),
                            declaredVariant(
                                name = "debug",
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredProjectDependencies = setOf(
                                    "debugImplementation->:lib::[com.example:blocked]"
                                )
                            )
                        )
                    ),
                    ":lib" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.OTHER,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredDependencies = setOf("com.example:blocked:1.0"),
                                declaredProjectDependencies = setOf("implementation->:app::[]")
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:blocked:1.0"),
            results.single { result -> result.variantName == DEFAULT_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
    }

    @Test
    fun `transitive project dependency edge excludes do not apply unselected owner variants`() {
        val blockedDependency = fakeComponentResult(
            group = "com.example",
            name = "blocked",
            version = "1.0",
            isProject = false
        )
        val coreProject = fakeComponentResult(
            isProject = true,
            projectPath = ":core"
        ) {
            addDependencyTo(blockedDependency)
        }
        val libProject = fakeComponentResult(
            isProject = true,
            projectPath = ":lib"
        ) {
            addDependencyTo(coreProject, selectedVariantDisplayName = "runtimeElements")
        }
        val appRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(libProject, selectedVariantDisplayName = "runtimeElements")
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredProjectDependencies = setOf("implementation->:lib::[]")
                            )
                        )
                    ),
                    ":lib" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.OTHER,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredProjectDependencies = setOf("implementation->:core::[]")
                            ),
                            declaredVariant(
                                name = "debug",
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredProjectDependencies = setOf(
                                    "debugImplementation->:core::[com.example:blocked]"
                                )
                            )
                        )
                    ),
                    ":core" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.OTHER,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredDependencies = setOf("com.example:blocked:1.0")
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:blocked:1.0"),
            results.single { result -> result.variantName == DEFAULT_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
    }

    @Test
    fun `project dependency edge excludes do not remove declared dependency needed by another root`() {
        val libProject = fakeComponentResult(
            isProject = true,
            projectPath = ":lib"
        )
        val excludingAppRoot = fakeComponentResult(projectPath = ":excluding-app") {
            addDependencyTo(libProject, selectedVariantDisplayName = "runtimeElements")
        }
        val includingAppRoot = fakeComponentResult(projectPath = ":including-app") {
            addDependencyTo(libProject, selectedVariantDisplayName = "runtimeElements")
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":excluding-app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredProjectDependencies = setOf(
                                    "implementation->:lib::[com.example:declared]"
                                )
                            )
                        )
                    ),
                    ":including-app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredProjectDependencies = setOf("implementation->:lib::[]")
                            )
                        )
                    ),
                    ":lib" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.OTHER,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredDependencies = setOf("com.example:declared:1.0")
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = excludingAppRoot,
                    projectPath = ":excluding-app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild
                ),
                root(
                    component = includingAppRoot,
                    projectPath = ":including-app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:declared:1.0"),
            results.single { result -> result.variantName == DEFAULT_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
    }

    @Test
    fun `compileOnly dependencies from reachable non app modules are added to main bucket`() {
        val compileOnlyDependency = dependency("com.example:compile-only:1.0")
        val libProject = fakeComponentResult(
            isProject = true,
            projectPath = ":lib"
        )
        val appRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(libProject)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredProjectDependencies = setOf("implementation->:lib::[]")
                            )
                        )
                    ),
                    ":lib" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.OTHER,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false,
                                compileOnlyDependenciesByShortId = mapOf(
                                    "com.example:compile-only" to compileOnlyDependency
                                )
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:compile-only:1.0"),
            results.single { result -> result.variantName == DEFAULT_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
    }

    @Test
    fun `compileOnly dependencies from generated non app modules do not require binary root reachability`() {
        val compileOnlyDependency = dependency("com.example:standalone-compile-only:1.0")
        val appRoot = fakeComponentResult(projectPath = ":app")

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false
                            )
                        )
                    ),
                    ":standalone" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.OTHER,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = JvmBuild,
                                leaf = false,
                                compileOnlyDependenciesByShortId = mapOf(
                                    "com.example:standalone-compile-only" to compileOnlyDependency
                                )
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:standalone-compile-only:1.0"),
            results.single { result -> result.variantName == DEFAULT_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
    }

    @Test
    fun `standalone android test roots do not promote test closure into default bucket`() {
        val testDependency = fakeComponentResult(
            group = "com.example",
            name = "android-test-helper",
            version = "1.0",
            isProject = false
        )
        val testRoot = fakeComponentResult(projectPath = ":ui-tests") {
            addDependencyTo(testDependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":ui-tests" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_TEST,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false
                            ),
                            declaredVariant(
                                name = ANDROID_TEST_VARIANT,
                                variantType = AndroidTest,
                                leaf = false
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = testRoot,
                    projectPath = ":ui-tests",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild,
                    targetBuckets = setOf(ANDROID_TEST_VARIANT)
                )
            )
        ).resolve()

        assertEquals(
            emptyList<String>(),
            results.single { result -> result.variantName == DEFAULT_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
        assertEquals(
            listOf("com.example:android-test-helper:1.0"),
            results.single { result -> result.variantName == ANDROID_TEST_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
    }

    @Test
    fun `declared main dependencies from generated non app modules require binary root reachability`() {
        val mainDependency = fakeComponentResult(
            group = "com.example",
            name = "main",
            version = "1.0",
            isProject = false
        )
        val appRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(mainDependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false
                            )
                        )
                    ),
                    ":lib" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.OTHER,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredDependencies = setOf("com.example:declared-main:1.0")
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:main:1.0"),
            results.single { result -> result.variantName == DEFAULT_VARIANT }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
    }

    @Test
    fun `emits selected composite hierarchy buckets instead of dropping them`() {
        val compositeDependency = fakeComponentResult(
            group = "com.example",
            name = "composite",
            version = "1.0",
            isProject = false
        )
        val demoFreeDebugRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(compositeDependency)
        }
        val demoFreeReleaseRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(compositeDependency)
        }
        val demoPaidRoot = fakeComponentResult(projectPath = ":app")
        val fullPaidRoot = fakeComponentResult(projectPath = ":app")

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = "demoFreeDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("demo", "free"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "demo", "free", "demoFree")
                            ),
                            declaredVariant(
                                name = "demoFreeRelease",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "release",
                                productFlavors = listOf("demo", "free"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "release", "demo", "free", "demoFree")
                            ),
                            declaredVariant(
                                name = "demoPaidDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("demo", "paid"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "demo", "paid", "demoPaid")
                            ),
                            declaredVariant(
                                name = "fullPaidDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("full", "paid"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "full", "paid", "fullPaid")
                            ),
                            declaredVariant(
                                name = "demoFree",
                                variantType = AndroidBuild,
                                leaf = false,
                                extendsFrom = setOf(DEFAULT_VARIANT)
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = demoFreeDebugRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "demoFreeDebug",
                    leafName = "demoFreeDebug",
                    variantType = AndroidBuild
                ),
                root(
                    component = demoFreeReleaseRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "demoFreeRelease",
                    leafName = "demoFreeRelease",
                    variantType = AndroidBuild
                ),
                root(
                    component = demoPaidRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "demoPaidDebug",
                    leafName = "demoPaidDebug",
                    variantType = AndroidBuild
                ),
                root(
                    component = fullPaidRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "fullPaidDebug",
                    leafName = "fullPaidDebug",
                    variantType = AndroidBuild
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:composite:1.0"),
            results.single { result -> result.variantName == "demoFree" }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
        assertNull(results.singleOrNull { result -> result.variantName == "demoFreeDebug" })
        assertNull(results.singleOrNull { result -> result.variantName == "demoFreeRelease" })
    }

    @Test
    fun `removes globally merged leaf bucket dependency covered by merged ancestor bucket`() {
        val sharedDependency = fakeComponentResult(
            group = "com.example",
            name = "shared",
            version = "1.0",
            isProject = false
        )
        val appHmsMoveitRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(sharedDependency)
        }
        val appHmsOvoRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(sharedDependency)
        }
        val testAppHmsMoveitRoot = fakeComponentResult(projectPath = ":test-app") {
            addDependencyTo(sharedDependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = "hmsMoveitDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("hms", "moveit"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "hms", "moveit")
                            ),
                            declaredVariant(
                                name = "hmsOvoDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("hms", "ovo"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "hms", "ovo")
                            )
                        )
                    ),
                    ":test-app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = "hmsMoveitDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("hms", "moveit"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "hms", "moveit")
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appHmsMoveitRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "hmsMoveitDebug",
                    leafName = "hmsMoveitDebug",
                    variantType = AndroidBuild
                ),
                root(
                    component = appHmsOvoRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "hmsOvoDebug",
                    leafName = "hmsOvoDebug",
                    variantType = AndroidBuild
                ),
                root(
                    component = testAppHmsMoveitRoot,
                    projectPath = ":test-app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "hmsMoveitDebug",
                    leafName = "hmsMoveitDebug",
                    variantType = AndroidBuild
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:shared:1.0"),
            results.single { result -> result.variantName == "debug" }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
        assertNull(results.singleOrNull { result -> result.variantName == "hmsMoveitDebug" })
    }

    @Test
    fun `keeps globally merged leaf bucket dependency when project ancestors differ`() {
        val sharedDependency = fakeComponentResult(
            group = "com.example",
            name = "shared",
            version = "1.0",
            isProject = false
        )
        val appFreeDebugRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(sharedDependency)
        }
        val appFreeReleaseRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(sharedDependency)
        }
        val libFreeDebugRoot = fakeComponentResult(projectPath = ":lib") {
            addDependencyTo(sharedDependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = "freeDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("free"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "free")
                            ),
                            declaredVariant(
                                name = "freeRelease",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "release",
                                productFlavors = listOf("free"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "release", "free")
                            )
                        )
                    ),
                    ":lib" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.OTHER,
                        variants = listOf(
                            declaredVariant(
                                name = "freeDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("free"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug")
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appFreeDebugRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "freeDebug",
                    leafName = "freeDebug",
                    variantType = AndroidBuild
                ),
                root(
                    component = appFreeReleaseRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "freeRelease",
                    leafName = "freeRelease",
                    variantType = AndroidBuild
                ),
                root(
                    component = libFreeDebugRoot,
                    projectPath = ":lib",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "freeDebug",
                    leafName = "freeDebug",
                    variantType = AndroidBuild
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:shared:1.0"),
            results.single { result -> result.variantName == "free" }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
        assertEquals(
            listOf("com.example:shared:1.0"),
            results.single { result -> result.variantName == "freeDebug" }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
    }

    @Test
    fun `globally merged leaf bucket adopts selected ancestor version when project ancestors differ`() {
        val lowerVersionDependency = fakeComponentResult(
            group = "com.example",
            name = "shared",
            version = "1.0",
            isProject = false
        )
        val higherVersionDependency = fakeComponentResult(
            group = "com.example",
            name = "shared",
            version = "2.0",
            isProject = false
        )
        val appFreeDebugRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(higherVersionDependency)
        }
        val appFreeReleaseRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(higherVersionDependency)
        }
        val libFreeDebugRoot = fakeComponentResult(projectPath = ":lib") {
            addDependencyTo(lowerVersionDependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = "freeDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("free"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "free")
                            ),
                            declaredVariant(
                                name = "freeRelease",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "release",
                                productFlavors = listOf("free"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "release", "free")
                            )
                        )
                    ),
                    ":lib" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.OTHER,
                        variants = listOf(
                            declaredVariant(
                                name = "freeDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("free"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug")
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appFreeDebugRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "freeDebug",
                    leafName = "freeDebug",
                    variantType = AndroidBuild
                ),
                root(
                    component = appFreeReleaseRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "freeRelease",
                    leafName = "freeRelease",
                    variantType = AndroidBuild
                ),
                root(
                    component = libFreeDebugRoot,
                    projectPath = ":lib",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "freeDebug",
                    leafName = "freeDebug",
                    variantType = AndroidBuild
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:shared:2.0"),
            results.single { result -> result.variantName == "free" }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
        assertEquals(
            listOf("com.example:shared:2.0"),
            results.single { result -> result.variantName == "freeDebug" }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
    }

    @Test
    fun `resolved leaf version wins over stale declared hierarchy version in final results`() {
        val resolvedDependency = fakeComponentResult(
            group = "com.example",
            name = "library",
            version = "2.0",
            isProject = false
        )
        val appRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(resolvedDependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = "debug",
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredDependencies = setOf("com.example:library:1.0"),
                                excludeRulesByShortId = mapOf(
                                    "com.example:library" to setOf(ExcludeRule("com.example", "blocked"))
                                ),
                                extendsFrom = setOf(DEFAULT_VARIANT)
                            ),
                            declaredVariant(
                                name = "freeDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("free"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "free")
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "freeDebug",
                    leafName = "freeDebug",
                    variantType = AndroidBuild,
                    variantNames = setOf("freeDebug", "debug", "free", DEFAULT_VARIANT)
                )
            )
        ).resolve()

        val debugDependency = results.single { result -> result.variantName == "debug" }
            .dependencies
            .getValue(COMPILE.name)
            .single()
        assertEquals("com.example:library:2.0", debugDependency.id)
        assertEquals(setOf(ExcludeRule("com.example", "blocked")), debugDependency.excludeRules)
    }

    @Test
    fun `default bucket keeps app declared exclude when dependency is inferred from leaves`() {
        val resolvedDependency = fakeComponentResult(
            group = "com.example",
            name = "library",
            version = "2.0",
            isProject = false
        )
        val debugRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(resolvedDependency)
        }
        val releaseRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(resolvedDependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = DEFAULT_VARIANT,
                                variantType = AndroidBuild,
                                leaf = false,
                                declaredDependencies = setOf("com.example:library:1.0"),
                                excludeRulesByShortId = mapOf(
                                    "com.example:library" to setOf(ExcludeRule("com.example", "blocked"))
                                )
                            ),
                            declaredVariant(
                                name = "debug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                extendsFrom = setOf(DEFAULT_VARIANT)
                            ),
                            declaredVariant(
                                name = "release",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "release",
                                extendsFrom = setOf(DEFAULT_VARIANT)
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = debugRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "debug",
                    leafName = "debug",
                    variantType = AndroidBuild,
                    variantNames = setOf("debug", DEFAULT_VARIANT)
                ),
                root(
                    component = releaseRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "release",
                    leafName = "release",
                    variantType = AndroidBuild,
                    variantNames = setOf("release", DEFAULT_VARIANT)
                )
            )
        ).resolve()

        val defaultDependency = results.single { result -> result.variantName == DEFAULT_VARIANT }
            .dependencies
            .getValue(COMPILE.name)
            .single()
        assertEquals("com.example:library:2.0", defaultDependency.id)
        assertEquals(setOf(ExcludeRule("com.example", "blocked")), defaultDependency.excludeRules)
    }

    @Test
    fun `project scoped main plans collapse to global bucket by max version`() {
        val lowerVersionDependency = fakeComponentResult(
            group = "com.example",
            name = "shared",
            version = "1.0",
            isProject = false
        )
        val higherVersionDependency = fakeComponentResult(
            group = "com.example",
            name = "shared",
            version = "2.0",
            isProject = false
        )
        val appRoot = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(lowerVersionDependency)
        }
        val testAppRoot = fakeComponentResult(projectPath = ":test-app") {
            addDependencyTo(higherVersionDependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = "freeDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("free")
                            )
                        )
                    ),
                    ":test-app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = "freeDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("free")
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = appRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = "debug",
                    variantType = AndroidBuild
                ),
                root(
                    component = testAppRoot,
                    projectPath = ":test-app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = "debug",
                    variantType = AndroidBuild
                )
            )
        ).resolve()

        assertEquals(
            listOf("com.example:shared:2.0"),
            results.single { result -> result.variantName == "debug" }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
    }

    @Test
    fun `unsafe flavor hierarchy roots do not make debug dependency direct in flavor buckets`() {
        val pagingDependency = fakeComponentResult(
            group = "androidx.paging",
            name = "paging-runtime",
            version = "3.1.1",
            isProject = false
        )
        val emptyRoot = fakeComponentResult(projectPath = ":app")
        fun rootWithPaging() = fakeComponentResult(projectPath = ":app") {
            addDependencyTo(pagingDependency)
        }

        val results = AggregatedDependencyResolver(
            logger = ProjectBuilder.builder().build().logger,
            declaredDependencyMetadata = DeclaredDependencyMetadata(
                projects = mapOf(
                    ":app" to ProjectDeclaredDependencyMetadata(
                        projectType = DeclaredProjectType.ANDROID_APPLICATION,
                        variants = listOf(
                            declaredVariant(
                                name = "demoFreeDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("demo", "free"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "demo", "free")
                            ),
                            declaredVariant(
                                name = "demoPaidDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("demo", "paid"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "demo", "paid")
                            ),
                            declaredVariant(
                                name = "fullFreeDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("full", "free"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "full", "free")
                            ),
                            declaredVariant(
                                name = "fullPaidDebug",
                                variantType = AndroidBuild,
                                leaf = true,
                                buildType = "debug",
                                productFlavors = listOf("full", "paid"),
                                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "full", "paid")
                            )
                        )
                    )
                )
            ),
            workspaceDependencyRoots = listOf(
                root(
                    component = emptyRoot,
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = DEFAULT_VARIANT,
                    variantType = AndroidBuild
                ),
                root(
                    component = rootWithPaging(),
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = "demo",
                    variantType = AndroidBuild
                ),
                root(
                    component = rootWithPaging(),
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = "free",
                    variantType = AndroidBuild
                ),
                root(
                    component = rootWithPaging(),
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = "full",
                    variantType = AndroidBuild
                ),
                root(
                    component = rootWithPaging(),
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = "paid",
                    variantType = AndroidBuild
                ),
                root(
                    component = rootWithPaging(),
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "demoFreeDebug",
                    leafName = "demoFreeDebug",
                    variantType = AndroidBuild
                ),
                root(
                    component = rootWithPaging(),
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "demoPaidDebug",
                    leafName = "demoPaidDebug",
                    variantType = AndroidBuild
                ),
                root(
                    component = rootWithPaging(),
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "fullFreeDebug",
                    leafName = "fullFreeDebug",
                    variantType = AndroidBuild
                ),
                root(
                    component = rootWithPaging(),
                    projectPath = ":app",
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "fullPaidDebug",
                    leafName = "fullPaidDebug",
                    variantType = AndroidBuild
                )
            )
        ).resolve()

        assertEquals(
            listOf("androidx.paging:paging-runtime:3.1.1"),
            results.single { result -> result.variantName == "debug" }
                .dependencies
                .getValue(COMPILE.name)
                .map(ResolvedDependency::id)
        )
        assertNull(results.singleOrNull { result -> result.variantName == "demo" })
        assertNull(results.singleOrNull { result -> result.variantName == "free" })
        assertNull(results.singleOrNull { result -> result.variantName == "full" })
        assertNull(results.singleOrNull { result -> result.variantName == "paid" })
    }

    private fun dependency(id: String): ResolvedDependency {
        return ResolvedDependency.fromId(id, "maven")
    }

    private fun covered(bucketName: String, dependency: ResolvedDependency): CoveredDependency {
        return CoveredDependency(bucketName, dependency)
    }

    private fun declaredVariant(
        name: String,
        variantType: com.grab.grazel.gradle.variant.VariantType,
        leaf: Boolean,
        buildType: String? = null,
        productFlavors: List<String> = emptyList(),
        declaredDependencies: Set<String> = emptySet(),
        declaredProjectDependencies: Set<String> = emptySet(),
        excludeRulesByShortId: Map<String, Set<ExcludeRule>> = emptyMap(),
        compileOnlyDependenciesByShortId: Map<String, ResolvedDependency> = emptyMap(),
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
            productFlavors = productFlavors,
            declaredDependencies = declaredDependencies,
            declaredDependencyDeclarations = declaredDependencies.mapTo(sortedSetOf(
                compareBy<DeclaredExternalDependency> { declaration -> declaration.bucketName }
                    .thenBy { declaration -> declaration.configurationName }
                    .thenBy { declaration -> declaration.id }
            )) { dependencyId ->
                DeclaredExternalDependency(
                    configurationName = "$name:fixture",
                    bucketName = name,
                    id = dependencyId
                )
            },
            declaredProjectDependencies = declaredProjectDependencies,
            excludeRulesByShortId = excludeRulesByShortId,
            compileOnlyBucketName = name,
            compileOnlyDependenciesByShortId = compileOnlyDependenciesByShortId
        )
    }

    private fun root(
        component: ResolvedComponentResult,
        projectPath: String,
        kind: AggregatedDependencyRootKind,
        bucketName: String,
        leafName: String? = null,
        variantType: com.grab.grazel.gradle.variant.VariantType,
        traverseProjectNodes: Boolean = true,
        directDependencyShortIds: Set<String> = emptySet(),
        variantNames: Set<String> = setOf(bucketName, DEFAULT_VARIANT),
        targetBuckets: Set<String> = emptySet(),
        rootExcludeRulesByShortId: Map<String, Set<ExcludeRule>> = emptyMap()
    ): AggregatedDependencyRoot {
        return AggregatedDependencyRoot(
            root = component,
            metadata = AggregatedDependencyRootMetadata(
                projectPath = projectPath,
                kind = kind,
                configurationName = "$bucketName:${kind.name}",
                bucketName = bucketName,
                leafName = leafName,
                variantNames = variantNames,
                variantType = variantType,
                traverseProjectNodes = traverseProjectNodes,
                directDependencyShortIds = directDependencyShortIds,
                targetBuckets = targetBuckets,
                rootExcludeRulesByShortId = rootExcludeRulesByShortId
            )
        )
    }

    private fun org.gradle.api.internal.artifacts.result.DefaultResolvedComponentResult.addDependencyTo(
        component: ResolvedComponentResult,
        selectedVariantDisplayName: String = ""
    ) {
        val moduleVersion = component.moduleVersion!!
        val moduleIdentifier = DefaultModuleIdentifier.newId(
            moduleVersion.group,
            moduleVersion.name
        )
        addDependency(
            DefaultResolvedDependencyResult(
                DefaultModuleComponentSelector.newSelector(moduleIdentifier, moduleVersion.version),
                false,
                component,
                DefaultResolvedVariantResult(
                    DefaultModuleComponentIdentifier.newId(moduleIdentifier, moduleVersion.version),
                    object : DisplayName {
                        override fun getDisplayName(): String = selectedVariantDisplayName
                        override fun getCapitalizedDisplayName(): String = selectedVariantDisplayName
                    },
                    FakeAttributeContainer(),
                    ImmutableCapabilities.EMPTY,
                    null
                ),
                this
            )
        )
    }

    private fun declaredAppMetadata(): DeclaredDependencyMetadata {
        return DeclaredDependencyMetadata(
            projects = mapOf(
                ":app" to ProjectDeclaredDependencyMetadata(
                    projectType = DeclaredProjectType.ANDROID_APPLICATION,
                    variants = listOf(
                        declaredVariant(
                            name = DEFAULT_VARIANT,
                            variantType = AndroidBuild,
                            leaf = false
                        )
                    )
                )
            )
        )
    }

    private fun failingRootComponent(): ResolvedComponentResult {
        return Proxy.newProxyInstance(
            ResolvedComponentResult::class.java.classLoader,
            arrayOf(ResolvedComponentResult::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "toString" -> "project :app"
                "hashCode" -> 1
                "equals" -> false
                "getDependencies" -> throw IllegalStateException("broken root")
                else -> throw UnsupportedOperationException(method.name)
            }
        } as ResolvedComponentResult
    }

}
