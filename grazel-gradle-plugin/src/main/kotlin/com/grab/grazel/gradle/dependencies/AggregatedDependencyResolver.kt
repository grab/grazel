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

import com.android.build.api.attributes.AgpVersionAttr
import com.android.build.gradle.api.BaseVariant
import com.android.builder.model.Version
import com.grab.grazel.gradle.MigrationChecker
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.COMPILE
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.KSP
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.hasKsp
import com.grab.grazel.gradle.variant.AndroidVariant
import com.grab.grazel.gradle.variant.VariantBuilder
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.Usage.JAVA_RUNTIME
import org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.attributes.java.TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import java.util.TreeSet

/**
 * Attribute name for AGP's BuildTypeAttr (qualified form used for disambiguation).
 * Using string-keyed [Attribute] avoids a compile dependency on AGP internal attribute classes.
 */
private const val BUILD_TYPE_ATTR_NAME = "com.android.build.api.attributes.BuildTypeAttr"

/**
 * Attribute name prefix for AGP's ProductFlavorAttr (one per flavor dimension).
 * Full form: "com.android.build.api.attributes.ProductFlavor:<dimensionName>"
 */
private const val PRODUCT_FLAVOR_ATTR_PREFIX = "com.android.build.api.attributes.ProductFlavor"

/**
 * Resolves all external dependencies for the migratable project set via a single aggregating
 * [Configuration] per variant on the root project, instead of the per-(project × variant)
 * resolution fan-out that the existing pipeline performs.
 *
 * When [aggregatedDependencyResolution][com.grab.grazel.extension.ExperimentsExtension.aggregatedDependencyResolution]
 * is enabled, this class produces one [ResolveDependenciesResult] per variant which is then
 * fed into [ComputeWorkspaceDependencies.compute]. The downstream pipeline (grouping, version
 * arbitration, transitive flattening, override-target computation, KSP aggregation) is
 * unchanged — only the resolution source changes.
 *
 * **Attribute injection:** to disambiguate multi-flavor projects, Gradle requires the aggregating
 * config to carry `BuildTypeAttr` + one `ProductFlavorAttr` per dimension. These are injected
 * from an [AndroidVariant.backingVariant] representative for true AGP leaf variants (confirmed
 * by spike in design notes: "Spike 2 — verify the known fix (attribute injection)").
 *
 * **Scope coverage:** the aggregating config uses `java-runtime` usage, matching what
 * `*CompileClasspath` resolves to in AGP. KSP is handled in a separate per-project pass.
 *
 * @param rootProject The Gradle root project on which aggregating configurations are created.
 * @param migrationChecker Identifies which sub-projects are migratable.
 * @param variantBuilder Enumerates the variant set per sub-project.
 */
