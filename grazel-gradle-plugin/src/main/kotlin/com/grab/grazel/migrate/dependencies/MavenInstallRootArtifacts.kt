package com.grab.grazel.migrate.dependencies

import com.grab.grazel.gradle.dependencies.mavenOverrideTarget
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.dependencies.model.hasSameResolvedArtifactIdentityAs
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.LINT_VARIANT
import java.util.ArrayDeque

internal fun WorkspaceDependencies.mavenInstallRootArtifactsByVariant(): Map<String, List<ResolvedDependency>> {
    val workspaceArtifacts = variantDeps.toVariantScopedArtifacts()
    return variantDeps.mapValues { (variantName, artifacts) ->
        val scopedTransitiveClasspath = variantTransitiveClasspath[variantName]
        val fallbackTransitiveClasspath = when {
            variantName == DEFAULT_VARIANT -> emptyMap()
            scopedTransitiveClasspath == null -> transitiveClasspath
            else -> transitiveClasspath.filterKeys { shortId -> shortId !in scopedTransitiveClasspath }
        }
        artifacts.mavenInstallRootArtifacts(
            variantName = variantName,
            workspaceArtifacts = workspaceArtifacts,
            transitiveClasspath = scopedTransitiveClasspath ?: when (variantName) {
                DEFAULT_VARIANT -> transitiveClasspath
                else -> emptyMap()
            },
            workspaceTransitiveClasspath = fallbackTransitiveClasspath,
            promotedTransitiveClasspath = transitiveClasspath
        )
    }
}

private fun List<ResolvedDependency>.mavenInstallRootArtifacts(
    variantName: String,
    workspaceArtifacts: VariantScopedArtifacts,
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
            rootArtifacts.reachableDependencyShortIds(variantName, workspaceArtifacts)
        )
        .filterNot(rootShortIds::contains)
        .toSortedSet()

    val transform: (OwnedResolvedDependency) -> ResolvedDependency = when (variantName) {
        DEFAULT_VARIANT -> { artifact -> artifact.dependency.copy(overrideTarget = null) }
        else -> OwnedResolvedDependency::asOwnerOverride
    }
    return (rootArtifacts.asSequence() + reachableShortIds
        .asSequence()
        .mapNotNull { shortId -> workspaceArtifacts.ownerFor(variantName, shortId) }
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
    variantName: String,
    workspaceArtifacts: VariantScopedArtifacts
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

        workspaceArtifacts.ownerFor(variantName, shortId)
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

private class VariantScopedArtifacts(
    private val artifactsByVariant: Map<String, Map<String, OwnedResolvedDependency>>
) {
    private val fallbackOwnersByShortId = linkedMapOf<String, OwnedResolvedDependency>().also { ownersByShortId ->
        artifactsByVariant
            .toSortedMap()
            .values
            .forEach { artifacts ->
                artifacts.forEach { (shortId, owner) ->
                    ownersByShortId.putIfAbsent(shortId, owner)
                }
            }
    }

    fun ownerFor(variantName: String, shortId: String): OwnedResolvedDependency? {
        val currentOwner = artifactsByVariant[variantName]?.get(shortId)
        val defaultOwner = artifactsByVariant[DEFAULT_VARIANT]?.get(shortId)
        if (
            currentOwner != null &&
            defaultOwner != null &&
            currentOwner.dependency.hasSameResolvedArtifactIdentityAs(defaultOwner.dependency)
        ) {
            return defaultOwner
        }
        return currentOwner
            ?: defaultOwner
            ?: fallbackOwnersByShortId[shortId]
    }
}

private fun Map<String, List<ResolvedDependency>>.toVariantScopedArtifacts(): VariantScopedArtifacts =
    VariantScopedArtifacts(
        mapValues { (variantName, dependencies) ->
            dependencies
                .map { dependency -> OwnedResolvedDependency(variantName, dependency) }
                .associateBy { ownedDependency -> ownedDependency.dependency.shortId }
        }
    )

private fun OwnedResolvedDependency.asOwnerOverride(): ResolvedDependency {
    return dependency.copy(
        direct = false,
        overrideTarget = dependency.overrideTarget ?: when (variantName) {
            DEFAULT_VARIANT -> mavenOverrideTarget(dependency.shortId, DEFAULT_VARIANT)
            else -> mavenOverrideTarget(dependency.shortId, variantName)
        }
    )
}
