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
import com.grab.grazel.gradle.hasKsp
import com.grab.grazel.gradle.variant.Variant
import com.grab.grazel.gradle.variant.VariantBuilder
import com.grab.grazel.gradle.variant.extendsOnlyFromDefaultVariants
import com.grab.grazel.gradle.variant.isBase
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.attributes.Attribute
import java.util.TreeSet

/**
 * Resolves all external dependencies for the migratable project set by performing **one aggregated
 * root resolution per synthetic variant** (O(V)) instead of the per-(project × variant) fan-out the
 * OFF path uses.
 *
 * When [aggregatedDependencyResolution][com.grab.grazel.extension.ExperimentsExtension.aggregatedDependencyResolution]
 * is enabled, this class produces one [ResolveDependenciesResult] per synthetic variant name
 * (e.g. "default", "debug", "androidTest", "lint", flavor singles) which is then fed into
 * [ComputeWorkspaceDependencies.computeFromResults]. The downstream pipeline (grouping, version
 * arbitration, transitive flattening, override-target computation, KSP aggregation) is unchanged —
 * only the resolution source changes.
 *
 * **Approach — custom-consumable-config root aggregation:**
 * For each synthetic variant `V`, on every migratable project we create a *consumable* configuration
 * (`grazelExportCompile<V>`) that `extendsFrom` that project's grazel-synthesized
 * `grazel<V>CompileClasspath` (so it exposes the project's full declared closure for `V`, including
 * `implementation` deps) and is tagged with a unique custom attribute `com.grab.grazel.export`. A
 * single resolvable configuration on the root project (`grazelAggregatedCompile<V>`) carrying the
 * same attribute depends on all participating projects and is resolved **once** — Gradle performs the
 * cross-project conflict resolution natively in a single pass.
 *
 * The custom attribute uniquely identifies the consumable, so it sidesteps AGP's
 * `AmbiguousGraphVariantsException` entirely (no `BuildTypeAttr`/`ProductFlavorAttr` juggling) and
 * still correctly separates `default` from `debug` because each project's underlying
 * `grazel<V>CompileClasspath` extends the right declared scopes (e.g. `default` excludes
 * `debugImplementation`).
 *
 * A dependency is emitted as **direct** iff its `shortId` is declared by some project for this
 * variant (the union of the projects' declared compile deps), mirroring the per-module "direct"
 * notion the OFF path uses. Only direct deps are emitted; transitives are carried in
 * [ResolvedDependency.dependencies] and re-expanded by [ComputeWorkspaceDependencies]. (Emitting
 * transitives here would make every non-`default` bucket collapse into `default`.)
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

    private val exportAttribute = Attribute.of("com.grab.grazel.export", String::class.java)

    /**
     * Resolve all variants across all migratable projects via one aggregated root resolution per
     * synthetic variant.
     *
     * @return One [ResolveDependenciesResult] per unique synthetic variant name, covering COMPILE and
     *   KSP scopes.
     */
    fun resolve(): List<ResolveDependenciesResult> {
        val migratableProjects = rootProject.subprojects
            .filter { migrationChecker.canMigrate(it) }

        if (migratableProjects.isEmpty()) return emptyList()

        // Map variant-name → list of (project, variant). Only "synthetic" variants (base /
        // extends-only-from-defaults) — the bucket-defining variants the OFF path resolves. Leaf
        // variants (e.g. demoFreeDebug) inherit their deps from these and are excluded. Mirrors
        // [ResolveVariantDependenciesTask] which resolves `isBase || extendsOnlyFromDefaultVariants`.
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

        // Deterministic ordering: sort by variant name length then lexicographically, matching the
        // VariantBuilder sort order.
        return variantToProjectVariants.entries
            .sortedWith(compareBy({ it.key.length }, { it.key }))
            .map { (variantName, projectVariants) ->
                ResolveDependenciesResult(
                    variantName = variantName,
                    dependencies = mapOf(
                        COMPILE.name to resolveAggregatedCompileDeps(variantName, projectVariants),
                        KSP.name to resolveKspDeps(projectVariants, variantName)
                    )
                )
            }
    }

    /**
     * Resolve the compile deps for [variantName] across all [projectVariants] in ONE root
     * resolution. Builds a consumable config per project (exposing its declared closure for this
     * variant) plus a single root resolvable config that consumes them, then walks the resolved
     * graph descending through the project nodes.
     */
    private fun resolveAggregatedCompileDeps(
        variantName: String,
        projectVariants: List<Pair<Project, Variant<*>>>
    ): Set<ResolvedDependency> {
        val attrValue = "compile-$variantName"
        val consumableName = "grazelExportCompile${variantName.capitalize()}"

        // Union of declared external deps across all projects = the "direct" set, plus their exclude
        // rules. Collected from declared dependencies only (no resolution).
        val directDepShortIds = hashSetOf<String>()
        val excludeRulesMap = hashMapOf<String, MutableSet<ExcludeRule>>()
        val participatingProjects = mutableListOf<Project>()
        // Donor config whose standard attributes (Usage/Category/TargetJvmEnvironment/
        // KotlinPlatformType/AgpVersion) we copy onto the aggregating configs. Without these, Gradle
        // cannot select correct variants for TRANSITIVE external deps → shallow graph + wrong
        // versions. Prefer the richest attribute set (Android configs carry more attrs than plain JVM).
        var attributeDonor: Configuration? = null

        for ((project, variant) in projectVariants) {
            val sourceConfigs = getCompileConfigurations(project, variant)
            if (sourceConfigs.isEmpty()) continue

            sourceConfigs.asSequence()
                .flatMap { it.allDependencies }
                .filterIsInstance<ExternalDependency>()
                .forEach { dep ->
                    val shortId = "${dep.group}:${dep.name}"
                    directDepShortIds.add(shortId)
                    val rules = dep.excludeRules.mapNotNull { rule ->
                        @Suppress("USELESS_ELVIS") // Gradle lying, module and group can be null
                        val group = rule.group ?: ""
                        @Suppress("USELESS_ELVIS")
                        val module = rule.module ?: ""
                        if (group.isBlank() || module.isBlank()) null else ExcludeRule(group, module)
                    }
                    if (rules.isNotEmpty()) excludeRulesMap.getOrPut(shortId) { hashSetOf() }.addAll(rules)
                }

            val source = sourceConfigs.first()
            if (source.attributes.keySet().size > (attributeDonor?.attributes?.keySet()?.size ?: -1)) {
                attributeDonor = source
            }

            // Consumable exposing this project's full declared closure for the variant. Extend the
            // DECLARABLE parent scopes (implementation/api/compileOnly/...) that the resolvable
            // grazel<V>CompileClasspath itself extends — extending that resolvable config directly
            // does NOT propagate Android modules' deps as outgoing. Configs that hold direct deps
            // themselves (e.g. `lintChecks`, empty extendsFrom) are extended directly. Copy the
            // source's standard attributes, then add the unique export attr for selection.
            project.configurations.maybeCreate(consumableName).apply {
                isCanBeConsumed = true
                isCanBeResolved = false
                sourceConfigs.forEach { src ->
                    if (src.extendsFrom.isNotEmpty()) src.extendsFrom.forEach { extendsFrom(it) }
                    else extendsFrom(src)
                }
                copyAttributes(source, this)
                attributes.attribute(exportAttribute, attrValue)
            }
            participatingProjects += project
        }

        if (participatingProjects.isEmpty()) return emptySet()

        // Single root resolvable config consuming every participating project's consumable. Carries
        // the donor's standard attributes (so transitive external deps resolve to the right variants
        // and versions) plus the unique export attr (so each project's grazelExport consumable is
        // selected without AGP variant ambiguity).
        val rootConfig = rootProject.configurations.maybeCreate(
            "grazelAggregatedCompile${variantName.capitalize()}"
        ).apply {
            isCanBeResolved = true
            isCanBeConsumed = false
            attributeDonor?.let { copyAttributes(it, this) }
            attributes.attribute(exportAttribute, attrValue)
            participatingProjects.forEach { p ->
                // Target the consumable BY CONFIGURATION NAME, bypassing attribute matching: this
                // forces selection of grazelExportCompile<V> (instead of the project's standard
                // apiElements/runtimeElements, which only expose `api` deps) and avoids the
                // cross-ecosystem attribute mismatch that left Android projects UNRESOLVED.
                dependencies.add(
                    rootProject.dependencies.project(
                        mapOf("path" to p.path, "configuration" to consumableName)
                    )
                )
            }
        }

        val result = TreeSet<ResolvedDependency>()
        try {
            val root = rootConfig.incoming.resolutionResult.root
            // ---- TEMPORARY DIAGNOSTIC INSTRUMENTATION (remove after debugging) ----
            val dbg = rootProject.logger
            @Suppress("UNCHECKED_CAST")
            val rootAttrs = rootConfig.attributes.let { a ->
                a.keySet().joinToString { "${it.name}=${a.getAttribute(it as Attribute<Any>)}" }
            }
            dbg.lifecycle(
                "Grazel[AggDbg] variant=$variantName projects=${participatingProjects.size} " +
                    "directShortIds=${directDepShortIds.size} rootAttrs={$rootAttrs}"
            )
            root.dependencies.forEach { dep ->
                when (dep) {
                    is ResolvedDependencyResult -> {
                        val sel = dep.selected
                        val variants = sel.variants.joinToString { it.displayName }
                        dbg.lifecycle(
                            "Grazel[AggDbg]   ${dep.requested} -> $sel variant=[$variants] " +
                                "children=${sel.dependencies.size}"
                        )
                    }

                    is UnresolvedDependencyResult ->
                        dbg.lifecycle("Grazel[AggDbg]   UNRESOLVED ${dep.requested}: ${dep.failure.message}")

                    else -> {}
                }
            }
            // ---- end instrumentation ----
            ResolvedComponentsVisitor().visit(
                root = root,
                logger = rootProject.logger::info,
                traverseProjectNodes = true
            ) { visitResult ->
                val moduleVersion = visitResult.component.moduleVersion ?: return@visit null
                val shortId = "${moduleVersion.group}:${moduleVersion.name}"

                // DIRECT-ONLY emission: only deps declared by some project for this variant.
                // Transitives are carried in `dependencies` and re-expanded by computeInternal;
                // emitting them here would collapse every non-default bucket into default.
                if (shortId !in directDepShortIds) return@visit null

                ResolvedDependency(
                    id = visitResult.component.toString(),
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
                    excludeRules = excludeRulesMap[shortId] ?: emptySet(),
                    requiresJetifier = visitResult.requiresJetifier,
                )
            }.let(result::addAll)
        } catch (e: Exception) {
            rootProject.logger.warn(
                "Grazel[AggregatedDependencyResolver]: aggregated compile resolution failed for " +
                    "$variantName: ${e.message}"
            )
        }
        rootProject.logger.lifecycle("Grazel[AggDbg] variant=$variantName emitted=${result.size}")
        return result
    }

    /**
     * Copy every attribute from [from] onto [to] (preserving key types). Used to give the
     * aggregating configs the same standard attributes (Usage/Category/TargetJvmEnvironment/
     * KotlinPlatformType/AgpVersion) as the per-variant source config, so Gradle resolves transitive
     * external deps to the correct variants. Best-effort: silently skips on the rare
     * already-resolved case (mirrors [com.grab.grazel.gradle.variant.ConfigurationParsingVariant]).
     */
    private fun copyAttributes(from: Configuration, to: Configuration) {
        val src = from.attributes
        src.keySet().forEach { key ->
            @Suppress("UNCHECKED_CAST")
            val typed = key as Attribute<Any>
            val value = src.getAttribute(typed) ?: return@forEach
            try {
                to.attributes.attribute(typed, value)
            } catch (e: IllegalArgumentException) {
                // Configuration already resolved in this Gradle execution; ignore.
            }
        }
    }

    /**
     * Find the resolvable compile configurations for [variant] on [project].
     *
     * 1. Look up the grazel-synthesized config by name (`grazel${name.capitalize()}CompileClasspath`).
     * 2. For JVM projects' default/test variants, fall back to the standard Gradle config names.
     * 3. Otherwise try `variant.compileConfiguration` — may fail if locked for mutation; then empty.
     */
    private fun getCompileConfigurations(
        project: Project,
        variant: Variant<*>
    ): List<Configuration> {
        val grazelConfigName = "grazel${variant.name.capitalize()}CompileClasspath"
        val byName = project.configurations.findByName(grazelConfigName)
            ?: when (variant.name) {
                "default" -> project.configurations.findByName("compileClasspath")
                "test" -> project.configurations.findByName("testCompileClasspath")
                else -> null
            }

        if (byName != null) return listOf(byName)

        return try {
            variant.compileConfiguration.toList()
        } catch (e: Exception) {
            rootProject.logger.info(
                "Grazel[AggregatedDependencyResolver]: could not get compileConfiguration " +
                    "for ${project.path}/${variant.name}: ${e.message}"
            )
            emptyList()
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
