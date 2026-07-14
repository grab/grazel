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
import com.grab.grazel.gradle.dependencies.model.CandidateMavenRepoKind.AGGREGATED
import com.grab.grazel.gradle.dependencies.model.CandidateMavenRepoKind.VARIANT
import com.grab.grazel.gradle.dependencies.model.ExcludeRule
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspacePlan
import com.grab.grazel.gradle.dependencies.toMavenRepoName
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.maven.MavenCoordinates
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.file.ProjectLayout
import org.gradle.api.internal.artifacts.repositories.DefaultMavenArtifactRepository
import java.util.TreeSet
import javax.inject.Inject

/**
 * Utility class to convert planned Maven repositories to [MavenInstallData] while accounting for
 * user preferences provided via [grazelExtension].
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

    private val excludedExternalRepositoriesByRepoName: Map<String, List<String>> by lazy {
        mavenInstallExtension.excludedExternalRepositoryVariablesByRepoName.get()
    }

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
        workspacePlan: WorkspacePlan,
        externalArtifacts: Set<String>,
        externalRepositories: Set<String>,
        materializedMavenRepos: Set<String>,
    ): Set<MavenInstallData> {
        val supportedRepositories = calculateSupportedRepositories()
        val result = workspacePlan
            .repoPlan
            .asSequence()
            .filter { (_, candidateRepo) -> candidateRepo.kind == VARIANT }
            .mapNotNullTo(TreeSet(compareBy(MavenInstallData::name))) { (mavenInstallName, candidateRepo) ->
                if (mavenInstallName !in materializedMavenRepos) {
                    return@mapNotNullTo null
                }
                val rootArtifacts = candidateRepo.pinInputs
                val allArtifacts = rootArtifacts + grazelExtension
                    .dependencies
                    .overrideArtifactVersions
                    .get()
                    .map { ResolvedDependency.fromId(it, mavenInstallName) }
                    .asSequence()

                val mavenInstallArtifacts = allArtifacts
                    .mapTo(TreeSet(compareBy(MavenInstallArtifact::id)), ::toMavenInstallArtifact)
                    .also { if (it.isEmpty()) return@mapNotNullTo null }

                val mavenInstallJson = layout
                    .projectDirectory
                    .file(mavenInstallJsonName(mavenInstallName)).asFile

                val jetifierArtifacts = (
                    rootArtifacts
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
                    externalArtifacts = if (mavenInstallName == DEFAULT_VARIANT.toMavenRepoName()) {
                        externalArtifacts
                    } else {
                        emptySet()
                    },
                    repositories = supportedRepositories,
                    externalRepositories = if (mavenInstallName == DEFAULT_VARIANT.toMavenRepoName()) {
                        externalRepositoriesFor(mavenInstallName, externalRepositories)
                    } else {
                        emptySet()
                    },
                    jetifierConfig = JetifierConfig(
                        isEnabled = jetifierArtifacts.isNotEmpty(),
                        artifacts = jetifierArtifacts
                    ),
                    failOnMissingChecksum = false,
                    excludeArtifacts = mavenInstallExtension.excludeArtifacts.get().toSet(),
                    overrideTargets = candidateRepo.overrideTargets,
                    resolveTimeout = mavenInstallExtension.resolveTimeout,
                    artifactPinning = mavenInstallExtension.artifactPinning.enabled.get(),
                    versionConflictPolicy = mavenInstallExtension.versionConflictPolicy,
                    mavenInstallJson = mavenInstallJson.name,
                    isMavenInstallJsonEnabled = mavenInstallExtension.artifactPinning.enabled.get() && mavenInstallJson.exists(),
                    additionalCoursierOptions = mavenInstallExtension.additionalCoursierOptions.get()
                )
            }

        // Generate maven_install entries for aggregated repos (e.g. ksp_maven)
        workspacePlan.repoPlan.forEach { (repoName, candidateRepo) ->
            if (candidateRepo.kind != AGGREGATED) return@forEach
            if (repoName !in materializedMavenRepos) return@forEach

            val mavenInstallArtifacts = candidateRepo.pinInputs
                .mapTo(TreeSet(compareBy(MavenInstallArtifact::id)), ::toMavenInstallArtifact)
            if (mavenInstallArtifacts.isEmpty()) return@forEach

            val mavenInstallJson = layout
                .projectDirectory
                .file(mavenInstallJsonName(repoName)).asFile

            result.add(
                MavenInstallData(
                    name = repoName,
                    artifacts = mavenInstallArtifacts,
                    externalArtifacts = emptySet(),
                    repositories = supportedRepositories,
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

    private fun toMavenInstallArtifact(
        dependency: ResolvedDependency,
    ): MavenInstallArtifact {
        val coordinate = MavenCoordinates.parse(dependency.id)
        val overrideVersion = overrideVersionsMap[dependency.shortId] ?: coordinate.version
        val artifactId = "${coordinate.group}:${coordinate.module}:$overrideVersion"
        val exclusions = dependency.excludeRules.mapNotNull(::toExclusion)
        return when {
            exclusions.isEmpty() -> SimpleArtifact(artifactId)
            else -> DetailedArtifact(
                group = coordinate.group,
                artifact = coordinate.module,
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

    private fun toMavenRepository(repository: DefaultMavenArtifactRepository): DefaultMavenRepository {
        val passwordCredentials = try {
            repository.getCredentials(PasswordCredentials::class.java)
        } catch (_: Exception) {
            // We only support basic auth now
            null
        }
        val username = if (includeCredentials) passwordCredentials?.username else null
        val password = if (includeCredentials) passwordCredentials?.password else null
        return DefaultMavenRepository(
            repository.url.toString(),
            username,
            password
        )
    }

    private fun calculateSupportedRepositories(): Set<DefaultMavenRepository> =
        repositoryDataSource
            .supportedRepositories
            .map { repository -> toMavenRepository(repository) }
            .toSet()

    private fun externalRepositoriesFor(
        mavenInstallName: String,
        externalRepositories: Set<String>,
    ): Set<String> =
        externalRepositories - excludedExternalRepositoriesByRepoName[mavenInstallName].orEmpty().toSet()
}
