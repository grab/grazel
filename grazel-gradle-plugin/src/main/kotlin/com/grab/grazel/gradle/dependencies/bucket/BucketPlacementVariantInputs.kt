/*
 * Copyright 2026 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.gradle.dependencies.bucket

import com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadata
import com.grab.grazel.gradle.dependencies.DeclaredExternalDependency
import com.grab.grazel.gradle.dependencies.DeclaredVariantDependencyMetadata
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild

/**
 * Bridges user-declared dependency buckets (e.g. `demoImplementation`) - which don't necessarily
 * correspond to any variant Gradle itself materializes - into the variant graph placement operates
 * on. For every bucket name a leaf variant's declared dependencies reference, a synthetic "declared
 * owner" [BucketPlacementVariantInput] is derived ([ownerVariantFor]) and wired as an `extendsFrom`
 * parent of every leaf it applies to. Without this, a dependency declared under a non-variant
 * bucket name would have nowhere to be placed in the hierarchy the placement engine reasons about.
 */
internal fun DeclaredDependencyMetadata.mainBucketVariants(projectPath: String): List<BucketPlacementVariantInput> {
    val androidBuildVariants = projects[projectPath]
        ?.variants
        .orEmpty()
        .asSequence()
        .filter { variant -> variant.variantType == AndroidBuild }
        .toList()
    val declaredOwnerBucketNames = androidBuildVariants
        .asSequence()
        .flatMap { variant -> variant.declaredDependencyDeclarations.asSequence() }
        .map(DeclaredExternalDependency::bucketName)
        .filter { bucketName -> bucketName != DEFAULT_VARIANT }
        .toSortedSet()
    val declaredOwnersByName = declaredOwnerBucketNames
        .mapNotNull { bucketName ->
            ownerVariantFor(
                variants = androidBuildVariants,
                projectPath = projectPath,
                bucketName = bucketName
            )
        }
        .associateBy(BucketPlacementVariantInput::name)

    return androidBuildVariants
        .asSequence()
        .map { variant ->
            val declaredOwnersForLeaf = if (variant.androidLeafVariant) {
                declaredOwnersByName
                    .values
                    .filter { owner ->
                        owner.name != variant.name &&
                            owner.appliesTo(leafVariant = variant)
                    }
                    .map(BucketPlacementVariantInput::name)
                    .toSet()
            } else {
                emptySet()
            }
            BucketPlacementVariantInput(
                name = variant.name,
                extendsFrom = variant.extendsFrom + declaredOwnersForLeaf,
                buildType = variant.buildType,
                productFlavors = variant.productFlavors,
                leaf = variant.androidLeafVariant,
                projectPath = projectPath,
                variantType = variant.variantType
            )
        }
        .plus(declaredOwnersByName.values.asSequence())
        .sortedBy(BucketPlacementVariantInput::name)
        .toList()
}

/**
 * A synthesized owner bucket variant should extend [leafVariant] if either the leaf already
 * declares this owner name directly in its `extendsFrom`, or - for a multi-part owner name (e.g. a
 * flavor+buildType combo) - every one of its non-default constituent parts is already among the
 * leaf's `extendsFrom`. The latter check is what lets a combo owner bucket (never itself declared
 * as a variant) still correctly attach to exactly the leaves that belong to every one of its parts.
 */
private fun BucketPlacementVariantInput.appliesTo(
    leafVariant: DeclaredVariantDependencyMetadata
): Boolean {
    if (name in leafVariant.extendsFrom) return true
    val parentNames = extendsFrom - DEFAULT_VARIANT - name
    return parentNames.isNotEmpty() && parentNames.all { parentName -> parentName in leafVariant.extendsFrom }
}

/**
 * Typed decomposition of a single candidate owner-bucket name.
 *
 * Carrying the typed [flavors] and [buildType] that were used to construct the bucket name lets
 * [ownerVariantFor] read the decomposition directly instead of re-deriving it from the
 * synthesised string via substring/endsWith matching.
 */
