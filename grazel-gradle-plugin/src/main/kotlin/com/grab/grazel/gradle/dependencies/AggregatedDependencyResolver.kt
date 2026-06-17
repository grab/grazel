/*
 * Copyright 2024 Grabtaxi Holdings PTE LTD (GRAB)
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

import com.grab.grazel.gradle.MigrationChecker
import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.gradle.dependencies.model.ExcludeRule
import com.grab.grazel.gradle.dependencies.model.OverrideTarget
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.COMPILE
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.KSP
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.hasSameBucketOwnerAs
import com.grab.grazel.gradle.dependencies.model.hasSameEffectiveIdentityAs
import com.grab.grazel.gradle.dependencies.model.versionInfo
import com.grab.grazel.gradle.variant.ANDROID_TEST_VARIANT
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.TEST_VARIANT
import com.grab.grazel.gradle.variant.Variant
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.hasKsp
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.Test
import com.grab.grazel.gradle.variant.VariantBuilder
import com.grab.grazel.gradle.variant.extendsOnlyFromDefaultVariants
import com.grab.grazel.gradle.variant.isBase
import com.grab.grazel.migrate.dependencies.toMavenRepoName
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.util.TreeSet

/**
 * Resolves all external dependencies for the migratable project set by resolving app module leaf
 * variant `RuntimeClasspath` configurations directly, then bucketing via set-intersection logic.
 *
 * When [aggregatedDependencyResolution][com.grab.grazel.extension.ExperimentsExtension.aggregatedDependencyResolution]
 * is enabled, this class:
 * 1. Finds all migratable binary modules — `com.android.application` and standalone
 *    `com.android.test` modules (the latter declare their own deps and are treated as app roots).
 * 2. Collects all leaf variants (those with a non-null backingVariant of type [BaseVariant] and
 *    not isBase/extendsOnlyFromDefaultVariants).
 * 3. Resolves `<leafName>RuntimeClasspath` for each leaf, plus `UnitTest` and `AndroidTest`
 *    classpaths.
 * 4. Buckets via set-intersection logic: default (intersection of all), per-build-type, per-flavor,
 *    per-leaf-residual, test, androidTest, lint.
 *
 * The downstream [ComputeWorkspaceDependencies] pipeline is unchanged — both paths feed into
 * [ComputeWorkspaceDependencies.computeFromResults].
 *
 * @param rootProject The Gradle root project.
 * @param migrationChecker Identifies which sub-projects are migratable.
 * @param variantBuilder Enumerates the variant set per sub-project.
 */
