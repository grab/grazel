package com.grab.grazel.gradle.dependencies

import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.dependencies.model.hasSameResolvedArtifactIdentityAs
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.LINT_VARIANT
import java.util.ArrayDeque

internal fun WorkspaceDependencies.mavenInstallRootArtifactsByVariant(): Map<String, List<ResolvedDependency>> {
    val workspaceArtifacts = variantScopedArtifacts(variantDependencies = variantDeps)
    return variantDeps.mapValues { (variantName, artifacts) ->
        val scopedTransitiveClasspath = variantTransitiveClasspath[variantName]
        val fallbackTransitiveClasspath = when (variantName) {
            DEFAULT_VARIANT -> emptyMap()
            else -> transitiveClasspath
        }
        val fallbackTransitiveClasspathExcludedRoots = when {
            variantName == DEFAULT_VARIANT || scopedTransitiveClasspath == null -> emptySet()
            else -> scopedTransitiveClasspath.keys
        }
        mavenInstallRootArtifacts(
            artifacts = artifacts,
            variantName = variantName,
            workspaceArtifacts = workspaceArtifacts,
            transitiveClasspath = scopedTransitiveClasspath ?: when (variantName) {
                DEFAULT_VARIANT -> transitiveClasspath
                else -> emptyMap()
            },
            workspaceTransitiveClasspath = fallbackTransitiveClasspath,
            workspaceTransitiveClasspathExcludedRoots = fallbackTransitiveClasspathExcludedRoots,
            promotedTransitiveClasspath = transitiveClasspath
        )
    }
}

private fun mavenInstallRootArtifacts(
    artifacts: List<ResolvedDependency>,
    variantName: String,
    workspaceArtifacts: VariantScopedArtifacts,
    transitiveClasspath: Map<String, Set<String>> = emptyMap(),
    workspaceTransitiveClasspath: Map<String, Set<String>> = emptyMap(),
    workspaceTransitiveClasspathExcludedRoots: Set<String> = emptySet(),
    promotedTransitiveClasspath: Map<String, Set<String>> = emptyMap(),
): List<ResolvedDependency> {
    if (artifacts.isEmpty()) {
        return emptyList()
    }

    val rootArtifacts = when (variantName) {
        DEFAULT_VARIANT, LINT_VARIANT -> artifacts
        else -> artifacts.filter(ResolvedDependency::isMavenRootArtifact)
    }
    val rootShortIds = rootArtifacts.mapTo(sortedSetOf(), ResolvedDependency::shortId)
    val reachableShortIds = (
        reachableTransitiveShortIds(
            transitiveClasspath = transitiveClasspath,
            rootShortIds = rootShortIds
            ) +
            reachableTransitiveShortIds(
                transitiveClasspath = workspaceTransitiveClasspath,
                rootShortIds = rootShortIds,
                excludedRootShortIds = workspaceTransitiveClasspathExcludedRoots
            ) +
            reachablePromotedRootTransitiveShortIds(
                transitiveClasspath = transitiveClasspath,
                rootShortIds = rootShortIds,
                workspaceTransitiveClasspath = promotedTransitiveClasspath
            ) +
            reachableDependencyShortIds(
                rootArtifacts = rootArtifacts,
                variantName = variantName,
                workspaceArtifacts = workspaceArtifacts
            )
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

private fun reachableTransitiveShortIds(
    transitiveClasspath: Map<String, Set<String>>,
    rootShortIds: Set<String>,
    excludedRootShortIds: Set<String> = emptySet()
): Set<String> = transitiveClasspath.asSequence()
    .filter { (rootShortId, _) ->
        rootShortId in rootShortIds && rootShortId !in excludedRootShortIds
    }
    .flatMap { (_, transitiveShortIds) -> transitiveShortIds.asSequence() }
    .toSet()

private fun reachablePromotedRootTransitiveShortIds(
    transitiveClasspath: Map<String, Set<String>>,
    rootShortIds: Set<String>,
    workspaceTransitiveClasspath: Map<String, Set<String>>
): Set<String> = transitiveClasspath.asSequence()
    .filter { (ownerShortId, scopedTransitiveShortIds) ->
        ownerShortId !in rootShortIds && scopedTransitiveShortIds.any(rootShortIds::contains)
    }
    .flatMap { (ownerShortId, scopedTransitiveShortIds) ->
        (scopedTransitiveShortIds + workspaceTransitiveClasspath[ownerShortId].orEmpty()).asSequence()
    }
    .toSet()

private fun reachableDependencyShortIds(
    rootArtifacts: List<ResolvedDependency>,
    variantName: String,
    workspaceArtifacts: VariantScopedArtifacts
): Set<String> {
    val queue = ArrayDeque<String>()
    val visited = sortedSetOf<String>()
    rootArtifacts.forEach { dependency ->
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

private fun variantScopedArtifacts(
    variantDependencies: Map<String, List<ResolvedDependency>>
): VariantScopedArtifacts =
    VariantScopedArtifacts(
        variantDependencies.mapValues { (variantName, dependencies) ->
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
