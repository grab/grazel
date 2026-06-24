package com.grab.grazel.migrate.dependencies

import com.grab.grazel.gradle.dependencies.mavenOverrideTarget
import com.grab.grazel.gradle.dependencies.model.OverrideTarget
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.dependencies.model.merge
import com.grab.grazel.gradle.dependencies.model.versionInfo
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.LINT_VARIANT
import java.util.ArrayDeque

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
            workspaceTransitiveClasspath = fallbackTransitiveClasspath,
            promotedTransitiveClasspath = transitiveClasspath
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
        workspaceTransitiveClasspath = workspaceTransitiveClasspath,
        promotedTransitiveClasspath = workspaceTransitiveClasspath
    )
}

private fun List<ResolvedDependency>.mavenInstallRootArtifacts(
    variantName: String,
    workspaceArtifactByShortId: Map<String, OwnedResolvedDependency>,
    transitiveClasspath: Map<String, Set<String>> = emptyMap(),
    workspaceTransitiveClasspath: Map<String, Set<String>> = emptyMap(),
    promotedTransitiveClasspath: Map<String, Set<String>> = emptyMap(),
): List<ResolvedDependency> {
    if (isEmpty()) {
        return emptyList()
    }

    val rootArtifacts = when (variantName) {
        DEFAULT_VARIANT, LINT_VARIANT -> this
        else -> filter(ResolvedDependency::isMavenRootArtifact)
    }
    val rootShortIds = rootArtifacts.mapTo(sortedSetOf(), ResolvedDependency::shortId)
    val reachableShortIds = (
        transitiveClasspath.reachableTransitiveShortIds(rootShortIds) +
            workspaceTransitiveClasspath.reachableTransitiveShortIds(rootShortIds) +
            transitiveClasspath.reachablePromotedRootTransitiveShortIds(
                rootShortIds = rootShortIds,
                workspaceTransitiveClasspath = promotedTransitiveClasspath
            ) +
            rootArtifacts.reachableDependencyShortIds(workspaceArtifactByShortId)
        )
        .filterNot(rootShortIds::contains)
        .toSortedSet()

    val transform: (OwnedResolvedDependency) -> ResolvedDependency = when (variantName) {
        DEFAULT_VARIANT -> { artifact -> artifact.dependency.copy(overrideTarget = null) }
        else -> OwnedResolvedDependency::asOwnerOverride
    }
    return (rootArtifacts.asSequence() + reachableShortIds
        .asSequence()
        .mapNotNull(workspaceArtifactByShortId::get)
        .map(transform))
        .distinctBy(ResolvedDependency::shortId)
        .sortedBy(ResolvedDependency::id)
        .toList()
}

private fun Map<String, Set<String>>.reachableTransitiveShortIds(
    rootShortIds: Set<String>
): Set<String> = asSequence()
    .filter { (rootShortId, _) -> rootShortId in rootShortIds }
    .flatMap { (_, transitiveShortIds) -> transitiveShortIds.asSequence() }
    .toSet()

private fun Map<String, Set<String>>.reachablePromotedRootTransitiveShortIds(
    rootShortIds: Set<String>,
    workspaceTransitiveClasspath: Map<String, Set<String>>
): Set<String> = asSequence()
    .filter { (ownerShortId, scopedTransitiveShortIds) ->
        ownerShortId !in rootShortIds && scopedTransitiveShortIds.any(rootShortIds::contains)
    }
    .flatMap { (ownerShortId, scopedTransitiveShortIds) ->
        (scopedTransitiveShortIds + workspaceTransitiveClasspath[ownerShortId].orEmpty()).asSequence()
    }
    .toSet()

private fun List<ResolvedDependency>.reachableDependencyShortIds(
    artifactByShortId: Map<String, OwnedResolvedDependency>
): Set<String> {
    val queue = ArrayDeque<String>()
    val visited = sortedSetOf<String>()
    forEach { dependency ->
        dependency.dependencies
            .map(ResolvedDependency::from)
            .mapTo(queue, ResolvedDependency::shortId)
    }

    while (queue.isNotEmpty()) {
        val shortId = queue.removeFirst()
        if (!visited.add(shortId)) continue

        artifactByShortId[shortId]
            ?.dependency
            ?.dependencies
            .orEmpty()
            .map(ResolvedDependency::from)
            .mapTo(queue, ResolvedDependency::shortId)
    }

    return visited
}

private fun ResolvedDependency.isMavenRootArtifact(): Boolean {
    return direct
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
        overrideTarget = dependency.overrideTarget ?: when (variantName) {
            DEFAULT_VARIANT -> dependency.defaultOwnerOverrideTarget()
            else -> mavenOverrideTarget(dependency.shortId, variantName)
        }
    )
}

private fun ResolvedDependency.defaultOwnerOverrideTarget(): OverrideTarget? {
    return when {
        shortId.startsWith("androidx.databinding:") -> mavenOverrideTarget(shortId, DEFAULT_VARIANT)
        else -> null
    }
}
