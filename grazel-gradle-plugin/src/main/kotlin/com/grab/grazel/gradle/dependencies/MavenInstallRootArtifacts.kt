package com.grab.grazel.gradle.dependencies

import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.dependencies.model.hasSameResolvedArtifactIdentityAs
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.LINT_VARIANT
import java.util.ArrayDeque

/**
 * Per variant, selects which transitive-classpath view to feed [mavenInstallRootArtifacts] and
 * which artifacts of other variants may be promoted in as fallback roots. [DEFAULT_VARIANT] is
 * special-cased throughout because it has no narrower scope to fall back *from* — it IS the
 * fallback for every other variant:
 * - `scopedTransitiveClasspath` (this variant's own transitive edges, if computed) is preferred;
 *   when absent, [DEFAULT_VARIANT] falls back to the workspace-wide `transitiveClasspath` while any
 *   other variant falls back to nothing (an empty map), since only the default variant's roots are
 *   guaranteed a workspace-wide view.
 * - `fallbackTransitiveClasspathExcludedRoots` prevents double-counting: roots already covered by
 *   this variant's own `scopedTransitiveClasspath` must be excluded from the workspace-level
 *   fallback lookup, otherwise the same root's transitive set would be unioned in twice.
 * - `promotedTransitiveClasspath` is always the unscoped workspace `transitiveClasspath`, used by
 *   [reachablePromotedRootTransitiveShortIds] to decide whether a non-root owner should be promoted.
 */
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

/**
 * Computes the set of `maven_install` pin inputs for one variant repo: the variant's own root
 * artifacts, plus every short ID reachable from those roots via any of four distinct reachability
 * sources, each covering a different way an artifact can be "pulled in" without itself being a
 * direct root of this variant:
 * 1. [reachableTransitiveShortIds] over this variant's own scoped `transitiveClasspath`.
 * 2. The same, over `workspaceTransitiveClasspath`, but excluding roots already covered by (1) via
 *    `workspaceTransitiveClasspathExcludedRoots` to avoid double-processing.
 * 3. [reachablePromotedRootTransitiveShortIds] — promotes non-root owners whose own transitive set
 *    overlaps this variant's roots.
 * 4. [reachableDependencyShortIds] — a declared-dependency-graph BFS as a catch-all for artifacts
 *    not captured by classpath data at all.
 * Reachable short IDs that are themselves already root artifacts are filtered out to avoid
 * re-adding a root as its own transitive dependency. For [DEFAULT_VARIANT]/`LINT_VARIANT`, every
 * resolved artifact is treated as a root (not just [ResolvedDependency.direct] ones) since those
 * repos own the full set rather than a per-variant override subset; the owner-override [transform]
 * likewise differs for [DEFAULT_VARIANT] (strip any override target — it's the canonical owner) vs
 * other variants (rewrite as an owner-override pointing back at the artifact's true owning repo).
 */
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

/**
 * Promotes artifacts owned by a non-root (`ownerShortId !in rootShortIds`) into the reachable set
 * whenever that owner's own scoped transitive set already intersects a current root — i.e. some
 * root already transitively depends on this owner, so the owner's dependencies must be pulled in
 * too even though the owner itself isn't a root of this variant. Once promoted, the owner's
 * dependencies are unioned from *both* [transitiveClasspath] (this variant's scoped view) and
 * [workspaceTransitiveClasspath] (the unscoped workspace view), since the owner's edges may only be
 * recorded at the workspace level if it isn't itself a root anywhere in this variant.
 */
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

/**
 * BFS over declared dependency edges (not classpath data) starting from [rootArtifacts]' own
 * `dependencies` sets, resolving each edge's owner for this variant via [VariantScopedArtifacts.ownerFor]
 * to find its further dependencies. This is a fallback path independent of transitive-classpath
 * computation, so it is the only one of the four reachability sources that can traverse a cycle in
 * the declared dependency graph — the `visited` set is required to terminate, and checking it
 * *before* expanding a node (rather than on enqueue) means a node already queued twice is only
 * expanded once.
 */
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

    /**
     * Prefers the [DEFAULT_VARIANT] owner over [variantName]'s own owner whenever both resolve to
     * the same artifact identity ([hasSameResolvedArtifactIdentityAs]) — even though [variantName]'s
     * own entry exists — so that an artifact identical across variants is consistently attributed to
     * the default variant's repo rather than flip-flopping between per-variant owners depending on
     * which variant happened to be processed. This keeps override targets ([asOwnerOverride]) stable
     * across variants for artifacts that don't actually differ between them.
     */
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
