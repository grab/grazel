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

package com.grab.grazel.migrate.android

import com.android.build.gradle.BaseExtension
import com.android.build.gradle.TestExtension
import com.android.build.gradle.api.AndroidSourceSet
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.gradle.ConfigurationScope
import com.grab.grazel.gradle.dependencies.BuildGraphType
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import com.grab.grazel.gradle.dependencies.DependencyGraphs
import com.grab.grazel.gradle.dependencies.GradleDependencyToBazelDependency
import com.grab.grazel.gradle.isAndroid
import com.grab.grazel.gradle.variant.AndroidVariantDataSource
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.gradle.variant.VariantMatcher
import com.grab.grazel.gradle.variant.getMigratableBuildVariants
import com.grab.grazel.gradle.variant.nameSuffix
import dagger.Lazy
import org.gradle.api.Project
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.getByType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface for resolving target project information for instrumentation tests.
 *
 * The com.android.test plugin requires a targetProjectPath that points to the application
 * being tested. This resolver handles finding that project, matching its variant, and
 * creating the appropriate Bazel dependencies.
 */
internal interface TargetProjectResolver {
    /**
     * Resolves the target project for a test module.
     *
     * @param testProject The test project (with com.android.test plugin)
     * @param targetProjectPath The path to the target project being tested
     * @param testVariant The variant of the test project
     * @return TargetProjectResolution indicating success or the type of failure
     */
    fun resolve(
        testProject: Project,
        targetProjectPath: String,
        testVariant: MatchedVariant
    ): TargetProjectResolution
}

/**
 * Default implementation of TargetProjectResolver.
 */
@Singleton
internal class DefaultTargetProjectResolver
@Inject
constructor(
    private val variantMatcher: VariantMatcher,
) : TargetProjectResolver {

    override fun resolve(
        testProject: Project,
        targetProjectPath: String,
        testVariant: MatchedVariant
    ): TargetProjectResolution {
        // Find the target project in the Gradle project graph
        val targetProject = testProject.rootProject.findProject(targetProjectPath)
            ?: return TargetProjectResolution.ProjectNotFound(targetProjectPath)

        // Verify it's an Android project
        if (!targetProject.isAndroid) {
            return TargetProjectResolution.NotAndroidProject(targetProject)
        }

        // Match the variant in the target project
        // We use the same variant name/flavor as the test variant
        val targetVariants = variantMatcher.matchedVariants(
            project = targetProject,
            scope = ConfigurationScope.BUILD
        )

        // Try to find a matching variant - prefer exact match by name
        val targetVariant = targetVariants.firstOrNull { matchedVariant ->
            matchedVariant.variant.name == testVariant.variant.name
        } ?: targetVariants.firstOrNull() // Fallback to first available variant

        if (targetVariant == null) {
            return TargetProjectResolution.VariantNotMatched(
                targetProject = targetProject,
                requestedVariant = testVariant.variant.name
            )
        }

        // Create the instruments dependency - this points to the binary target
        val instrumentsDependency = BazelDependency.StringDependency(
            "//${targetProject.path.removePrefix(":")}:${targetProject.name}${targetVariant.nameSuffix}"
        )

        // Create the associate dependency - this points to the library target
        val associateDependency = BazelDependency.ProjectDependency(
            dependencyProject = targetProject,
            prefix = "lib_",
            suffix = targetVariant.nameSuffix
        )

        return TargetProjectResolution.Success(
            targetProject = targetProject,
            targetVariant = targetVariant,
            instrumentsDependency = instrumentsDependency,
            associateDependency = associateDependency
        )
    }
}

/**
 * Interface for extracting Android test data from a project with com.android.test plugin.
 */
internal interface AndroidTestDataExtractor {
    /**
     * Extracts Android test data from a project.
     *
     * @param project The Gradle project to extract data from
     * @param matchedVariant The matched variant for the test
     * @return AndroidTestData containing all necessary information for generating android_local_test rule
     */
    fun extract(project: Project, matchedVariant: MatchedVariant): AndroidTestData
}

/**
 * Default implementation of AndroidTestDataExtractor.
 *
 * This extractor handles projects using the com.android.test plugin, which is designed for
 * standalone instrumentation test modules that test another Android application module.
 */
