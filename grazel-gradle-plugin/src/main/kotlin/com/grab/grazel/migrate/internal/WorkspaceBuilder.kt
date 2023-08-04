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

package com.grab.grazel.migrate.internal

import com.android.build.gradle.BaseExtension
import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.rules.DAGGER_ARTIFACTS
import com.grab.grazel.bazel.rules.DAGGER_REPOSITORIES
import com.grab.grazel.bazel.rules.GRAB_BAZEL_COMMON_ARTIFACTS
import com.grab.grazel.bazel.rules.androidNdkRepository
import com.grab.grazel.bazel.rules.androidSdkRepository
import com.grab.grazel.bazel.rules.bazelCommonRepository
import com.grab.grazel.bazel.rules.daggerWorkspaceRules
import com.grab.grazel.bazel.rules.kotlinCompiler
import com.grab.grazel.bazel.rules.kotlinRepository
import com.grab.grazel.bazel.rules.loadBazelCommonArtifacts
import com.grab.grazel.bazel.rules.loadDaggerArtifactsAndRepositories
import com.grab.grazel.bazel.rules.mavenInstall
import com.grab.grazel.bazel.rules.registerKotlinToolchain
import com.grab.grazel.bazel.rules.toolAndroidRepository
import com.grab.grazel.bazel.rules.workspace
import com.grab.grazel.bazel.starlark.LoadStrategy
import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.bazel.starlark.add
import com.grab.grazel.bazel.starlark.statements
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.GradleProjectInfo
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.isAndroidApplication
import com.grab.grazel.migrate.BazelFileBuilder
import com.grab.grazel.migrate.android.parseCompileSdkVersion
import com.grab.grazel.migrate.dependencies.ArtifactsPinner
import com.grab.grazel.migrate.dependencies.MavenInstallArtifactsCalculator
import org.gradle.api.Project
import org.gradle.kotlin.dsl.the
import javax.inject.Inject
import javax.inject.Singleton

internal class WorkspaceBuilder(
    private val rootProject: Project,
    private val projectsToMigrate: List<Project>,
    private val grazelExtension: GrazelExtension,
    private val gradleProjectInfo: GradleProjectInfo,
    private val artifactsPinner: ArtifactsPinner,
    private val workspaceDependencies: WorkspaceDependencies,
    private val mavenInstallArtifactsCalculator: MavenInstallArtifactsCalculator
) : BazelFileBuilder {
    @Singleton
    class Factory @Inject constructor(
        @param:RootProject private val rootProject: Project,
        private val grazelExtension: GrazelExtension,
        private val gradleProjectInfo: GradleProjectInfo,
        private val artifactsPinner: ArtifactsPinner,
        private val mavenInstallArtifactsCalculator: MavenInstallArtifactsCalculator
    ) {
        fun create(
            projectsToMigrate: List<Project>,
            workspaceDependencies: WorkspaceDependencies = WorkspaceDependencies(emptyMap()),
        ) = WorkspaceBuilder(
            rootProject,
            projectsToMigrate,
            grazelExtension,
            gradleProjectInfo,
            artifactsPinner,
            workspaceDependencies,
            mavenInstallArtifactsCalculator
        )
    }

    override fun build() = statements(loadStrategy = LoadStrategy.Inline()) {
        workspace(name = rootProject.name)

        kotlinRules()

        bazelCommon()

        buildJvmRules()

        addAndroidSdkRepositories(this)

        toolsAndroid()
    }

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

        loadBazelCommonArtifacts(grazelExtension.rules.bazelCommon.repository.name)
        externalArtifacts += GRAB_BAZEL_COMMON_ARTIFACTS

        val mavenInstall = grazelExtension.rules.mavenInstall.apply {
            add(repository)
        }
        mavenInstallArtifactsCalculator.get(
            workspaceDependencies,
            externalArtifacts.toSortedSet(),
            externalRepositories.toSortedSet(),
        ).forEach { mavenInstallData ->
            mavenInstall(
                name = mavenInstallData.name,
                rulesJvmExternalName = mavenInstall.repository.name,
                artifacts = mavenInstallData.artifacts,
                externalArtifacts = mavenInstallData.externalArtifacts,
                mavenRepositories = mavenInstallData.repositories,
                externalRepositories = mavenInstallData.externalRepositories,
                jetify = mavenInstallData.jetifierData.isEnabled,
                jetifyIncludeList = mavenInstallData.jetifierData.includeList,
                failOnMissingChecksum = false,
                resolveTimeout = mavenInstallData.resolveTimeout,
                excludeArtifacts = mavenInstallData.excludeArtifacts,
                overrideTargets = mavenInstallData.overrideTargets,
                versionConflictPolicy = mavenInstallData.versionConflictPolicy,
                artifactPinning = artifactsPinner.isEnabled
            )
        }
    }


    /** Configure imports for Grab bazel common repository */
    private fun StatementsBuilder.bazelCommon() {
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

    internal fun addAndroidSdkRepositories(statementsBuilder: StatementsBuilder) {
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
    private fun StatementsBuilder.kotlinRules() {
        val kotlin = grazelExtension.rules.kotlin
        kotlinRepository(repositoryRule = kotlin.repository)
        kotlinCompiler(kotlin.compiler.tag, kotlin.compiler.sha)
        registerKotlinToolchain(toolchain = kotlin.toolchain)
    }
}