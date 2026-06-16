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
import com.grab.grazel.gradle.dependencies.model.VersionInfo
import com.grab.grazel.gradle.hasKsp
import com.grab.grazel.gradle.variant.Variant
import com.grab.grazel.gradle.variant.VariantBuilder
import com.grab.grazel.gradle.variant.extendsOnlyFromDefaultVariants
import com.grab.grazel.gradle.variant.isBase
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.util.TreeMap
import java.util.TreeSet

/**
 * Resolves all external dependencies for the migratable project set by iterating each
 * project's own grazel-synthesized classpath configurations per variant and aggregating the
 * results in memory via max-version selection.
 *
 * When [aggregatedDependencyResolution][com.grab.grazel.extension.ExperimentsExtension.aggregatedDependencyResolution]
 * is enabled, this class produces one [ResolveDependenciesResult] per synthetic variant name
 * (e.g. "default", "debug", "androidTest", "lint", "demoFree", etc.) which is then fed into
 * [ComputeWorkspaceDependencies.computeFromResults]. The downstream pipeline (grouping, version
 * arbitration, transitive flattening, override-target computation, KSP aggregation) is
 * unchanged — only the resolution source changes.
 *
 * **Approach — per-project synthetic config aggregation:**
 * For each synthetic variant name, for each migratable project that exposes this variant,
 * we resolve the project's own grazel-synthesized compile classpath configuration
 * (e.g., `grazelDefaultCompileClasspath`, `grazelDebugCompileClasspath`). These configs are
 * already set up by [com.grab.grazel.gradle.variant.ConfigurationParsingVariant.classpathConfiguration]
 * and represent the per-module deps for that variant. We visit each config with
 * [ResolvedComponentsVisitor] (in default mode, without traverseProjectNodes), collect
 * direct+transitive external deps, then union them across all projects using max-version
 * selection — mirroring what [com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependencies]
 * does in the OFF path but performing it at the variant-closure level.
 *
 * This avoids the need to create a root-level aggregating configuration with project
 * dependencies (which requires precise AGP variant-disambiguation attributes and doesn't
 * work cleanly for synthetic/base variants that are not real AGP leaf variants).
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

    /**
     * Resolve all variants across all migratable projects by aggregating each project's
     * own per-variant grazel-synthesized classpath configurations.
     *
     * @return One [ResolveDependenciesResult] per unique variant name, covering COMPILE and KSP scopes.
     */
    fun resolve(): List<ResolveDependenciesResult> {
        val migratableProjects = rootProject.subprojects
            .filter { migrationChecker.canMigrate(it) }

        if (migratableProjects.isEmpty()) return emptyList()

        // Build a map from variant-name → list of (project, variant) pairs.
        // Only include "synthetic" variants (base / extends-only-from-defaults) because these
        // are the bucket-defining variants that the OFF path also resolves independently.
        // Leaf variants (e.g., demoFreeDebug) inherit their deps from these base variants and
        // are excluded here — their deps are already captured in the base variant buckets.
        // This mirrors [com.grab.grazel.tasks.internal.ResolveVariantDependenciesTask] which
        // always resolves variants where `isBase || extendsOnlyFromDefaultVariants`.
        val variantToProjectVariants: Map<String, List<Pair<Project, Variant<*>>>> =
            migratableProjects
                .flatMap { project ->
                    try {
                        variantBuilder.build(project)
                            .filter { variant -> variant.isBase || variant.extendsOnlyFromDefaultVariants }
                            .map { variant -> variant.name to (project to variant) }
                    } catch (e: Exception) {
                        rootProject.logger.warn(
                            "Grazel[AggregatedDependencyResolver]: failed to enumerate variants " +
                                "for ${project.path}: ${e.message}"
                        )
                        emptyList()
                    }
                }
                .groupBy(
                    keySelector = { (name, _) -> name },
                    valueTransform = { (_, projectVariant) -> projectVariant }
                )

        // Deterministic ordering: sort by variant name length then lexicographically,
        // matching the VariantBuilder sort order.
        return variantToProjectVariants.entries
            .sortedWith(compareBy({ it.key.length }, { it.key }))
            .map { (variantName, projectVariants) ->
                resolveVariant(variantName, projectVariants)
            }
    }

    /**
     * For [variantName], resolve each project's own compile classpath configuration for that
     * variant and union the results via max-version selection.
     *
     * Each project's variant config is a grazel-synthesized resolvable config
     * (e.g., `grazelDefaultCompileClasspath`, `grazelDebugCompileClasspath`) that already
     * encodes the module's per-variant deps. We visit each config using [ResolvedComponentsVisitor]
     * in default mode (no project-node traversal required — these configs already resolve only
     * external deps), collect the results, and merge them with max-version deduplication.
     *
     * Direct/transitive distinction:
     * - A dep is **direct** if it is a direct dependency of the config (i.e., appears in
     *   `configuration.dependencies`). We identify direct deps by comparing shortIds against the
     *   config's `allDependencies` set (external deps only), mirroring the per-module logic in
     *   [com.grab.grazel.tasks.internal.ResolveVariantDependenciesTask].
     * - All deps still carry their full `transitiveDeps` via [ResolvedDependency.dependencies],
     *   used by [ComputeWorkspaceDependencies.computeInternal] to re-expand the classpath.
     *
     * Exclude rules are collected from each config's `ExternalDependency.excludeRules` and
     * attached to the corresponding [ResolvedDependency], matching the per-module behavior.
     */
    private fun resolveVariant(
        variantName: String,
        projectVariants: List<Pair<Project, Variant<*>>>
    ): ResolveDependenciesResult {
        // Aggregate compile deps across all projects for this variant name.
        // Use a TreeMap keyed by shortId for max-version deduplication.
        val compileDepsMap: TreeMap<String, ResolvedDependency> = TreeMap()

        for ((project, variant) in projectVariants) {
            val perProjectDeps = resolveCompileDepsForVariant(project, variant)
            for (dep in perProjectDeps) {
                val existing = compileDepsMap[dep.shortId]
                if (existing == null) {
                    compileDepsMap[dep.shortId] = dep
                } else {
                    // Max-version selection, mirroring ComputeWorkspaceDependencies.maxVersionReducer
                    compileDepsMap[dep.shortId] = if (
                        VersionInfo(existing.version) >= VersionInfo(dep.version)
                    ) existing.mergeWith(dep) else dep.mergeWith(existing)
                }
            }
        }

        val kspDeps = resolveKspDeps(projectVariants, variantName)

        return ResolveDependenciesResult(
            variantName = variantName,
            dependencies = mapOf(
                COMPILE.name to compileDepsMap.values.toSortedSet(),
                KSP.name to kspDeps
            )
        )
    }

    /**
     * Resolve [variant]'s compile classpath for [project] by visiting the grazel-synthesized
     * compile configurations. Returns the set of [ResolvedDependency] for this (project, variant)
     * pair, with direct/transitive distinction encoded in [ResolvedDependency.direct].
     *
     * We look up the grazel-synthesized config **by name** rather than calling
     * `variant.compileConfiguration` (which would invoke `classpathConfiguration()` and attempt
     * to call `setExtendsFrom` again — forbidden once a config has been locked for mutation after
     * prior resolution in [com.grab.grazel.tasks.internal.ResolveVariantDependenciesTask]).
     *
     * Config name pattern: `grazel${variantName.capitalize()}CompileClasspath`
     * (matches [com.grab.grazel.gradle.variant.ConfigurationParsingVariant.classpathConfiguration]).
     * For JVM variants that use standard `compileClasspath` / `testCompileClasspath`, we fall
     * back to those names.
     */
    private fun resolveCompileDepsForVariant(
        project: Project,
        variant: Variant<*>
    ): Set<ResolvedDependency> {
        // Look up existing grazel-synthesized config by the known naming convention.
        // This avoids re-invoking classpathConfiguration() which would mutate the config.
        val configs: List<Configuration> = getCompileConfigurations(project, variant)
        if (configs.isEmpty()) return emptySet()

        return resolveFromConfigs(project, variant.name, configs)
    }

    /**
     * Find the resolvable compile configurations for [variant] on [project].
     *
     * Strategy:
     * 1. Look up the grazel-synthesized config by name (`grazel${name.capitalize()}CompileClasspath`).
     * 2. For JVM projects' default/test variants, fall back to the standard Gradle config names.
     * 3. If none found by name, try creating via `variant.compileConfiguration` — this may fail
     *    if the config was already locked for mutation; if so, return empty.
     */
    private fun getCompileConfigurations(
        project: Project,
        variant: Variant<*>
    ): List<Configuration> {
        val grazelConfigName = "grazel${variant.name.capitalize()}CompileClasspath"
        val byName = project.configurations.findByName(grazelConfigName)
            ?: when (variant.name) {
                // JVM variants use standard Gradle names, not grazel-synthesized ones
                "default" -> project.configurations.findByName("compileClasspath")
                "test" -> project.configurations.findByName("testCompileClasspath")
                else -> null
            }

        if (byName != null && byName.isCanBeResolved) return listOf(byName)

        // Fallback: create via variant API (may throw if config is locked for mutation)
        return try {
            variant.compileConfiguration.filter { it.isCanBeResolved }
        } catch (e: Exception) {
            rootProject.logger.info(
                "Grazel[AggregatedDependencyResolver]: could not get compileConfiguration " +
                    "for ${project.path}/${variant.name}: ${e.message}"
            )
            emptyList()
        }
    }

    /**
     * Resolve all external deps reachable through [configs] and return them as [ResolvedDependency].
     *
     * Direct deps are identified by comparing against the configs' `allDependencies` (external only).
     * Exclude rules are extracted from direct [ExternalDependency] declarations.
     */
    private fun resolveFromConfigs(
        project: Project,
        variantName: String,
        configs: List<Configuration>
    ): Set<ResolvedDependency> {
        // Collect direct dependency shortIds from the configs' declared dependencies.
        val directDepShortIds: Set<String> = configs
            .asSequence()
            .flatMap { it.allDependencies }
            .filterIsInstance<ExternalDependency>()
            .map { "${it.group}:${it.name}" }
            .toSet()

        // Collect exclude rules per shortId from direct external deps.
        val excludeRulesMap: Map<String, Set<ExcludeRule>> = configs
            .asSequence()
            .flatMap { it.allDependencies }
            .filterIsInstance<ExternalDependency>()
            .groupBy { "${it.group}:${it.name}" }
            .mapValues { (_, deps) ->
                deps.flatMap { dep ->
                    dep.excludeRules.mapNotNull { rule ->
                        @Suppress("USELESS_ELVIS") // Gradle lying, module and group can be null
                        val group = rule.group ?: ""
                        @Suppress("USELESS_ELVIS")
                        val module = rule.module ?: ""
                        if (group.isBlank() || module.isBlank()) null
                        else ExcludeRule(group, module)
                    }
                }.toSet()
            }.filterValues { it.isNotEmpty() }

        val result = TreeSet<ResolvedDependency>()

        for (config in configs) {
            try {
                val root = config.incoming.resolutionResult.root
                val visited = ResolvedComponentsVisitor().visit(
                    root = root,
                    logger = project.logger::info,
                    traverseProjectNodes = false
                ) { visitResult ->
                    val component = visitResult.component
                    val moduleVersion = component.moduleVersion ?: return@visit null
                    val shortId = "${moduleVersion.group}:${moduleVersion.name}"
                    val isDirect = shortId in directDepShortIds

                    // Only emit DIRECT deps — transitives are encoded in ResolvedDependency.dependencies.
                    // This mirrors the OFF path (ResolveVariantDependenciesTask with removeTransitives=true):
                    // the per-variant JSON files only contain direct deps; computeInternal's flattenClasspath
                    // step re-expands them from the ResolvedDependency.dependencies field.
                    // Including transitives here would make reducedClasspath["variant"] = empty for all
                    // non-default variants (since all transitives appear in the huge default classpath),
                    // collapsing the entire dependency graph into just the default bucket.
                    if (!isDirect) return@visit null

                    ResolvedDependency(
                        id = component.toString(),
                        shortId = shortId,
                        direct = true,
                        version = moduleVersion.version,
                        dependencies = visitResult.transitiveDeps.mapTo(TreeSet()) { depResult ->
                            ResolvedDependency.createDependencyNotation(
                                depResult.dependency,
                                depResult.requiresJetifier,
                                depResult.unjetifiedSource
                            )
                        },
                        repository = visitResult.repository,
                        excludeRules = excludeRulesMap.getOrDefault(shortId, emptySet()),
                        requiresJetifier = visitResult.requiresJetifier,
                    )
                }
                result.addAll(visited)
            } catch (e: Exception) {
                project.logger.warn(
                    "Grazel[AggregatedDependencyResolver]: compile resolution failed for " +
                        "${project.path}/$variantName: ${e.message}"
                )
            }
        }

        return result
    }

    /**
     * Collect KSP processor dependencies across all [projectVariants] for [variantName].
     *
     * KSP processor class name extraction requires downloading per-project JARs and reading
     * their service files. We collect KSP deps from each project's existing KSP classpath
     * configuration for this variant, mirroring the per-project logic in
     * [com.grab.grazel.tasks.internal.ResolveVariantDependenciesTask].
     */
    private fun resolveKspDeps(
        projectVariants: List<Pair<Project, Variant<*>>>,
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

            // Collect direct KSP dependency shortIds without triggering resolution
            val directDepShortIds: Set<String> = kspConfigs
                .asSequence()
                .flatMap { it.allDependencies }
                .filterIsInstance<ExternalDependency>()
                .map { "${it.group}:${it.name}" }
                .toSet()

            if (directDepShortIds.isEmpty()) continue

            // Download only direct KSP processor JARs (for class name extraction)
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

            // Resolve KSP graph and collect direct processor entries
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

/**
 * Merge [other] into this dependency, taking the higher-version entry's core fields
 * while combining direct, dependencies, excludeRules, jetifierSource, overrideTarget, processorClass.
 */
private fun ResolvedDependency.mergeWith(other: ResolvedDependency): ResolvedDependency = copy(
    direct = direct || other.direct,
    dependencies = (dependencies + other.dependencies).toSortedSet(),
    jetifierSource = jetifierSource ?: other.jetifierSource,
    overrideTarget = overrideTarget ?: other.overrideTarget,
    excludeRules = (excludeRules + other.excludeRules).toSortedSet(compareBy(ExcludeRule::toString)),
    processorClass = processorClass ?: other.processorClass,
)
