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

package com.grab.grazel.gradle.dependencies

import com.grab.grazel.gradle.dependencies.model.ExcludeRule
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.variant.ANDROID_TEST_VARIANT
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.TEST_VARIANT
import com.grab.grazel.gradle.variant.Variant
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.JvmBuild
import com.grab.grazel.gradle.variant.VariantType.Test
import com.grab.grazel.gradle.variant.toJvmVariantType
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalDependency

internal class DeclaredDependencyMetadataCollector {

    fun collectExcludeRulesByProjectPath(
        variantsByProject: Map<Project, Collection<Variant<*>>>,
        variantTypes: Set<VariantType>,
        variantNames: Set<String>
    ): Map<String, ProjectExcludeRules> {
        val ownerVariantTypes = variantTypes + variantTypes.map { it.toJvmVariantType }
        return variantsByProject
            .mapValues { (_, projectVariants) ->
                val variants = projectVariants.filter { variant -> variant.variantType in ownerVariantTypes }
                val bucketRules = variants
                    .asSequence()
                    .filter { variant -> variant.name in variantNames }
                    .flatMap { variant -> variant.variantConfigurations.asSequence() }
                    .asIterable()
                    .extractDeclaredExcludeRulesByShortId()
                val variantRulesByName = variants
                    .associate { variant ->
                        variant.name to variant
                            .variantConfigurations
                            .extractDeclaredExcludeRulesByShortId()
                    }
                    .filterValues { it.isNotEmpty() }
                val variantHierarchyNamesByName = variants
                    .associate { variant -> variant.name to (setOf(variant.name) + variant.extendsFrom) }

                ProjectExcludeRules(
                    bucketRulesByShortId = bucketRules,
                    variantRulesByName = variantRulesByName,
                    variantHierarchyNamesByName = variantHierarchyNamesByName
                )
            }
            .filterValues { it.hasRules }
            .mapKeys { (project, _) -> project.path }
    }

    fun collectCompileOnlyDependenciesByBucket(
        variantsByProject: Map<Project, Collection<Variant<*>>>,
        projects: Collection<Project>
    ): Map<String, Map<String, ResolvedDependency>> {
        val projectSet = projects.toSet()
        return variantsByProject
            .filterKeys { project -> project in projectSet }
            .values
            .flatten()
            .asSequence()
            .filter { variant ->
                variant.variantType == AndroidBuild ||
                    variant.variantType == AndroidTest ||
                    variant.variantType == JvmBuild ||
                    variant.variantType == Test
            }
            .flatMap { variant ->
                variant.variantConfigurations
                    .asSequence()
                    .filter { configuration -> configuration.isCompileOnlyDeclaration }
                    .flatMap { configuration ->
                        configuration.dependencies
                            .filterIsInstance<ExternalDependency>()
                            .asSequence()
                    }
                    .mapNotNull { dependency ->
                        val nullableGroup: String? = dependency.group
                        val group = nullableGroup ?: return@mapNotNull null
                        if (group.isBlank()) return@mapNotNull null
                        val name = dependency.name
                        val version = dependency.version ?: return@mapNotNull null
                        variant.compileOnlyBucketName to ResolvedDependency(
                            id = "$group:$name:$version",
                            version = version,
                            shortId = "$group:$name",
                            direct = true,
                            dependencies = emptySet(),
                            excludeRules = dependency.extractExcludeRules(),
                            repository = "Declared",
                            requiresJetifier = false
                        )
                    }
            }
            .groupBy({ (variantName, _) -> variantName }, { (_, dependency) -> dependency })
            .mapValues { (_, dependencies) ->
                dependencies
                    .groupBy(ResolvedDependency::shortId)
                    .mapValues { (_, duplicateDeclarations) ->
                        duplicateDeclarations.reduce(::mergeDependencyMetadataByMaxVersion)
                    }
            }
    }
}

private val Variant<*>.compileOnlyBucketName: String
    get() = when (variantType) {
        Test -> TEST_VARIANT
        AndroidTest -> ANDROID_TEST_VARIANT
        else -> name
    }

internal data class ProjectExcludeRules(
    val bucketRulesByShortId: Map<String, Set<ExcludeRule>>,
    val variantRulesByName: Map<String, Map<String, Set<ExcludeRule>>>,
    val variantHierarchyNamesByName: Map<String, Set<String>>
) {
    val hasRules: Boolean
        get() = bucketRulesByShortId.isNotEmpty() || variantRulesByName.isNotEmpty()

    fun rulesFor(shortId: String, selectedVariantDisplayName: String?): Set<ExcludeRule> {
        val selectedVariantNames = selectedVariantHierarchyNames(
            displayName = selectedVariantDisplayName,
            variantHierarchyNamesByName = variantHierarchyNamesByName
        )
        if (selectedVariantNames.isNotEmpty()) {
            return selectedVariantNames
                .flatMap { variantName -> variantRulesByName[variantName]?.get(shortId).orEmpty() }
                .toSortedSet(compareBy(ExcludeRule::toString))
        }
        return bucketRulesByShortId[shortId].orEmpty()
    }
}

