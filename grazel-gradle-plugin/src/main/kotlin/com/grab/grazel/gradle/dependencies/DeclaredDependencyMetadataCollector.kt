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
import com.grab.grazel.gradle.dependencies.model.intersectWith
import com.grab.grazel.gradle.isAndroidApplication
import com.grab.grazel.gradle.isAndroidTest
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.Variant
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.JvmBuild
import com.grab.grazel.gradle.variant.VariantType.Test
import com.grab.grazel.gradle.variant.compileOnlyBucketName
import com.grab.grazel.gradle.variant.compileOnlyDeclaredDependencyConfigurations
import com.grab.grazel.gradle.variant.declarationBucketName
import com.grab.grazel.gradle.variant.declaredDependencyConfigurations
import com.grab.grazel.gradle.variant.isWorkspaceAndroidLeaf
import com.grab.grazel.gradle.variant.toJvmVariantType
import com.grab.grazel.gradle.variant.workspaceBuildTypeName
import com.grab.grazel.gradle.variant.workspaceProductFlavorNames
import kotlinx.serialization.Serializable
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency

internal const val DECLARED_DEPENDENCY_REPOSITORY = "Declared"

internal class DeclaredDependencyMetadataCollector {
    fun collect(
        variantsByProject: Map<Project, Collection<Variant<*>>>,
        projects: Collection<Project>
    ): DeclaredDependencyMetadata {
        val projectSet = projects.toSet()
        val snapshotter = DeclaredProjectMetadataSnapshotter()
        return DeclaredDependencyMetadataMerger.merge(
            variantsByProject
                .entries
                .asSequence()
                .filter { (project, _) -> project in projectSet }
                .map { (project, variants) ->
                    project.path to snapshotter.snapshot(project, variants)
                }
                .asIterable()
        )
    }

}

internal class DeclaredProjectMetadataSnapshotter {
    fun snapshot(
        project: Project,
        variants: Collection<Variant<*>>
    ): ProjectDeclaredDependencyMetadata {
        return ProjectDeclaredDependencyMetadata(
            projectType = when {
                project.isAndroidApplication ->
                    DeclaredProjectType.ANDROID_APPLICATION
                project.isAndroidTest ->
                    DeclaredProjectType.ANDROID_TEST
                else -> DeclaredProjectType.OTHER
            },
            variants = variants
                .sortedWith(compareBy<Variant<*>> { variant -> variant.name }
                    .thenBy { variant -> variant.variantType.name })
                .map { variant ->
                    val declaredDependencyDeclarations =
                        variant.extractDeclaredExternalDependencyDeclarations()
                    DeclaredVariantDependencyMetadata(
                        name = variant.name,
                        variantType = variant.variantType,
                        extendsFrom = variant.extendsFrom.toSortedSet(),
                        variantConfigurationNames = variant.configurationNamesOf { variantConfigurations },
                        compileConfigurationNames = variant.configurationNamesOf { compileConfiguration },
                        runtimeConfigurationNames = variant.configurationNamesOf { runtimeConfiguration },
                        kspConfigurationNames = variant.configurationNamesOf { kspConfiguration },
                        androidLeafVariant = variant.isWorkspaceAndroidLeaf,
                        buildType = variant.workspaceBuildTypeName,
                        productFlavors = variant.workspaceProductFlavorNames,
                        declaredDependencies = declaredDependencyDeclarations
                            .mapTo(sortedSetOf(), DeclaredExternalDependency::id),
                        declaredDependencyDeclarations = declaredDependencyDeclarations,
                        declaredProjectDependencies = variant.extractDeclaredProjectDependencyIds(),
                        excludeRulesByShortId = extractDeclaredExcludeRulesByShortId(
                            configurations = variant.declaredDependencyConfigurations
                        ),
                        compileOnlyBucketName = variant.compileOnlyBucketName,
                        compileOnlyDependenciesByShortId = variant.extractCompileOnlyDependenciesByShortId()
                    )
                }
        )
    }
}

