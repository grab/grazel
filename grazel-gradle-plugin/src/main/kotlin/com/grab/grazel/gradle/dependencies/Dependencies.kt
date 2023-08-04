/*
 * Copyright 2022 Grabtaxi Holdings PTE LTD (GRAB)
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

import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.rules.ANDROIDX_GROUP
import com.grab.grazel.bazel.rules.ANNOTATION_ARTIFACT
import com.grab.grazel.bazel.rules.DAGGER_GROUP
import com.grab.grazel.bazel.rules.DATABINDING_GROUP
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.BazelDependency.StringDependency
import com.grab.grazel.gradle.ConfigurationDataSource
import com.grab.grazel.gradle.ConfigurationScope
import com.grab.grazel.gradle.ConfigurationScope.TEST
import com.grab.grazel.gradle.configurationScopes
import com.grab.grazel.gradle.hasDatabinding
import com.grab.grazel.gradle.variant.AndroidVariant
import com.grab.grazel.gradle.variant.AndroidVariantsExtractor
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.TEST_VARIANT
import com.grab.grazel.gradle.variant.Variant
import com.grab.grazel.gradle.variant.VariantBuilder
import com.grab.grazel.gradle.variant.isConfigScope
import com.grab.grazel.gradle.variant.isTest
import com.grab.grazel.gradle.variant.migratableConfigurations
import com.grab.grazel.util.GradleProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.internal.artifacts.DefaultResolvedDependency
import java.io.File
import java.util.TreeSet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maven group names for artifacts that should be excluded from dependencies calculation everywhere.
 */
internal val IGNORED_ARTIFACT_GROUPS = listOf(
    "com.android.tools.build",
    "org.jetbrains.kotlin"
)

/**
 * Simple data holder for a Maven artifact containing its group, name and version.
 */
internal data class MavenArtifact(
    val group: String?,
    val name: String?,
    val version: String? = null,
) {
    val id get() = "$group:$name"
    override fun toString() = "$group:$name:$version"
}

internal data class ArtifactsConfig(
    val excludedList: List<String> = emptyList(),
    val ignoredList: List<String> = emptyList()
)

internal interface DependenciesDataSource {
    /**
     * Return the project's maven dependencies before the resolution strategy and any other custom substitution by Gradle
     */
    fun mavenDependencies(
        project: Project,
        vararg buildGraphTypes: BuildGraphType
    ): Sequence<Dependency>

    /**
     * Return the project's project (module) dependencies before the resolution strategy and any other custom
     * substitutions by Gradle
     */
    fun projectDependencies(
        project: Project,
        vararg scopes: ConfigurationScope
    ): Sequence<Pair<Configuration, ProjectDependency>>

    /**
     * @return true if the project has any private dependencies in any configuration
     */
    @Deprecated("No longer supported")
    fun hasDepsFromUnsupportedRepositories(project: Project): Boolean

    /**
     * Verify if the project has any dependencies that are meant to be ignored. For example, if the [Project] uses any
     * dependency that was excluded via [GrazelExtension] then this method will return `true`.
     *
     * @param project the project to check against.
     */
    @Deprecated("No longer supported")
    fun hasIgnoredArtifacts(project: Project): Boolean

    /**
     * Returns map of [MavenArtifact] and the corresponding artifact file (aar or jar). Guarantees the
     * returned file is downloaded and available on disk
     *
     * @param rootProject The root project instance
     * @param fileExtension The file extension to look for. Use this to reduce the overall number of
     * values returned
     */
    fun dependencyArtifactMap(
        rootProject: Project,
        fileExtension: String? = null
    ): Map<MavenArtifact, File>

    /**
     * Non project dependencies for the given [buildGraphType]
     */
    fun collectMavenDeps(
        project: Project,
        buildGraphType: BuildGraphType
    ): List<BazelDependency>
}

