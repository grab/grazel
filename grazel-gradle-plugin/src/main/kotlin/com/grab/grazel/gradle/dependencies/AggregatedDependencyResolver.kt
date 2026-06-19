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
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.Test
import com.grab.grazel.migrate.dependencies.toMavenRepoName
import org.gradle.api.Project
import java.util.TreeSet

/**
 * Resolves all external dependencies for the migratable project set from task-wired workspace
 * dependency roots, then buckets them via set-intersection logic.
 *
 * The root inputs are prepared by the workspace dependency input registrar from app and standalone
 * `com.android.test` binary roots, plus task-produced declared metadata and KSP metadata. Bucket
 * output uses set-intersection logic: default, per-build-type, per-flavor, per-leaf residual, test,
 * androidTest, lint, and global KSP.
 *
 * The downstream [ComputeWorkspaceDependencies] pipeline consumes the synthetic bucket results.
 *
 * @param rootProject The Gradle root project.
 * @param declaredDependencyMetadata Serialized declaration and variant topology metadata produced
 *   before this task action. This keeps variant enumeration out of the workspace computation task.
 */
internal class AggregatedDependencyResolver(
    private val rootProject: Project,
    private val declaredDependencyMetadata: DeclaredDependencyMetadata = DeclaredDependencyMetadata.EMPTY,
    private val precomputedKspDependencies: Set<ResolvedDependency> = emptySet(),
    private val workspaceDependencyRoots: List<AggregatedDependencyRoot> = emptyList()
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
        val projectMetadataByPath = declaredDependencyMetadata.projects
        val migratableProjectPaths = projectMetadataByPath.keys.sorted()

        if (migratableProjectPaths.isEmpty()) return emptyList()

        fun variantsFor(projectPath: String): List<DeclaredVariantDependencyMetadata> =
            projectMetadataByPath[projectPath]?.variants.orEmpty()

        // Per-leaf-variant closure maps: leaf variant name -> Map<shortId, ResolvedDependency>
        val leafClosures = mutableMapOf<String, Map<String, ResolvedDependency>>()
        val leafUnitTestClosures = mutableMapOf<String, Map<String, ResolvedDependency>>()
        val leafAndroidTestClosures = mutableMapOf<String, Map<String, ResolvedDependency>>()
        // Synthetic hierarchy buckets such as default/debug/free, resolved from their own Gradle
        // declaration buckets. These prevent filtered leaves from making debug/flavor deps look
        // like default deps.
        val hierarchyBucketClosures = mutableMapOf<String, Map<String, ResolvedDependency>>()
        val testHierarchyBucketClosures = mutableMapOf<String, Map<String, ResolvedDependency>>()

        // Lint deps across all migratable projects
        var lintDeps = emptyMap<String, ResolvedDependency>()

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

        fun MutableMap<String, Map<String, ResolvedDependency>>.addToLeafBucket(
            leafName: String,
            closure: Map<String, ResolvedDependency>
        ) {
            if (closure.isEmpty()) return
            this[leafName] = this[leafName]
                ?.let { existing -> unionDependencyMaps(existing, closure) }
                ?: closure
        }

        fun AggregatedDependencyRootMetadata.variantHierarchyNames(): Set<String> {
            return variantNames.ifEmpty {
                listOfNotNull(leafName, bucketName).toSet()
            }
        }

        fun AggregatedDependencyRootMetadata.targetBucketNames(): Set<String> {
            return targetBuckets.ifEmpty {
                listOfNotNull(bucketName).toSet()
            }
        }

        fun excludeRulesFor(
            metadata: AggregatedDependencyRootMetadata,
            variantType: VariantType
        ): Map<String, ProjectExcludeRules> {
            return declaredDependencyMetadata.collectExcludeRulesByProjectPath(
                variantTypes = setOf(variantType),
                variantNames = metadata.variantHierarchyNames()
            )
        }

        var sawBinaryRoot = false

        workspaceDependencyRoots.forEach { aggregatedRoot ->
            val metadata = aggregatedRoot.metadata
            val closure = when (metadata.kind) {
                AggregatedDependencyRootKind.LINT -> resolveRootToDependencyMap(aggregatedRoot)
                AggregatedDependencyRootKind.MAIN_HIERARCHY,
                AggregatedDependencyRootKind.MAIN_LEAF -> resolveRootToDependencyMap(
                    aggregatedRoot = aggregatedRoot,
                    excludeRulesByProjectPath = excludeRulesFor(metadata, AndroidBuild)
                )
                AggregatedDependencyRootKind.TEST_HIERARCHY -> resolveRootToDependencyMap(
                    aggregatedRoot = aggregatedRoot,
                    excludeRulesByProjectPath = excludeRulesFor(
                        metadata = metadata,
                        variantType = metadata.variantType ?: when (metadata.bucketName) {
                            ANDROID_TEST_VARIANT -> AndroidTest
                            else -> Test
                        }
                    )
                )
                AggregatedDependencyRootKind.UNIT_TEST -> {
                    val leafHierarchyNames = metadata.variantHierarchyNames()
                    resolveRootToDependencyMap(
                        aggregatedRoot = aggregatedRoot,
                        excludeRulesByProjectPath = declaredDependencyMetadata
                            .collectExcludeRulesByProjectPath(
                                variantTypes = setOf(Test),
                                variantNames = variantNamesForLeafTest(
                                    variants = variantsFor(metadata.projectPath),
                                    leafHierarchyNames = leafHierarchyNames,
                                    testType = Test
                                )
                            )
                    )
                }
                AggregatedDependencyRootKind.ANDROID_TEST -> {
                    val leafHierarchyNames = metadata.variantHierarchyNames()
                    resolveRootToDependencyMap(
                        aggregatedRoot = aggregatedRoot,
                        excludeRulesByProjectPath = declaredDependencyMetadata
                            .collectExcludeRulesByProjectPath(
                                variantTypes = setOf(AndroidTest),
                                variantNames = variantNamesForLeafTest(
                                    variants = variantsFor(metadata.projectPath),
                                    leafHierarchyNames = leafHierarchyNames,
                                    testType = AndroidTest
                                )
                            )
                    )
                }
            }

            when (metadata.kind) {
                AggregatedDependencyRootKind.MAIN_HIERARCHY -> {
                    sawBinaryRoot = true
                    metadata.targetBucketNames().forEach { bucketName ->
                        when (bucketName) {
                            TEST_VARIANT, ANDROID_TEST_VARIANT ->
                                addToTestHierarchyBucket(bucketName, closure)
                            else -> addToHierarchyBucket(bucketName, closure)
                        }
                    }
                }
                AggregatedDependencyRootKind.MAIN_LEAF -> {
                    sawBinaryRoot = true
                    val leafName = metadata.leafName ?: metadata.bucketName ?: return@forEach
                    val targetBuckets = metadata.targetBucketNames()
                    targetBuckets.forEach { bucketName ->
                        when (bucketName) {
                            DEFAULT_VARIANT -> addToHierarchyBucket(DEFAULT_VARIANT, closure)
                            TEST_VARIANT, ANDROID_TEST_VARIANT ->
                                leafAndroidTestClosures.addToLeafBucket(leafName, closure)
                            else -> {
                                leafClosures.addToLeafBucket(bucketName, closure)
                            }
                        }
                    }
                }
                AggregatedDependencyRootKind.TEST_HIERARCHY -> {
                    metadata.targetBucketNames().forEach { bucketName ->
                        addToTestHierarchyBucket(bucketName, closure)
                    }
                }
                AggregatedDependencyRootKind.UNIT_TEST -> {
                    val leafName = metadata.leafName ?: metadata.bucketName ?: return@forEach
                    leafUnitTestClosures.addToLeafBucket(leafName, closure)
                }
                AggregatedDependencyRootKind.ANDROID_TEST -> {
                    val leafName = metadata.leafName ?: metadata.bucketName ?: return@forEach
                    leafAndroidTestClosures.addToLeafBucket(leafName, closure)
                }
                AggregatedDependencyRootKind.LINT -> {
                    lintDeps = unionDependencyMaps(lintDeps, closure)
                }
            }
        }

        if (!sawBinaryRoot) {
            logger.warn("Grazel: No migratable binary modules found for aggregated dependency resolution")
            return emptyList()
        }

        // Non-app compileOnly declarations are cheap metadata, not binary-root classpath edges.
        // Add only those declared buckets here; implementation/api deps still need to be reachable
        // from an app or standalone com.android.test binary root.
        declaredDependencyMetadata
            .collectCompileOnlyDependenciesByBucket(
                projectPaths = projectMetadataByPath
                    .filterValues { projectMetadata ->
                        projectMetadata.projectType == DeclaredProjectType.OTHER
                    }
                    .keys
            )
            .forEach { (bucketName, dependencies) ->
                when (bucketName) {
                    TEST_VARIANT, ANDROID_TEST_VARIANT -> addToTestHierarchyBucket(bucketName, dependencies)
                    else -> addToHierarchyBucket(bucketName, dependencies)
                }
            }

        if (
            leafClosures.isEmpty() &&
            hierarchyBucketClosures.isEmpty() &&
            leafUnitTestClosures.isEmpty() &&
            leafAndroidTestClosures.isEmpty() &&
            testHierarchyBucketClosures.isEmpty()
        ) {
            logger.warn("Grazel: No leaf variant runtime classpaths resolved")
            return emptyList()
        }

        val mainBucketPlan = MainDependencyBucketPlanner().plan(
            variants = declaredDependencyMetadata.mainBucketVariants(),
            hierarchyBucketClosures = hierarchyBucketClosures,
            leafClosures = leafClosures
        )
        val defaultDeps = mainBucketPlan.defaultBucket
        val buildTypeBuckets = mainBucketPlan.buildTypeBuckets
        val flavorBuckets = mainBucketPlan.flavorBuckets
        val perLeafBuckets = mainBucketPlan.leafBuckets
        val mainCoveredDeps = mainBucketPlan.coveredDependencies()

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
            val kspDeps = if (bucketName == "default") precomputedKspDependencies else emptySet()
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

    private val DeclaredVariantDependencyMetadata.isBase: Boolean
        get() = name == DEFAULT_VARIANT

    private val DeclaredVariantDependencyMetadata.extendsOnlyFromDefaultVariants: Boolean
        get() = extendsFrom.isEmpty() || extendsFrom.all { parent ->
            parent == DEFAULT_VARIANT || parent == TEST_VARIANT || parent == ANDROID_TEST_VARIANT
        }

    private fun variantNamesForLeafTest(
        variants: Collection<DeclaredVariantDependencyMetadata>,
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

    private fun resolveRootToDependencyMap(
        aggregatedRoot: AggregatedDependencyRoot,
        excludeRulesByProjectPath: Map<String, ProjectExcludeRules> = emptyMap()
    ): Map<String, ResolvedDependency> {
        val metadata = aggregatedRoot.metadata
        return try {
            val depMap = mutableMapOf<String, ResolvedDependency>()
            val visitResults = mutableListOf<ResolvedComponentsVisitor.VisitResult>()
            ResolvedComponentsVisitor().visit(
                root = aggregatedRoot.root,
                traverseProjectNodes = metadata.traverseProjectNodes
            ) { visitResult ->
                val component = visitResult.component
                val moduleVersion = component.moduleVersion ?: return@visit null
                val shortId = "${moduleVersion.group}:${moduleVersion.name}"
                // Skip BOM/platform (pom-only) components — rules_jvm_external rejects them with
                // "Unsupported packaging type: pom". BOM components appear in the resolution graph,
                // but have no actual jar/aar artifact.
                if (component.isBomComponent()) {
                    return@visit null
                }
                if (metadata.traverseProjectNodes && !visitResult.directFromProject) {
                    return@visit null
                }
                visitResults.add(visitResult)
                shortId
            }
            visitResults
                .sortedWith(
                    compareBy<ResolvedComponentsVisitor.VisitResult> { visitResult ->
                        visitResult.component.toString()
                    }
                        .thenBy { visitResult -> visitResult.repository }
                        .thenBy { visitResult -> visitResult.directProjectPath }
                        .thenBy { visitResult -> visitResult.directProjectVariantDisplayName }
                        .thenBy { visitResult -> visitResult.directFromProject }
                        .thenBy { visitResult -> visitResult.requiresJetifier }
                )
                .forEach { visitResult ->
                    val component = visitResult.component
                    val moduleVersion = component.moduleVersion ?: return@forEach
                    val shortId = "${moduleVersion.group}:${moduleVersion.name}"
                    val excludeRules = if (metadata.traverseProjectNodes) {
                        excludeRulesByProjectPath.excludeRulesFor(
                            rootProjectPath = metadata.projectPath,
                            rootExcludeRulesByShortId = metadata.rootExcludeRulesByShortId,
                            ownerProjectPath = visitResult.directProjectPath,
                            ownerProjectVariantDisplayName = visitResult.directProjectVariantDisplayName,
                            shortId = shortId
                        )
                    } else {
                        metadata.rootExcludeRulesByShortId.getOrDefault(shortId, emptySet())
                    }
                    val dependency = ResolvedDependency(
                        id = component.toString(),
                        shortId = shortId,
                        version = moduleVersion.version,
                        direct = if (metadata.traverseProjectNodes) {
                            true
                        } else {
                            shortId in metadata.directDependencyShortIds
                        },
                        dependencies = visitResult.transitiveDeps
                            .filterNot { dependencyResult ->
                                dependencyResult.dependency.isBomComponent()
                            }
                            .mapTo(TreeSet()) { dependencyResult ->
                                ResolvedDependency.createDependencyNotation(
                                    dependencyResult.dependency,
                                    dependencyResult.requiresJetifier,
                                    dependencyResult.unjetifiedSource
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
                }
            depMap
        } catch (e: Exception) {
            logger.warn(
                "Grazel: Failed to resolve aggregated root ${metadata.configurationName} " +
                    "for ${metadata.projectPath}: ${e.message}"
            )
            emptyMap()
        }
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