internal class AggregatedDependencyResolver(
    private val rootProject: Project,
    private val migrationChecker: MigrationChecker,
    private val variantBuilder: VariantBuilder,
) {

    /**
     * Resolve all variants across all migratable projects via root-level aggregating configs.
     *
     * @return One [ResolveDependenciesResult] per unique variant name, covering COMPILE and KSP scopes.
     */
    fun resolve(): List<ResolveDependenciesResult> {
        val migratableProjects = rootProject.subprojects
            .filter { migrationChecker.canMigrate(it) }

        if (migratableProjects.isEmpty()) return emptyList()

        // Collect unique variant names across all migratable projects.
        // variantBuilder.build() is safe at task-action time (afterEvaluate has run).
        val allVariantNames = migratableProjects
            .flatMap { project ->
                try {
                    variantBuilder.build(project).map { it.name }
                } catch (e: Exception) {
                    rootProject.logger.warn(
                        "Grazel[AggregatedDependencyResolver]: failed to enumerate variants " +
                            "for ${project.path}: ${e.message}"
                    )
                    emptyList()
                }
            }
            .toSortedSet() // deterministic ordering across runs

        return allVariantNames.map { variantName ->
            resolveVariant(variantName, migratableProjects)
        }
    }

    /**
     * Create one aggregating configuration for [variantName] on the root project, add every
     * migratable sub-project that exposes this variant as a project dependency, resolve the
     * transitive graph, and return the corresponding [ResolveDependenciesResult].
     */
    private fun resolveVariant(
        variantName: String,
        migratableProjects: List<Project>
    ): ResolveDependenciesResult {
        // Find a representative AndroidVariant to extract AGP disambiguation attributes.
        // Only AndroidVariant (real AGP leaf variant) carries buildType + productFlavors.
        val androidVariantRepresentative: AndroidVariant? = migratableProjects
            .asSequence()
            .flatMap { project ->
                try {
                    variantBuilder.build(project).asSequence()
                } catch (e: Exception) {
                    emptySequence()
                }
            }
            .filterIsInstance<AndroidVariant>()
            .firstOrNull { it.name == variantName }

        // Use maybeCreate so that re-invocations within the same Gradle execution don't fail.
        // The config is uniquely named per variant so it won't conflict with other configs.
        val configName = "grazelAggregated_${variantName}_compile"
        val aggregatingConfig = rootProject.configurations.maybeCreate(configName).also { cfg ->
            cfg.isCanBeResolved = true
            cfg.isCanBeConsumed = false
            cfg.isVisible = false
            cfg.description = "Grazel aggregated compile classpath for variant '$variantName'"
        }

        // Apply base attributes (matching ConfigurationParsingVariant.applyAttributes)
        val objects = rootProject.objects
        try {
            aggregatingConfig.attributes {
                attribute(USAGE_ATTRIBUTE, objects.named(Usage::class.java, JAVA_RUNTIME))
                attribute(
                    TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                    objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.ANDROID)
                )
                attribute(KotlinPlatformType.attribute, KotlinPlatformType.androidJvm)
                attribute(
                    AgpVersionAttr.ATTRIBUTE,
                    objects.named(AgpVersionAttr::class.java, Version.ANDROID_GRADLE_PLUGIN_VERSION)
                )
            }
        } catch (e: IllegalArgumentException) {
            rootProject.logger.warn(
                "Grazel[AggregatedDependencyResolver]: could not set base attributes on " +
                    "'$configName': ${e.message}"
            )
        }

        // Inject AGP variant disambiguation attributes when a real leaf variant is found
        if (androidVariantRepresentative != null) {
            injectVariantAttributes(aggregatingConfig, androidVariantRepresentative.backingVariant)
        }

        // Add sub-projects that have this variant as project dependencies
        val projectsAddedCount = addProjectDependencies(aggregatingConfig, migratableProjects, variantName)

        if (projectsAddedCount == 0) {
            // No sub-project exposes this variant — produce empty result
            return ResolveDependenciesResult(
                variantName = variantName,
                dependencies = mapOf(COMPILE.name to emptySet(), KSP.name to emptySet())
            )
        }

        val compileDeps = resolveCompileDeps(aggregatingConfig, variantName)
        val kspDeps = resolveKspDeps(migratableProjects, variantName)

        return ResolveDependenciesResult(
            variantName = variantName,
            dependencies = mapOf(
                COMPILE.name to compileDeps,
                KSP.name to kspDeps
            )
        )
    }

    /**
     * Inject AGP variant-specific attributes onto [config] to allow Gradle to select the
     * correct variant of each sub-project dependency.
     *
     * - `BuildTypeAttr`: disambiguates build type (debug / release).
     * - `ProductFlavorAttr:<dimension>`: disambiguates each flavor dimension independently.
     *
     * Uses string-typed [Attribute] instances (not the actual `BuildTypeAttr` / `ProductFlavorAttr`
     * classes) to match what AGP registers at configuration time, as confirmed by the spike.
     * AGP registers both the qualified form and a short-name alias; the qualified form is
     * sufficient for disambiguation (caveat 3 of design notes).
     */
    private fun injectVariantAttributes(config: Configuration, backingVariant: BaseVariant) {
        try {
            // BuildTypeAttr — required for all AGP variants with a build type
            val buildTypeAttr = Attribute.of(BUILD_TYPE_ATTR_NAME, String::class.java)
            config.attributes.attribute(buildTypeAttr, backingVariant.buildType.name)

            // ProductFlavorAttr per dimension — required when the project has multiple flavor dims.
            // No-flavor projects work without this (AGP compat rules ignore absent attrs: spike T4).
            backingVariant.productFlavors.forEach { flavor ->
                val dimension = flavor.dimension ?: return@forEach
                if (dimension.isNotBlank()) {
                    val flavorAttr = Attribute.of(
                        "$PRODUCT_FLAVOR_ATTR_PREFIX:$dimension",
                        String::class.java
                    )
                    config.attributes.attribute(flavorAttr, flavor.name)
                }
            }
        } catch (e: IllegalArgumentException) {
            rootProject.logger.warn(
                "Grazel[AggregatedDependencyResolver]: could not inject variant attrs for " +
                    "'${backingVariant.name}': ${e.message}"
            )
        }
    }

    /**
     * Add sub-projects that expose [variantName] as project dependencies of [config].
     *
     * @return The number of projects added.
     */
    private fun addProjectDependencies(
        config: Configuration,
        projects: List<Project>,
        variantName: String
    ): Int {
        var count = 0
        for (project in projects) {
            val hasVariant = try {
                variantBuilder.build(project).any { it.name == variantName }
            } catch (e: Exception) {
                false
            }
            if (hasVariant) {
                rootProject.dependencies.add(
                    config.name,
                    rootProject.dependencies.project(mapOf("path" to project.path))
                )
                count++
            }
        }
        return count
    }

    /**
     * Resolve [config]'s transitive graph via [ResolvedComponentsVisitor] — the same visitor
     * used in [com.grab.grazel.tasks.internal.ResolveVariantDependenciesTask] — and map each
     * visited component to a [ResolvedDependency].
     *
     * All deps are marked `direct = true` here because the per-variant aggregated resolution
     * produces the full transitive closure in a single graph; the distinction between direct
     * and transitive at the per-module level is not relevant at this stage (it is handled by
     * [ComputeWorkspaceDependencies] downstream via `allDependencies` flattening).
     */
    private fun resolveCompileDeps(
        config: Configuration,
        variantName: String
    ): Set<ResolvedDependency> = try {
        val root = config.incoming.resolutionResult.root
        ResolvedComponentsVisitor().visit(root, rootProject.logger::info) { visitResult ->
            val component = visitResult.component
            val moduleVersion = component.moduleVersion ?: return@visit null
            val shortId = "${moduleVersion.group}:${moduleVersion.name}"
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
                excludeRules = emptySet(),
                requiresJetifier = visitResult.requiresJetifier,
            )
        }
    } catch (e: Exception) {
        rootProject.logger.warn(
            "Grazel[AggregatedDependencyResolver]: compile resolution failed for variant " +
                "'$variantName': ${e.message}"
        )
        emptySet()
    }

    /**
     * Collect KSP processor dependencies across all [projects] for [variantName].
     *
     * KSP cannot be aggregated via a root config because processor class name extraction
     * requires downloading per-project JARs and reading their service files. Instead, we
     * collect KSP deps from each project's existing KSP classpath configuration for this
     * variant, mirroring the per-project logic in [com.grab.grazel.tasks.internal.ResolveVariantDependenciesTask].
     */
    private fun resolveKspDeps(
        projects: List<Project>,
        variantName: String
    ): Set<ResolvedDependency> {
        val kspDeps = TreeSet<ResolvedDependency>()

        for (project in projects) {
            if (!project.hasKsp) continue

            val variant = try {
                variantBuilder.build(project).firstOrNull { it.name == variantName }
            } catch (e: Exception) {
                null
            } ?: continue

            val kspConfigs = variant.kspConfiguration.filter { it.isCanBeResolved }
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