internal data class DeclaredProjectMetadataSource(
    val project: Project,
    val variants: List<Variant<*>>
)

internal object DeclaredProjectMetadataPlanner {
    fun plan(
        projects: Collection<Project>,
        variantsByProject: Map<Project, Iterable<Variant<*>>>
    ): List<DeclaredProjectMetadataSource> {
        return projects
            .sortedBy { project -> project.path }
            .map { project ->
                DeclaredProjectMetadataSource(
                    project = project,
                    variants = variantsByProject[project]?.toList().orEmpty()
                )
            }
    }
}

internal object DeclaredDependencyMetadataMerger {
    fun merge(
        projectMetadata: Iterable<Pair<String, ProjectDeclaredDependencyMetadata>>
    ): DeclaredDependencyMetadata {
        return DeclaredDependencyMetadata(
            projects = projectMetadata
                .sortedBy { (projectPath, _) -> projectPath }
                .associate { (projectPath, metadata) -> projectPath to metadata }
        )
    }

    fun mergeShards(shards: Iterable<DeclaredDependencyMetadata>): DeclaredDependencyMetadata {
        return merge(
            shards.flatMap { shard ->
                shard.projects.map { (projectPath, metadata) -> projectPath to metadata }
            }
        )
    }
}

@Serializable
internal data class ProjectDependencyBucket(
    val projectPath: String,
    val bucketName: String
)

