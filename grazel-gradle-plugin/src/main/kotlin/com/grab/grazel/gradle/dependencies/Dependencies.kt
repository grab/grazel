/*
 * Copyright 2021 Grabtaxi Holdings PTE LTE (GRAB)
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
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.ConfigurationDataSource
import com.grab.grazel.gradle.RepositoryDataSource
import com.grab.grazel.util.GradleProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.internal.artifacts.DefaultResolvedDependency
import org.gradle.api.internal.artifacts.result.ResolvedComponentResultInternal
import javax.inject.Inject
import javax.inject.Singleton

@Module(includes = [DependenciesBinder::class])
internal object DependenciesModule {

    @Provides
    @Singleton
    fun GrazelExtension.provideArtifactsConfig(): ArtifactsConfig = toArtifactsConfig()

    @Singleton
    @Provides
    fun dependencyResolutionCacheService(
        @RootProject rootProject: Project
    ): GradleProvider<@JvmSuppressWildcards DefaultDependencyResolutionService> =
        DefaultDependencyResolutionService.register(rootProject)
}

@Module
internal interface DependenciesBinder {
    @Binds
    fun DefaultDependenciesDataSource.dependenciesDataSource(): DependenciesDataSource
}

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

/**
 * Simple data holder for a Maven artifact containing it's group, name and version.
 */
internal data class MavenArtifact(
    val group: String?,
    val name: String?,
    val version: String? = null
) {
    val id get() = "$group:$name"
    override fun toString() = "$group:$name:$version"
}

internal data class ArtifactsConfig(
    val excludedList: List<String> = emptyList(),
    val ignoredList: List<String> = emptyList()
)

private fun GrazelExtension.toArtifactsConfig() = ArtifactsConfig(
    excludedList = rulesConfiguration.mavenInstall.excludeArtifacts.get(),
    ignoredList = dependenciesConfiguration.ignoreArtifacts.get()
)

internal interface DependenciesDataSource {
    /**
     * The actual resolved versions of dependencies calculated by Gradle respecting the resolution strategy and any other
     * custom substitution by gradle.
     *
     * The key is the `id` (group:name) of the artifact and value is the `version`.
     */
    val resolvedVersions: Map<String, String>

    /**
     * Map of artifact id to which repo it was resolved from
     */
    val resolvedRepo: Map<String, String>

    /**
     * Return the project's maven dependencies before the resolution strategy and any other custom substitution by gradle
     */
    fun mavenDependencies(project: Project): Sequence<Dependency>

    /**
     * Return the project's project (module) dependencies before the resolution strategy and any other custom substitution by gradle
     */
    fun projectDependencies(project: Project): Sequence<Pair<Configuration, ProjectDependency>>

    /**
     * Resolves all the external dependencies for the given project. By resolving all the dependencies, we get accurate
     * dependency information that respects resolution strategy, substitution and any other modification by gradle apart
     * from `build.gradle` definition aka first level module dependencies.
     */
    fun Project.externalResolvedDependencies(): List<ResolvedComponentResultInternal>

    /**
     * Returns the resolved artifacts dependencies for the given projects in the fully qualified Maven format.
     *
     * @param projects The list of projects for which the artifacts need to be resolved
     * @param repositoryDataSource The `RepositoryDataSource` instance that should be used to resolve the dependencies
     * @param overrideArtifactVersions List of fully qualified maven coordinates with versions that used for calculation
     *                                 instead of the one calculated automatically.
     *
     * @return List of artifacts in fully qualified Maven format
     */
    fun resolvedArtifactsFor(
        projects: List<Project>,
        overrideArtifactVersions: List<String> = emptyList()
    ): List<String>

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
}

