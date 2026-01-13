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
        val result = workspaceDependencies.result
            .mapNotNullTo(TreeSet(compareBy(MavenInstallData::name))) { (variantName, artifacts) ->
                val mavenInstallName = variantName.toMavenRepoName()
                val allArtifacts = artifacts + grazelExtension
                    .dependencies
                    .overrideArtifactVersions
                    .get()
                    .map { ResolvedDependency.fromId(it, mavenInstallName) }
                    .asSequence()

                val mavenInstallArtifacts = allArtifacts
                    .mapTo(TreeSet(compareBy(MavenInstallArtifact::id)), ::toMavenInstallArtifact)
                    .also { if (it.isEmpty()) return@mapNotNullTo null }

                val repositories = calculateRepositories(artifacts)

                // Overrides
                val overrideTargets = calculateOverrideTargets(artifacts)

                val mavenInstallJson = layout
                    .projectDirectory
                    .file("${mavenInstallName}_install.json").asFile

                val jetifierArtifacts = (
                    artifacts
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
                    versionConflictPolicy = mavenInstallExtension.versionConflictPolicy,
                    mavenInstallJson = mavenInstallJson.name,
                    isMavenInstallJsonEnabled = mavenInstallExtension.artifactPinning.enabled.get() && mavenInstallJson.exists()
                )
            }

        // Generate ksp_maven from aggregated KSP deps
        if (workspaceDependencies.kspResult.isNotEmpty()) {
            val kspArtifacts = workspaceDependencies.kspResult.values.toList()
            val kspMavenInstallArtifacts = kspArtifacts
                .mapTo(TreeSet(compareBy(MavenInstallArtifact::id)), ::toMavenInstallArtifact)

            val kspRepositories = calculateRepositoriesIncludingTransitives(kspArtifacts)

            val kspMavenInstallJson = layout
                .projectDirectory
                .file("ksp_maven_install.json").asFile

            result.add(
                MavenInstallData(
                    name = "ksp_maven",
                    artifacts = kspMavenInstallArtifacts,
                    externalArtifacts = emptySet(),
                    repositories = kspRepositories,
                    externalRepositories = emptySet(),
                    jetifierConfig = JetifierConfig(isEnabled = false, artifacts = emptySet()),
                    failOnMissingChecksum = false,
                    excludeArtifacts = mavenInstallExtension.excludeArtifacts.get().toSet(),
                    overrideTargets = emptyMap(),
                    resolveTimeout = mavenInstallExtension.resolveTimeout,
                    artifactPinning = mavenInstallExtension.artifactPinning.enabled.get(),
                    versionConflictPolicy = mavenInstallExtension.versionConflictPolicy,
                    mavenInstallJson = kspMavenInstallJson.name,
                    isMavenInstallJsonEnabled = mavenInstallExtension.artifactPinning.enabled.get() && kspMavenInstallJson.exists()
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

    private fun toMavenInstallArtifact(dependency: ResolvedDependency): MavenInstallArtifact {
        val (group, name, version) = dependency.id.split(":")
        val shortId = "${group}:${name}"
        val overrideVersion = overrideVersionsMap[shortId] ?: version
        val artifactId = "$group:$name:$overrideVersion"
        val exclusions = dependency.excludeRules.mapNotNull(::toExclusion)
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

    private fun calculateRepositories(artifacts: List<ResolvedDependency>): Set<DefaultMavenRepository> {
        val gradleRepositories = artifacts.map(ResolvedDependency::repository)
            .asSequence()
            .map { repositoryDataSource.allRepositoriesByName.getValue(it) }
            .sortedBy {
                val name: String = it.name
                repositoryDataSource.allRepositoriesByName.entries.indexOf<Any>(name) // Preserve order
            }.toSet()
        return gradleRepositories.map { it.toMavenRepository() }.toSet()
    }

    /**
     * Calculate repositories from both direct artifacts and their transitive dependencies.
     * This is needed for KSP where we only include direct processors as artifacts,
     * but transitives may come from different repositories (e.g., Maven Central).
     */
    private fun calculateRepositoriesIncludingTransitives(
        artifacts: List<ResolvedDependency>
    ): Set<DefaultMavenRepository> =
        artifacts
            .flatMap(ResolvedDependency::dependencies)
            .map(ResolvedDependency::from)
            .plus(artifacts)
            .let(::calculateRepositories)
}