private data class OwnerBucketSpec(
    val flavors: List<String>,
    val buildType: String?
)

/**
 * Resolves a candidate owner-bucket name (as referenced by a declared dependency) back to a full
 * [BucketPlacementVariantInput], since placement needs the owner's typed flavor/buildType
 * decomposition, not just its name. A direct match against an existing leaf variant is tried first;
 * otherwise the name is matched against every leaf's synthesized [candidateOwnerBucketSpecs] map.
 * Any qualifying leaf yields the same [OwnerBucketSpec] for a given name (the spec was built from
 * the exact typed parts that produced that name) *provided* bucket names are unique per typed
 * decomposition — which holds because AGP enforces flavor-name uniqueness across dimensions; the
 * code does not itself verify this. Given that convention it's safe to read the spec off the first
 * matching leaf rather than needing agreement across all of them.
 */
private fun ownerVariantFor(
    variants: Collection<DeclaredVariantDependencyMetadata>,
    projectPath: String,
    bucketName: String
): BucketPlacementVariantInput? {
    val matchingLeaf = variants.firstOrNull { variant ->
        variant.androidLeafVariant && variant.name == bucketName
    }
    if (matchingLeaf != null) {
        return BucketPlacementVariantInput(
            name = matchingLeaf.name,
            extendsFrom = matchingLeaf.extendsFrom,
            buildType = matchingLeaf.buildType,
            productFlavors = matchingLeaf.productFlavors,
            leaf = true,
            projectPath = projectPath,
            variantType = matchingLeaf.variantType
        )
    }
    // Build each leaf's spec map exactly once, then use it for both filtering and spec lookup.
    val specsByLeaf: Map<DeclaredVariantDependencyMetadata, Map<String, OwnerBucketSpec>> =
        variants
            .filter { it.androidLeafVariant }
            .associateWith { candidateOwnerBucketSpecs(it) }

    val matchingLeafCandidates = specsByLeaf
        .filter { (_, specs) -> specs.containsKey(bucketName) }
        .keys
        .toList()
    if (matchingLeafCandidates.isEmpty()) return null

    // Look up the typed spec for bucketName from any candidate leaf — every qualifying leaf
    // has the same OwnerBucketSpec for a given name (the spec is constructed from the typed
    // parts that produced the name), which is unambiguous given AGP's flavor-name uniqueness.
    val spec = specsByLeaf[matchingLeafCandidates.first()]?.get(bucketName) ?: return null

    val ownerFlavors = spec.flavors
        .sortedWith(compareBy { flavorName ->
            matchingLeafCandidates.first().productFlavors.indexOf(flavorName).takeIf { it >= 0 } ?: Int.MAX_VALUE
        })
    val ownerBuildType = spec.buildType
    return BucketPlacementVariantInput(
        name = bucketName,
        extendsFrom = (setOf(DEFAULT_VARIANT) + ownerFlavors + listOfNotNull(ownerBuildType)).toSortedSet(),
        buildType = ownerBuildType,
        productFlavors = ownerFlavors,
        leaf = false,
        projectPath = projectPath,
        variantType = AndroidBuild
    )
}

/**
 * Returns all candidate owner-bucket specs for [leafVariant], keyed by bucket name.
 *
 * Each [OwnerBucketSpec] carries the typed [OwnerBucketSpec.flavors] and
 * [OwnerBucketSpec.buildType] that were used to synthesise the name, so callers can
 * consume the typed decomposition instead of re-parsing the name.
 */