@Singleton
internal class DefaultDependenciesDataSource @Inject constructor(
    @param:RootProject private val rootProject: Project,
    private val configurationDataSource: ConfigurationDataSource,
    private val artifactsConfig: ArtifactsConfig,
    private val repositoryDataSource: RepositoryDataSource,
    private val dependencyResolutionService: GradleProvider<DefaultDependencyResolutionService>
) : DependenciesDataSource {

    override val resolvedVersions: Map<String, String> by lazy {
        rootProject
            .subprojects
            .asSequence()
            .flatMap { it.firstLevelModuleDependencies() }
            .flatMap { (listOf(it) + it.children).asSequence() }
            .map { it.moduleGroup + ":" + it.moduleName to it.moduleVersion }
            .toMap()
    }

    // TODO Can be moved else where for clarity
    override val resolvedRepo: Map<String, String> by lazy {
        rootProject
            .subprojects
            .asSequence()
            .flatMap { it.externalResolvedDependencies().asSequence() }
            .filter { it.repositoryName != null && it.moduleVersion != null }
            .mapNotNull { componentResult ->
                val moduleVersion = componentResult.moduleVersion
                val id = moduleVersion!!.group + ":" + moduleVersion.name
                id to componentResult.repositoryName!!
            }.distinct()
            .toMap()
    }

    private fun Project.resolvableConfigurations(): Sequence<Configuration> =
        configurationDataSource.resolvedConfigurations(this)

    /**
     * Given a group, name and version will update version with following property
     * * Overriden version by user
     * * Resolved version by Gradle
     * * Else declared version in buildscript.
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
        val newVersion = overrideArtifactVersions[id] ?: resolvedVersions[id] ?: mavenArtifact.version
        return mavenArtifact.copy(version = newVersion)
    }

    override fun resolvedArtifactsFor(
        projects: List<Project>,
        overrideArtifactVersions: List<String>
    ): List<String> {
        // Prepare override versions map
        val overrideArtifactVersionMap = overrideArtifactVersions.map { mavenCoordinate ->
            try {
                val chunks = mavenCoordinate.split(":")
                chunks.first() + ":" + chunks[1] to chunks[2]
            } catch (e: IndexOutOfBoundsException) {
                error("$mavenCoordinate is not a proper maven coordinate, please ensure version is correctly specified")
            }
        }.toMap()

        // Filter out configurations we are interested in.
        val configurations = projects
            .asSequence()
            .flatMap(configurationDataSource::configurations)
            .toList()

        // Calculate all the external artifacts
        val externalArtifacts = configurations.asSequence()
            .flatMap { it.dependencies.asSequence() }
            .filter { it.group != null }
            .filter { it !is ProjectDependency } // Filter out internal dependencies
            .map { dependency ->
                MavenArtifact(dependency.group, dependency.name, dependency.version)
            }

        // Collect all forced versions
        // (Perf fix) - collecting all projects' forced modules is costly, hence take the first sub project
        // TODO Provide option to consider all forced versions backed by a flag.
        val forcedVersions = sequenceOf(rootProject.subprojects.first())
            .flatMap(configurationDataSource::configurations)
            .let(::collectForcedVersions)

        return (DEFAULT_MAVEN_ARTIFACTS + externalArtifacts + forcedVersions)
            .asSequence()
            .distinctBy(MavenArtifact::id)
            .filter { mavenArtifact ->
                // Only allow dependencies from supported repositories
                mavenArtifact.isFromSupportedRepository(repositoryDataSource)
            }.filter { mavenArtifact ->
                // Don't include artifacts that are excluded or included
                !artifactsConfig.excludedList.contains(mavenArtifact.id)
                        && !artifactsConfig.ignoredList.contains(mavenArtifact.id)
            }.map { mavenArtifact ->
                // Fix the artifact version as per resolvedVersions or overrideVersions
                correctArtifactVersion(
                    mavenArtifact = mavenArtifact,
                    overrideArtifactVersions = overrideArtifactVersionMap
                )
            }.map(MavenArtifact::toString)
            .sorted()
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
            .any {
                artifactsConfig.ignoredList.contains(MavenArtifact(it.moduleGroup, it.moduleName).id)
            }
    }

    override fun mavenDependencies(project: Project) = declaredDependencies(project)
        .map { it.second }
        .filter { it.group != null && !DEP_GROUP_EMBEDDED_BY_RULES.contains(it.group) }
        .filter {
            val artifact = MavenArtifact(it.group, it.name).id
            !artifactsConfig.ignoredList.contains(artifact)
                    && !artifactsConfig.excludedList.contains(artifact)
        }
        .filter { it !is ProjectDependency }

    override fun projectDependencies(project: Project) = declaredDependencies(project)
        .filter { it.second is ProjectDependency }
        .map { it.first to it.second as ProjectDependency }

    override fun Project.externalResolvedDependencies() = dependencyResolutionService.get()
        .resolve(
            project = this,
            configurations = resolvableConfigurations()
        )

    /**
     * @return true if the [MavenArtifact] was fetched from a supported repository
     */
    private fun MavenArtifact.isFromSupportedRepository(
        repositoryDataSource: RepositoryDataSource
    ) = resolvedRepo.containsKey(id) && !repositoryDataSource
        .unsupportedRepositoryNames
        .contains(resolvedRepo.getValue(id))

    /**
     * Collects first level module dependencies from their resolved configuration. Additionally excludes any artifacts
     * that are not meant to be used in Bazel as defined by [DEP_GROUP_EMBEDDED_BY_RULES]
     *
     * @return Sequence of [DefaultResolvedDependency] in the first level
     */
    private fun Project.firstLevelModuleDependencies(): Sequence<DefaultResolvedDependency> {
        return resolvableConfigurations()
            .map(Configuration::getResolvedConfiguration)
            .flatMap {
                try {
                    it.firstLevelModuleDependencies.asSequence()
                } catch (e: Exception) {
                    sequenceOf<ResolvedDependency>()
                }
            }.filterIsInstance<DefaultResolvedDependency>()
            .filter { !DEP_GROUP_EMBEDDED_BY_RULES.contains(it.moduleGroup) }
    }

    internal fun firstLevelModuleDependencies(project: Project) = project.firstLevelModuleDependencies()

    /**
     * Collects dependencies from all available configuration in the pre-resolution state i.e without dependency resolutions.
     * These dependencies would be ideally used for sub targets instead of `WORKSPACE` file since they closely mirror what
     * was defined in `build.gradle` file.
     *
     * @return Sequence of `Configuration` and `Dependency`
     */
    private fun declaredDependencies(project: Project): Sequence<Pair<Configuration, Dependency>> {
        return configurationDataSource.configurations(project)
            .flatMap { configuration ->
                configuration
                    .dependencies
                    .asSequence()
                    .map { configuration to it }
            }
    }

    /**
     * Collects any custom resolution strategy (particularly forced modules) defined in the given `configurations`
     *
     * @return Gradle's forced modules artifacts parsed to `MavenArtifact`.
     */
    private fun collectForcedVersions(configurations: Sequence<Configuration>): Sequence<MavenArtifact> {
        return mutableMapOf<MavenArtifact, String>().apply {
            configurations.asSequence()
                .flatMap { it.resolutionStrategy.forcedModules.asSequence() }
                .forEach { mvSelector ->
                    val key = MavenArtifact(
                        mvSelector.group,
                        mvSelector.name,
                        mvSelector.version
                    )
                    put(key, key.id)
                }
        }.keys.asSequence()
    }
}