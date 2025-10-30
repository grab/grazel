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

import com.android.build.gradle.TestExtension
import com.android.build.gradle.api.AndroidSourceSet
import com.grab.grazel.bazel.rules.Multidex
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.gradle.ConfigurationScope
import com.grab.grazel.gradle.dependencies.BuildGraphType
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import com.grab.grazel.gradle.dependencies.DependencyGraphs
import com.grab.grazel.gradle.dependencies.GradleDependencyToBazelDependency
import com.grab.grazel.gradle.hasCompose
import com.grab.grazel.gradle.isAndroid
import com.grab.grazel.gradle.variant.AndroidVariantDataSource
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.gradle.variant.VariantMatcher
import com.grab.grazel.gradle.variant.getMigratableBuildVariants
import com.grab.grazel.gradle.variant.nameSuffix
import dagger.Lazy
import org.gradle.api.Project
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
        val targetProject = testProject.rootProject.findProject(targetProjectPath)
            ?: return TargetProjectResolution.ProjectNotFound(targetProjectPath)

        if (!targetProject.isAndroid) {
            return TargetProjectResolution.NotAndroidProject(targetProject)
        }

        val targetVariants = variantMatcher.matchedVariants(
            project = targetProject,
            scope = ConfigurationScope.BUILD
        )

        // Match by the APP's variant name, not the test module's actual variant name
        val targetVariant = targetVariants.firstOrNull { matchedVariant ->
            matchedVariant.variantName == testVariant.variantName
        }

        if (targetVariant == null) {
            return TargetProjectResolution.VariantNotMatched(
                targetProject = targetProject,
                requestedVariant = testVariant.variantName
            )
        }

        // Create the instruments dependency - this points to the binary target
        val instrumentsDependency = BazelDependency.StringDependency(
            "//${targetProject.path.removePrefix(":")}:${targetProject.name}${targetVariant.nameSuffix}"
        )

        // Create the associate dependency - this points to the library target
        // For separate test modules (cross-module), use base library target without _kt suffix
        // to avoid "Dependencies on .jar artifacts are not allowed" error in android_binary.
        // The _kt target brings in .jar artifacts that can't be used in cross-module scenarios.
        val associateDependency = BazelDependency.ProjectDependency(
            dependencyProject = targetProject,
            prefix = "lib_",
            suffix = targetVariant.nameSuffix  // No _kt suffix for cross-module
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
     * @param androidLibraryData Library data extracted from AndroidLibraryDataExtractor
     * @param androidBinaryData Binary data extracted from AndroidBinaryDataExtractor
     * @return AndroidTestData containing all necessary information for generating android_instrumentation_binary rule
     */
    fun extract(
        project: Project,
        matchedVariant: MatchedVariant,
        androidLibraryData: AndroidLibraryData,
        androidBinaryData: AndroidBinaryData
    ): AndroidTestData
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
    private val keyStoreExtractor: KeyStoreExtractor,
    private val androidVariantDataSource: AndroidVariantDataSource,
) : AndroidTestDataExtractor {

    override fun extract(
        project: Project,
        matchedVariant: MatchedVariant,
        androidLibraryData: AndroidLibraryData,
        androidBinaryData: AndroidBinaryData
    ): AndroidTestData {
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

        return project.extract(
            matchedVariant = matchedVariant,
            extension = testExtension,
            targetResolution = targetResolution,
            androidLibraryData = androidLibraryData,
            androidBinaryData = androidBinaryData,
        )
    }

    private fun Project.extract(
        matchedVariant: MatchedVariant,
        extension: TestExtension,
        targetResolution: TargetProjectResolution.Success,
        androidLibraryData: AndroidLibraryData,
        androidBinaryData: AndroidBinaryData,
    ): AndroidTestData {
        // Associates links test to app library (for accessing app internals)
        val associates = listOf(targetResolution.associateDependency)

        val migratableSourceSets = matchedVariant.variant.sourceSets
            .filterIsInstance<AndroidSourceSet>()
            .toList()

        // Test-specific resource handling
        val resources = unitTestResources(migratableSourceSets.asSequence()).toList()
        val resourceFiles = androidSources(migratableSourceSets, SourceSetType.RESOURCES).toList()
        val resourceStripPrefix = resourceStripPrefix(migratableSourceSets.asSequence())
        val assets = androidSources(migratableSourceSets, SourceSetType.ASSETS).toList()

        val targetPackage = targetResolution.targetVariant.variant.applicationId

        val testInstrumentationRunner = extension.defaultConfig.testInstrumentationRunner
            ?: "androidx.test.runner.AndroidJUnitRunner" // Default runner

        // Combine library deps with test deps, but filter out the target app
        // (it's handled via 'instruments' and 'associates')
        val combinedDeps = (androidLibraryData.deps + dependenciesDataSource.collectMavenDeps(
            this,
            BuildGraphType(ConfigurationScope.BUILD, matchedVariant.variant)
        )).filterNot { dep ->
            dep is BazelDependency.ProjectDependency &&
                dep.dependencyProject.path == targetResolution.targetProject.path
        }

        val debugKey = keyStoreExtractor.extract(
            rootProject = targetResolution.targetProject.rootProject,
            variant = androidVariantDataSource.getMigratableBuildVariants(targetResolution.targetProject).firstOrNull()
        )

        return AndroidTestData(
            // Use data from AndroidLibraryDataExtractor (already includes variant suffix)
            name = androidLibraryData.name,
            srcs = androidLibraryData.srcs,
            resourceSets = androidLibraryData.resourceSets,
            resValuesData = androidLibraryData.resValuesData,
            manifestFile = androidLibraryData.manifestFile,
            customPackage = androidLibraryData.customPackage,
            packageName = targetPackage,
            buildConfigData = androidLibraryData.buildConfigData,
            deps = combinedDeps.sorted(),
            plugins = androidLibraryData.plugins,
            compose = androidLibraryData.compose,
            databinding = androidLibraryData.databinding,
            tags = androidLibraryData.tags,
            lintConfigData = androidLibraryData.lintConfigData,
            // Use data from AndroidBinaryDataExtractor
            manifestValues = androidBinaryData.manifestValues,
            debugKey = debugKey,
            resConfigs = androidBinaryData.resConfigs,
            // Test-specific fields
            associates = associates,
            instruments = targetResolution.instrumentsDependency,
            targetPackage = targetPackage,
            testInstrumentationRunner = testInstrumentationRunner,
            resources = resources,
            resourceFiles = resourceFiles,
            resourceStripPrefix = resourceStripPrefix,
            assets = assets
        )
    }
}
