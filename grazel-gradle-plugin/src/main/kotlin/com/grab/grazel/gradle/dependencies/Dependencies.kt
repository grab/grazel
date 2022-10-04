/*
 * Copyright 2021 Grabtaxi Holdings PTE LTD (GRAB)
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
import com.grab.grazel.bazel.rules.MavenInstallArtifact
import com.grab.grazel.bazel.rules.MavenInstallArtifact.Exclusion.SimpleExclusion
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.AndroidVariantsExtractor
import com.grab.grazel.gradle.ConfigurationDataSource
import com.grab.grazel.gradle.ConfigurationScope
import com.grab.grazel.gradle.RepositoryDataSource
import com.grab.grazel.gradle.configurationScopes
import com.grab.grazel.util.GradleProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.internal.artifacts.DefaultResolvedDependency
import org.gradle.api.internal.artifacts.result.ResolvedComponentResultInternal
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TODO To remove this once test rules support is added
 */
private val DEFAULT_MAVEN_ARTIFACTS: List<MavenArtifact> = listOf(
    MavenArtifact("junit", "junit", "4.12"),
    MavenArtifact("org.mockito", "mockito-core", "3.4.6"),
    MavenArtifact("com.nhaarman", "mockito-kotlin", "1.6.0")
)

/**
 * Maven group names for artifacts that should be excluded from dependencies calculation everywhere.
 */
internal val DEP_GROUP_EMBEDDED_BY_RULES = listOf(
    "com.android.tools.build",
    "org.jetbrains.kotlin"
)

data class ExcludeRule(
    val group: String,
    val artifact: String
) {
    override fun toString(): String = "$group:$artifact"
}

/**
 * Simple data holder for a Maven artifact containing its group, name and version.
 */
internal data class MavenArtifact(
    val group: String?,
    val name: String?,
    val version: String? = null,
    val excludeRules: Set<ExcludeRule> = emptySet()
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
     * Returns the resolved artifacts dependencies for the given projects in the fully qualified Maven format.
     *
     * @param projects The list of projects for which the artifacts need to be resolved
     * @param overrideArtifactVersions List of fully qualified maven coordinates with versions that used for calculation
     *                                 instead of the one calculated automatically.
     *
     * @return List of artifacts in fully qualified Maven format
     */
    fun resolvedArtifactsFor(
        projects: List<Project>,
        overrideArtifactVersions: List<String> = emptyList()
    ): List<MavenInstallArtifact>

    /**
     * @return true if the project has any private dependencies in any configuration
     */
    fun hasDepsFromUnsupportedRepositories(project: Project): Boolean

    /**
     * Verify if the project has any dependencies that are meant to be ignored. For example, if the [Project] uses any
     * dependency that was excluded via [GrazelExtension] then this method will return `true`.
     *
     * @param project the project to check against.
     */
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
}