internal class AggregatedDependencyResolver(
    private val rootProject: Project,
    private val migrationChecker: MigrationChecker,
    private val variantBuilder: VariantBuilder,
    private val declaredDependencyMetadataCollector: DeclaredDependencyMetadataCollector =
        DeclaredDependencyMetadataCollector()
) {

    private val logger = rootProject.logger

    /**
     * Resolve all variants across all migratable projects by resolving app module leaf
     * RuntimeClasspath configurations and bucketing the results.
     *
     * @return One [ResolveDependenciesResult] per synthetic bucket name (e.g. "default", "debug",
     *   "test", "androidTest", "lint", flavor names, or leaf-specific names), covering COMPILE and
     *   KSP scopes.
     */
    fun resolve(): List<ResolveDependenciesResult> {
        val migratableProjects = rootProject.subprojects.filter { migrationChecker.canMigrate(it) }

        if (migratableProjects.isEmpty()) return emptyList()

        val variantsByProject = migratableProjects.associateWith { project ->
            try {
                variantBuilder.build(project)
            } catch (e: Exception) {
                logger.warn("Grazel: Failed to enumerate variants for ${project.path}: ${e.message}")
                emptyList()
            }
        }

        // Application modules are the root classpaths for main buckets. Standalone
        // com.android.test modules are also binary roots, but their implementation deps belong to
        // the androidTest bucket, not to main/default buckets.
        val appProjects = migratableProjects.filter {
            it.plugins.hasPlugin("com.android.application")
        }
        val standaloneTestProjects = migratableProjects.filter {
            it.plugins.hasPlugin("com.android.test")
        }

        if (appProjects.isEmpty() && standaloneTestProjects.isEmpty()) {
            logger.warn("Grazel: No migratable binary modules found for aggregated dependency resolution")
            return emptyList()
        }

        // Per-leaf-variant closure maps: leaf variant name -> Map<shortId, ResolvedDependency>
        val leafClosures = mutableMapOf<String, Map<String, ResolvedDependency>>()
        val leafUnitTestClosures = mutableMapOf<String, Map<String, ResolvedDependency>>()
        val leafAndroidTestClosures = mutableMapOf<String, Map<String, ResolvedDependency>>()
        // leaf name -> build type name
        val leafBuildTypes = mutableMapOf<String, String>()
        // leaf name -> flavor names
        val leafFlavors = mutableMapOf<String, List<String>>()
        // Synthetic hierarchy buckets such as default/debug/free, resolved from their own Gradle
        // declaration buckets. These prevent filtered leaves from making debug/flavor deps look
        // like default deps.
        val hierarchyBucketClosures = mutableMapOf<String, Map<String, ResolvedDependency>>()
        val testHierarchyBucketClosures = mutableMapOf<String, Map<String, ResolvedDependency>>()

        // Lint deps across all migratable projects
        var lintDeps = emptyMap<String, ResolvedDependency>()

        // KSP deps: collected from all migratable projects across all (synthetic) variants
        // We collect projectVariant pairs keyed by variantName for later KSP resolution
        val kspProjectVariants = mutableMapOf<String, MutableList<Pair<Project, com.grab.grazel.gradle.variant.Variant<*>>>>()

        for (app in appProjects) {
            val variants = variantsByProject[app].orEmpty()
            if (variants.isEmpty()) continue

            variants
                .filter { v -> v.variantType == AndroidBuild && (v.isBase || v.extendsOnlyFromDefaultVariants) }
                .forEach { v ->
                    val closure = resolveVariantToDependencyMap(v, app, variantsByProject)
                    if (closure.isNotEmpty()) {
                        hierarchyBucketClosures[v.name] = hierarchyBucketClosures[v.name]
                            ?.let { existing -> unionDependencyMaps(existing, closure) }
                            ?: closure
                    }
                }
            variants
                .filter { v ->
                    (v.variantType == Test && v.name == TEST_VARIANT) ||
                        (v.variantType == AndroidTest && v.name == ANDROID_TEST_VARIANT)
                }
                .forEach { v ->
                    val closure = resolveVariantToDependencyMap(v, app, variantsByProject)
                    if (closure.isNotEmpty()) {
                        testHierarchyBucketClosures[v.name] = testHierarchyBucketClosures[v.name]
                            ?.let { existing -> unionDependencyMaps(existing, closure) }
                            ?: closure
                    }
                }

            // Leaf variants: every concrete AGP BaseVariant is a binary/root classpath we can
            // resolve directly. Build-type-only apps expose debug/release as BaseVariants that
            // extend only default; they still need to be treated as leaves or the aggregated path
            // has no root classpath to resolve.
            val leafVariants = variants.filter { v ->
                v.variantType == AndroidBuild &&
                    v.backingVariant is com.android.build.gradle.api.BaseVariant
            }

            for (v in leafVariants) {
                val leafName = v.name
                val runtimeConfig = app.configurations.findByName("${leafName}RuntimeClasspath")
                    ?: continue
                val leafHierarchyNames = setOf(leafName) + v.extendsFrom
                val mainExcludeRulesByProjectPath = declaredDependencyMetadataCollector
                    .collectExcludeRulesByProjectPath(
                        variantsByProject = variantsByProject,
                        variantTypes = setOf(AndroidBuild),
                        variantNames = leafHierarchyNames
                    )
                val runtimeClosure = resolveConfigToDependencyMap(
                    runtimeConfig,
                    app,
                    mainExcludeRulesByProjectPath
                )
                if (runtimeClosure.isEmpty()) continue

                // Also resolve CompileClasspath to capture compileOnly + api-of-consumed-libs deps
                // that are omitted from RuntimeClasspath. Union both; prefer higher version on conflict.
                val compileConfig = app.configurations.findByName("${leafName}CompileClasspath")
                val compileClosure = if (compileConfig != null) {
                    resolveConfigToDependencyMap(compileConfig, app, mainExcludeRulesByProjectPath)
                } else emptyMap()
                val closure = unionDependencyMaps(runtimeClosure, compileClosure)

                // Union with any existing entry for this leaf name (multiple app/test modules can
                // share the same leaf variant name, e.g. sample-android and sample-android-tests
                // both have "demoFreeDebug"). We prefer higher versions on conflict.
                leafClosures[leafName] = if (leafClosures.containsKey(leafName)) {
                    unionDependencyMaps(leafClosures[leafName]!!, closure)
                } else {
                    closure
                }

                // Record build type and flavors for bucketing (set unconditionally using the first
                // module that provides this leaf; multiple modules share the same variant structure)
                val bv = v.backingVariant as com.android.build.gradle.api.BaseVariant
                if (!leafBuildTypes.containsKey(leafName)) {
                    leafBuildTypes[leafName] = bv.buildType.name
                    leafFlavors[leafName] = bv.productFlavors.map { it.name }
                }

                // Unit test: union runtime + compile classpaths
                val unitTestRuntime = app.configurations.findByName("${leafName}UnitTestRuntimeClasspath")
                val unitTestCompile = app.configurations.findByName("${leafName}UnitTestCompileClasspath")
                if (unitTestRuntime != null || unitTestCompile != null) {
                    val unitTestVariantNames = variantNamesForLeafTest(
                        variants = variants,
                        leafHierarchyNames = leafHierarchyNames,
                        testType = Test
                    )
                    val unitTestExcludeRulesByProjectPath = declaredDependencyMetadataCollector
                        .collectExcludeRulesByProjectPath(
                            variantsByProject = variantsByProject,
                            variantTypes = setOf(Test),
                            variantNames = unitTestVariantNames
                        )
                    val rtMap = unitTestRuntime
                        ?.let { resolveConfigToDependencyMap(it, app, unitTestExcludeRulesByProjectPath) }
                        ?: emptyMap()
                    val cpMap = unitTestCompile
                        ?.let { resolveConfigToDependencyMap(it, app, unitTestExcludeRulesByProjectPath) }
                        ?: emptyMap()
                    val testClosure = unionDependencyMaps(rtMap, cpMap)
                    if (testClosure.isNotEmpty()) {
                        leafUnitTestClosures[leafName] = if (leafUnitTestClosures.containsKey(leafName)) {
                            unionDependencyMaps(leafUnitTestClosures[leafName]!!, testClosure)
                        } else {
                            testClosure
                        }
                    }
                }

                // Android test: union runtime + compile classpaths
                val androidTestRuntime = app.configurations.findByName("${leafName}AndroidTestRuntimeClasspath")
                val androidTestCompile = app.configurations.findByName("${leafName}AndroidTestCompileClasspath")
                if (androidTestRuntime != null || androidTestCompile != null) {
                    val androidTestVariantNames = variantNamesForLeafTest(
                        variants = variants,
                        leafHierarchyNames = leafHierarchyNames,
                        testType = AndroidTest
                    )
                    val androidTestExcludeRulesByProjectPath = declaredDependencyMetadataCollector
                        .collectExcludeRulesByProjectPath(
                            variantsByProject = variantsByProject,
                            variantTypes = setOf(AndroidTest),
                            variantNames = androidTestVariantNames
                        )
                    val rtMap = androidTestRuntime
                        ?.let { resolveConfigToDependencyMap(it, app, androidTestExcludeRulesByProjectPath) }
                        ?: emptyMap()
                    val cpMap = androidTestCompile
                        ?.let { resolveConfigToDependencyMap(it, app, androidTestExcludeRulesByProjectPath) }
                        ?: emptyMap()
                    val androidTestClosure = unionDependencyMaps(rtMap, cpMap)
                    if (androidTestClosure.isNotEmpty()) {
                        leafAndroidTestClosures[leafName] = if (leafAndroidTestClosures.containsKey(leafName)) {
                            unionDependencyMaps(leafAndroidTestClosures[leafName]!!, androidTestClosure)
                        } else {
                            androidTestClosure
                        }
                    }
                }
            }
        }

        fun addToHierarchyBucket(
            bucketName: String,
            closure: Map<String, ResolvedDependency>
        ) {
            if (closure.isEmpty()) return
            hierarchyBucketClosures[bucketName] = hierarchyBucketClosures[bucketName]
                ?.let { existing -> unionDependencyMaps(existing, closure) }
                ?: closure
        }

        fun addToTestHierarchyBucket(
            bucketName: String,
            closure: Map<String, ResolvedDependency>
        ) {
            if (closure.isEmpty()) return
            testHierarchyBucketClosures[bucketName] = testHierarchyBucketClosures[bucketName]
                ?.let { existing -> unionDependencyMaps(existing, closure) }
                ?: closure
        }

        for (testApp in standaloneTestProjects) {
            val variants = variantsByProject[testApp].orEmpty()
            if (variants.isEmpty()) continue

            variants
                .filter { v -> v.variantType == AndroidBuild && (v.isBase || v.extendsOnlyFromDefaultVariants) }
                .forEach { v ->
                    val closure = resolveVariantToDependencyMap(v, testApp, variantsByProject)
                    if (closure.isNotEmpty()) {
                        addToHierarchyBucket(DEFAULT_VARIANT, closure)
                        addToTestHierarchyBucket(ANDROID_TEST_VARIANT, closure)
                    }
                }

            val leafVariants = variants.filter { v ->
                v.variantType == AndroidBuild &&
                    v.backingVariant is com.android.build.gradle.api.BaseVariant
            }

            for (v in leafVariants) {
                val leafName = v.name
                val runtimeConfig = testApp.configurations.findByName("${leafName}RuntimeClasspath")
                    ?: continue
                val leafHierarchyNames = setOf(leafName) + v.extendsFrom
                val excludeRulesByProjectPath = declaredDependencyMetadataCollector
                    .collectExcludeRulesByProjectPath(
                        variantsByProject = variantsByProject,
                        variantTypes = setOf(AndroidBuild),
                        variantNames = leafHierarchyNames
                    )
                val runtimeClosure = resolveConfigToDependencyMap(
                    runtimeConfig,
                    testApp,
                    excludeRulesByProjectPath
                )
                if (runtimeClosure.isEmpty()) continue

                val compileConfig = testApp.configurations.findByName("${leafName}CompileClasspath")
                val compileClosure = if (compileConfig != null) {
                    resolveConfigToDependencyMap(compileConfig, testApp, excludeRulesByProjectPath)
                } else emptyMap()
                val closure = unionDependencyMaps(runtimeClosure, compileClosure)

                leafAndroidTestClosures[leafName] = if (leafAndroidTestClosures.containsKey(leafName)) {
                    unionDependencyMaps(leafAndroidTestClosures[leafName]!!, closure)
                } else {
                    closure
                }
                // Standalone com.android.test modules are binary roots, but their declared deps
                // were historically labelled from @maven. Keep that broad ownership so generated
                // BUILD files stay compatible while androidTest can still own only leftovers.
                addToHierarchyBucket(DEFAULT_VARIANT, closure)
            }
        }

        // For non-app/non-test library modules, also resolve their compileClasspath to capture
        // compileOnly deps (e.g. lint API deps in com.android.lint modules, annotation processors,
        // etc.) that are NOT transitively reachable from any app module's classpath.
        // These are unioned into EVERY leaf closure so they land in the intersection (default bucket)
        // and thus map to @maven — the same place the non-aggregated path would have put them.
        val nonAppProjects = migratableProjects.filter { project ->
            !project.plugins.hasPlugin("com.android.application") &&
                !project.plugins.hasPlugin("com.android.test")
        }
        declaredDependencyMetadataCollector
            .collectCompileOnlyDependenciesByBucket(
                variantsByProject = variantsByProject,
                projects = nonAppProjects
            )
            .forEach { (bucketName, dependencies) ->
                when (bucketName) {
                    TEST_VARIANT, ANDROID_TEST_VARIANT -> addToTestHierarchyBucket(bucketName, dependencies)
                    else -> addToHierarchyBucket(bucketName, dependencies)
                }
            }

        for (project in nonAppProjects) {
            // Try common compileClasspath config names (Kotlin JVM, Android library default variant)
            val compileOnlyConfigs = listOf("compileClasspath", "debugCompileClasspath", "releaseCompileClasspath")
            for (configName in compileOnlyConfigs) {
                val config = project.configurations.findByName(configName) ?: continue
                val extraDeps = resolveConfigToDependencyMap(config, project)
                if (extraDeps.isEmpty()) continue
                // Union into all existing leaf closures so these deps appear in the intersection
                for (leafName in leafClosures.keys.toList()) {
                    leafClosures[leafName] = unionDependencyMaps(leafClosures[leafName]!!, extraDeps)
                }
                break // One successful resolution per project is enough
            }
        }

        // Collect lint deps from all migratable projects
        for (project in migratableProjects) {
            project.configurations.findByName("lintChecks")?.let { lintConfig ->
                lintDeps = unionDependencyMaps(
                    lintDeps,
                    resolveConfigToDependencyMap(lintConfig, project)
                )
            }
        }

        // Collect KSP project-variants from all migratable projects (synthetic variants only)
        for (project in migratableProjects) {
            val variants = variantsByProject[project].orEmpty()
            variants
                .filter { it.isBase || it.extendsOnlyFromDefaultVariants }
                .forEach { v ->
                    kspProjectVariants.getOrPut(v.name) { mutableListOf() }.add(project to v)
                }
        }

        if (leafClosures.isEmpty()) {
            logger.warn("Grazel: No leaf variant runtime classpaths resolved")
            return emptyList()
        }

        val allLeafNames = leafClosures.keys.toList()

        // default = intersection of all leaf closures with the same bucket-owner identity
        val leafIntersectionDeps = intersectByBucketOwner(allLeafNames.map { leafClosures[it]!! })
        val hierarchyDefaultDeps = hierarchyBucketClosures[DEFAULT_VARIANT].orEmpty()
        val nonDefaultHierarchyDeps = hierarchyBucketClosures
            .filterKeys { it != DEFAULT_VARIANT }
            .values
            .flatMap { it.values }
        val defaultDeps = (leafIntersectionDeps + hierarchyDefaultDeps)
            .withoutDependenciesOwnedByNonDefaultHierarchy(
                hierarchyDefaultDeps = hierarchyDefaultDeps,
                nonDefaultHierarchyDependencies = nonDefaultHierarchyDeps
            )

        fun Map<String, ResolvedDependency>.asCoveredBy(bucketName: String): List<CoveredDependency> {
            return values.map { CoveredDependency(bucketName, it) }
        }

        fun coveredByDefault(): List<CoveredDependency> {
            return defaultDeps.asCoveredBy(DEFAULT_VARIANT)
        }

        // Build type buckets: intersection of all leaves for a given build type, minus default
        val buildTypeBuckets = mutableMapOf<String, Map<String, ResolvedDependency>>()
        val buildTypes = leafBuildTypes.values.toSet()
        for (bt in buildTypes) {
            val btLeaves = allLeafNames.filter { leafBuildTypes[it] == bt }
            if (btLeaves.isEmpty()) continue
            val hierarchyDeps = hierarchyBucketClosures[bt]
            val candidateDeps = if (hierarchyDeps != null) {
                hierarchyDeps
            } else {
                intersectByBucketOwner(btLeaves.map { leafClosures[it]!! })
            }
            val btDeps = candidateDeps.withoutDependenciesCoveredBy(coveredByDefault())
            if (btDeps.isNotEmpty()) {
                buildTypeBuckets[bt] = btDeps
            }
        }

        // Flavor buckets: intersection of all leaves sharing a flavor, minus default
        val flavorBuckets = mutableMapOf<String, Map<String, ResolvedDependency>>()
        val allFlavors = leafFlavors.values.flatten().toSet()
        for (flavor in allFlavors) {
            val flavorLeaves = allLeafNames.filter { leafFlavors[it]?.contains(flavor) == true }
            if (flavorLeaves.isEmpty()) continue
            val hierarchyDeps = hierarchyBucketClosures[flavor]
            val candidateDeps = if (hierarchyDeps != null) {
                hierarchyDeps
            } else {
                intersectByBucketOwner(flavorLeaves.map { leafClosures[it]!! })
            }
            val flavorDeps = candidateDeps.withoutDependenciesCoveredBy(
                coveredByDefault() + buildTypeBuckets.flatMap { (bucketName, deps) ->
                    deps.asCoveredBy(bucketName)
                }
            )
            if (flavorDeps.isNotEmpty()) {
                flavorBuckets[flavor] = flavorDeps
            }
        }

        // Per-leaf residual buckets: deps not covered by default, build-type, or flavor buckets
        val perLeafBuckets = mutableMapOf<String, Map<String, ResolvedDependency>>()
        for (leafName in allLeafNames) {
            val bt = leafBuildTypes[leafName] ?: ""
            val flavors = leafFlavors[leafName] ?: emptyList()
            val coveredDeps = coveredByDefault() +
                buildTypeBuckets[bt].orEmpty().asCoveredBy(bt) +
                flavors.flatMap { flavor -> flavorBuckets[flavor].orEmpty().asCoveredBy(flavor) }
            val leafDeps = leafClosures[leafName]!!.withoutDependenciesCoveredBy(coveredDeps)
            if (leafDeps.isNotEmpty()) {
                perLeafBuckets[leafName] = leafDeps
            }
        }

        val mainCoveredDeps = buildList {
            addAll(coveredByDefault())
            buildTypeBuckets.forEach { (bucketName, deps) -> addAll(deps.asCoveredBy(bucketName)) }
            flavorBuckets.forEach { (bucketName, deps) -> addAll(deps.asCoveredBy(bucketName)) }
        }

        fun intersectClosures(
            closures: Map<String, Map<String, ResolvedDependency>>
        ): Map<String, ResolvedDependency> {
            return intersectByBucketOwner(closures.values)
        }

        // Test bucket: explicit test deps, or intersection of all leaf unit-test closures,
        // minus deps already directly covered by main buckets. This matches the legacy per-variant
        // resolver: non-base variants subtract direct base deps, but can still own first-level deps
        // that only appear transitively in the base/default flattened repo.
        val testCandidates = unionDependencyMaps(
            testHierarchyBucketClosures[TEST_VARIANT].orEmpty(),
            intersectClosures(leafUnitTestClosures)
        )
        val testDeps = testCandidates.withoutDependenciesCoveredByDirectDependencies(mainCoveredDeps)

        // AndroidTest bucket: explicit androidTest deps, or intersection of all leaf android-test
        // closures, minus deps already directly covered by main buckets.
        val androidTestCandidates = unionDependencyMaps(
            testHierarchyBucketClosures[ANDROID_TEST_VARIANT].orEmpty(),
            intersectClosures(leafAndroidTestClosures)
        )
        val androidTestDeps = androidTestCandidates.withoutDependenciesCoveredByDirectDependencies(mainCoveredDeps)

        // Build the ResolveDependenciesResult list
        val results = mutableListOf<ResolveDependenciesResult>()

        fun buildResult(
            bucketName: String,
            deps: Map<String, ResolvedDependency>
        ): ResolveDependenciesResult {
            val kspDeps = if (bucketName == "default") {
                resolveKspDeps(kspProjectVariants["default"] ?: emptyList(), "default")
            } else emptySet()
            return ResolveDependenciesResult(
                variantName = bucketName,
                dependencies = mapOf(
                    COMPILE.name to deps.values.toSortedSet(),
                    KSP.name to kspDeps
                )
            )
        }

        // Default bucket (required — ComputeWorkspaceDependencies.computeInternal calls getValue(DEFAULT_VARIANT))
        results.add(buildResult("default", defaultDeps))

        buildTypeBuckets.forEach { (bt, deps) -> results.add(buildResult(bt, deps)) }
        flavorBuckets.forEach { (f, deps) -> results.add(buildResult(f, deps)) }
        perLeafBuckets.forEach { (leaf, deps) -> results.add(buildResult(leaf, deps)) }
        if (testDeps.isNotEmpty()) results.add(buildResult("test", testDeps))
        if (androidTestDeps.isNotEmpty()) results.add(buildResult("androidTest", androidTestDeps))
        if (lintDeps.isNotEmpty()) results.add(buildResult("lint", lintDeps))

        return results
    }

    private fun resolveVariantToDependencyMap(
        variant: Variant<*>,
        project: Project,
        variantsByProject: Map<Project, Collection<Variant<*>>>,
    ): Map<String, ResolvedDependency> {
        val excludeRulesByProjectPath = declaredDependencyMetadataCollector
            .collectExcludeRulesByProjectPath(
                variantsByProject = variantsByProject,
                variantTypes = setOf(variant.variantType),
                variantNames = setOf(variant.name) + variant.extendsFrom
            )
        val runtimeClosure = resolveConfigurationsToDependencyMap(
            variant.runtimeConfiguration,
            project,
            excludeRulesByProjectPath
        )
        val compileClosure = resolveConfigurationsToDependencyMap(
            variant.compileConfiguration,
            project,
            excludeRulesByProjectPath
        )
        return unionDependencyMaps(runtimeClosure, compileClosure)
    }

    private fun resolveConfigurationsToDependencyMap(
        configurations: Set<Configuration>,
        project: Project,
        excludeRulesByProjectPath: Map<String, ProjectExcludeRules> = emptyMap()
    ): Map<String, ResolvedDependency> {
        return configurations.fold(emptyMap()) { result, configuration ->
            unionDependencyMaps(
                result,
                resolveConfigToDependencyMap(configuration, project, excludeRulesByProjectPath)
            )
        }
    }

    private fun variantNamesForLeafTest(
        variants: Collection<Variant<*>>,
        leafHierarchyNames: Set<String>,
        testType: VariantType
    ): Set<String> {
        val relevantTestVariantNames = variants
            .asSequence()
            .filter { variant -> variant.variantType == testType }
            .filter { variant ->
                variant.name in leafHierarchyNames ||
                    variant.extendsFrom.any { it in leafHierarchyNames } ||
                    leafHierarchyNames.any { variantName ->
                        variant.name.contains(variantName, ignoreCase = true)
                    }
            }
            .flatMap { variant -> (setOf(variant.name) + variant.extendsFrom).asSequence() }
            .toSet()
        return leafHierarchyNames + relevantTestVariantNames
    }

    /**
     * Union two dependency maps, preferring the higher version on shortId conflicts.
     * This merges RuntimeClasspath and CompileClasspath results so that compileOnly
     * and api-of-consumed-libs entries (present only in CompileClasspath) are included.
     */
    private fun unionDependencyMaps(
        runtime: Map<String, ResolvedDependency>,
        compile: Map<String, ResolvedDependency>
    ): Map<String, ResolvedDependency> {
        if (compile.isEmpty()) return runtime
        if (runtime.isEmpty()) return compile
        val merged = runtime.toMutableMap()
        for ((shortId, compileDep) in compile) {
            val existing = merged[shortId]
            if (existing == null) {
                merged[shortId] = compileDep
            } else {
                merged[shortId] = mergeDependencyMetadataByMaxVersion(existing, compileDep)
            }
        }
        return merged
    }

    /**
     * Resolve a configuration to a map of shortId -> [ResolvedDependency].
     *
     * Walks through project nodes so a binary/root classpath can contribute direct external
     * dependencies declared by any project in that classpath. Only those direct external
     * dependencies are emitted; their transitive closures stay nested in
     * [ResolvedDependency.dependencies], matching [com.grab.grazel.tasks.internal.ResolveVariantDependenciesTask].
     */
    private fun resolveConfigToDependencyMap(
        config: Configuration,
        project: Project,
        excludeRulesByProjectPath: Map<String, ProjectExcludeRules> = emptyMap()
    ): Map<String, ResolvedDependency> {
        return try {
            val excludeRulesByShortId = config.extractExcludeRulesByShortId()
            val root = config.incoming.resolutionResult.root
            val depMap = mutableMapOf<String, ResolvedDependency>()
            ResolvedComponentsVisitor().visit(
                root = root,
                logger = logger::info,
                traverseProjectNodes = true
            ) { visitResult ->
                val moduleVersion = visitResult.component.moduleVersion ?: return@visit null
                if (!visitResult.directFromProject) return@visit null
                val shortId = "${moduleVersion.group}:${moduleVersion.name}"
                // Skip BOM/platform (pom-only) components — rules_jvm_external rejects them with
                // "Unsupported packaging type: pom". BOM components appear in the resolution graph
                // but have no actual jar/aar artifact. We detect them by checking the resolved
                // variant attributes for "org.gradle.category" == "platform". As a fallback, we
                // also skip components whose module name ends with "-bom" (a common BOM convention).
                // Note: filtering is done here in the visitor transform, before adding to depMap.
                if (moduleVersion.name.endsWith("-bom", ignoreCase = true) ||
                    moduleVersion.name.endsWith(".bom", ignoreCase = true)) {
                    return@visit null
                }
                val excludeRules = excludeRulesByProjectPath.excludeRulesFor(
                    rootProjectPath = project.path,
                    rootExcludeRulesByShortId = excludeRulesByShortId,
                    ownerProjectPath = visitResult.directProjectPath,
                    ownerProjectVariantDisplayName = visitResult.directProjectVariantDisplayName,
                    shortId = shortId
                )
                val dependency = ResolvedDependency(
                    id = visitResult.component.toString(),
                    shortId = shortId,
                    version = moduleVersion.version,
                    direct = true,
                    dependencies = visitResult.transitiveDeps
                        .filterNot { depResult ->
                            // Also exclude BOM entries from the transitive dependency set so they
                            // don't leak into the flattened classpath via allDependencies expansion.
                            val mv = depResult.dependency.moduleVersion
                            mv != null && (mv.name.endsWith("-bom", ignoreCase = true) ||
                                mv.name.endsWith(".bom", ignoreCase = true))
                        }
                        .mapTo(TreeSet()) { depResult ->
                            ResolvedDependency.createDependencyNotation(
                                depResult.dependency,
                                depResult.requiresJetifier,
                                depResult.unjetifiedSource
                            )
                        },
                    excludeRules = excludeRules,
                    repository = visitResult.repository,
                    requiresJetifier = visitResult.requiresJetifier
                )
                val existingDependency = depMap[shortId]
                val mergedDependency = if (existingDependency == null) {
                    dependency
                } else {
                    mergeDependencyMetadataByMaxVersion(existingDependency, dependency)
                }
                depMap[shortId] = mergedDependency
                mergedDependency
            }
            depMap
        } catch (e: Exception) {
            logger.warn(
                "Grazel: Failed to resolve config ${config.name} for ${project.name}: ${e.message}"
            )
            emptyMap()
        }
    }

    /**
     * Collect KSP processor dependencies across all [projectVariants] for [variantName].
     *
     * KSP processor class-name extraction needs per-project JAR download, so this stays per-project
     * (mirroring [com.grab.grazel.tasks.internal.ResolveVariantDependenciesTask]); KSP deps are
     * aggregated downstream by [ComputeWorkspaceDependencies].
     */
    private fun resolveKspDeps(
        projectVariants: List<Pair<Project, com.grab.grazel.gradle.variant.Variant<*>>>,
        variantName: String
    ): Set<ResolvedDependency> {
        val kspDeps = TreeSet<ResolvedDependency>()

        for ((project, variant) in projectVariants) {
            if (!project.hasKsp) continue

            val kspConfigs = try {
                variant.kspConfiguration.filter { it.isCanBeResolved }
            } catch (e: Exception) {
                continue
            }
            if (kspConfigs.isEmpty()) continue

            val directDepShortIds: Set<String> = kspConfigs
                .asSequence()
                .flatMap { it.allDependencies }
                .filterIsInstance<ExternalDependency>()
                .map { "${it.group}:${it.name}" }
                .toSet()

            if (directDepShortIds.isEmpty()) continue

            val artifactResults: List<Pair<String, java.io.File>> = kspConfigs.flatMap { cfg ->
                cfg.incoming
                    .artifactView {
                        isLenient = true
                        componentFilter { id -> id is ModuleComponentIdentifier }
                    }
                    .artifacts
                    .mapNotNull { artifact ->
                        val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                            ?: return@mapNotNull null
                        val shortId = "${id.group}:${id.module}"
                        if (shortId in directDepShortIds) shortId to artifact.file else null
                    }
            }

            val artifactMapping: Map<String, String> = artifactResults
                .associate { (shortId, file) -> shortId to file.name }
            val processorClassMap: Map<String, String> = KspProcessorClassExtractor
                .extractProcessorClasses(
                    artifactResults.map { it.second }.toSet(),
                    artifactMapping
                )

            kspConfigs.forEach { cfg ->
                try {
                    val root = cfg.incoming.resolutionResult.root
                    ResolvedComponentsVisitor().visit(root) { visitResult ->
                        val component = visitResult.component
                        val moduleVersion = component.moduleVersion ?: return@visit null
                        val shortId = "${moduleVersion.group}:${moduleVersion.name}"
                        if (shortId in directDepShortIds) {
                            ResolvedDependency(
                                id = component.toString(),
                                shortId = shortId,
                                direct = true,
                                version = moduleVersion.version,
                                dependencies = emptySet(),
                                excludeRules = emptySet(),
                                repository = visitResult.repository,
                                requiresJetifier = visitResult.requiresJetifier,
                                processorClass = processorClassMap[shortId]
                            )
                        } else null
                    }.let(kspDeps::addAll)
                } catch (e: Exception) {
                    project.logger.warn(
                        "Grazel[AggregatedDependencyResolver]: KSP resolution failed for " +
                            "${project.path}/$variantName: ${e.message}"
                    )
                }
            }
        }

        return kspDeps
    }
}

