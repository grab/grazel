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
import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.gradle.dependencies.mavenOverrideTarget
import com.grab.grazel.gradle.RepositoryDataSource
import com.grab.grazel.gradle.dependencies.DefaultJetifierExclusions
import com.grab.grazel.gradle.dependencies.model.ExcludeRule
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.dependencies.model.hasSameBucketOwnerAs
import com.grab.grazel.gradle.dependencies.model.versionInfo
import com.grab.grazel.gradle.variant.ANDROID_TEST_VARIANT
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.LINT_VARIANT
import com.grab.grazel.gradle.variant.TEST_VARIANT
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

    private val alwaysMaterializedVariants = setOf(
        DEFAULT_VARIANT,
        TEST_VARIANT,
        ANDROID_TEST_VARIANT,
        LINT_VARIANT
    )

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
        referencedMavenRepos: Set<String> = emptySet(),
    ): Set<MavenInstallData> {
        val rootArtifactsByVariant = workspaceDependencies.mavenInstallRootArtifactsByVariant()
        val defaultOwnerArtifacts = rootArtifactsByVariant[DEFAULT_VARIANT].orEmpty()
        val variantInputs = workspaceDependencies.variantDeps.map { (variantName, _) ->
            val mavenInstallName = variantName.toMavenRepoName()
            val rootArtifacts = rootArtifactsByVariant.getValue(variantName)
            VariantMavenInstallInput(
                variantName = variantName,
                mavenInstallName = mavenInstallName,
                rootArtifacts = rootArtifacts,
                overrideTargets = calculateOverrideTargets(
                    artifacts = rootArtifacts,
                    owningMavenRepoName = mavenInstallName,
                    defaultOwnerArtifacts = when (variantName) {
                        DEFAULT_VARIANT -> emptyList()
                        else -> defaultOwnerArtifacts
                    }
                )
            )
        }
        val materializedMavenRepos = variantInputs.materializedMavenRepos(referencedMavenRepos)

        val result = variantInputs
            .mapNotNullTo(TreeSet(compareBy(MavenInstallData::name))) { input ->
                val variantName = input.variantName
                val mavenInstallName = input.mavenInstallName
                if (
                    materializedMavenRepos != null &&
                    mavenInstallName !in materializedMavenRepos &&
                    variantName !in alwaysMaterializedVariants
                ) {
                    return@mapNotNullTo null
                }
                val rootArtifacts = input.rootArtifacts
                val allArtifacts = rootArtifacts + grazelExtension
                    .dependencies
                    .overrideArtifactVersions
                    .get()
                    .map { ResolvedDependency.fromId(it, mavenInstallName) }
                    .asSequence()

                val mavenInstallArtifacts = allArtifacts
                    .mapTo(TreeSet(compareBy(MavenInstallArtifact::id)), ::toMavenInstallArtifact)
                    .also { if (it.isEmpty()) return@mapNotNullTo null }

                val repositories = calculateSupportedRepositories()

                val mavenInstallJson = layout
                    .projectDirectory
                    .file("${mavenInstallName}_install.json").asFile

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
                    externalArtifacts = if (variantName == DEFAULT_VARIANT) externalArtifacts else emptySet(),
                    repositories = repositories,
                    externalRepositories = if (variantName == DEFAULT_VARIANT) externalRepositories else emptySet(),
                    jetifierConfig = JetifierConfig(
                        isEnabled = jetifierArtifacts.isNotEmpty(),
                        artifacts = jetifierArtifacts
                    ),
                    failOnMissingChecksum = false,
                    excludeArtifacts = mavenInstallExtension.excludeArtifacts.get().toSet(),
                    overrideTargets = input.overrideTargets,
                    resolveTimeout = mavenInstallExtension.resolveTimeout,
                    artifactPinning = mavenInstallExtension.artifactPinning.enabled.get(),
                    versionConflictPolicy = mavenInstallExtension.versionConflictPolicy,
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

    private data class VariantMavenInstallInput(
        val variantName: String,
        val mavenInstallName: String,
        val rootArtifacts: List<ResolvedDependency>,
        val overrideTargets: Map<String, String>
    )

    private fun List<VariantMavenInstallInput>.materializedMavenRepos(
        referencedMavenRepos: Set<String>
    ): Set<String>? {
        if (referencedMavenRepos.isEmpty()) return null

        val availableRepos = mapTo(mutableSetOf(), VariantMavenInstallInput::mavenInstallName)
        val materializedRepos = (
            referencedMavenRepos +
                alwaysMaterializedVariants.map(String::toMavenRepoName)
            ).toMutableSet()

        var changed: Boolean
        do {
            changed = false
            filter { input -> input.mavenInstallName in materializedRepos }
                .flatMap { input ->
                    input.overrideTargets.values
                        .mapNotNull { label -> label.referencedMavenRepo(availableRepos) }
                }
                .forEach { overrideTargetRepo ->
                    changed = materializedRepos.add(overrideTargetRepo) || changed
                }
        } while (changed)

        return materializedRepos
    }

    private fun String.referencedMavenRepo(availableRepos: Set<String>): String? =
        removePrefix("@")
            .substringBefore("//")
            .takeIf(availableRepos::contains)

    private fun calculateOverrideTargets(
        artifacts: List<ResolvedDependency>,
        owningMavenRepoName: String,
        defaultOwnerArtifacts: List<ResolvedDependency> = emptyList()
    ): Map<String, String> {
        val artifactsShortIdMap = artifacts.groupBy { it.shortId }
        val extensionOverrideEligibleShortIds = artifactsShortIdMap.keys +
            defaultOwnerArtifacts.mapTo(mutableSetOf(), ResolvedDependency::shortId)
        val overridesFromDefaultOwner = defaultOwnerArtifacts
            .asSequence()
            .filter { defaultArtifact -> defaultArtifact.shortId in artifactsShortIdMap }
            .filterNot { defaultArtifact ->
                artifactsShortIdMap[defaultArtifact.shortId]
                    .orEmpty()
                    .any { artifact -> artifact.shouldKeepOwnTargetInsteadOf(defaultArtifact) }
            }
            .map { defaultArtifact ->
                defaultArtifact.shortId to mavenOverrideTarget(
                    defaultArtifact.shortId,
                    DEFAULT_VARIANT
                ).label.toString()
            }
        val overridesFromExtension = mavenInstallExtension.overrideTargetLabels
            .get()
            .toList()
            .filter { (shortId, _) -> shortId in extensionOverrideEligibleShortIds }
        val overridesFromArtifacts = artifacts
            .asSequence()
            .mapNotNull(ResolvedDependency::overrideTarget)
            .map { it.artifactShortId to it.label.toString() }
        return (overridesFromDefaultOwner + overridesFromArtifacts + overridesFromExtension)
            .filterNot { (shortId, label) -> label.isExactSelfOverride(shortId, owningMavenRepoName) }
            .sortedBy { it.toString() }
            .toMap()
    }

    private fun ResolvedDependency.shouldKeepOwnTargetInsteadOf(
        defaultArtifact: ResolvedDependency
    ): Boolean {
        if (overrideTarget != null) return false
        return when {
            versionInfo > defaultArtifact.versionInfo -> true
            versionInfo < defaultArtifact.versionInfo -> false
            else -> !defaultArtifact.hasSameBucketOwnerAs(this)
        }
    }

    private fun String.isExactSelfOverride(shortId: String, owningMavenRepoName: String): Boolean {
        val (group, name) = shortId.split(":")
        val ownLabel = MavenDependency(
            repo = owningMavenRepoName,
            group = group,
            name = name
        ).toString()
        return this == ownLabel
    }

    private fun toMavenInstallArtifact(
        dependency: ResolvedDependency,
    ): MavenInstallArtifact {
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

    private fun calculateSupportedRepositories(): Set<DefaultMavenRepository> =
        repositoryDataSource
            .supportedRepositories
            .map { it.toMavenRepository() }
            .toSet()
}