internal fun selectedVariantHierarchyNames(
    displayName: String?,
    variantHierarchyNamesByName: Map<String, Set<String>>
): Set<String> {
    if (displayName.isNullOrBlank()) return emptySet()
    val matchingNames = variantHierarchyNamesByName.keys.filter { variantName ->
        displayName.startsWith(variantName, ignoreCase = true)
    }
    val longestLength = matchingNames.maxOfOrNull(String::length)
    val hierarchyNames = if (longestLength != null) {
        matchingNames
            .filter { it.length == longestLength }
            .flatMap { variantName -> variantHierarchyNamesByName[variantName].orEmpty() }
            .toSet()
    } else {
        emptySet()
    }
    return hierarchyNames
        .ifEmpty {
            when (displayName) {
                "apiElements", "runtimeElements" -> variantHierarchyNamesByName[DEFAULT_VARIANT].orEmpty()
                else -> emptySet()
            }
        }
}

private val Configuration.isCompileOnlyDeclaration: Boolean
    get() {
        val normalizedName = name.lowercase()
        return "compileonly" in normalizedName && "dependenciesmetadata" !in normalizedName
    }

private fun ExternalDependency.extractExcludeRules(): Set<ExcludeRule> {
    return excludeRules
        .map {
            @Suppress("USELESS_ELVIS") // Gradle metadata can expose null group/module values.
            ExcludeRule(
                group = it.group ?: "",
                artifact = it.module ?: ""
            )
        }
        .filterNot { it.group.isBlank() || it.artifact.isBlank() }
        .toSet()
}

internal fun Configuration.extractExcludeRulesByShortId(): Map<String, Set<ExcludeRule>> {
    return allDependencies
        .filterIsInstance<ExternalDependency>()
        .filter { dependency -> !dependency.group.isNullOrBlank() }
        .groupBy { dependency -> "${dependency.group}:${dependency.name}" }
        .mapValues { (_, dependencies) ->
            dependencies
                .flatMap { dependency -> dependency.extractExcludeRules() }
                .toSortedSet(compareBy(ExcludeRule::toString))
        }
        .filterValues { it.isNotEmpty() }
}

internal fun Iterable<Configuration>.extractExcludeRulesByShortId(): Map<String, Set<ExcludeRule>> {
    return asSequence()
        .flatMap { configuration -> configuration.extractExcludeRulesByShortId().asSequence() }
        .groupBy({ it.key }, { it.value })
        .mapValues { (_, ruleSets) ->
            ruleSets
                .flatten()
                .toSortedSet(compareBy(ExcludeRule::toString))
        }
        .filterValues { it.isNotEmpty() }
}

internal fun Configuration.extractDeclaredExcludeRulesByShortId(): Map<String, Set<ExcludeRule>> {
    if (!isDeclarationBucket) return emptyMap()
    return dependencies
        .filterIsInstance<ExternalDependency>()
        .filter { dependency -> !dependency.group.isNullOrBlank() }
        .groupBy { dependency -> "${dependency.group}:${dependency.name}" }
        .mapValues { (_, dependencies) ->
            dependencies
                .flatMap { dependency -> dependency.extractExcludeRules() }
                .toSortedSet(compareBy(ExcludeRule::toString))
        }
        .filterValues { it.isNotEmpty() }
}

internal fun Iterable<Configuration>.extractDeclaredExcludeRulesByShortId(): Map<String, Set<ExcludeRule>> {
    return asSequence()
        .flatMap { configuration -> configuration.extractDeclaredExcludeRulesByShortId().asSequence() }
        .groupBy({ it.key }, { it.value })
        .mapValues { (_, ruleSets) ->
            ruleSets
                .flatten()
                .toSortedSet(compareBy(ExcludeRule::toString))
        }
        .filterValues { it.isNotEmpty() }
}

private val Configuration.isDeclarationBucket: Boolean
    get() {
        val normalizedName = name.lowercase()
        return "classpath" !in normalizedName && "dependenciesmetadata" !in normalizedName
    }

internal fun Map<String, ProjectExcludeRules>.excludeRulesFor(
    rootProjectPath: String,
    rootExcludeRulesByShortId: Map<String, Set<ExcludeRule>>,
    ownerProjectPath: String?,
    ownerProjectVariantDisplayName: String?,
    shortId: String
): Set<ExcludeRule> {
    val ownerRules = ownerProjectPath
        ?.let { path ->
            this[path]?.rulesFor(
                shortId = shortId,
                selectedVariantDisplayName = ownerProjectVariantDisplayName
            )
        }
        .orEmpty()
    val rootRules = when {
        ownerProjectPath == null -> rootExcludeRulesByShortId[shortId].orEmpty()
        ownerProjectPath == rootProjectPath && ownerRules.isEmpty() ->
            rootExcludeRulesByShortId[shortId].orEmpty()
        else -> emptySet()
    }
    return (ownerRules + rootRules).toSortedSet(compareBy(ExcludeRule::toString))
}