internal fun mergeDependencyMetadataByMaxVersion(
    existing: ResolvedDependency,
    candidate: ResolvedDependency
): ResolvedDependency {
    val winner = if (existing.versionInfo > candidate.versionInfo) {
        existing
    } else {
        candidate
    }
    val other = if (winner === existing) candidate else existing
    return winner.copy(
        direct = winner.direct || other.direct,
        excludeRules = (winner.excludeRules + other.excludeRules)
            .toSortedSet(compareBy(ExcludeRule::toString)),
        requiresJetifier = winner.requiresJetifier || other.requiresJetifier,
        jetifierSource = winner.jetifierSource ?: other.jetifierSource,
        overrideTarget = winner.overrideTarget ?: other.overrideTarget,
        processorClass = winner.processorClass ?: other.processorClass,
    )
}

internal data class CoveredDependency(
    val bucketName: String,
    val dependency: ResolvedDependency
)

internal fun Map<String, ResolvedDependency>.withoutDependenciesCoveredBy(
    coveredDependencies: Iterable<CoveredDependency>
): Map<String, ResolvedDependency> {
    val coveredByShortId = coveredDependencies.groupBy { it.dependency.shortId }
    return mapNotNull { (shortId, dependency) ->
        val coveredDependency = coveredByShortId[shortId].orEmpty()
            .firstOrNull { covered ->
                covered.dependency.hasSameBucketOwnerAs(dependency) &&
                    (!dependency.direct || covered.dependency.direct)
            }
        when {
            coveredDependency == null -> shortId to dependency
            coveredDependency.dependency.hasSameEffectiveIdentityAs(dependency) -> null
            dependency.direct && coveredDependency.dependency.direct -> {
                shortId to dependency.copy(
                    overrideTarget = dependency.overrideTarget ?: coveredDependency.toOverrideTarget()
                )
            }
            else -> null
        }
    }
        .toMap()
}