@Serializable
internal data class DeclaredDependencyMetadata(
    val projects: Map<String, ProjectDeclaredDependencyMetadata> = emptyMap()
) {
    companion object {
        val EMPTY = DeclaredDependencyMetadata()
    }

    fun collectExcludeRulesByProjectPath(
        variantTypes: Set<VariantType>,
        variantNames: Set<String>
    ): Map<String, ProjectExcludeRules> {
        val ownerVariantTypes = variantTypes + variantTypes.map { it.toJvmVariantType }
        return projects
            .mapValues { (_, projectMetadata) ->
                val variants = projectMetadata.variants
                    .filter { variant -> variant.variantType in ownerVariantTypes }
                val bucketRules = variants
                    .filter { variant -> variant.name in variantNames }
                    .map(DeclaredVariantDependencyMetadata::excludeRulesByShortId)
                    .let(::mergeExcludeRulesByShortId)
                val variantRulesByName = variants
                    .associate { variant -> variant.name to variant.excludeRulesByShortId }
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
    }

    fun collectCompileOnlyDependenciesByBucket(
        projectPaths: Collection<String>
    ): Map<String, Map<String, ResolvedDependency>> {
        return collectCompileOnlyDependenciesByProjectBucket(projectPaths)
            .asSequence()
            .map { (bucket, dependencies) -> bucket.bucketName to dependencies.values }
            .groupBy({ (bucketName, _) -> bucketName }, { (_, dependencies) -> dependencies })
            .mapValues { (_, dependencies) ->
                dependencies
                    .flatten()
                    .groupBy(ResolvedDependency::shortId)
                    .mapValues { (_, duplicateDeclarations) ->
                        duplicateDeclarations.reduce(::mergeDependencyMetadataByMaxVersion)
                    }
            }
    }

    fun collectCompileOnlyDependenciesByProjectBucket(
        projectPaths: Collection<String>
    ): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> {
        val projectPathSet = projectPaths.toSet()
        return projects
            .filterKeys { projectPath -> projectPath in projectPathSet }
            .flatMap { (projectPath, projectMetadata) ->
                projectMetadata.variants
                    .asSequence()
                    .filter { variant ->
                        variant.variantType == AndroidBuild ||
                            variant.variantType == AndroidTest ||
                            variant.variantType == JvmBuild ||
                            variant.variantType == Test
                    }
                    .flatMap { variant ->
                        variant.compileOnlyDependenciesByShortId.values
                            .asSequence()
                            .map { dependency ->
                                ProjectDependencyBucket(projectPath, variant.compileOnlyBucketName) to dependency
                            }
                    }
                    .toList()
            }
            .let(::mergeByProjectBucket)
    }

    fun collectDeclaredMainDependenciesByProjectBucket(
        projectPaths: Collection<String>
    ): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> =
        collectDeclaredDependenciesByProjectBucket(
            projectPaths = projectPaths,
            variantTypes = setOf(AndroidBuild, JvmBuild)
        )

    fun collectDeclaredTestDependenciesByProjectBucket(
        projectPaths: Collection<String>
    ): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> =
        collectDeclaredDependenciesByProjectBucket(
            projectPaths = projectPaths,
            variantTypes = setOf(Test, AndroidTest)
        )

    private fun collectDeclaredDependenciesByProjectBucket(
        projectPaths: Collection<String>,
        variantTypes: Set<VariantType>
    ): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> {
        val projectPathSet = projectPaths.toSet()
        return projects
            .filterKeys { projectPath -> projectPath in projectPathSet }
            .flatMap { (projectPath, projectMetadata) ->
                projectMetadata.variants
                    .asSequence()
                    .filter { variant -> variant.variantType in variantTypes }
                    .flatMap { variant ->
                        variant.declaredDependencyDeclarations
                            .asSequence()
                            .mapNotNull { declaration ->
                                declaration.id.toDeclaredResolvedDependency(
                                    excludeRulesByShortId = variant.excludeRulesByShortId
                                )?.let { dependency -> declaration to dependency }
                            }
                            .map { (declaration, dependency) ->
                                ProjectDependencyBucket(projectPath, declaration.bucketName) to dependency
                            }
                    }
                    .toList()
            }
            .let(::mergeByProjectBucket)
    }
}

private fun mergeByProjectBucket(
    projectBucketDependencies: Iterable<Pair<ProjectDependencyBucket, ResolvedDependency>>
): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> {
    return projectBucketDependencies.groupBy({ (bucket, _) -> bucket }, { (_, dependency) -> dependency })
        .mapValues { (_, dependencies) ->
            dependencies
                .groupBy(ResolvedDependency::shortId)
                .mapValues { (_, duplicateDeclarations) ->
                    duplicateDeclarations.reduce(::mergeDependencyMetadataByMaxVersion)
                }
        }
        .toSortedMap(
            compareBy<ProjectDependencyBucket> { bucket -> bucket.projectPath }
                .thenBy { bucket -> bucket.bucketName }
        )
}

@Serializable
internal data class ProjectDeclaredDependencyMetadata(
    val projectType: DeclaredProjectType = DeclaredProjectType.OTHER,
    val variants: List<DeclaredVariantDependencyMetadata> = emptyList()
) {
    val hasMetadata: Boolean
        get() = variants.any { variant ->
            variant.declaredDependencyDeclarations.isNotEmpty() ||
                variant.declaredProjectDependencies.isNotEmpty() ||
                variant.excludeRulesByShortId.isNotEmpty() ||
                variant.compileOnlyDependenciesByShortId.isNotEmpty()
        }
}

@Serializable
internal enum class DeclaredProjectType {
    ANDROID_APPLICATION,
    ANDROID_TEST,
    OTHER
}

@Serializable
internal data class DeclaredVariantDependencyMetadata(
    val name: String,
    val variantType: VariantType,
    val extendsFrom: Set<String>,
    val variantConfigurationNames: Set<String>,
    val compileConfigurationNames: Set<String>,
    val runtimeConfigurationNames: Set<String>,
    val kspConfigurationNames: Set<String>,
    val androidLeafVariant: Boolean,
    val buildType: String?,
    val productFlavors: List<String>,
    val declaredDependencies: Set<String>,
    val declaredDependencyDeclarations: Set<DeclaredExternalDependency> = emptySet(),
    val declaredProjectDependencies: Set<String>,
    val excludeRulesByShortId: Map<String, Set<ExcludeRule>>,
    val compileOnlyBucketName: String,
    val compileOnlyDependenciesByShortId: Map<String, ResolvedDependency>
)

@Serializable
internal data class DeclaredExternalDependency(
    val configurationName: String,
    val bucketName: String,
    val id: String
)

private fun Variant<*>.configurationNamesOf(
    configurationsProvider: Variant<*>.() -> Set<Configuration>
): Set<String> {
    return try {
        configurationNames(configurationsProvider())
    } catch (e: Exception) {
        emptySet()
    }
}

private fun configurationNames(configurations: Set<Configuration>): Set<String> {
    return configurations.mapTo(sortedSetOf()) { configuration -> configuration.name }
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
                .mapNotNull { variantName -> variantRulesByName[variantName]?.get(shortId) }
                .let(::intersectExcludeRuleSets)
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

private fun ModuleDependency.extractExcludeRules(): Set<ExcludeRule> {
    return excludeRules
        .map {
            @Suppress("USELESS_ELVIS") // Gradle metadata can expose null group/module values.
            ExcludeRule(
                group = it.group ?: "",
                artifact = it.module ?: ""
            )
        }
        .filterNot { it.group.isBlank() || it.artifact.isBlank() }
        .toSortedSet(compareBy(ExcludeRule::toString))
}

internal fun Configuration.extractExcludeRulesByShortId(): Map<String, Set<ExcludeRule>> {
    return allDependencies
        .filterIsInstance<ExternalDependency>()
        .filter { dependency -> !dependency.group.isNullOrBlank() }
        .groupBy { dependency -> "${dependency.group}:${dependency.name}" }
        .mapValues { (_, dependencies) ->
            dependencies
                .map { dependency -> dependency.extractExcludeRules() }
                .let(::intersectExcludeRuleSets)
        }
        .filterValues { it.isNotEmpty() }
        .toSortedMap()
}

internal fun extractExcludeRulesByShortId(
    configurations: Iterable<Configuration>
): Map<String, Set<ExcludeRule>> {
    return configurations.asSequence()
        .flatMap { configuration -> configuration.extractExcludeRulesByShortId().asSequence() }
        .groupBy({ it.key }, { it.value })
        .mapValues { (_, ruleSets) ->
            intersectExcludeRuleSets(ruleSets)
        }
        .filterValues { it.isNotEmpty() }
        .toSortedMap()
}

internal fun Configuration.extractDeclaredExcludeRulesByShortId(): Map<String, Set<ExcludeRule>> {
    return dependencies
        .filterIsInstance<ExternalDependency>()
        .filter { dependency -> !dependency.group.isNullOrBlank() }
        .groupBy { dependency -> "${dependency.group}:${dependency.name}" }
        .mapValues { (_, dependencies) ->
            dependencies
                .map { dependency -> dependency.extractExcludeRules() }
                .let(::intersectExcludeRuleSets)
        }
        .filterValues { it.isNotEmpty() }
        .toSortedMap()
}

internal fun extractDeclaredExcludeRulesByShortId(
    configurations: Iterable<Configuration>
): Map<String, Set<ExcludeRule>> {
    return configurations.asSequence()
        .flatMap { configuration -> configuration.extractDeclaredExcludeRulesByShortId().asSequence() }
        .groupBy({ it.key }, { it.value })
        .mapValues { (_, ruleSets) ->
            intersectExcludeRuleSets(ruleSets)
        }
        .filterValues { it.isNotEmpty() }
        .toSortedMap()
}

private fun Variant<*>.extractDeclaredExternalDependencyDeclarations(): Set<DeclaredExternalDependency> {
    return declaredDependencyConfigurations
        .asSequence()
        .flatMap { configuration ->
            configuration.dependencies
                .filterIsInstance<ExternalDependency>()
                .asSequence()
                .map { dependency -> configuration to dependency }
        }
        .mapNotNull { (configuration, dependency) ->
            val nullableGroup: String? = dependency.group
            val group = nullableGroup?.takeUnless(String::isBlank) ?: return@mapNotNull null
            val version = dependency.version.orEmpty()
            DeclaredExternalDependency(
                configurationName = configuration.name,
                bucketName = configuration.declarationBucketName(),
                id = "$group:${dependency.name}:$version"
            )
        }
        .toSortedSet(compareBy<DeclaredExternalDependency> { declaration -> declaration.bucketName }
            .thenBy { declaration -> declaration.configurationName }
            .thenBy { declaration -> declaration.id })
}

private fun Variant<*>.extractDeclaredProjectDependencyIds(): Set<String> {
    return declaredDependencyConfigurations
        .asSequence()
        .flatMap { configuration ->
            configuration.dependencies
                .filterIsInstance<ProjectDependency>()
                .asSequence()
                .map { dependency ->
                    val targetConfiguration = dependency.targetConfiguration.orEmpty()
                    val excludeRules = dependency.extractExcludeRules()
                        .joinToString(prefix = "[", postfix = "]") { rule -> "${rule.group}:${rule.artifact}" }
                    "${configuration.name}->${dependency.dependencyProject.path}:$targetConfiguration:$excludeRules"
                }
        }
        .toSortedSet()
}

private fun Variant<*>.extractCompileOnlyDependenciesByShortId(): Map<String, ResolvedDependency> {
    return compileOnlyDeclaredDependencyConfigurations
        .asSequence()
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
            ResolvedDependency(
                id = "$group:$name:$version",
                version = version,
                shortId = "$group:$name",
                direct = true,
                dependencies = emptySet(),
                excludeRules = dependency.extractExcludeRules(),
                repository = DECLARED_DEPENDENCY_REPOSITORY,
                requiresJetifier = false
            )
        }
        .groupBy(ResolvedDependency::shortId)
        .mapValues { (_, duplicateDeclarations) ->
            duplicateDeclarations.reduce(::mergeDependencyMetadataByMaxVersion)
        }
        .toSortedMap()
}

private fun String.toDeclaredResolvedDependency(
    excludeRulesByShortId: Map<String, Set<ExcludeRule>>
): ResolvedDependency? {
    val chunks = split(":")
    if (chunks.size != 3) return null
    val (group, name, version) = chunks
    if (group.isBlank() || name.isBlank() || version.isBlank()) return null
    val shortId = "$group:$name"
    return ResolvedDependency
        .fromId("$group:$name:$version", DECLARED_DEPENDENCY_REPOSITORY)
        .copy(excludeRules = excludeRulesByShortId[shortId].orEmpty())
}

private fun mergeExcludeRulesByShortId(
    rulesByVariant: Iterable<Map<String, Set<ExcludeRule>>>
): Map<String, Set<ExcludeRule>> {
    return rulesByVariant.asSequence()
        .flatMap { rulesByShortId -> rulesByShortId.asSequence() }
        .groupBy({ it.key }, { it.value })
        .mapValues { (_, ruleSets) ->
            intersectExcludeRuleSets(ruleSets)
        }
        .filterValues { it.isNotEmpty() }
        .toSortedMap()
}

private fun intersectExcludeRuleSets(ruleSets: Iterable<Set<ExcludeRule>>): Set<ExcludeRule> {
    val iterator = ruleSets.iterator()
    if (!iterator.hasNext()) return emptySet()
    var result = iterator.next().toSortedSet(compareBy(ExcludeRule::toString))
    while (iterator.hasNext()) {
        result = result.intersectWith(iterator.next()).toSortedSet(compareBy(ExcludeRule::toString))
        if (result.isEmpty()) return emptySet()
    }
    return result
}

internal fun excludeRulesForDependency(
    excludeRulesByProjectPath: Map<String, ProjectExcludeRules>,
    rootProjectPath: String,
    rootExcludeRulesByShortId: Map<String, Set<ExcludeRule>>,
    ownerProjectPath: String?,
    ownerProjectVariantDisplayName: String?,
    shortId: String
): Set<ExcludeRule> {
    val ownerRules = ownerProjectPath
        ?.let { path ->
            excludeRulesByProjectPath[path]?.rulesFor(
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
