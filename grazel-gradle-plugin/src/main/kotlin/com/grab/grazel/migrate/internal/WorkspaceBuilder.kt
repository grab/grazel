/*
 * Copyright 2021 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.migrate.internal

import com.android.build.gradle.BaseExtension
import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.rules.DAGGER_ARTIFACTS
import com.grab.grazel.bazel.rules.DAGGER_GROUP
import com.grab.grazel.bazel.rules.DAGGER_REPOSITORIES
import com.grab.grazel.bazel.rules.DATABINDING_ARTIFACTS
import com.grab.grazel.bazel.rules.DATABINDING_GROUP
import com.grab.grazel.bazel.rules.GRAB_BAZEL_COMMON_ARTIFACTS
import com.grab.grazel.bazel.rules.MavenRepository
import com.grab.grazel.bazel.rules.MavenRepository.DefaultMavenRepository
import com.grab.grazel.bazel.rules.androidNdkRepository
import com.grab.grazel.bazel.rules.androidSdkRepository
import com.grab.grazel.bazel.rules.bazelCommonRepository
import com.grab.grazel.bazel.rules.daggerWorkspaceRules
import com.grab.grazel.bazel.rules.jvmRules
import com.grab.grazel.bazel.rules.kotlinCompiler
import com.grab.grazel.bazel.rules.kotlinRepository
import com.grab.grazel.bazel.rules.loadBazelCommonArtifacts
import com.grab.grazel.bazel.rules.loadDaggerArtifactsAndRepositories
import com.grab.grazel.bazel.rules.registerKotlinToolchain
import com.grab.grazel.bazel.rules.toolAndroidRepository
import com.grab.grazel.bazel.rules.workspace
import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.bazel.starlark.statements
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.GradleProjectInfo
import com.grab.grazel.gradle.RepositoryDataSource
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import com.grab.grazel.gradle.dependencies.MavenArtifact
import com.grab.grazel.gradle.dependencies.toMavenInstallArtifact
import com.grab.grazel.gradle.isAndroidApplication
import com.grab.grazel.migrate.BazelFileBuilder
import com.grab.grazel.migrate.android.JetifierDataExtractor
import com.grab.grazel.migrate.android.parseCompileSdkVersion
import com.grab.grazel.migrate.dependencies.ArtifactsPinner
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.kotlin.dsl.the
import javax.inject.Inject
import javax.inject.Singleton

internal class WorkspaceBuilder(
    private val rootProject: Project,
    private val projectsToMigrate: List<Project>,
    private val grazelExtension: GrazelExtension,
    private val gradleProjectInfo: GradleProjectInfo,
    private val dependenciesDataSource: DependenciesDataSource,
    private val repositoryDataSource: RepositoryDataSource,
    private val artifactsPinner: ArtifactsPinner
) : BazelFileBuilder {
    @Singleton
    class Factory @Inject constructor(
        @param:RootProject private val rootProject: Project,
        private val grazelExtension: GrazelExtension,
        private val gradleProjectInfo: GradleProjectInfo,
        private val dependenciesDataSource: DependenciesDataSource,
        private val repositoryDataSource: RepositoryDataSource,
        private val artifactsPinner: ArtifactsPinner,
    ) {
        fun create(
            projectsToMigrate: List<Project>
        ) = WorkspaceBuilder(
            rootProject,
            projectsToMigrate,
            grazelExtension,
            gradleProjectInfo,
            dependenciesDataSource,
            repositoryDataSource,
            artifactsPinner
        )
    }

    private val dependenciesExtension get() = grazelExtension.dependencies
    private val mavenInstall get() = grazelExtension.rules.mavenInstall
    private val hasDatabinding = gradleProjectInfo.hasDatabinding

    override fun build() = statements {
        workspace(name = rootProject.name)

        buildKotlinRules()

        setupBazelCommon()

        buildJvmRules()

        addAndroidSdkRepositories(this)

        toolsAndroid()
    }

    private val injectedRepositories = listOf<MavenRepository>(
        DefaultMavenRepository("https://maven.google.com"),
        DefaultMavenRepository("https://repo1.maven.org/maven2")
    )

    private fun StatementsBuilder.buildJvmRules() {
        val hasDagger = gradleProjectInfo.hasDagger

        val externalArtifacts = mutableListOf<String>()
        val externalRepositories = mutableListOf<String>()

        if (hasDagger) {
            daggerWorkspaceRules(grazelExtension.rules.dagger)
            loadDaggerArtifactsAndRepositories()
            // TODO Remove dagger rules and build generic annotation processor config
            externalArtifacts += DAGGER_ARTIFACTS
            externalRepositories += DAGGER_REPOSITORIES
        }

        if (hasDatabinding) {
            loadBazelCommonArtifacts(grazelExtension.rules.bazelCommon.repository.name)
            externalArtifacts += GRAB_BAZEL_COMMON_ARTIFACTS
        }

        val mavenArtifacts = dependenciesDataSource
            .resolvedArtifactsFor(
                projects = projectsToMigrate,
                overrideArtifactVersions = dependenciesExtension.overrideArtifactVersions.get()
            ).asSequence()
            .filter {
                val dagger = if (hasDagger) !it.id.contains(DAGGER_GROUP) else true
                val db = if (hasDatabinding) !it.id.contains(DATABINDING_GROUP) else true
                dagger && db
            }

        val databindingArtifacts = if (!hasDatabinding) emptySequence() else {
            DATABINDING_ARTIFACTS.map(MavenArtifact::toMavenInstallArtifact).asSequence()
        }

        val repositories = repositoryDataSource.supportedRepositories
            .map { repo ->
                val passwordCredentials = try {
                    repo.getCredentials(PasswordCredentials::class.java)
                } catch (e: Exception) {
                    // We only support basic auth now
                    null
                }
                DefaultMavenRepository(
                    repo.url.toString(),
                    passwordCredentials?.username,
                    passwordCredentials?.password
                )
            }

        val allArtifacts = (mavenArtifacts + databindingArtifacts).sortedBy { it.id }.toSet()

        val jetifierData = JetifierDataExtractor().extract(
            rootProject = rootProject,
            includeList = mavenInstall.jetifyIncludeList.get(),
            excludeList = mavenInstall.jetifyExcludeList.get(),
            allArtifacts = allArtifacts.map { it.id }
        )

        jvmRules(
            rulesJvmExternalRule = mavenInstall.repository,
            artifacts = allArtifacts,
            mavenRepositories = (repositories + injectedRepositories).distinct().toList(),
            externalArtifacts = externalArtifacts.toList(),
            externalRepositories = externalRepositories.toList(),
            jetify = jetifierData.isEnabled,
            jetifyIncludeList = jetifierData.includeList,
            failOnMissingChecksum = false,
            artifactPinning = artifactsPinner.isEnabled,
            mavenInstallJson = artifactsPinner.mavenInstallJson(),
            resolveTimeout = mavenInstall.resolveTimeout,
            excludeArtifacts = mavenInstall.excludeArtifacts.get(),
            overrideTargets = mavenInstall.overrideTargetLabels.get(),
            versionConflictPolicy = mavenInstall.versionConflictPolicy,
        )
    }


    /** Configure imports for Grab bazel common repository */
    private fun StatementsBuilder.setupBazelCommon() {
        val bazelCommon = grazelExtension.rules.bazelCommon
        val bazelCommonRepo = bazelCommon.repository
        val toolchains = bazelCommon.toolchains
        val buildifier = toolchains.buildifier

        bazelCommonRepository(
            bazelCommonRepo,
            buildifier.releaseVersion,
        )
    }

    private fun StatementsBuilder.toolsAndroid() {
        if (gradleProjectInfo.hasGooglePlayServices) {
            toolAndroidRepository(grazelExtension.rules.googleServices.repository)
        }
    }

    internal fun addAndroidSdkRepositories(statementsBuilder: StatementsBuilder): Unit =
        statementsBuilder.run {
            // Find the android application module and extract compileSdk and buildToolsVersion
            rootProject
                .subprojects
                .firstOrNull(Project::isAndroidApplication)
                ?.let { project ->
                    val baseExtension = project.the<BaseExtension>()
                    // Parse API level using DefaultApiVersion since AGP rewrites declared compileSdkVersion to string.
                    androidSdkRepository(
                        apiLevel = parseCompileSdkVersion(baseExtension.compileSdkVersion),
                        buildToolsVersion = baseExtension.buildToolsVersion
                    )
                } ?: androidSdkRepository()

            // Add repository for NDK
            validateNdkApiLevel()
            androidNdkRepository(
                ndkApiLevel = grazelExtension.android.ndkApiLevel
            )
        }

    private fun validateNdkApiLevel() {
        val ndkApiLevel = grazelExtension.android.ndkApiLevel ?: return
        if (ndkApiLevel <= 0) {
            throw IllegalStateException("ndkApiLevel value should be greater than 0")
        }
    }

    /**
     * Add Kotlin specific statements to WORKSPACE namely
     * * Kotlin repository
     * * Kotlin compiler
     * * Registering toolchains
     */
    private fun StatementsBuilder.buildKotlinRules() {
        val kotlin = grazelExtension.rules.kotlin
        kotlinRepository(repositoryRule = kotlin.repository)
        kotlinCompiler(kotlin.compiler.tag, kotlin.compiler.sha)
        registerKotlinToolchain(toolchain = kotlin.toolchain)
    }
}