internal fun Map<String, ResolvedDependency>.withoutDependenciesOwnedByNonDefaultHierarchy(
    hierarchyDefaultDeps: Map<String, ResolvedDependency>,
    nonDefaultHierarchyDependencies: Iterable<ResolvedDependency>
): Map<String, ResolvedDependency> {
    val nonDefaultByShortId = nonDefaultHierarchyDependencies.groupBy { it.shortId }
    return filterValues { dependency ->
        val hasSameNonDefaultOwner = nonDefaultByShortId[dependency.shortId]
            .orEmpty()
            .any { nonDefaultDependency ->
                nonDefaultDependency.hasSameBucketOwnerAs(dependency)
            }
        !hasSameNonDefaultOwner ||
            hierarchyDefaultDeps[dependency.shortId]?.hasSameBucketOwnerAs(dependency) == true
    }
}

internal fun Map<String, ResolvedDependency>.withoutDependenciesCoveredByDirectDependencies(
    coveredDependencies: Iterable<CoveredDependency>
): Map<String, ResolvedDependency> {
    return withoutDependenciesCoveredBy(
        coveredDependencies.filter { it.dependency.direct }
    )
}

internal fun intersectByBucketOwner(
    closures: Iterable<Map<String, ResolvedDependency>>
): Map<String, ResolvedDependency> {
    val closureList = closures.toList()
    if (closureList.isEmpty()) return emptyMap()
    val intersectionIds = closureList
        .map { it.keys }
        .reduce { a, b -> a intersect b }
    val firstClosure = closureList.first()
    return intersectionIds.mapNotNull { id ->
        val candidate = firstClosure[id]!!
        if (closureList.all { closure -> closure[id]?.hasSameBucketOwnerAs(candidate) == true }) {
            id to candidate
        } else {
            null
        }
    }.toMap()
}

private fun CoveredDependency.toOverrideTarget(): OverrideTarget {
    val (group, name) = dependency.shortId.split(":")
    return OverrideTarget(
        artifactShortId = dependency.shortId,
        label = MavenDependency(
            repo = bucketName.toMavenRepoName(),
            group = group,
            name = name
        )
    )
}
