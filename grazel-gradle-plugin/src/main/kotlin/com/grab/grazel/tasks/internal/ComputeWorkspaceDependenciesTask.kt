/*
 * Copyright 2023 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.tasks.internal

import com.grab.grazel.gradle.ConfigurationDataSource
import com.grab.grazel.gradle.MigrationChecker
import com.android.build.gradle.api.BaseVariant
import com.grab.grazel.gradle.dependencies.AggregatedDependencyResolver
import com.grab.grazel.gradle.dependencies.AggregatedDependencyRootKind
import com.grab.grazel.gradle.dependencies.AggregatedDependencyRootMetadata
import com.grab.grazel.gradle.dependencies.AggregatedDependencyRootSnapshot
import com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependencies
import com.grab.grazel.gradle.dependencies.DefaultDependencyGraphsService
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadata
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import com.grab.grazel.gradle.dependencies.extractExcludeRulesByShortId
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.KSP
import com.grab.grazel.gradle.variant.AndroidVariantDataSource
import com.grab.grazel.gradle.variant.ANDROID_TEST_VARIANT
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.TEST_VARIANT
import com.grab.grazel.gradle.variant.Variant
import com.grab.grazel.gradle.variant.VariantBuilder
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.variant.extendsOnlyFromDefaultVariants
import com.grab.grazel.gradle.variant.isBase
import com.grab.grazel.util.fromJson
import com.grab.grazel.util.GradleProvider
import com.grab.grazel.util.Json
import com.grab.grazel.util.logHeap
import com.grab.grazel.util.writeJson
import dagger.Lazy
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

@CacheableTask
internal abstract class ComputeWorkspaceDependenciesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val declaredDependencyMetadata: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val kspDependencies: RegularFileProperty

    @get:Input
    abstract val aggregatedDependencyRootSnapshots: ListProperty<String>

    @get:Internal
    abstract val dependencyResolutionService: Property<DefaultDependencyResolutionService>

    @get:OutputFile
    abstract val workspaceDependencies: RegularFileProperty

    init {
        group = GRAZEL_TASK_GROUP
        description = "Computes external maven dependencies for bazel"
    }

    @TaskAction
    fun action() {
        logger.logHeap("ComputeWorkspaceDeps:start")

        val resolver = AggregatedDependencyResolver(
            rootProject = project.rootProject,
            declaredDependencyMetadata = fromJson<DeclaredDependencyMetadata>(
                declaredDependencyMetadata.get()
            ),
            precomputedKspDependencies = fromJson<ResolveDependenciesResult>(
                kspDependencies.get()
            ).dependencies.getOrDefault(KSP.name, emptySet()),
            aggregatedDependencyRoots = aggregatedDependencyRootSnapshots.get()
                .map { snapshotJson -> Json.decodeFromString<AggregatedDependencyRootSnapshot>(snapshotJson) }
        )
        val result = ComputeWorkspaceDependencies().computeFromResults(resolver.resolve())

        logger.logHeap("ComputeWorkspaceDeps:computed")
        runBlocking {
            val populateCache = async(Dispatchers.Default) {
                dependencyResolutionService.get().populateCache(result)
            }
            val writeResult = async(Dispatchers.IO) {
                writeJson(result, workspaceDependencies.get())
            }
            awaitAll<Any>(populateCache, writeResult)
        }
        logger.logHeap("ComputeWorkspaceDeps:done")
    }

    companion object {
        private const val TASK_NAME = "computeWorkspaceDependencies"
        internal fun register(
            rootProject: Project,
            variantBuilderProvider: Lazy<VariantBuilder>,
            migrationChecker: Lazy<MigrationChecker>,
            dependencyResolutionService: GradleProvider<DefaultDependencyResolutionService>,
            dependencyGraphsService: GradleProvider<DefaultDependencyGraphsService>,
            dependenciesDataSource: Lazy<DependenciesDataSource>,
            configurationDataSource: Lazy<ConfigurationDataSource>,
            androidVariantDataSource: Lazy<AndroidVariantDataSource>,
        ): TaskProvider<ComputeWorkspaceDependenciesTask> {
            dependencyGraphsService.get().configure(
                rootProject = rootProject,
                dependenciesDataSource = dependenciesDataSource.get(),
                configurationDataSource = configurationDataSource.get(),
                androidVariantDataSource = androidVariantDataSource.get()
            )

            val declaredDependencyMetadataTask = CollectDeclaredDependencyMetadataTask.register(
                rootProject = rootProject,
                variantBuilderProvider = variantBuilderProvider,
                migrationChecker = migrationChecker
            )
            val kspProcessorDependenciesTask = CollectKspProcessorDependenciesTask.register(
                rootProject = rootProject,
                migrationChecker = migrationChecker
            )
            val computeTask = rootProject.tasks
                .register<ComputeWorkspaceDependenciesTask>(TASK_NAME) {
                    workspaceDependencies.set(
                        rootProject.layout.buildDirectory.file("grazel/dependencies.json")
                    )
                    this.dependencyResolutionService.set(dependencyResolutionService)
                    aggregatedDependencyRootSnapshots.convention(emptyList())
                }
            rootProject.afterEvaluate {
                computeTask.configure {
                    declaredDependencyMetadata.set(
                        declaredDependencyMetadataTask.flatMap { it.declaredDependencyMetadata }
                    )
                    kspDependencies.set(
                        kspProcessorDependenciesTask.flatMap { it.kspDependencies }
                    )
                    configureAggregatedDependencyRoots(
                        rootProject = rootProject,
                        variantBuilder = variantBuilderProvider.get(),
                        migrationChecker = migrationChecker.get()
                    )
                    dependsOn(declaredDependencyMetadataTask)
                    dependsOn(kspProcessorDependenciesTask)
                }
            }
            return computeTask
        }

        private fun ComputeWorkspaceDependenciesTask.configureAggregatedDependencyRoots(
            rootProject: Project,
            variantBuilder: VariantBuilder,
            migrationChecker: MigrationChecker
        ) {
            val migratableProjects = rootProject.subprojects
                .filter { project -> migrationChecker.canMigrate(project) }
                .sortedBy { project -> project.path }
            val variantsByProject = migratableProjects.associateWith { project ->
                try {
                    variantBuilder.build(project)
                } catch (e: Exception) {
                    rootProject.logger.warn(
                        "Grazel: Failed to enumerate variants for ${project.path}: ${e.message}"
                    )
                    emptySet()
                }
            }
            val binaryProjects = migratableProjects.filter { project ->
                project.plugins.hasPlugin("com.android.application") ||
                    project.plugins.hasPlugin("com.android.test")
            }

            binaryProjects.forEach { project ->
                val standaloneTestProject = project.plugins.hasPlugin("com.android.test")
                val variants = variantsByProject[project].orEmpty().sortedForAggregatedRoots()
                variants
                    .filter { variant ->
                        variant.variantType == VariantType.AndroidBuild &&
                            (variant.isBase || variant.extendsOnlyFromDefaultVariants)
                    }
                    .forEach { variant ->
                        val targetBuckets = if (standaloneTestProject) {
                            setOf(DEFAULT_VARIANT, ANDROID_TEST_VARIANT)
                        } else {
                            setOf(variant.name)
                        }
                        addVariantRoots(
                            project = project,
                            variant = variant,
                            kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                            bucketName = variant.name,
                            targetBuckets = targetBuckets
                        )
                    }
                variants
                    .filter { variant ->
                        (variant.variantType == VariantType.Test && variant.name == TEST_VARIANT) ||
                            (variant.variantType == VariantType.AndroidTest && variant.name == ANDROID_TEST_VARIANT)
                    }
                    .forEach { variant ->
                        addVariantRoots(
                            project = project,
                            variant = variant,
                            kind = AggregatedDependencyRootKind.TEST_HIERARCHY,
                            bucketName = variant.name,
                            targetBuckets = setOf(variant.name)
                        )
                    }
                variants
                    .filter { variant -> variant.variantType == VariantType.AndroidBuild && variant.isAndroidLeaf }
                    .forEach { variant ->
                        addVariantRoots(
                            project = project,
                            variant = variant,
                            kind = AggregatedDependencyRootKind.MAIN_LEAF,
                            bucketName = variant.name,
                            targetBuckets = if (standaloneTestProject) {
                                setOf(ANDROID_TEST_VARIANT, DEFAULT_VARIANT)
                            } else {
                                setOf(variant.name)
                            }
                        )
                        addNamedRoots(
                            project = project,
                            kind = AggregatedDependencyRootKind.UNIT_TEST,
                            bucketName = variant.name,
                            variantType = null,
                            leafVariant = variant,
                            configurationNames = listOf(
                                "${variant.name}UnitTestRuntimeClasspath",
                                "${variant.name}UnitTestCompileClasspath"
                            ),
                            traverseProjectNodes = true
                        )
                        addNamedRoots(
                            project = project,
                            kind = AggregatedDependencyRootKind.ANDROID_TEST,
                            bucketName = variant.name,
                            variantType = null,
                            leafVariant = variant,
                            configurationNames = listOf(
                                "${variant.name}AndroidTestRuntimeClasspath",
                                "${variant.name}AndroidTestCompileClasspath"
                            ),
                            traverseProjectNodes = true
                        )
                    }
            }

            migratableProjects.forEach { project ->
                project.configurations.findByName("lintChecks")?.let { lintConfig ->
                    addRoot(
                        project = project,
                        kind = AggregatedDependencyRootKind.LINT,
                        bucketName = null,
                        leafVariant = null,
                        variantType = null,
                        configuration = lintConfig,
                        traverseProjectNodes = false,
                        directDependencyShortIds = lintConfig.directExternalDependencyShortIds()
                    )
                }
            }
        }

        private val Variant<*>.isAndroidLeaf: Boolean
            get() = backingVariant is BaseVariant

        private fun ComputeWorkspaceDependenciesTask.addVariantRoots(
            project: Project,
            variant: Variant<*>,
            kind: AggregatedDependencyRootKind,
            bucketName: String,
            targetBuckets: Set<String>
        ) {
            addRoots(
                project = project,
                kind = kind,
                bucketName = bucketName,
                leafVariant = variant,
                variantType = variant.variantType,
                configurations = variant.safeRuntimeConfigurations() + variant.safeCompileConfigurations(),
                traverseProjectNodes = true,
                targetBuckets = targetBuckets
            )
        }

        private fun ComputeWorkspaceDependenciesTask.addNamedRoots(
            project: Project,
            kind: AggregatedDependencyRootKind,
            bucketName: String?,
            leafVariant: Variant<*>?,
            variantType: VariantType?,
            configurationNames: Iterable<String>,
            traverseProjectNodes: Boolean
        ) {
            addRoots(
                project = project,
                kind = kind,
                bucketName = bucketName,
                leafVariant = leafVariant,
                variantType = variantType,
                configurations = configurationNames.mapNotNull { name ->
                    project.configurations.findByName(name)
                },
                traverseProjectNodes = traverseProjectNodes,
                targetBuckets = emptySet()
            )
        }

        private fun ComputeWorkspaceDependenciesTask.addRoots(
            project: Project,
            kind: AggregatedDependencyRootKind,
            bucketName: String?,
            leafVariant: Variant<*>?,
            variantType: VariantType?,
            configurations: Iterable<Configuration>,
            traverseProjectNodes: Boolean,
            targetBuckets: Set<String>
        ) {
            configurations.sortedBy { configuration -> configuration.name }.forEach { configuration ->
                addRoot(
                    project = project,
                    kind = kind,
                    bucketName = bucketName,
                    leafVariant = leafVariant,
                    variantType = variantType,
                    configuration = configuration,
                    traverseProjectNodes = traverseProjectNodes,
                    targetBuckets = targetBuckets
                )
            }
        }

        private fun Iterable<Variant<*>>.sortedForAggregatedRoots(): List<Variant<*>> {
            return sortedWith(
                compareBy<Variant<*>> { variant -> variant.variantType.name }
                    .thenBy { variant -> variant.name }
            )
        }

        private fun ComputeWorkspaceDependenciesTask.addRoot(
            project: Project,
            kind: AggregatedDependencyRootKind,
            bucketName: String?,
            leafVariant: Variant<*>?,
            variantType: VariantType?,
            configuration: Configuration,
            traverseProjectNodes: Boolean,
            directDependencyShortIds: Set<String> = emptySet(),
            targetBuckets: Set<String> = emptySet()
        ) {
            if (!configuration.isCanBeResolved) return
            val rootComponent = configuration.incoming.resolutionResult.rootComponent
            val baseVariant = leafVariant?.backingVariant as? BaseVariant
            val metadata = AggregatedDependencyRootMetadata(
                projectPath = project.path,
                kind = kind,
                configurationName = configuration.name,
                bucketName = bucketName,
                leafName = leafVariant?.name,
                variantNames = (
                    listOfNotNull(leafVariant?.name) +
                        leafVariant?.extendsFrom.orEmpty()
                    ).toSortedSet(),
                variantType = variantType,
                buildType = baseVariant?.buildType?.name,
                productFlavors = baseVariant?.productFlavors?.map { it.name }.orEmpty(),
                targetBuckets = targetBuckets.toSortedSet(),
                traverseProjectNodes = traverseProjectNodes,
                directDependencyShortIds = directDependencyShortIds.toSortedSet(),
                rootExcludeRulesByShortId = configuration.extractExcludeRulesByShortId()
            )
            aggregatedDependencyRootSnapshots.add(
                rootComponent.map { root ->
                    Json.encodeToString(
                        AggregatedDependencyRootSnapshot.from(
                            root = root,
                            metadata = metadata
                        )
                    )
                }
            )
        }

        private fun Variant<*>.safeRuntimeConfigurations(): Set<Configuration> {
            return try {
                runtimeConfiguration
            } catch (e: Exception) {
                emptySet()
            }
        }

        private fun Variant<*>.safeCompileConfigurations(): Set<Configuration> {
            return try {
                compileConfiguration
            } catch (e: Exception) {
                emptySet()
            }
        }

        private fun Configuration.directExternalDependencyShortIds(): Set<String> {
            return incoming.dependencies
                .asSequence()
                .filterIsInstance<ExternalDependency>()
                .map { dependency -> "${dependency.group}:${dependency.name}" }
                .toSet()
        }

    }
}
