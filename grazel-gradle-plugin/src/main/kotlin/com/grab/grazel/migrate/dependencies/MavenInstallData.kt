package com.grab.grazel.migrate.dependencies

import com.grab.grazel.bazel.rules.DAGGER_ARTIFACTS
import com.grab.grazel.bazel.rules.DAGGER_REPOSITORIES
import com.grab.grazel.bazel.rules.DAGGER_REPOSITORY_URLS
import com.grab.grazel.bazel.rules.GRAB_BAZEL_COMMON_ARTIFACTS
import com.grab.grazel.bazel.rules.MavenInstallArtifact
import com.grab.grazel.bazel.rules.MavenRepository
import com.grab.grazel.bazel.rules.MavenRepository.DefaultMavenRepository
import com.grab.grazel.bazel.rules.repositoryInputSpec
import kotlinx.serialization.Serializable

@Serializable
internal data class MavenInstallRepositoryInputs(
    val repositoriesByName: Map<String, List<String>>,
)

internal data class MavenInstallData(
    val name: String,
    val artifacts: Set<MavenInstallArtifact>,
    val externalArtifacts: Set<String>,
    val repositories: Set<MavenRepository>,
    val externalRepositories: Set<String>,
    val jetifierConfig: JetifierConfig,
    val failOnMissingChecksum: Boolean,
    val resolveTimeout: Int,
    val overrideTargets: Map<String, String>,
    val excludeArtifacts: Set<String>,
    val artifactPinning: Boolean,
    val mavenInstallJson: String?,
    /**
     * Flag to denote if maven_install_json is enabled, if disabled
     * then in generated code maven_install_json will be commented out
     */
    val isMavenInstallJsonEnabled: Boolean,
    val versionConflictPolicy: String?,
    val additionalCoursierOptions: List<String> = listOf("--parallel", "12")
)

internal data class JetifierConfig(
    val isEnabled: Boolean,
    val artifacts: Set<String>
)

internal data class MavenInstallExternalInputs(
    val artifacts: Set<String>,
    val repositories: Set<String>,
)

internal fun mavenInstallExternalInputs(
    hasDagger: Boolean,
): MavenInstallExternalInputs {
    val externalArtifacts = sortedSetOf<String>()
    val externalRepositories = sortedSetOf<String>()
    if (hasDagger) {
        externalArtifacts += DAGGER_ARTIFACTS
        externalRepositories += DAGGER_REPOSITORIES
    }
    externalArtifacts += GRAB_BAZEL_COMMON_ARTIFACTS
    return MavenInstallExternalInputs(
        artifacts = externalArtifacts,
        repositories = externalRepositories,
    )
}

internal fun MavenInstallData.repositoryInputSpecs(): List<String> =
    (repositories.map { repository ->
        when (repository) {
            is DefaultMavenRepository -> repository.repositoryInputSpec()
        }
    } + externalRepositories.flatMap(::externalRepositoryInputSpecs)).sorted()

private fun externalRepositoryInputSpecs(variableName: String): List<String> =
    when (variableName) {
        DAGGER_REPOSITORIES -> DAGGER_REPOSITORY_URLS.map(::repositoryInputSpec)
        else -> error("Unsupported external Maven repository variable for local pinning: $variableName")
    }