@Singleton
internal class DefaultDependenciesDataSource @Inject constructor(
    private val grazelExtension: GrazelExtension,
    private val configurationDataSource: ConfigurationDataSource,
    private val artifactsConfig: ArtifactsConfig,
    private val dependencyResolutionService: GradleProvider<DefaultDependencyResolutionService>,
    private val androidVariantsExtractor: AndroidVariantsExtractor,
    private val variantBuilder: VariantBuilder,
) : DependenciesDataSource {

    private val configurationScopes by lazy { grazelExtension.configurationScopes() }

    private fun Project.buildGraphTypes() =
        configurationScopes.flatMap { configurationScope ->
            androidVariantsExtractor.getVariants(this).map { variant ->
                BuildGraphType(configurationScope, variant)
            }
        }

    /**
     * @return `true` when the `MavenArtifact` is present is ignored by user.
     */
    private val MavenArtifact.isIgnored get() = artifactsConfig.ignoredList.contains(id)

    /**
     * @return `true` when the `MavenArtifact` is present is excluded by user.
     */
    private val MavenArtifact.isExcluded get() = artifactsConfig.excludedList.contains(id)

    override fun hasDepsFromUnsupportedRepositories(project: Project): Boolean {
        return false
    }

    override fun hasIgnoredArtifacts(project: Project): Boolean {
        return project.firstLevelModuleDependencies()
            .flatMap { (listOf(it) + it.children).asSequence() }
            .filter { it.moduleGroup !in IGNORED_ARTIFACT_GROUPS }
            .any { MavenArtifact(it.moduleGroup, it.moduleName).isIgnored }
    }

    override fun mavenDependencies(
        project: Project,
        vararg buildGraphTypes: BuildGraphType
    ): Sequence<Dependency> {
        return declaredDependencies(
            project,
            *buildGraphTypes.map { it.configurationScope }.toTypedArray()
        ).filter { (configuration, _) ->
            if (buildGraphTypes.isEmpty()) {
                true
            } else {
                configurationDataSource.isThisConfigurationBelongsToThisVariants(
                    project,
                    *buildGraphTypes.map { it.variant }.toTypedArray(),
                    configuration = configuration
                )
            }
        }.map { it.second }
            .filter { it.group != null && it.group !in IGNORED_ARTIFACT_GROUPS }
            .filter {
                val artifact = MavenArtifact(it.group, it.name)
                !artifact.isExcluded && !artifact.isIgnored
            }.filter { it !is ProjectDependency }
    }

    override fun projectDependencies(
        project: Project, vararg scopes: ConfigurationScope
    ) = declaredDependencies(project, *scopes)
        .filter { it.second is ProjectDependency }
        .map { it.first to it.second as ProjectDependency }

    override fun dependencyArtifactMap(
        rootProject: Project,
        fileExtension: String?
    ): Map<MavenArtifact, File> {
        val results = mutableMapOf<MavenArtifact, File>()
        rootProject.subprojects
            .asSequence()
            .flatMap { project ->
                variantBuilder.build(project)
                    .asSequence()
                    .filterIsInstance<AndroidVariant>()
                    .filter { !it.variantType.isTest }
            }.flatMap { it.compileConfiguration }
            .flatMapTo(TreeSet(compareBy { it.id.toString() })) { configuration ->
                configuration
                    .incoming
                    .artifactView {
                        isLenient = true
                        componentFilter { identifier -> identifier is ModuleComponentIdentifier }
                    }.artifacts
            }.asSequence()
            .filter { it.file.extension == fileExtension }
            .forEach { artifactResult ->
                val artifact = artifactResult.id.componentIdentifier as ModuleComponentIdentifier
                results.getOrPut(
                    MavenArtifact(
                        group = artifact.group,
                        name = artifact.module,
                        version = artifact.version,
                    )
                ) { artifactResult.file }
            }
        return results
    }

    override fun collectMavenDeps(
        project: Project,
        buildGraphType: BuildGraphType
    ): List<BazelDependency> {
        val variants = variantBuilder.build(project).groupBy(Variant<*>::name)
        val inputVariant = buildGraphType.variant
        // From input variant map to Grazel variant
        val grazelVariant: Variant<*> = when {
            inputVariant != null -> variants[inputVariant.name]!!.first {
                it.variantType.isConfigScope(project, buildGraphType.configurationScope)
            }
            // Input variant is null, probably legacy code path assumes non android project has no
            // variants. To compensate, map it to `default` or `test` based on
            // BuildGraphType.configurationScope
            // This will no longer needed after migrating legacy code path to use variants
            else -> when (buildGraphType.configurationScope) {
                TEST -> variants[TEST_VARIANT]!!.first()
                else -> variants[DEFAULT_VARIANT]!!.first()
            }
        }

        return grazelVariant.migratableConfigurations
            .asSequence()
            .flatMap { it.allDependencies.filterIsInstance<ExternalDependency>() }
            .filter { it.group !in IGNORED_ARTIFACT_GROUPS }
            .filter {
                val artifact = MavenArtifact(it.group, it.name)
                !artifact.isExcluded && !artifact.isIgnored
            }.filter {
                if (project.hasDatabinding) {
                    it.group != DATABINDING_GROUP && (it.group != ANDROIDX_GROUP && it.name != ANNOTATION_ARTIFACT)
                } else true
            }.map { dependency ->
                val variantHierarchy = buildSet {
                    add(grazelVariant.name)
                    addAll(grazelVariant.extendsFrom.reversed())
                }
                when (dependency.group) {
                    DAGGER_GROUP -> StringDependency("//:dagger")
                    else -> dependencyResolutionService.get().get(
                        variants = variantHierarchy,
                        group = dependency.group,
                        name = dependency.name
                    ) ?: run {
                        error("$dependency cant be found for migrating ${project.name}")
                    }
                }
            }.distinct()
            .toList()
    }

    /**
     * Collects first level module dependencies from their resolved configuration. Additionally, excludes any artifacts
     * that are not meant to be used in Bazel as defined by [IGNORED_ARTIFACT_GROUPS]
     *
     * @return Sequence of [DefaultResolvedDependency] in the first level
     */
    private fun Project.firstLevelModuleDependencies(
        buildGraphTypes: List<BuildGraphType> = buildGraphTypes()
    ): Sequence<DefaultResolvedDependency> {
        return configurationDataSource
            .resolvedConfigurations(
                project = this,
                buildGraphTypes = buildGraphTypes.toTypedArray()
            ).map { it.resolvedConfiguration.lenientConfiguration }
            .flatMap {
                try {
                    it.firstLevelModuleDependencies.asSequence()
                } catch (e: Exception) {
                    sequenceOf<ResolvedDependency>()
                }
            }.filterIsInstance<DefaultResolvedDependency>()
            .filter { it.moduleGroup !in IGNORED_ARTIFACT_GROUPS }
    }

    internal fun firstLevelModuleDependencies(project: Project) =
        project.firstLevelModuleDependencies()

    /**
     * Collects dependencies from all available configuration in the pre-resolution state i.e without dependency resolutions.
     * These dependencies would be ideally used for sub targets instead of `WORKSPACE` file since they closely mirror what
     * was defined in `build.gradle` file.
     *
     * @return Sequence of `Configuration` and `Dependency`
     */
    private fun declaredDependencies(
        project: Project,
        vararg scopes: ConfigurationScope
    ): Sequence<Pair<Configuration, Dependency>> {
        return configurationDataSource.configurations(project, *scopes)
            .flatMap { configuration ->
                configuration
                    .dependencies
                    .asSequence()
                    .map { dependency -> configuration to dependency }
            }
    }
}