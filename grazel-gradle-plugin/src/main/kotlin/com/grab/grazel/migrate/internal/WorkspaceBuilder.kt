/*
 * Copyright 2021 Grabtaxi Holdings PTE LTE (GRAB)
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
import com.grab.grazel.bazel.rules.androidToolsRepository
import com.grab.grazel.bazel.rules.daggerWorkspaceRules
import com.grab.grazel.bazel.rules.googleServicesWorkspaceDependencies
import com.grab.grazel.bazel.rules.jvmRules
import com.grab.grazel.bazel.rules.kotlinCompiler
import com.grab.grazel.bazel.rules.kotlinRepository
import com.grab.grazel.bazel.rules.loadBazelCommonArtifacts
import com.grab.grazel.bazel.rules.loadDaggerArtifactsAndRepositories
import com.grab.grazel.bazel.rules.registerKotlinToolchain
import com.grab.grazel.bazel.rules.workspace
import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.bazel.starlark.statements
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.GradleProjectInfo
import com.grab.grazel.gradle.RepositoryDataSource
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import com.grab.grazel.gradle.dependencies.MavenArtifact
import com.grab.grazel.gradle.isAndroidApplication
import com.grab.grazel.migrate.BazelFileBuilder
import com.grab.grazel.migrate.android.JetifierDataExtractor
import com.grab.grazel.migrate.android.parseCompileSdkVersion
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
    private val repositoryDataSource: RepositoryDataSource
) : BazelFileBuilder {
    @Singleton
    class Factory @Inject constructor(
        @param:RootProject private val rootProject: Project,
        private val grazelExtension: GrazelExtension,
        private val gradleProjectInfo: GradleProjectInfo,
        private val dependenciesDataSource: DependenciesDataSource,
        private val repositoryDataSource: RepositoryDataSource
    ) {
        fun create(
            projectsToMigrate: List<Project>
        ) = WorkspaceBuilder(
            rootProject,
            projectsToMigrate,
            grazelExtension,
            gradleProjectInfo,
            dependenciesDataSource,
            repositoryDataSource
        )
    }

    private val dependenciesConfiguration get() = grazelExtension.dependenciesConfiguration
    private val mavenInstallConfig get() = grazelExtension.rulesConfiguration.mavenInstall

    private val hasDatabinding = gradleProjectInfo.hasDatabinding

    override fun build() = statements {
        workspace(name = rootProject.name)

        setupBazelCommon()

        buildJvmRules()

        buildKotlinRules()

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
            daggerWorkspaceRules()
            loadDaggerArtifactsAndRepositories()
            externalArtifacts += DAGGER_ARTIFACTS
            externalRepositories += DAGGER_REPOSITORIES
        }

        if (hasDatabinding) {
            loadBazelCommonArtifacts(grazelExtension.rulesConfiguration.bazelCommon.repository.name)
            externalArtifacts += GRAB_BAZEL_COMMON_ARTIFACTS
        }

        val mavenArtifacts = dependenciesDataSource
            .resolvedArtifactsFor(
                projects = projectsToMigrate,
                overrideArtifactVersions = dependenciesConfiguration.overrideArtifactVersions.get()
            ).asSequence()
            .filter {
                val dagger = if (hasDagger) !it.contains(DAGGER_GROUP) else true
                val db = if (hasDatabinding) !it.contains(DATABINDING_GROUP) else true
                dagger && db
            }

        val databindingArtifacts = if (!hasDatabinding) emptySequence() else {
            DATABINDING_ARTIFACTS.map(MavenArtifact::toString).asSequence()
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

        val allArtifacts = (mavenArtifacts + databindingArtifacts)
            .distinct()
            .sorted()
            .toList()

        val jetifierData = JetifierDataExtractor().extract(
            rootProject = rootProject,
            includeList = mavenInstallConfig.jetifyIncludeList.get(),
            excludeList = mavenInstallConfig.jetifyExcludeList.get(),
            allArtifacts = allArtifacts
        )

        jvmRules(
            artifacts = allArtifacts,
            mavenRepositories = (repositories + injectedRepositories).distinct().toList(),
            externalArtifacts = externalArtifacts.toList(),
            externalRepositories = externalRepositories.toList(),
            jetify = jetifierData.isEnabled,
            jetifyIncludeList = jetifierData.includeList,
            failOnMissingChecksum = false,
            resolveTimeout = mavenInstallConfig.resolveTimeout,
            excludeArtifacts = mavenInstallConfig.excludeArtifacts.get()
        )
    }


    /**
     * Configure imports for Grab bazel common repository
     */
    private fun StatementsBuilder.setupBazelCommon() {
        val bazelCommonRepo = grazelExtension.rulesConfiguration.bazelCommon.repository

        bazelCommonRepo.addTo(this)
        bazelCommonRepo.remote?.run {
            androidToolsRepository(
                commit = bazelCommonRepo.commit,
                remote = this
            )
        }
    }

    private fun StatementsBuilder.toolsAndroid() {
        if (gradleProjectInfo.hasGooglePlayServices) {
            googleServicesWorkspaceDependencies()
        }
    }

    internal fun addAndroidSdkRepositories(statementsBuilder: StatementsBuilder): Unit = statementsBuilder.run {
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
        androidNdkRepository()
    }

    /**
     * Add Kotlin specific statements to WORKSPACE namely
     * * Kotlin repository
     * * Kotlin compiler
     * * Registering toolchains
     */
    private fun StatementsBuilder.buildKotlinRules() {
        val kotlin = grazelExtension.rulesConfiguration.kotlin
        kotlinRepository(repositoryRule = kotlin.repository)
        kotlinCompiler(kotlin.compiler.version, kotlin.compiler.sha)
        registerKotlinToolchain(toolchain = kotlin.toolchain)
    }
}