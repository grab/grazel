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
import com.grab.grazel.bazel.rules.MavenInstallArtifact.DetailedArtifact
import com.grab.grazel.bazel.rules.MavenInstallArtifact.Exclusion.SimpleExclusion
import com.grab.grazel.bazel.rules.MavenInstallArtifact.SimpleArtifact
import com.grab.grazel.bazel.rules.MavenRepository.DefaultMavenRepository
import com.grab.grazel.gradle.RepositoryDataSource
import com.grab.grazel.gradle.dependencies.DefaultJetifierExclusions
import com.grab.grazel.gradle.dependencies.model.ExcludeRule
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.LINT_VARIANT
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.file.ProjectLayout
import org.gradle.api.internal.artifacts.repositories.DefaultMavenArtifactRepository
import java.util.TreeSet
import javax.inject.Inject

/**
 * Utility class to convert [WorkspaceDependencies] to [MavenInstallData] accounting for various
 * user preferences provided via [grazelExtension]
 */
internal class MavenInstallArtifactsCalculator
@Inject
constructor(
    private val repositoryDataSource: RepositoryDataSource,
    private val grazelExtension: GrazelExtension,
) {
    private val excludeArtifactsDenyList by lazy {
        grazelExtension.rules.mavenInstall.excludeArtifactsDenyList.get()
    }

    private val mavenInstallExtension get() = grazelExtension.rules.mavenInstall

    private val includeCredentials get() = mavenInstallExtension.includeCredentials

    /** Map of user configured overrides for artifact versions. */
    private val overrideVersionsMap: Map< /*shortId*/ String, /*version*/ String> by lazy {
        grazelExtension
            .dependencies
            .overrideArtifactVersions
            .get()
            .associateBy(
                { it.substringBeforeLast(":") },
                { it.split(":").last() }
            )
    }

    fun get(
        layout: ProjectLayout,
        workspaceDependencies: WorkspaceDependencies,
        externalArtifacts: Set<String>,
        externalRepositories: Set<String>,
    ): Set<MavenInstallData> {
        val result = workspaceDependencies.variantDeps
            .mapNotNullTo(TreeSet(compareBy(MavenInstallData::name))) { (variantName, artifacts) ->
                val mavenInstallName = variantName.toMavenRepoName()
                val artifactsToResolve = artifacts.mavenInstallRootArtifacts(
                    variantName = variantName,
                    transitiveClasspath = workspaceDependencies.variantTransitiveClasspath[variantName]
                        ?: workspaceDependencies.transitiveClasspath
                )
                val defaultOwnedExclusions = defaultOwnedExclusions(
                    variantName = variantName,
                    defaultArtifacts = workspaceDependencies.variantDeps[DEFAULT_VARIANT].orEmpty(),
                    artifacts = artifacts,
                )
                val allArtifacts = artifactsToResolve + grazelExtension
                    .dependencies
                    .overrideArtifactVersions
                    .get()
                    .map { ResolvedDependency.fromId(it, mavenInstallName) }
                    .asSequence()

                val mavenInstallArtifacts = allArtifacts
                    .mapTo(TreeSet(compareBy(MavenInstallArtifact::id))) {
                        toMavenInstallArtifact(it, defaultOwnedExclusions)
                    }
                    .also { if (it.isEmpty()) return@mapNotNullTo null }

                val repositories = calculateSupportedRepositories()

                // Overrides
                val overrideTargets = calculateOverrideTargets(artifactsToResolve)

                val mavenInstallJson = layout
                    .projectDirectory
                    .file("${mavenInstallName}_install.json").asFile

                val jetifierArtifacts = (
                    artifactsToResolve
                        .asSequence()
                        .mapNotNull { if (it.requiresJetifier) it.shortId else it.jetifierSource }
                        .toList()
                        + mavenInstallExtension.jetifyIncludeList.get()
                        - mavenInstallExtension.jetifyExcludeList.get().toSet()
                        - DefaultJetifierExclusions
                    ).toSortedSet()

                MavenInstallData(
                    name = mavenInstallName,
                    artifacts = mavenInstallArtifacts,
                    externalArtifacts = if (variantName == DEFAULT_VARIANT) externalArtifacts else emptySet(),
                    repositories = repositories,
                    externalRepositories = if (variantName == DEFAULT_VARIANT) externalRepositories else emptySet(),
                    jetifierConfig = JetifierConfig(
                        isEnabled = jetifierArtifacts.isNotEmpty(),
                        artifacts = jetifierArtifacts
                    ),
                    failOnMissingChecksum = false,
                    excludeArtifacts = mavenInstallExtension.excludeArtifacts.get().toSet(),
                    overrideTargets = overrideTargets,
                    resolveTimeout = mavenInstallExtension.resolveTimeout,
                    artifactPinning = mavenInstallExtension.artifactPinning.enabled.get(),
                    versionConflictPolicy = mavenInstallExtension.versionConflictPolicy
                        .takeIf { variantName == DEFAULT_VARIANT },
                    mavenInstallJson = mavenInstallJson.name,
                    isMavenInstallJsonEnabled = mavenInstallExtension.artifactPinning.enabled.get() && mavenInstallJson.exists(),
                    additionalCoursierOptions = mavenInstallExtension.additionalCoursierOptions.get()
                )
            }

        // Generate maven_install entries for aggregated repos (e.g. ksp_maven)
        workspaceDependencies.aggregatedRepos.forEach { (repoName, artifacts) ->
            val mavenInstallArtifacts = artifacts
                .mapTo(TreeSet(compareBy(MavenInstallArtifact::id)), ::toMavenInstallArtifact)
            if (mavenInstallArtifacts.isEmpty()) return@forEach

            val repositories = calculateSupportedRepositories()
            val mavenInstallJson = layout
                .projectDirectory
                .file("${repoName}_install.json").asFile

            result.add(
                MavenInstallData(
                    name = repoName,
                    artifacts = mavenInstallArtifacts,
                    externalArtifacts = emptySet(),
                    repositories = repositories,
                    externalRepositories = emptySet(),
                    jetifierConfig = JetifierConfig(isEnabled = false, artifacts = emptySet()),
                    failOnMissingChecksum = false,
                    excludeArtifacts = mavenInstallExtension.excludeArtifacts.get().toSet(),
                    overrideTargets = emptyMap(),
                    resolveTimeout = mavenInstallExtension.resolveTimeout,
                    artifactPinning = mavenInstallExtension.artifactPinning.enabled.get(),
                    versionConflictPolicy = mavenInstallExtension.versionConflictPolicy,
                    mavenInstallJson = mavenInstallJson.name,
                    isMavenInstallJsonEnabled = mavenInstallExtension.artifactPinning.enabled.get() && mavenInstallJson.exists(),
                    additionalCoursierOptions = mavenInstallExtension.additionalCoursierOptions.get()
                )
            )
        }

        return result
    }

    private fun calculateOverrideTargets(
        artifacts: List<ResolvedDependency>
    ): Map<String, String> {
        val artifactsShortIdMap = artifacts.groupBy { it.shortId }
        val overridesFromExtension = mavenInstallExtension.overrideTargetLabels.get().toList()
        val overridesFromArtifacts = artifacts
            .asSequence()
            .mapNotNull(ResolvedDependency::overrideTarget)
            .map { it.artifactShortId to it.label.toString() }
        return (overridesFromArtifacts + overridesFromExtension)
            .filter { (shortId, _) -> shortId in artifactsShortIdMap }
            .sortedBy { it.toString() }
            .toMap()
    }

    private fun defaultOwnedExclusions(
        variantName: String,
        defaultArtifacts: List<ResolvedDependency>,
        artifacts: List<ResolvedDependency>,
    ): Set<String> {
        if (variantName == DEFAULT_VARIANT) return emptySet()
        val artifactShortIds = artifacts.mapTo(mutableSetOf(), ResolvedDependency::shortId)
        return defaultArtifacts
            .asSequence()
            .map(ResolvedDependency::shortId)
            .filterNot { it in artifactShortIds }
            .toSortedSet()
    }

    private fun toMavenInstallArtifact(
        dependency: ResolvedDependency,
        additionalExclusions: Set<String> = emptySet(),
    ): MavenInstallArtifact {
        val (group, name, version) = dependency.id.split(":")
        val shortId = "${group}:${name}"
        val overrideVersion = overrideVersionsMap[shortId] ?: version
        val artifactId = "$group:$name:$overrideVersion"
        val exclusions = (
            dependency.excludeRules.mapNotNull(::toExclusion) +
                additionalExclusions.mapNotNull(::toExclusion)
            ).distinct()
        return when {
            exclusions.isEmpty() -> SimpleArtifact(artifactId)
            else -> DetailedArtifact(
                group = group,
                artifact = name,
                version = overrideVersion,
                exclusions = exclusions
            )
        }
    }

    private fun toExclusion(id: String): SimpleExclusion? =
        when (id) {
            !in excludeArtifactsDenyList -> SimpleExclusion(id)
            else -> null
        }

    private fun toExclusion(excludeRule: ExcludeRule): SimpleExclusion? {
        return when (val id = "${excludeRule.group}:${excludeRule.artifact}") {
            !in excludeArtifactsDenyList -> return SimpleExclusion(id)
            else -> null
        }
    }

    private fun DefaultMavenArtifactRepository.toMavenRepository(): DefaultMavenRepository {
        val passwordCredentials = try {
            getCredentials(PasswordCredentials::class.java)
        } catch (e: Exception) {
            // We only support basic auth now
            null
        }
        val username = if (includeCredentials) passwordCredentials?.username else null
        val password = if (includeCredentials) passwordCredentials?.password else null
        return DefaultMavenRepository(
            url.toString(),
            username,
            password
        )
    }

    private fun calculateSupportedRepositories(): Set<DefaultMavenRepository> =
        repositoryDataSource
            .supportedRepositories
            .map { it.toMavenRepository() }
            .toSet()
}

internal fun List<ResolvedDependency>.mavenInstallRootArtifacts(
    variantName: String,
    transitiveClasspath: Map<String, Set<String>> = emptyMap()
): List<ResolvedDependency> {
    if (variantName == DEFAULT_VARIANT) {
        return filter { it.overrideTarget == null }
    }
    if (variantName == LINT_VARIANT) {
        return this
    }

    val overrideCarriersRequiredByDirectRoots = asSequence()
        .filter(ResolvedDependency::direct)
        .flatMap { dependency -> transitiveClasspath[dependency.shortId].orEmpty().asSequence() }
        .toSet()

    return filter { dependency ->
        dependency.direct ||
            (dependency.overrideTarget != null &&
                dependency.shortId in overrideCarriersRequiredByDirectRoots)
        }
}
