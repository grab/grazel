/*
 * Copyright 2022 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.migrate.dependencies

import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.rules.MavenInstallArtifact
import com.grab.grazel.bazel.rules.MavenInstallArtifact.*
import com.grab.grazel.bazel.rules.MavenInstallArtifact.Exclusion.*
import com.grab.grazel.bazel.rules.MavenRepository.*
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.RepositoryDataSource
import com.grab.grazel.gradle.dependencies.IGNORED_ARTIFACT_GROUPS
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.Variant
import com.grab.grazel.gradle.variant.VariantBuilder
import com.grab.grazel.gradle.variant.migratableConfigurations
import com.grab.grazel.migrate.android.JetifierDataExtractor
import com.grab.grazel.migrate.dependencies.model.ExcludeRule
import com.grab.grazel.migrate.dependencies.model.MavenExternalArtifact
import com.grab.grazel.migrate.dependencies.model.Repository
import com.grab.grazel.migrate.dependencies.model.mergeWith
import com.grab.grazel.migrate.dependencies.model.toMavenArtifact
import com.grab.grazel.util.ansiCyan
import com.grab.grazel.util.ansiYellow
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.artifacts.result.ResolutionResult
import org.gradle.api.internal.artifacts.repositories.DefaultMavenArtifactRepository
import org.gradle.api.internal.artifacts.result.DefaultResolvedComponentResult
import org.gradle.api.internal.artifacts.result.DefaultResolvedDependencyResult
import java.util.*
import javax.inject.Inject

internal class MavenInstallArtifactsCalculator
@Inject
constructor(
    @param:RootProject private val rootProject: Project,
    private val repositoryDataSource: RepositoryDataSource,
    private val grazelExtension: GrazelExtension,
    private val variantBuilder: VariantBuilder,
    private val mavenInstallStore: MavenInstallStore
) {
    private val excludeArtifactsDenyList by lazy {
        grazelExtension.rules.mavenInstall.excludeArtifactsDenyList.get()
    }
    private val mavenInstallExtension get() = grazelExtension.rules.mavenInstall

    /**
     * Calculates `MavenInstallData` with exclude rules, dependency resolution, and repositories for
     * the given `projectsToMigrate`.
     */
    fun get(
        projectsToMigrate: List<Project>,
        externalArtifacts: Set<String>,
        externalRepositories: Set<String>
    ): Set<MavenInstallData> {
        val artifactsMap = calculateArtifactsMap(projectsToMigrate)
        return artifactsMap.mapNotNull { (variantName, artifacts) ->
            val name = variantName.toMavenRepoName()
            val mavenInstallArtifacts = artifacts.asSequence().map { artifact ->
                when {
                    artifact.excludeRules.isEmpty() -> SimpleArtifact(artifact.id)
                    else -> DetailedArtifact(
                        group = artifact.group,
                        artifact = artifact.name,
                        version = artifact.version,
                        exclusions = artifact.excludeRules.map {
                            SimpleExclusion("${it.group}:${it.artifact}")
                        }
                    )
                }
            }.sortedBy { it.id }.toSet().also { if (it.isEmpty()) return@mapNotNull null }

            val mavenRepositories = artifacts
                .asSequence()
                .map { it.repository.repository }
                .distinct()
                .map { repo -> repo.toMavenRepository() }
                .toSet()

            val overridesFromExtension = mavenInstallExtension.overrideTargetLabels.get().toList()
            val overridesFromArtifacts = artifacts
                .mapNotNull(MavenExternalArtifact::overrideTarget)
                .map { it.artifactShortId to it.label.toString() }
                .toList()
            val overrideTargets = (overridesFromArtifacts + overridesFromExtension)
                .sortedWith(
                    compareBy(Pair<String, String>::second).thenBy(Pair<String, String>::first)
                ).toMap()
            MavenInstallData(
                name = name,
                artifacts = mavenInstallArtifacts,
                externalArtifacts = if (variantName == DEFAULT_VARIANT) externalArtifacts else emptySet(),
                repositories = mavenRepositories,
                externalRepositories = if (variantName == DEFAULT_VARIANT) externalRepositories else emptySet(),
                jetifierData = JetifierDataExtractor().extract(
                    rootProject = rootProject,
                    includeList = mavenInstallExtension.jetifyIncludeList.get(),
                    excludeList = mavenInstallExtension.jetifyExcludeList.get(),
                    allArtifacts = mavenInstallArtifacts.map(MavenInstallArtifact::id)
                ),
                failOnMissingChecksum = false,
                excludeArtifacts = mavenInstallExtension.excludeArtifacts.get().toSet(),
                overrideTargets = overrideTargets,
                resolveTimeout = mavenInstallExtension.resolveTimeout,
                artifactPinning = mavenInstallExtension.artifactPinning.enabled.get(),
                versionConflictPolicy = mavenInstallExtension.versionConflictPolicy
            )
        }.sortedBy { it.name }.toSet()
    }


    /**
     * Calculate a map of `variantName` and their [MavenExternalArtifact] instances from each project's
     * configurations.
     */
    private fun calculateArtifactsMap(
        projectsToMigrate: List<Project>
    ): Map<String, Sequence<MavenExternalArtifact>> {
        val allVariants = projectsToMigrate.flatMap { variantBuilder.build(it) }

        // Group variants and their configurations
        val variantConfigs = allVariants
            .groupBy(Variant<*>::name, Variant<*>::migratableConfigurations)
            .mapValues { it.value.flatten().asSequence() }

        // With the variant specific configurations, map to their direct dependencies.
        val variantDependencies = resolveVariantDirectDependencies(variantConfigs)

        // Reduce variant dependencies to only contain dependencies unique to them.
        val reducedDependencies = reduceDependencies(
            variantDependencies = variantDependencies,
            variantsExtendsMap = allVariants
                .groupBy(Variant<*>::name, Variant<*>::extendsFrom)
                .mapValues { it.value.flatten().toSet() }
        ).onEach { (variantName, dependencies) ->
            // Cache the computed buckets into MavenInstallStore
            val variantRepoName = variantName.toMavenRepoName()
            dependencies.forEach { artifact ->
                mavenInstallStore[variantRepoName, artifact.group] = artifact.name
            }
        }

        // Filtered dependencies would not have accurate transitive closure in each bucket since
        // dependencies might have been filtered in above step. To fix, we compute a new transitive
        // graph using MavenInstallArtifact.componentResult
        return computeFlatTransitiveGraph(reducedDependencies)
    }

    /**
     * Takes a list of variant name and the list of [Configuration] in them to produce
     * a `MavenExternalArtifact` of direct dependencies.
     *
     * The data required for `MavenExternalArtifact` comes from different places and this method merges
     * from all of them to produce `Map` of variants and `MavenExternalArtifact`s. The is derived as
     * stated below.
     *
     *  * Repository is calculated from merging all repositories in the project.
     *  * Exclude rules are calculated from `ExternalDependency` provided from `configuration.dependencies`
     *  * [org.gradle.api.artifacts.ResolvableDependencies.getDependencies] is used to calculate the direct
     *  dependencies of a configuration.
     *  * [ResolutionResult] is used to calculate dependency versions after resolving the correct version
     *  and on which repository it was resolved from.
     *  Only [ResolutionResult] contains transitive dependencies' information hence it is retained in
     *  [MavenExternalArtifact.componentResult] for further processing
     */
    private fun resolveVariantDirectDependencies(
        variantConfigs: Map<String, Sequence<Configuration>>
    ): Map<String, Sequence<MavenExternalArtifact>> {
        val repositories = repositoryDataSource.allRepositoriesByName
        return variantConfigs.mapValues { (_, configurations) ->
            val visitedComponents = mutableMapOf<String, String>()
            val excludeRules = calculateExcludeRules(configurations)
            configurations
                .filter { it.isCanBeResolved }
                .flatMap { config ->
                    val resolvableDependencies = config.incoming
                    val directDependencies = resolvableDependencies
                        .dependencies
                        .asSequence()
                        .filterIsInstance<ExternalDependency>()
                        .groupBy { "${it.group}:${it.name}" }
                    resolvableDependencies
                        .resolutionResult
                        .allComponents
                        .asSequence()
                        .filter { !it.toString().startsWith("project :") }
                        .filter { it.toString() !in visitedComponents }
                        .filter { it.moduleVersion!!.group !in IGNORED_ARTIFACT_GROUPS }
                        .filterIsInstance<DefaultResolvedComponentResult>()
                        .filter { component ->
                            component.moduleVersion
                                ?.let { id -> "${id.group}:${id.name}" }
                                ?.let { it in directDependencies } ?: false
                        }.map { component ->
                            val version = component.moduleVersion!!
                            MavenExternalArtifact(
                                group = version.group,
                                version = version.version,
                                name = version.name,
                                repository = Repository(
                                    name = component.repositoryName!!,
                                    repository = repositories[component.repositoryName!!]!!
                                ),
                                excludeRules = excludeRules.getOrDefault(
                                    version.toString(),
                                    emptyList()
                                )
                            ).apply { componentResult = component }.also {
                                visitedComponents[it.id] = ""
                            }
                        }
                }.pickMaxVersion()
        }
    }

    /**
     * From the [Sequence] of [MavenExternalArtifact] potentially containing duplicates, picks the
     * max version of a dependency.
     */
    private fun Sequence<MavenExternalArtifact>.pickMaxVersion() = groupBy {
        it.shortId
    }.mapValues { (_, artifacts) ->
        artifacts.maxOf { it }.let { maxVersionArtifact ->
            when {
                artifacts.size > 1 -> {
                    val others = artifacts.filter { it != maxVersionArtifact }
                    maxVersionArtifact.mergeWith(others = others)
                }

                else -> maxVersionArtifact
            }
        }
    }.asSequence().map { it.value }

    /**
     * Calculate and merge exclude rules from all dependency declarations.
     *
     * @param configurations Configurations to merge exclude rules from
     * @return Map of maven id and its merged exclude rules.
     */
    private fun calculateExcludeRules(
        configurations: Sequence<Configuration>
    ): Map<String, List<ExcludeRule>> {
        return configurations
            .flatMap { config -> config.hierarchy.flatMap { it.dependencies } }
            .filter { it.group != null }
            .filterIsInstance<ExternalDependency>()
            .groupBy { dep -> "${dep.group}:${dep.name}:${dep.version}" }
            .mapValues { (_, artifacts) ->
                artifacts.flatMap { it.extractExcludeRules() }
                    .distinct()
                    .sortedBy { it.toString() }
            }.filterValues { it.isNotEmpty() }
    }


    /**
     * Variant to dependencies map can contain duplicates i.e dependencies in one variant can be
     * also present in another. This method tries to ensure the base variants (default, test) contains
     * all the common dependencies from their descendants by utilising [Variant.extendsFrom] property.
     *
     * For example:
     *
     *       // and "default" extendsFrom "flavor"
     *      "default" = ["artifact1", "artifact2", "artifact3"]
     *      "flavor" = ["artifact1", "artifact3"]
     *      result = ["artifact3"]
     */
    private fun reduceDependencies(
        variantDependencies: Map<String, Sequence<MavenExternalArtifact>>,
        variantsExtendsMap: Map</* Variant name */String, Set</* Extends name */String>>
    ): Map</* Variant name */String, Sequence<MavenExternalArtifact>> {
        val results = variantsExtendsMap.mapValues { (currentVariant, extendsFrom) ->
            if (currentVariant == DEFAULT_VARIANT || extendsFrom.isEmpty()) {
                variantDependencies.getOrDefault(currentVariant, emptySequence())
            } else {
                val baseVariantDeps = extendsFrom
                    .asSequence()
                    .map { extends ->
                        variantDependencies.getOrDefault(
                            extends,
                            emptySequence()
                        )
                    }.flatten()
                    .distinctBy { it.id }
                reduce(
                    currentDeps = variantDependencies.getOrDefault(currentVariant, emptySequence()),
                    baseVariantDeps = baseVariantDeps
                )
            }
        }
        return results
    }

    private fun reduce(
        currentDeps: Sequence<MavenExternalArtifact>,
        baseVariantDeps: Sequence<MavenExternalArtifact>
    ): Sequence<MavenExternalArtifact> {
        val baseVariantDepsMap = baseVariantDeps.groupBy { it.id }
        // Filter all dependencies that are not in baseDeps
        return currentDeps
            .filter { !baseVariantDepsMap.contains(it.id) }
            .sortedBy { it.id }
    }


    private fun computeFlatTransitiveGraph(
        reducedDeps: Map<String, Sequence<MavenExternalArtifact>>
    ): Map<String, Sequence<MavenExternalArtifact>> {
        val repositories = repositoryDataSource.allRepositoriesByName
        val results = mutableMapOf<String, Sequence<MavenExternalArtifact>>()

        // Compute the default classpath first
        val defaultClasspath = flatten(reducedDeps[DEFAULT_VARIANT]!!, repositories)
            .also { dependencies -> results[DEFAULT_VARIANT] = dependencies }

        val defaultClasspathMap = defaultClasspath.associateBy(MavenExternalArtifact::shortId)

        // With default classpath as base, compute the transitive graph of other classpaths
        reducedDeps.filter { it.key != DEFAULT_VARIANT }
            .mapValues { (_, dependencies) ->
                flatten(
                    dependencies = dependencies,
                    repositories = repositories,
                    computeOverrides = true,
                    defaultClasspath = defaultClasspathMap
                )
            }.forEach { (variant, artifacts) -> results[variant] = artifacts }
        return results
    }

    private fun flatten(
        dependencies: Sequence<MavenExternalArtifact>,
        repositories: Map<String, DefaultMavenArtifactRepository>,
        computeOverrides: Boolean = false,
        defaultClasspath: Map<String, MavenExternalArtifact> = emptyMap()
    ): Sequence<MavenExternalArtifact> {
        // Flatten transitive graph of each ComponentResult by visiting all dependencies
        val visited = mutableMapOf<String, DefaultResolvedComponentResult>()
        return dependencies.flatMap { mavenArtifact ->
            val result = mutableSetOf<MavenExternalArtifact>()

            fun visit(componentResult: DefaultResolvedComponentResult, level: Int = 0) {
                printIndented(level, componentResult.toString())
                visited.getOrPut(
                    componentResult.moduleVersion.toString()
                ) { componentResult }
                if (componentResult.moduleVersion!!.group in IGNORED_ARTIFACT_GROUPS)
                    return

                componentResult.dependencies
                    .asSequence()
                    .filterIsInstance<DefaultResolvedDependencyResult>()
                    .map { it.selected }
                    .filterIsInstance<DefaultResolvedComponentResult>()
                    .map { selected ->
                        selected to selected.toMavenArtifact(
                            repositories = repositories,
                            defaultClasspath = if (computeOverrides) defaultClasspath else emptyMap()
                        )
                    }.filter { (_, artifact) -> artifact.group !in IGNORED_ARTIFACT_GROUPS }
                    .forEach { (selected, artifact: MavenExternalArtifact) ->
                        result += artifact
                        if (!visited.contains(selected.moduleVersion!!.toString())) {
                            visit(selected, level + 1)
                        }
                    }
            }
            result += mavenArtifact
            visit(mavenArtifact.componentResult)
            result
        }.pickMaxVersion()
    }

    private fun DefaultMavenArtifactRepository.toMavenRepository(): DefaultMavenRepository {
        val passwordCredentials = try {
            getCredentials(PasswordCredentials::class.java)
        } catch (e: Exception) {
            // We only support basic auth now
            null
        }
        return DefaultMavenRepository(
            url.toString(),
            passwordCredentials?.username,
            passwordCredentials?.password
        )
    }

    private fun ExternalDependency.extractExcludeRules(): Set<ExcludeRule> {
        return excludeRules
            .asSequence()
            .map {
                @Suppress("USELESS_ELVIS") // Gradle lying, module can be null
                (ExcludeRule(
                    it.group,
                    it.module ?: ""
                ))
            }
            .filterNot { it.artifact.isNullOrBlank() }
            .filterNot { it.toString() in excludeArtifactsDenyList }
            .toSet()
    }

    private fun printIndented(level: Int, message: String) {
        val prefix = if (level == 0) "─" else " └"
        val indent = (0..level * 2).joinToString(separator = "") { "─" }
        val msg = message.let { if (level == 0) it.ansiCyan else it.ansiYellow }
        rootProject.logger.info("$prefix$indent $msg")
    }
}