@Singleton
internal class DefaultDependenciesDataSource @Inject constructor(
    @param:RootProject private val rootProject: Project,
    private val grazelExtension: GrazelExtension,
    private val configurationDataSource: ConfigurationDataSource,
    private val artifactsConfig: ArtifactsConfig,
    private val repositoryDataSource: RepositoryDataSource,
    private val dependencyResolutionService: GradleProvider<DefaultDependencyResolutionService>,
    private val androidVariantsExtractor: AndroidVariantsExtractor
) : DependenciesDataSource {

    private val configurationScopes by lazy { grazelExtension.configurationScopes() }

    private val excludeArtifactsDenyList by lazy { grazelExtension.rules.mavenInstall.excludeArtifactsDenyList.get() }

    private val resolvedVersions: Map</* maven coord */ String,/* version */  String> by lazy {
        rootProject
            .subprojects
            .asSequence()
            .flatMap { it.firstLevelModuleDependencies() }
            .flatMap { (listOf(it) + it.children).asSequence() }
            .map { it.moduleGroup + ":" + it.moduleName to it.moduleVersion }
            .toMap()
    }

    // TODO Can be moved else where for clarity
    private val resolvedRepositories: Map</* maven coord */ String,/* repo name */  String> by lazy {
        rootProject
            .subprojects
            .asSequence()
            .flatMap { it.externalResolvedDependencies().asSequence() }
            .filter { it.repositoryName != null && it.moduleVersion != null }
            .map { componentResult ->
                val moduleVersion = componentResult.moduleVersion
                val id = moduleVersion!!.group + ":" + moduleVersion.name
                id to componentResult.repositoryName!!
            }.distinct()
            .toMap()
    }

    private fun Project.buildGraphTypes() =
        configurationScopes.flatMap { configurationScope ->
            androidVariantsExtractor.getVariants(this).map { variant ->
                BuildGraphType(configurationScope, variant)
            }
        }

    /**
     * Given a group, name and version will update version with following properties
     * * Overridden version by user
     * * Resolved version by Gradle
     * * Declared version in buildscript.
     */
    private fun correctArtifactVersion(
        mavenArtifact: MavenArtifact,
        overrideArtifactVersions: Map<String, String> = emptyMap()
    ): MavenArtifact {
        // To correctly calculate the actual used version, we map the version from resolvedVersions since
        // resolvedVersions would contain the actual resolved dependency version (respecting resolution strategy)
        // instead of the ones declared in a project's build file
        // Additionally we also check if user needs to override the version via overrideArtifactVersions and use
        // that if found
        val id = "${mavenArtifact.group}:${mavenArtifact.name}"
        val newVersion = overrideArtifactVersions[id]
            ?: resolvedVersions[id]
            ?: mavenArtifact.version
        return mavenArtifact.copy(version = newVersion)
    }

    /**
     * @return `true` when the `MavenArtifact` is present is ignored by user.
     */
    private val MavenArtifact.isIgnored get() = artifactsConfig.ignoredList.contains(id)

    /**
     * @return `true` when the `MavenArtifact` is present is excluded by user.
     */
    private val MavenArtifact.isExcluded get() = artifactsConfig.excludedList.contains(id)

    override fun resolvedArtifactsFor(
        projects: List<Project>,
        overrideArtifactVersions: List<String>
    ): List<MavenInstallArtifact> {
        // Prepare override versions map
        val overrideArtifactVersionMap = overrideArtifactVersions.associate { mavenCoordinate ->
            try {
                val chunks = mavenCoordinate.split(":")
                chunks.first() + ":" + chunks[1] to chunks[2]
            } catch (e: IndexOutOfBoundsException) {
                error("$mavenCoordinate is not a proper maven coordinate, please ensure version is correctly specified")
            }
        }

        // Filter out configurations we are interested in.
        val configurations = projects
            .asSequence()
            .flatMap { project ->
                configurationDataSource.configurations(
                    project,
                    *configurationScopes
                )
            }
            .toList()

        // Calculate all the external artifacts
        val externalArtifacts = configurations.asSequence()
            .flatMap { it.dependencies.asSequence() }
            .filter { it.group != null }
            .filterIsInstance<ExternalDependency>()
            .map { dependency -> dependency.toMavenArtifact() }


        // Collect all forced versions
        // (Perf fix) - collecting all projects' forced modules is costly, hence take the first sub project
        // TODO Provide option to consider all forced versions backed by a flag.
        val forcedVersions = sequenceOf(rootProject.subprojects.first())
            .flatMap { project ->
                configurationDataSource.configurations(
                    project,
                    *configurationScopes
                )
            }
            .let(::collectForcedVersions)

        return (DEFAULT_MAVEN_ARTIFACTS + externalArtifacts + forcedVersions)
            .groupBy { it.id }
            .map { (_, mavenArtifacts) ->
                // Merge all exclude rules so that we have a cumulative set
                mavenArtifacts
                    .first()
                    .copy(
                        excludeRules = mavenArtifacts
                            .flatMap(MavenArtifact::excludeRules)
                            .toSet()
                    )
            }.asSequence()
            .filter { mavenArtifact ->
                // Only allow dependencies from supported repositories
                mavenArtifact.isFromSupportedRepository(repositoryDataSource)
            }.filter { mavenArtifact ->
                // Don't include artifacts that are excluded or included
                !mavenArtifact.isIgnored && !mavenArtifact.isExcluded
            }.map { mavenArtifact ->
                // Fix the artifact version as per resolvedVersions or overrideVersions
                correctArtifactVersion(
                    mavenArtifact = mavenArtifact,
                    overrideArtifactVersions = overrideArtifactVersionMap
                )
            }.map(MavenArtifact::toMavenInstallArtifact)
            .toList()
    }

    override fun hasDepsFromUnsupportedRepositories(project: Project): Boolean {
        return project
            .externalResolvedDependencies()
            .mapNotNull(ResolvedComponentResultInternal::getRepositoryName)
            .any { repoName -> repositoryDataSource.unsupportedRepositoryNames.contains(repoName) }
    }

    override fun hasIgnoredArtifacts(project: Project): Boolean {
        return project.firstLevelModuleDependencies()
            .flatMap { (listOf(it) + it.children).asSequence() }
            .filter { !DEP_GROUP_EMBEDDED_BY_RULES.contains(it.moduleGroup) }
            .any { MavenArtifact(it.moduleGroup, it.moduleName).isIgnored }
    }

    override fun mavenDependencies(
        project: Project,
        vararg buildGraphTypes: BuildGraphType
    ): Sequence<Dependency> =
        declaredDependencies(project, *buildGraphTypes.map { it.configurationScope }.toTypedArray())
            .filter { (configuration, _) ->
                if (buildGraphTypes.isEmpty()) {
                    true
                } else {
                    configurationDataSource.isThisConfigurationBelongsToThisVariants(
                        project,
                        *buildGraphTypes.map { it.variant }.toTypedArray(),
                        configuration = configuration
                    )
                }
            }
            .map { it.second }
            .filter { it.group != null && !DEP_GROUP_EMBEDDED_BY_RULES.contains(it.group) }
            .filter {
                val artifact = MavenArtifact(it.group, it.name)
                !artifact.isExcluded && !artifact.isIgnored
            }.filter { it !is ProjectDependency }

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

        fun add(defaultResolvedDependency: DefaultResolvedDependency) {
            defaultResolvedDependency.moduleArtifacts
                .firstOrNull()
                ?.file
                ?.let { file ->
                    if (fileExtension == null || file.extension == fileExtension) {
                        results.getOrPut(defaultResolvedDependency.toMavenArtifact()) { file }
                    }
                }
        }
        rootProject.subprojects
            .flatMap { it.firstLevelModuleDependencies() }
            .onEach(::add)
            // It would be easier to just flatMapTo(set) but we are dealing with graphs and we need
            // to avoid unnecessary recalculation
            .forEach { defaultResolvedDependency ->
                defaultResolvedDependency
                    .outgoingEdges // will get all transitives of this artifact
                    .filterIsInstance<DefaultResolvedDependency>()
                    .forEach(::add)
            }
        return results
    }

    /**
     * Resolves all the external dependencies for the given project. By resolving all the dependencies, we get accurate
     * dependency information that respects resolution strategy, substitution and any other modification by Gradle apart
     * from `build.gradle` definition aka first level module dependencies.
     */
    private fun Project.externalResolvedDependencies() = dependencyResolutionService.get()
        .resolve(
            project = this,
            configurations = configurationDataSource.resolvedConfigurations(
                this,
                *buildGraphTypes().toTypedArray()
            )
        )

    /**
     * @return true if the [MavenArtifact] was fetched from a supported repository
     */
    private fun MavenArtifact.isFromSupportedRepository(
        repositoryDataSource: RepositoryDataSource
    ) = resolvedRepositories.containsKey(id) && !repositoryDataSource
        .unsupportedRepositoryNames
        .contains(resolvedRepositories.getValue(id))

    /**
     * Collects first level module dependencies from their resolved configuration. Additionally, excludes any artifacts
     * that are not meant to be used in Bazel as defined by [DEP_GROUP_EMBEDDED_BY_RULES]
     *
     * @return Sequence of [DefaultResolvedDependency] in the first level
     */
    private fun Project.firstLevelModuleDependencies(): Sequence<DefaultResolvedDependency> {
        return configurationDataSource.resolvedConfigurations(
            this,
            *buildGraphTypes().toTypedArray()
        )
            .map { it.resolvedConfiguration.lenientConfiguration }
            .flatMap {
                try {
                    it.firstLevelModuleDependencies.asSequence()
                } catch (e: Exception) {
                    sequenceOf<ResolvedDependency>()
                }
            }.filterIsInstance<DefaultResolvedDependency>()
            .filter { !DEP_GROUP_EMBEDDED_BY_RULES.contains(it.moduleGroup) }
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

    /**
     * Collects any custom resolution strategy (particularly forced modules) defined in the given `configurations`
     *
     * @return Gradle's forced modules artifacts parsed to `MavenArtifact`.
     */
    private fun collectForcedVersions(
        configurations: Sequence<Configuration>
    ): Sequence<MavenArtifact> = mutableMapOf<MavenArtifact, String>().apply {
        configurations.asSequence()
            .flatMap { it.resolutionStrategy.forcedModules.asSequence() }
            .forEach { mvSelector ->
                val key = MavenArtifact(mvSelector.group, mvSelector.name, mvSelector.version)
                put(key, key.id)
            }
    }.keys.asSequence()

    /**
     * Map [ExternalDependency] to [MavenArtifact] with relevant details like exclude rules.
     */
    private fun ExternalDependency.toMavenArtifact(): MavenArtifact {
        return MavenArtifact(
            group = group,
            name = name,
            version = version,
            excludeRules = excludeRules
                .asSequence()
                .map {
                    @Suppress("USELESS_ELVIS") // Gradle lying, module can be null
                    ExcludeRule(it.group, it.module ?: "")
                }
                .filterNot { it.artifact.isNullOrBlank() }
                .filterNot { excludeArtifactsDenyList.contains(it.toString()) }
                .toSet()
        )
    }

    private fun DefaultResolvedDependency.toMavenArtifact() = MavenArtifact(
        group = moduleGroup,
        name = moduleName,
        version = moduleVersion,
    )
}

internal fun MavenArtifact.toMavenInstallArtifact(): MavenInstallArtifact {
    return when {
        excludeRules.isEmpty() -> MavenInstallArtifact.SimpleArtifact(toString())
        else -> MavenInstallArtifact.DetailedArtifact(
            group = group!!,
            artifact = name!!,
            version = version!!,
            exclusions = excludeRules.map { SimpleExclusion("${it.group}:${it.artifact}") }
        )
    }
}