private fun candidateOwnerBucketSpecs(
    leafVariant: DeclaredVariantDependencyMetadata
): Map<String, OwnerBucketSpec> {
    if (!leafVariant.androidLeafVariant) return emptyMap()
    val flavors = leafVariant.productFlavors
    val flavorCombinations: Map<String, List<String>> = orderedCombinations(flavors)
    val buildType = leafVariant.buildType
    return buildMap {
        flavors.forEach { flavor ->
            put(flavor, OwnerBucketSpec(flavors = listOf(flavor), buildType = null))
        }
        buildType?.let { bt ->
            put(bt, OwnerBucketSpec(flavors = emptyList(), buildType = bt))
        }
        flavorCombinations.forEach { (comboName, comboFlavors) ->
            put(comboName, OwnerBucketSpec(flavors = comboFlavors, buildType = null))
        }
        if (buildType != null) {
            // Merge the single-flavor and combo+buildType entries into one loop.
            val allFlavourSources: Map<String, List<String>> =
                flavors.associateBy({ it }, { listOf(it) }) + flavorCombinations
            allFlavourSources.forEach { (baseName, baseFlavors) ->
                val withBt = baseName + buildType.bucketPartCapitalized()
                put(withBt, OwnerBucketSpec(flavors = baseFlavors, buildType = buildType))
            }
        }
        put(leafVariant.name, OwnerBucketSpec(flavors = flavors, buildType = buildType))
    }
}

private fun String.bucketPartCapitalized(): String {
    return replaceFirstChar { char ->
        if (char.isLowerCase()) char.titlecase() else char.toString()
    }
}

/**
 * Returns every size-≥2 ordered subset of [parts] as a mapping from the synthesised
 * bucket name to the typed flavor list that produced it.
 *
 * The name follows the same convention as before: the first part is lower-cased as-is,
 * each subsequent part has its first character capitalised.
 *
 * Returning the typed subset alongside the name lets callers build [OwnerBucketSpec]
 * entries without any string-scanning — eliminating the `comboName.contains(f)` pattern
 * that breaks when a flavor name is a substring of a combo name built from other flavors.
 */
private fun orderedCombinations(parts: List<String>): Map<String, List<String>> {
    if (parts.size < 2) return emptyMap()
    // Enumerate all subsets of size ≥ 2 by iterating over bitmasks.
    val n = parts.size
    return buildMap {
        for (mask in 0 until (1 shl n)) {
            val subset = parts.filterIndexed { index, _ -> (mask shr index) and 1 == 1 }
            if (subset.size < 2) continue
            val name = subset.joinToString(separator = "") { part ->
                if (part == subset.first()) part else part.bucketPartCapitalized()
            }
            put(name, subset)
        }
    }
}

internal fun DeclaredDependencyMetadata.mainBucketVariantsByProject(): List<BucketPlacementVariantInput> {
    return projects
        .keys
        .sorted()
        .flatMap { projectPath -> mainBucketVariants(projectPath) }
}

internal fun DeclaredDependencyMetadata.testBucketVariantsByProject(
    variantType: VariantType,
    baseBucketName: String
): List<BucketPlacementVariantInput> {
    return projects
        .keys
        .sorted()
        .flatMap { projectPath ->
            projects[projectPath]
                ?.variants
                .orEmpty()
                .asSequence()
                .filter { variant -> variant.variantType == variantType }
                .map { variant ->
                    BucketPlacementVariantInput(
                        name = variant.name,
                        extendsFrom = variant.testBucketExtendsFrom(
                            baseBucketName = baseBucketName
                        ),
                        buildType = null,
                        productFlavors = emptyList(),
                        leaf = variant.androidLeafVariant,
                        projectPath = projectPath,
                        variantType = variant.variantType
                    )
                }
                .toList()
        }
        .sortedWith(compareBy(BucketPlacementVariantInput::projectPath).thenBy(BucketPlacementVariantInput::name))
}

private fun DeclaredVariantDependencyMetadata.testBucketExtendsFrom(
    baseBucketName: String
): Set<String> {
    return (extendsFrom + baseBucketName)
        .filter { parentName -> parentName != name }
        .toSortedSet()
}
