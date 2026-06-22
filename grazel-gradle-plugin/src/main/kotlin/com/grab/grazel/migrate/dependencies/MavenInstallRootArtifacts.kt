package com.grab.grazel.migrate.dependencies

import com.grab.grazel.gradle.dependencies.mavenOverrideTarget
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.dependencies.model.merge
import com.grab.grazel.gradle.dependencies.model.versionInfo
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT

internal fun WorkspaceDependencies.mavenInstallRootArtifactsByVariant(): Map<String, List<ResolvedDependency>> {
    val workspaceArtifactByShortId = variantDeps.selectedArtifactByShortId()
    return variantDeps.mapValues { (variantName, artifacts) ->
        val scopedTransitiveClasspath = variantTransitiveClasspath[variantName]
        val fallbackTransitiveClasspath = when {
            variantName == DEFAULT_VARIANT -> emptyMap()
            scopedTransitiveClasspath == null -> transitiveClasspath
            else -> transitiveClasspath.filterKeys { shortId -> shortId !in scopedTransitiveClasspath }
        }
        artifacts.mavenInstallRootArtifacts(
            variantName = variantName,
            workspaceArtifactByShortId = workspaceArtifactByShortId,
            transitiveClasspath = scopedTransitiveClasspath ?: when (variantName) {
                DEFAULT_VARIANT -> transitiveClasspath
                else -> emptyMap()
            },
            workspaceTransitiveClasspath = fallbackTransitiveClasspath
        )
    }
}

internal fun List<ResolvedDependency>.mavenInstallRootArtifacts(
    variantName: String,
    defaultArtifacts: List<ResolvedDependency> = emptyList(),
    workspaceArtifactsByVariant: Map<String, List<ResolvedDependency>> = mapOf(
        DEFAULT_VARIANT to defaultArtifacts
    ),
    transitiveClasspath: Map<String, Set<String>> = emptyMap(),
    workspaceTransitiveClasspath: Map<String, Set<String>> = emptyMap(),
): List<ResolvedDependency> {
    return mavenInstallRootArtifacts(
        variantName = variantName,
        workspaceArtifactByShortId = workspaceArtifactsByVariant.selectedArtifactByShortId(),
        transitiveClasspath = transitiveClasspath,
        workspaceTransitiveClasspath = workspaceTransitiveClasspath
    )
}

private fun List<ResolvedDependency>.mavenInstallRootArtifacts(
    variantName: String,
    workspaceArtifactByShortId: Map<String, OwnedResolvedDependency>,
    transitiveClasspath: Map<String, Set<String>> = emptyMap(),
    workspaceTransitiveClasspath: Map<String, Set<String>> = emptyMap(),
): List<ResolvedDependency> {
    if (transitiveClasspath.isEmpty()) {
        return when {
            variantName == DEFAULT_VARIANT || workspaceTransitiveClasspath.isEmpty() ->
                sortedBy(ResolvedDependency::id)
            else -> withReachableArtifacts(
                transitiveClasspath = workspaceTransitiveClasspath,
                artifactByShortId = workspaceArtifactByShortId,
                transform = OwnedResolvedDependency::asOwnerOverride
            )
        }
    }

    if (variantName == DEFAULT_VARIANT) {
        return withReachableArtifacts(
            transitiveClasspath = transitiveClasspath,
            artifactByShortId = workspaceArtifactByShortId,
            transform = { artifact -> artifact.dependency.copy(overrideTarget = null) }
        )
    }

    return withReachableArtifacts(
        transitiveClasspath = transitiveClasspath,
        artifactByShortId = workspaceArtifactByShortId,
        transform = OwnedResolvedDependency::asOwnerOverride
    ).withReachableArtifacts(
        transitiveClasspath = workspaceTransitiveClasspath,
        artifactByShortId = workspaceArtifactByShortId,
        transform = OwnedResolvedDependency::asOwnerOverride
    )
}

private fun List<ResolvedDependency>.withReachableArtifacts(
    transitiveClasspath: Map<String, Set<String>>,
    artifactByShortId: Map<String, OwnedResolvedDependency>,
    transform: (OwnedResolvedDependency) -> ResolvedDependency
): List<ResolvedDependency> {
    val existingShortIds = mapTo(mutableSetOf(), ResolvedDependency::shortId)
    val inheritedShortIds = transitiveClasspath
        .asSequence()
        .filter { (rootShortId, _) -> rootShortId in existingShortIds }
        .flatMap { (_, transitiveShortIds) -> transitiveShortIds.asSequence() }
        .filterNot(existingShortIds::contains)
        .toSet()

    if (inheritedShortIds.isEmpty()) {
        return sortedBy(ResolvedDependency::id)
    }

    return (asSequence() + inheritedShortIds
        .asSequence()
        .mapNotNull(artifactByShortId::get)
        .map(transform))
        .distinctBy(ResolvedDependency::shortId)
        .sortedBy(ResolvedDependency::id)
        .toList()
}

private data class OwnedResolvedDependency(
    val variantName: String,
    val dependency: ResolvedDependency
)

private fun Map<String, List<ResolvedDependency>>.selectedArtifactByShortId(): Map<String, OwnedResolvedDependency> =
    entries
        .asSequence()
        .flatMap { (variantName, dependencies) ->
            dependencies.asSequence().map { dependency ->
                OwnedResolvedDependency(variantName, dependency)
            }
        }
        .groupBy { ownedDependency -> ownedDependency.dependency.shortId }
        .mapValues { (_, artifacts) ->
            artifacts.reduce { selected, candidate ->
                selected.mergeSelected(candidate)
            }
        }

private fun OwnedResolvedDependency.mergeSelected(
    other: OwnedResolvedDependency
): OwnedResolvedDependency {
    val winner = when {
        dependency.versionInfo > other.dependency.versionInfo -> this
        other.dependency.versionInfo > dependency.versionInfo -> other
        variantName == DEFAULT_VARIANT -> this
        other.variantName == DEFAULT_VARIANT -> other
        variantName <= other.variantName -> this
        else -> other
    }
    val loser = if (winner === this) other else this
    return winner.copy(dependency = winner.dependency.merge(loser.dependency))
}

private fun OwnedResolvedDependency.asOwnerOverride(): ResolvedDependency {
    return dependency.copy(
        direct = false,
        overrideTarget = mavenOverrideTarget(dependency.shortId, variantName)
    )
}
