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
import com.grab.grazel.gradle.dependencies.model.ExcludeRule
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.migrate.android.JetifierDataExtractor
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.file.ProjectLayout
import org.gradle.api.internal.artifacts.repositories.DefaultMavenArtifactRepository
import java.util.*
import javax.inject.Inject

/**
 * Utility class to convert [WorkspaceDependencies] to [MavenInstallData] accounting for various
 * user preferences provided via [grazelExtension]
 */
internal class MavenInstallArtifactsCalculator
@Inject
constructor(
    @param:RootProject private val rootProject: Project,
    private val repositoryDataSource: RepositoryDataSource,
    private val grazelExtension: GrazelExtension,
) {
    private val excludeArtifactsDenyList by lazy {
        grazelExtension.rules.mavenInstall.excludeArtifactsDenyList.get()
    }

    private val mavenInstallExtension get() = grazelExtension.rules.mavenInstall

    /**
     * Map of user configured overrides for artifact versions.
     */
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
    ): Set<MavenInstallData> = workspaceDependencies.result
        .mapNotNullTo(TreeSet(compareBy(MavenInstallData::name))) { (variantName, artifacts) ->
            val mavenInstallName = variantName.toMavenRepoName()
            val mavenInstallArtifacts = artifacts
                .mapTo(TreeSet(compareBy(MavenInstallArtifact::id)), ::toMavenInstallArtifact)
                .also { if (it.isEmpty()) return@mapNotNullTo null }

            // Repositories
            val repositories = artifacts
                .mapTo(TreeSet(), ResolvedDependency::repository)
                .mapTo(mutableSetOf()) {
                    repositoryDataSource.allRepositoriesByName
                        .getValue(it)
                        .toMavenRepository()
                }

            // Overrides
            val overridesFromExtension = mavenInstallExtension.overrideTargetLabels.get().toList()
            val overridesFromArtifacts = artifacts
                .mapNotNull(ResolvedDependency::overrideTarget)
                .map { it.artifactShortId to it.label.toString() }
                .toList()
            val overrideTargets = (overridesFromArtifacts + overridesFromExtension)
                .sortedWith(
                    compareBy(Pair<String, String>::second)
                        .thenBy(Pair<String, String>::first)
                ).toMap()

            val mavenInstallJson = layout
                .projectDirectory
                .file("${mavenInstallName}_install.json").asFile

            MavenInstallData(
                name = mavenInstallName,
                artifacts = mavenInstallArtifacts,
                externalArtifacts = if (variantName == DEFAULT_VARIANT) externalArtifacts else emptySet(),
                repositories = repositories,
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
                versionConflictPolicy = mavenInstallExtension.versionConflictPolicy,
                mavenInstallJson = mavenInstallJson.name,
                isMavenInstallJsonEnabled = mavenInstallJson.exists()
            )
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
        val includeCredentials = mavenInstallExtension.includeCredentials
        val username = if (includeCredentials) passwordCredentials?.username else null
        val password = if (includeCredentials) passwordCredentials?.password else null
        return DefaultMavenRepository(
            url.toString(),
            username,
            password
        )
    }
}