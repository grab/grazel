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
import com.grab.grazel.gradle.dependencies.model.ExcludeRule
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.COMPILE
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.KSP
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.versionInfo
import com.grab.grazel.gradle.variant.ANDROID_TEST_VARIANT
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.TEST_VARIANT
import com.grab.grazel.gradle.variant.Variant
import com.grab.grazel.gradle.hasKsp
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.Test
import com.grab.grazel.gradle.variant.VariantBuilder
import com.grab.grazel.gradle.variant.extendsOnlyFromDefaultVariants
import com.grab.grazel.gradle.variant.isBase
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

        val excludeRulesByShortId = collectExcludeRules(migratableProjects)

        // Identify APP (binary) and standalone test (com.android.test) modules — they have transitive
        // runtime closures that cover all libs. com.android.test modules are standalone test apps
        // and may declare dependencies not present in the main app module.
        val appProjects = migratableProjects.filter {
            it.plugins.hasPlugin("com.android.application") ||
                it.plugins.hasPlugin("com.android.test")
        }

        if (appProjects.isEmpty()) {
            logger.warn("Grazel: No migratable app modules found for aggregated dependency resolution")
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
            val variants = try {
                variantBuilder.build(app)
            } catch (e: Exception) {
                logger.warn("Grazel: Failed to enumerate variants for ${app.path}: ${e.message}")
                continue
            }

            variants
                .filter { v -> v.variantType == AndroidBuild && (v.isBase || v.extendsOnlyFromDefaultVariants) }
                .forEach { v ->
                    val closure = resolveVariantToDependencyMap(v, app, excludeRulesByShortId)
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
                    val closure = resolveVariantToDependencyMap(v, app, excludeRulesByShortId)
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
                val runtimeClosure = resolveConfigToDependencyMap(
                    runtimeConfig,
                    app,
                    excludeRulesByShortId
                )
                if (runtimeClosure.isEmpty()) continue

                // Also resolve CompileClasspath to capture compileOnly + api-of-consumed-libs deps
                // that are omitted from RuntimeClasspath. Union both; prefer higher version on conflict.
                val compileConfig = app.configurations.findByName("${leafName}CompileClasspath")
                val compileClosure = if (compileConfig != null) {
                    resolveConfigToDependencyMap(compileConfig, app, excludeRulesByShortId)
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
                    val rtMap = unitTestRuntime
                        ?.let { resolveConfigToDependencyMap(it, app, excludeRulesByShortId) }
                        ?: emptyMap()
                    val cpMap = unitTestCompile
                        ?.let { resolveConfigToDependencyMap(it, app, excludeRulesByShortId) }
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
                    val rtMap = androidTestRuntime
                        ?.let { resolveConfigToDependencyMap(it, app, excludeRulesByShortId) }
                        ?: emptyMap()
                    val cpMap = androidTestCompile
                        ?.let { resolveConfigToDependencyMap(it, app, excludeRulesByShortId) }
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

        // For non-app/non-test library modules, also resolve their compileClasspath to capture
        // compileOnly deps (e.g. lint API deps in com.android.lint modules, annotation processors,
        // etc.) that are NOT transitively reachable from any app module's classpath.
        // These are unioned into EVERY leaf closure so they land in the intersection (default bucket)
        // and thus map to @maven — the same place the non-aggregated path would have put them.
        val nonAppProjects = migratableProjects.filter { project ->
            !project.plugins.hasPlugin("com.android.application") &&
                !project.plugins.hasPlugin("com.android.test")
        }
        for (project in nonAppProjects) {
            // Try common compileClasspath config names (Kotlin JVM, Android library default variant)
            val compileOnlyConfigs = listOf("compileClasspath", "debugCompileClasspath", "releaseCompileClasspath")
            for (configName in compileOnlyConfigs) {
                val config = project.configurations.findByName(configName) ?: continue
                val extraDeps = resolveConfigToDependencyMap(config, project, excludeRulesByShortId)
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
                    resolveConfigToDependencyMap(lintConfig, project, excludeRulesByShortId)
                )
            }
        }

        // Collect KSP project-variants from all migratable projects (synthetic variants only)
        for (project in migratableProjects) {
            val variants = try {
                variantBuilder.build(project)
            } catch (e: Exception) {
                continue
            }
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

        // default = intersection of all leaf closures
        val leafIntersectionIds: Set<String> = allLeafNames
            .map { leafClosures[it]!!.keys }
            .reduce { a, b -> a intersect b }
        val hierarchyDefaultIds = hierarchyBucketClosures[DEFAULT_VARIANT]?.keys ?: emptySet()
        val nonDefaultHierarchyIds = hierarchyBucketClosures
            .filterKeys { it != DEFAULT_VARIANT }
            .values
            .flatMapTo(mutableSetOf()) { it.keys }
        val defaultIds = (leafIntersectionIds + hierarchyDefaultIds) -
            (nonDefaultHierarchyIds - hierarchyDefaultIds)

        // Build type buckets: intersection of all leaves for a given build type, minus default
        val buildTypeBuckets = mutableMapOf<String, Map<String, ResolvedDependency>>()
        val buildTypes = leafBuildTypes.values.toSet()
        for (bt in buildTypes) {
            val btLeaves = allLeafNames.filter { leafBuildTypes[it] == bt }
            if (btLeaves.isEmpty()) continue
            val hierarchyDeps = hierarchyBucketClosures[bt]
            val btIds = if (hierarchyDeps != null) {
                hierarchyDeps.keys - defaultIds
            } else {
                btLeaves.map { leafClosures[it]!!.keys }.reduce { a, b -> a intersect b } - defaultIds
            }
            if (btIds.isNotEmpty()) {
                val firstLeaf = btLeaves.first()
                buildTypeBuckets[bt] = btIds.associateWith { id ->
                    hierarchyDeps?.get(id) ?: leafClosures[firstLeaf]!![id]!!
                }
            }
        }

        // Flavor buckets: intersection of all leaves sharing a flavor, minus default
        val flavorBuckets = mutableMapOf<String, Map<String, ResolvedDependency>>()
        val allFlavors = leafFlavors.values.flatten().toSet()
        val buildTypeBucketIds = buildTypeBuckets.values.flatMapTo(mutableSetOf()) { it.keys }
        for (flavor in allFlavors) {
            val flavorLeaves = allLeafNames.filter { leafFlavors[it]?.contains(flavor) == true }
            if (flavorLeaves.isEmpty()) continue
            val hierarchyDeps = hierarchyBucketClosures[flavor]
            val flavorIds = if (hierarchyDeps != null) {
                hierarchyDeps.keys - defaultIds - buildTypeBucketIds
            } else {
                flavorLeaves.map { leafClosures[it]!!.keys }.reduce { a, b -> a intersect b } -
                    defaultIds - buildTypeBucketIds
            }
            if (flavorIds.isNotEmpty()) {
                val firstLeaf = flavorLeaves.first()
                flavorBuckets[flavor] = flavorIds.associateWith { id ->
                    hierarchyDeps?.get(id) ?: leafClosures[firstLeaf]!![id]!!
                }
            }
        }

        // Per-leaf residual buckets: deps not covered by default, build-type, or flavor buckets
        val perLeafBuckets = mutableMapOf<String, Map<String, ResolvedDependency>>()
        for (leafName in allLeafNames) {
            val bt = leafBuildTypes[leafName] ?: ""
            val flavors = leafFlavors[leafName] ?: emptyList()
            val coveredIds: Set<String> = defaultIds +
                (buildTypeBuckets[bt]?.keys ?: emptySet<String>()) +
                flavors.flatMap { flavorBuckets[it]?.keys ?: emptySet() }
            val leafIds = leafClosures[leafName]!!.keys - coveredIds
            if (leafIds.isNotEmpty()) {
                perLeafBuckets[leafName] = leafIds.associateWith { id -> leafClosures[leafName]!![id]!! }
            }
        }

        val defaultDeps: Map<String, ResolvedDependency> = defaultIds.associateWith { id ->
            hierarchyBucketClosures[DEFAULT_VARIANT]?.get(id) ?: leafClosures[allLeafNames.first()]!![id]!!
        }
        val mainCoveredDeps = buildMap {
            putAll(defaultDeps)
            buildTypeBuckets.values.forEach(::putAll)
            flavorBuckets.values.forEach(::putAll)
        }

        fun isDirectlyCoveredByMain(id: String): Boolean = mainCoveredDeps[id]?.direct == true

        fun intersectClosures(
            closures: Map<String, Map<String, ResolvedDependency>>
        ): Map<String, ResolvedDependency> {
            if (closures.isEmpty()) return emptyMap()
            val intersectionIds = closures.values
                .map { it.keys }
                .reduce { a, b -> a intersect b }
            val firstClosure = closures.values.first()
            return intersectionIds.associateWith { id -> firstClosure[id]!! }
        }

        // Test bucket: explicit test deps, or intersection of all leaf unit-test closures,
        // minus deps already directly covered by main buckets. This matches the legacy per-variant
        // resolver: non-base variants subtract direct base deps, but can still own first-level deps
        // that only appear transitively in the base/default flattened repo.
        val testCandidates = unionDependencyMaps(
            testHierarchyBucketClosures[TEST_VARIANT].orEmpty(),
            intersectClosures(leafUnitTestClosures)
        )
        val testDeps = testCandidates
            .filterKeys { id -> !isDirectlyCoveredByMain(id) }

        // AndroidTest bucket: explicit androidTest deps, or intersection of all leaf android-test
        // closures, minus deps already directly covered by main buckets.
        val androidTestCandidates = unionDependencyMaps(
            testHierarchyBucketClosures[ANDROID_TEST_VARIANT].orEmpty(),
            intersectClosures(leafAndroidTestClosures)
        )
        val androidTestDeps = androidTestCandidates
            .filterKeys { id -> !isDirectlyCoveredByMain(id) }

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
        excludeRulesByShortId: Map<String, Set<ExcludeRule>>
    ): Map<String, ResolvedDependency> {
        val runtimeClosure = resolveConfigurationsToDependencyMap(
            variant.runtimeConfiguration,
            project,
            excludeRulesByShortId
        )
        val compileClosure = resolveConfigurationsToDependencyMap(
            variant.compileConfiguration,
            project,
            excludeRulesByShortId
        )
        return unionDependencyMaps(runtimeClosure, compileClosure)
    }

    private fun resolveConfigurationsToDependencyMap(
        configurations: Set<Configuration>,
        project: Project,
        excludeRulesByShortId: Map<String, Set<ExcludeRule>>
    ): Map<String, ResolvedDependency> {
        return configurations.fold(emptyMap()) { result, configuration ->
            unionDependencyMaps(
                result,
                resolveConfigToDependencyMap(configuration, project, excludeRulesByShortId)
            )
        }
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
            } else if (compileDep.versionInfo > existing.versionInfo) {
                merged[shortId] = compileDep
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
        excludeRulesByShortId: Map<String, Set<ExcludeRule>>
    ): Map<String, ResolvedDependency> {
        return try {
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
                ResolvedDependency(
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
                    excludeRules = excludeRulesByShortId[shortId].orEmpty(),
                    repository = visitResult.repository,
                    requiresJetifier = visitResult.requiresJetifier
                ).also { dep ->
                    // Last-write wins — the visitor emits each component once but we guard anyway
                    depMap[shortId] = dep
                }
            }
            depMap
        } catch (e: Exception) {
            logger.warn(
                "Grazel: Failed to resolve config ${config.name} for ${project.name}: ${e.message}"
            )
            emptyMap()
        }
    }

    private fun collectExcludeRules(projects: Collection<Project>): Map<String, Set<ExcludeRule>> {
        return projects
            .asSequence()
            .flatMap { project -> project.configurations.asSequence() }
            .flatMap { configuration -> configuration.dependencies.asSequence() }
            .filterIsInstance<ExternalDependency>()
            .groupBy { dependency -> "${dependency.group}:${dependency.name}" }
            .mapValues { (_, dependencies) ->
                dependencies
                    .flatMap { dependency -> dependency.extractExcludeRules() }
                    .toSortedSet(compareBy(ExcludeRule::toString))
            }
            .filterValues { it.isNotEmpty() }
    }

    private fun ExternalDependency.extractExcludeRules(): Set<ExcludeRule> {
        return excludeRules
            .map {
                @Suppress("USELESS_ELVIS") // Gradle metadata can expose null group/module values.
                ExcludeRule(
                    group = it.group ?: "",
                    artifact = it.module ?: ""
                )
            }
            .filterNot { it.group.isBlank() || it.artifact.isBlank() }
            .toSet()
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
