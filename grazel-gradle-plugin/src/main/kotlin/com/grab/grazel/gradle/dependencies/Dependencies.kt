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

import com.grab.grazel.bazel.rules.ANDROIDX_GROUP
import com.grab.grazel.bazel.rules.ANNOTATION_ARTIFACT
import com.grab.grazel.bazel.rules.DAGGER_GROUP
import com.grab.grazel.bazel.rules.DATABINDING_GROUP
import com.grab.grazel.bazel.rules.KspProcessor
import com.grab.grazel.bazel.rules.kspPluginTarget
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.bazel.starlark.BazelDependency.StringDependency
import com.grab.grazel.gradle.ConfigurationDataSource
import com.grab.grazel.gradle.hasDatabinding
import com.grab.grazel.gradle.hasKotlinAndroidExtensions
import com.grab.grazel.gradle.isAndroidTest
import com.grab.grazel.gradle.variant.AndroidVariant
import com.grab.grazel.gradle.variant.ANDROID_TEST_VARIANT
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.LINT_VARIANT
import com.grab.grazel.gradle.variant.Variant
import com.grab.grazel.gradle.variant.VariantBuilder
import com.grab.grazel.gradle.variant.VariantGraphKey
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.variant.id
import com.grab.grazel.gradle.variant.isTest
import com.grab.grazel.gradle.variant.migratableConfigurations
import com.grab.grazel.migrate.dependencies.toMavenRepoName
import com.grab.grazel.util.GradleProvider
import com.grab.grazel.util.addTo
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.internal.artifacts.DefaultResolvedDependency
import java.io.File
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maven group names for artifacts that should be excluded from dependencies calculation everywhere.
 */
internal val IGNORED_ARTIFACT_GROUPS = listOf(
    "com.android.tools.build",
    "org.jetbrains.kotlin"
)

internal val PARCELIZE_DEPS = listOf(
    MavenDependency(group = "org.jetbrains.kotlin", name = "kotlin-parcelize-runtime"),
    MavenDependency(group = "org.jetbrains.kotlin", name = "kotlin-android-extensions-runtime"),
)

private val DATABINDING_ARTIFACT_GROUPS = setOf(
    DATABINDING_GROUP,
    "com.android.databinding",
)

private val MavenArtifact.isDatabindingProvidedArtifact: Boolean
    get() = group in DATABINDING_ARTIFACT_GROUPS ||
        (group == ANDROIDX_GROUP && name == ANNOTATION_ARTIFACT)

private const val AUTO_SERVICE_GROUP = "com.google.auto.service"
private const val AUTO_SERVICE_ARTIFACT = "auto-service"
private const val AUTO_SERVICE_TARGET = "@grab_bazel_common//third_party/auto-service"

/** Simple data holder for a Maven artifact containing its group, name and version. */
internal data class MavenArtifact(
    val group: String?,
    val name: String?,
    val version: String? = null,
) : Comparable<MavenArtifact> {
    val id get() = "$group:$name"

    override fun compareTo(other: MavenArtifact): Int {
        return toString().compareTo(other.toString())
    }

    override fun toString() = "$group:$name:$version"
}

internal data class ArtifactsConfig(
    val excludedList: List<String> = emptyList(),
    val ignoredList: List<String> = emptyList()
)

internal interface DependenciesDataSource {
    /**
     * Return the project's project (module) dependencies filtered by VariantType.
     */
    fun projectDependencies(
        project: Project,
        vararg variantTypes: VariantType
    ): Sequence<Pair<Configuration, ProjectDependency>>

    /**
     * Returns map of [MavenArtifact] and the corresponding artifact file (aar or jar). Guarantees
     * the returned file is downloaded and available on disk
     *
     * @param rootProject The root project instance
     * @param fileExtension The file extension to look for. Use this to reduce the overall number of
     *    values returned
     */
    fun dependencyArtifactMap(
        rootProject: Project,
        fileExtension: String? = null
    ): Map<MavenArtifact, File>

    /** Non project dependencies for the given [variantKey]. */
    fun collectMavenDeps(
        project: Project,
        variantKey: VariantGraphKey,
        preferredVariantNames: List<String> = emptyList()
    ): List<BazelDependency>

    /** Collects all transitive maven dependencies for the given [variantKey] */
    fun collectTransitiveMavenDeps(
        project: Project,
        variantKey: VariantGraphKey
    ): Set<MavenDependency>

