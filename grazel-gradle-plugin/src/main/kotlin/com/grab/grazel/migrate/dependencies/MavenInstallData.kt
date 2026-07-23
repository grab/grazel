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
    val repositoriesByName: Map<String, List<MavenInstallRepositoryInput>>,
)

@Serializable
internal data class MavenInstallRepositoryInput(
    val repositoryInputSpec: String,
    val canonicalUrl: String,
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

internal fun repositoryInputs(mavenInstallData: MavenInstallData): List<MavenInstallRepositoryInput> =
    (mavenInstallData.repositories.map { repository ->
        when (repository) {
            is DefaultMavenRepository -> MavenInstallRepositoryInput(
                repositoryInputSpec = repository.repositoryInputSpec(),
                canonicalUrl = repository.repositoryInputUrl()
            )
        }
    } + mavenInstallData.externalRepositories.flatMap(::externalRepositoryInputs))
        .sortedWith(
            compareBy<MavenInstallRepositoryInput> { input -> input.repositoryInputSpec }
                .thenBy { input -> input.canonicalUrl }
        )

private fun externalRepositoryInputs(variableName: String): List<MavenInstallRepositoryInput> =
    externalRepositoryUrls(variableName).map { url ->
        MavenInstallRepositoryInput(
            repositoryInputSpec = repositoryInputSpec(url),
            canonicalUrl = url
        )
    }

/**
 * Expands a bare Starlark identifier (e.g. `DAGGER_REPOSITORIES`) that a WORKSPACE `repositories =
 * [...] + <VARIABLE>` list can reference into the canonical URLs it resolves to at Bazel load time.
 * Shared between [externalRepositoryInputs] (grazel's own generation-time model) and
 * [WorkspaceMavenInstallRepositories] (reading the same variable back out of the WORKSPACE text at
 * pin/reconstruction time) so both sides expand a given variable identically.
 */
internal fun externalRepositoryUrls(variableName: String): List<String> =
    when (variableName) {
        DAGGER_REPOSITORIES -> DAGGER_REPOSITORY_URLS
        else -> error(
            "Unsupported external Maven repository variable for local pinning: $variableName. " +
                "Add explicit expansion support for this variable or opt it out with " +
                "excludeExternalRepositoryVariables(\"<maven_repo_name>\", \"$variableName\")."
        )
    }

internal fun repositoryUrls(mavenInstallRepositoryInputs: MavenInstallRepositoryInputs): Set<String> =
    mavenInstallRepositoryInputs
        .repositoriesByName
        .values
        .asSequence()
        .flatten()
        .map { input -> input.canonicalUrl }
        .toSortedSet()