@Singleton
internal class DefaultAndroidTestDataExtractor
@Inject
constructor(
    private val targetProjectResolver: TargetProjectResolver,
    private val dependenciesDataSource: DependenciesDataSource,
    private val dependencyGraphsProvider: Lazy<DependencyGraphs>,
    private val gradleDependencyToBazelDependency: GradleDependencyToBazelDependency,
    private val androidManifestParser: AndroidManifestParser,
    private val manifestValuesBuilder: ManifestValuesBuilder,
    private val keyStoreExtractor: KeyStoreExtractor,
    private val androidVariantDataSource: AndroidVariantDataSource,
) : AndroidTestDataExtractor {

    private val projectDependencyGraphs get() = dependencyGraphsProvider.get()

    override fun extract(project: Project, matchedVariant: MatchedVariant): AndroidTestData {
        val testExtension = project.extensions.getByType<TestExtension>()

        // Extract targetProjectPath - this is REQUIRED for com.android.test modules
        val targetProjectPath = testExtension.targetProjectPath
            ?: throw IllegalStateException(
                "targetProjectPath is required for com.android.test module ${project.path}. " +
                    "Please set targetProjectPath in the test module's build.gradle."
            )

        // Resolve the target project and get its details
        val targetResolution = targetProjectResolver.resolve(
            testProject = project,
            targetProjectPath = targetProjectPath,
            testVariant = matchedVariant
        )

        when (targetResolution) {
            is TargetProjectResolution.ProjectNotFound -> {
                throw IllegalStateException(
                    "Target project '$targetProjectPath' specified in ${project.path} was not found. " +
                        "Please check that the project path is correct."
                )
            }
            is TargetProjectResolution.NotAndroidProject -> {
                throw IllegalStateException(
                    "Target project '${targetResolution.targetProject.path}' specified in ${project.path} " +
                        "is not an Android project. com.android.test modules can only test Android applications."
                )
            }
            is TargetProjectResolution.VariantNotMatched -> {
                throw IllegalStateException(
                    "Could not match variant '${targetResolution.requestedVariant}' in target project " +
                        "'${targetResolution.targetProject.path}'. Available variants might not include this variant."
                )
            }
            is TargetProjectResolution.Success -> {
                // Continue with successful resolution
            }
        }

        val resolution = targetResolution as TargetProjectResolution.Success
        val targetProject = resolution.targetProject
        val targetVariant = resolution.targetVariant

        // Extract test module source sets
        val migratableSourceSets = matchedVariant.variant.sourceSets
            .filterIsInstance<AndroidSourceSet>()
            .toList()

        // Extract sources using BUILD configuration scope (not ANDROID_TEST)
        val srcs = project.androidSources(migratableSourceSets, SourceSetType.JAVA_KOTLIN).toList()

        // Extract resources and assets
        val resources = project.androidSources(migratableSourceSets, SourceSetType.RESOURCES).toList()
        val assets = project.androidSources(migratableSourceSets, SourceSetType.ASSETS).toList()

        // Extract custom package from test project's manifest
        val customPackage = androidManifestParser.parsePackageName(
            testExtension,
            migratableSourceSets
        ) ?: ""

        // Extract target package from the target app's variant
        val targetPackage = targetVariant.variant.applicationId

        // Extract test instrumentation runner
        val testInstrumentationRunner = testExtension.defaultConfig.testInstrumentationRunner
            ?: "androidx.test.runner.AndroidJUnitRunner" // Default runner

        // Extract test application ID (if set, otherwise defaults to targetPackage.test)
        val testApplicationId = testExtension.defaultConfig.applicationId

        // Build manifest values for the test module using BUILD scope
        val manifestValues = manifestValuesBuilder.build(
            project = project,
            matchedVariant = matchedVariant,
            defaultConfig = testExtension.defaultConfig,
            configurationScope = ConfigurationScope.BUILD
        )

        // Extract dependencies using BUILD scope (not ANDROID_TEST)
        val deps = projectDependencyGraphs.directDependencies(
            project = project,
            buildGraphType = BuildGraphType(ConfigurationScope.BUILD, matchedVariant.variant)
        ).map { dependency ->
            gradleDependencyToBazelDependency.map(project, dependency, matchedVariant)
        } + dependenciesDataSource.collectMavenDeps(
            project = project,
            buildGraphType = BuildGraphType(ConfigurationScope.BUILD, matchedVariant.variant)
        )

        // Extract debug key from TARGET project (not test project)
        val debugKey = keyStoreExtractor.extract(
            rootProject = targetProject.rootProject,
            variant = androidVariantDataSource.getMigratableBuildVariants(targetProject).firstOrNull()
        )

        return AndroidTestData(
            name = "${project.name}${matchedVariant.nameSuffix}",
            srcs = srcs,
            deps = deps.sorted(),
            instruments = resolution.instrumentsDependency,
            customPackage = customPackage,
            targetPackage = targetPackage,
            testInstrumentationRunner = testInstrumentationRunner,
            manifestValues = manifestValues.mapValues { it.value ?: "" },
            debugKey = debugKey,
            resources = resources,
            assets = assets,
            tags = emptyList(), // Tags can be added later if needed
            visibility = listOf("//visibility:public") // Default visibility
        )
    }
}