    /**
     * Collects KSP plugin dependencies for the given [variantKey].
     * Returns BazelDependency references to root-level kt_ksp_plugin targets.
     */
    fun collectKspPluginDeps(project: Project, variantKey: VariantGraphKey): List<BazelDependency>
}

@Singleton
internal class DefaultDependenciesDataSource @Inject constructor(
    private val configurationDataSource: ConfigurationDataSource,
    private val artifactsConfig: ArtifactsConfig,
    private val dependencyResolutionService: GradleProvider<DefaultDependencyResolutionService>,
    private val variantBuilder: VariantBuilder,
) : DependenciesDataSource {

    /** @return `true` when the `MavenArtifact` is present is ignored by user. */
    private val MavenArtifact.isIgnored get() = artifactsConfig.ignoredList.contains(id)

    /** @return `true` when the `MavenArtifact` is present is excluded by user. */
    private val MavenArtifact.isExcluded get() = artifactsConfig.excludedList.contains(id)

    private val MavenArtifact.isBom: Boolean get() = name.isBomArtifactName()

    override fun projectDependencies(
        project: Project, vararg variantTypes: VariantType
    ) = declaredDependencies(project, *variantTypes)
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
        variantKey: VariantGraphKey,
        preferredVariantNames: List<String>
    ): List<BazelDependency> {
        val allVariants = variantBuilder.build(project)
        val grazelVariant: Variant<*> = findGrazelVariantByKey(allVariants, variantKey)
        data class DirectVariantDeclaration(
            val bucketName: String,
            val configurationName: String,
            val dependency: ExternalDependency
        )

        fun ExternalDependency.shouldUseForBazel(): Boolean {
            if (group in IGNORED_ARTIFACT_GROUPS) return false

            val artifact = MavenArtifact(group, name)
            if (artifact.isBom || artifact.isExcluded || artifact.isIgnored) return false

            return !project.hasDatabinding || !artifact.isDatabindingProvidedArtifact
        }

        val lookupVariantHierarchy = mavenLookupVariantHierarchy(
            project = project,
            allVariants = allVariants,
            grazelVariant = grazelVariant,
            variantKey = variantKey,
            preferredVariantNames = preferredVariantNames
        )
        val repoPriority = mavenLookupVariantHierarchy(
            project = project,
            allVariants = allVariants,
            grazelVariant = grazelVariant,
            variantKey = variantKey,
            preferredVariantNames = preferredVariantNames,
            includeDefaultVariant = false
        ).mapIndexed { index, variantName ->
            variantName.toMavenRepoName() to index
        }.toMap()

        fun BazelDependency.repoRank(): Int = when (this) {
            is MavenDependency -> repoPriority[repo] ?: Int.MAX_VALUE
            else -> Int.MAX_VALUE
        }

        fun DirectVariantDeclaration.bucketSpecificity(): Int {
            val ownerBucketName = bucketName
            if (ownerBucketName == DEFAULT_VARIANT) return 0
            if (ownerBucketName == grazelVariant.name) return Int.MAX_VALUE
            return grazelVariant.extendsFrom
                .filter { parentName -> parentName != DEFAULT_VARIANT }
                .count { parentName -> ownerBucketName.contains(parentName, ignoreCase = true) }
                .coerceAtLeast(1)
        }

        val variantsByName = allVariants
            .asSequence()
            .filter { variant -> variant.variantType == grazelVariant.variantType }
            .associateBy { variant -> variant.name }

        fun lookupHierarchyForDeclaration(bucketName: String): Set<String> {
            if (bucketName == DEFAULT_VARIANT) {
                return buildSet {
                    add(DEFAULT_VARIANT)
                    addAll(lookupVariantHierarchy)
                }
            }
            if (bucketName == grazelVariant.name) {
                return lookupVariantHierarchy
            }
            val variant = variantsByName[bucketName]
            return buildSet {
                add(bucketName)
                if (variantKey.variantType.prefersDefaultMavenDeps) {
                    add(DEFAULT_VARIANT)
                }
                addAll(variant?.extendsFrom.orEmpty().reversed())
            }
        }

        fun Collection<DirectVariantDeclaration>.bestErrorDeclaration(): DirectVariantDeclaration =
            maxWithOrNull(
                compareBy<DirectVariantDeclaration> { declaration -> declaration.bucketSpecificity() }
                    .thenBy { declaration -> declaration.bucketName }
                    .thenBy { declaration -> declaration.configurationName }
                    .thenBy { declaration -> declaration.dependency.shortId }
            )
                ?: error("No candidate dependency declaration found")

        fun missingMavenDependencyMessage(
            declaration: DirectVariantDeclaration,
            lookupVariants: Set<String>
        ): String {
            val dependency = declaration.dependency
            val shortId = dependency.shortId
            val declaredVersion = dependency.version
            val declarationLocation = buildString {
                append(project.path)
                if (declaration.configurationName.isNotBlank()) {
                    append(":")
                    append(declaration.configurationName)
                }
            }
            val versionlessHint = if (declaredVersion.isNullOrBlank()) {
                " The declaration is versionless, so Grazel can only use it if Gradle resolved " +
                    "the artifact through an app or com.android.test binary root. Add an explicit " +
                    "version or make the dependency reachable from a binary root."
            } else {
                ""
            }
            return "Maven dependency $shortId declared in $declarationLocation for bucket " +
                "${declaration.bucketName} was not found in workspace dependency buckets " +
                lookupVariants.joinToString(prefix = "[", postfix = "]") { variant -> variant.toMavenRepoName() } +
                ".$versionlessHint"
        }

        fun shouldFailOnMissingMavenDependency(lookupVariants: Set<String>): Boolean {
            val resolutionService = dependencyResolutionService.get()
            if (!resolutionService.hasMainBucketReachability()) return true
            return lookupVariants.any { bucketName ->
                resolutionService.isReachableMainBucket(project.path, bucketName)
            }
        }

        val directDeclarationVariantNames = buildSet {
            add(grazelVariant.name)
            addAll(grazelVariant.extendsFrom)
        }
        val directVariantDeclarationsByShortId = allVariants
            .asSequence()
            .filter { variant -> variant.variantType == grazelVariant.variantType }
            .filter { variant -> variant.name in directDeclarationVariantNames }
            .flatMap { variant ->
                variant.variantConfigurations
                    .asSequence()
                    .filter { configuration -> configuration.isExternalDependencyDeclaration }
                    .flatMap { configuration ->
                        configuration.dependencies
                            .filterIsInstance<ExternalDependency>()
                            .asSequence()
                            .map { dependency ->
                                DirectVariantDeclaration(
                                    bucketName = configuration.declarationBucketName(),
                                    configurationName = configuration.name,
                                    dependency = dependency
                                )
                            }
                    }
            }
            .filter { declaration -> declaration.dependency.shouldUseForBazel() }
            .groupBy { declaration -> declaration.dependency.shortId }

        return grazelVariant.migratableConfigurations
            .asSequence()
            .flatMap { it.allDependencies.filterIsInstance<ExternalDependency>() }
            .filter { dependency -> dependency.shouldUseForBazel() }
            .groupBy { dependency -> dependency.shortId }
            .values
            .mapNotNull { dependencies ->
                val firstDependency = dependencies.first()
                when {
                    firstDependency.group == AUTO_SERVICE_GROUP &&
                        firstDependency.name == AUTO_SERVICE_ARTIFACT -> StringDependency(AUTO_SERVICE_TARGET)
                    firstDependency.group == DAGGER_GROUP -> StringDependency("//:dagger")
                    else -> {
                        val directVariantDeclarations =
                            directVariantDeclarationsByShortId[firstDependency.shortId].orEmpty()
                        val candidateDeclarations = directVariantDeclarations.ifEmpty {
                            dependencies.take(1).map { dependency ->
                                DirectVariantDeclaration(
                                    bucketName = grazelVariant.name,
                                    configurationName = grazelVariant
                                        .migratableConfigurations
                                        .firstOrNull { configuration ->
                                            dependency in configuration.allDependencies
                                        }
                                        ?.name
                                        .orEmpty(),
                                    dependency = dependency
                                )
                            }
                        }
                        val selectedDependency = candidateDeclarations
                            .asSequence()
                            .mapNotNull { declaration ->
                                val dependency = declaration.dependency
                                dependencyResolutionService.get().getMavenDependency(
                                    variants = lookupHierarchyForDeclaration(declaration.bucketName),
                                    group = dependency.group,
                                    name = dependency.name,
                                    version = null
                                )?.let { mavenDependency -> declaration to mavenDependency }
                            }.minWithOrNull(
                                compareByDescending<Pair<DirectVariantDeclaration, BazelDependency>> {
                                    it.first.bucketSpecificity()
                                }
                                    .thenBy { (_, bazelDependency) -> bazelDependency.repoRank() }
                                    .thenBy { (_, bazelDependency) -> bazelDependency.toString() }
                            )
                            ?.second
                        if (selectedDependency != null) {
                            selectedDependency
                        } else {
                            val declaration = candidateDeclarations.bestErrorDeclaration()
                            val lookupVariants = lookupHierarchyForDeclaration(declaration.bucketName)
                            if (shouldFailOnMissingMavenDependency(lookupVariants)) {
                                throw IllegalStateException(
                                    missingMavenDependencyMessage(
                                        declaration = declaration,
                                        lookupVariants = lookupVariants
                                    )
                                )
                            }
                            null
                        }
                    }
                }
            }.distinct()
            .toList()
    }

    override fun collectTransitiveMavenDeps(
        project: Project,
        variantKey: VariantGraphKey
    ): Set<MavenDependency> {
        val allVariants = variantBuilder.build(project)
        val grazelVariant = findGrazelVariantByKey(allVariants, variantKey)
        return collectOwnTransitiveMavenDeps(
            project = project,
            variantKey = variantKey,
            allVariants = allVariants,
            grazelVariant = grazelVariant
        )
    }

    private fun collectOwnTransitiveMavenDeps(
        project: Project,
        variantKey: VariantGraphKey,
        allVariants: Set<Variant<*>>,
        grazelVariant: Variant<*>
    ): Set<MavenDependency> {
        val labelLookupVariantHierarchy = mavenLookupVariantHierarchy(
            project = project,
            allVariants = allVariants,
            grazelVariant = grazelVariant,
            variantKey = variantKey
        )
        val closureLookupVariantHierarchy = mavenTransitiveLookupVariantHierarchy(
            project = project,
            allVariants = allVariants,
            grazelVariant = grazelVariant,
            variantKey = variantKey
        )
        val resolutionService = dependencyResolutionService.get()

        fun labelFor(shortId: String): MavenDependency {
            val (group, name) = shortId.split(":")
            return resolutionService.getMavenDependency(
                variants = labelLookupVariantHierarchy,
                group = group,
                name = name
            ) ?: MavenDependency(group = group, name = name)
        }

        return grazelVariant
            .migratableConfigurations
            .asSequence()
            .flatMap { it.allDependencies.filterIsInstance<ExternalDependency>() }
            .filter { dep -> !MavenArtifact(dep.group, dep.name).isBom }
            .flatMapTo(TreeSet()) { dep ->
                val directDependency = labelFor(dep.shortId)
                val ownerScopedClosureLookupVariantHierarchy =
                    if (variantKey.variantType == VariantType.AndroidBuild && directDependency.repo == "maven") {
                        setOf(DEFAULT_VARIANT)
                    } else {
                        closureLookupVariantHierarchy
                    }
                val variantTransitiveDependencies = resolutionService
                    .getVariantTransitiveDependencies(
                        variants = ownerScopedClosureLookupVariantHierarchy,
                        shortId = dep.shortId
                    )
                val globalTransitiveDependencies = resolutionService
                    .getTransitiveDependencies(dep.shortId)
                val transitiveDependencyShortIds = when {
                    variantTransitiveDependencies == null -> globalTransitiveDependencies
                    variantTransitiveDependencies.isEmpty() && directDependency.repo == "maven" ->
                        globalTransitiveDependencies
                    variantTransitiveDependencies.isEmpty() -> emptySet()
                    else -> variantTransitiveDependencies + globalTransitiveDependencies
                }
                val transitiveDependencies = transitiveDependencyShortIds
                    .map(::labelFor)
                buildList {
                    if (
                        variantTransitiveDependencies == null &&
                        transitiveDependencies.isEmpty() &&
                        directDependency.repo != "maven"
                    ) {
                        addAll(resolutionService.getMavenDependenciesInRepo(directDependency.repo))
                    } else {
                        transitiveDependencies.addTo(this)
                    }
                    add(directDependency)
                    if (project.hasKotlinAndroidExtensions) {
                        addAll(PARCELIZE_DEPS)
                    }
                }
        }
    }

    private fun mavenTransitiveLookupVariantHierarchy(
        project: Project,
        allVariants: Set<Variant<*>>,
        grazelVariant: Variant<*>,
        variantKey: VariantGraphKey,
    ): Set<String> {
        val hierarchy = mavenLookupVariantHierarchy(
            project = project,
            allVariants = allVariants,
            grazelVariant = grazelVariant,
            variantKey = variantKey,
            includeDefaultVariant = false
        )
        return if (variantKey.variantType.prefersDefaultMavenDeps) {
            hierarchy + DEFAULT_VARIANT
        } else {
            hierarchy
        }
    }

    /**
     * Find variant by VariantGraphKey.
     * The variantKey.variantId format is "projectPath:variantNameVariantType", so we strip the
     * project path prefix to match against Variant.id which is just "variantNameVariantType".
     */
    private fun findGrazelVariantByKey(project: Project, variantKey: VariantGraphKey): Variant<*> {
        val variants = variantBuilder.build(project)
        return findGrazelVariantByKey(variants, variantKey)
    }

    private fun findGrazelVariantByKey(
        variants: Set<Variant<*>>,
        variantKey: VariantGraphKey
    ): Variant<*> {
        // Strip project path prefix from variantId to match Variant.id format
        val variantIdWithoutProject = variantKey.variantId.substringAfterLast(":")
        return variants.first { it.id == variantIdWithoutProject }
    }

    private fun mavenLookupVariantHierarchy(
        project: Project,
        allVariants: Set<Variant<*>>,
        grazelVariant: Variant<*>,
        variantKey: VariantGraphKey,
        preferredVariantNames: List<String> = emptyList(),
        includeDefaultVariant: Boolean = true
    ): Set<String> = buildSet {
        addAll(preferredVariantNames)
        if (project.isAndroidTest && variantKey.variantType == VariantType.AndroidBuild) {
            add(ANDROID_TEST_VARIANT)
        }
        if (includeDefaultVariant && variantKey.variantType.prefersDefaultMavenDeps) {
            add(DEFAULT_VARIANT)
        }
        add(grazelVariant.name)
        addAll(grazelVariant.extendsFrom.reversed())
        if (variantKey.variantType == VariantType.JvmBuild && allVariants.any { it.variantType == VariantType.Lint }) {
            add(LINT_VARIANT)
        }
    }

    private val VariantType.prefersDefaultMavenDeps: Boolean
        get() = this == VariantType.AndroidTest || this == VariantType.Test

    /**
     * Collects first level module dependencies from their resolved configuration. Additionally,
     * excludes any artifacts that are not meant to be used in Bazel as defined by
     * [IGNORED_ARTIFACT_GROUPS]
     *
     * @return Sequence of [DefaultResolvedDependency] in the first level
     */
    private fun Project.firstLevelModuleDependencies(
        variantTypes: Array<VariantType> = arrayOf(
            VariantType.AndroidBuild,
            VariantType.Test,
            VariantType.AndroidTest
        )
    ): Sequence<DefaultResolvedDependency> {
        return configurationDataSource
            .resolvedConfigurations(
                project = this,
                variantTypes = variantTypes
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
     * Collects dependencies from all available configuration in the pre-resolution state i.e
     * without dependency resolutions. These dependencies would be ideally used for sub targets
     * instead of `WORKSPACE` file since they closely mirror what was defined in `build.gradle`
     * file.
     *
     * @return Sequence of `Configuration` and `Dependency`
     */
    private fun declaredDependencies(
        project: Project,
        vararg variantTypes: VariantType
    ): Sequence<Pair<Configuration, Dependency>> {
        return configurationDataSource.configurations(project, *variantTypes)
            .flatMap { configuration ->
                configuration
                    .dependencies
                    .asSequence()
                    .map { dependency -> configuration to dependency }
            }
    }

    private val ExternalDependency.shortId get() = module.group + ":" + module.name

    private val Configuration.isExternalDependencyDeclaration: Boolean
        get() {
            val normalizedName = name.lowercase()
            if ("dependenciesmetadata" in normalizedName || "classpath" in normalizedName) {
                return false
            }
            return normalizedName.endsWith("implementation") ||
                normalizedName.endsWith("api") ||
                normalizedName.endsWith("compileonly") ||
                normalizedName.endsWith("runtimeonly")
        }

    override fun collectKspPluginDeps(
        project: Project,
        variantKey: VariantGraphKey
    ): List<BazelDependency> {
        val validProcessorShortIds = dependencyResolutionService.get().getValidKspProcessorShortIds()
        val grazelVariant: Variant<*> = findGrazelVariantByKey(project, variantKey)
        return grazelVariant.kspConfiguration
            .asSequence()
            .flatMap { config -> config.allDependencies.filterIsInstance<ExternalDependency>() }
            .distinctBy { it.shortId }
            .filter { dep -> "${dep.module.group}:${dep.module.name}" in validProcessorShortIds }
            .map { dep ->
                // Create a minimal KspProcessor just for target name generation
                val processor = KspProcessor(
                    group = dep.module.group,
                    name = dep.module.name,
                    version = dep.version
                )
                kspPluginTarget(processor)
            }
            .toList()
    }
}
