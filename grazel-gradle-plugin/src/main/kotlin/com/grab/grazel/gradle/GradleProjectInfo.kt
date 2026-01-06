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

package com.grab.grazel.gradle

import com.grab.grazel.GrazelExtension
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.dependencies.DefaultDependencyGraphsService
import com.grab.grazel.extension.KspProcessorConfig
import com.grab.grazel.gradle.dependencies.DependencyGraphs
import com.grab.grazel.gradle.dependencies.KspProcessorClassExtractor
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.util.GradleProvider
import com.grab.grazel.gradle.variant.VariantBuilder
import com.grab.grazel.gradle.variant.isTest
import dagger.Lazy
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.kotlin.dsl.provideDelegate
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data holder for KSP processor dependencies.
 *
 * @param group Maven group ID
 * @param name Maven artifact name
 * @param version Maven version
 * @param processorClass Fully-qualified class name of the KSP processor provider
 * @param generatesJava Whether this processor generates Java code
 * @param targetEmbeddedCompiler Whether to use embedded Kotlin compiler
 */
data class KspProcessor(
    val group: String,
    val name: String,
    val version: String?,
    val processorClass: String = "",
    val generatesJava: Boolean = false,
    val targetEmbeddedCompiler: Boolean = false
) : Comparable<KspProcessor> {
    /** Maven coordinate in format `group:name` */
    val shortId get() = "$group:$name"

    /** Maven coordinate in format `group:name:version` */
    val id get() = "$group:$name:${version ?: ""}"

    override fun compareTo(other: KspProcessor) = shortId.compareTo(other.shortId)
}

/**
 * Common metadata about a Gradle project.
 */
@Deprecated(message = "Consider migrating to target API")
interface GradleProjectInfo {
    val rootProject: Project
    val grazelExtension: GrazelExtension
    val hasDagger: Boolean
    val hasAndroidExtension: Boolean
    val hasGooglePlayServices: Boolean
    val hasKsp: Boolean
    val kspDependencies: Set<KspProcessor>
    val rootLintXml: File // TODO(arun) Implementing here due to lack of better place for root project data.
}

internal class DefaultGradleProjectInfo(
    override val rootProject: Project,
    override val grazelExtension: GrazelExtension,
    private val dependencyGraphsService: GradleProvider<DefaultDependencyGraphsService>,
    private val workspaceDependencies: WorkspaceDependencies,
    private val variantBuilder: VariantBuilder
) : GradleProjectInfo {

    @Singleton
    class Factory
    @Inject constructor(
        @param:RootProject
        private val rootProject: Project,
        private val grazelExtension: GrazelExtension,
        private val dependencyGraphsService: GradleProvider<DefaultDependencyGraphsService>,
        private val variantBuilder: VariantBuilder,
    ) {
        fun create(
            workspaceDependencies: WorkspaceDependencies
        ): GradleProjectInfo = DefaultGradleProjectInfo(
            rootProject,
            grazelExtension,
            dependencyGraphsService,
            workspaceDependencies,
            variantBuilder
        )
    }

    private val projectGraph: DependencyGraphs get() = dependencyGraphsService.get().get()

    override val hasDagger: Boolean by lazy {
        workspaceDependencies
            .result
            .values
            .parallelStream()
            .flatMap { it.stream() }
            .anyMatch { it.shortId.contains("com.google.dagger") }
    }

    override val hasAndroidExtension: Boolean by lazy {
        projectGraph
            .nodesByVariant()
            .any(Project::hasKotlinAndroidExtensions)
    }

    override val hasGooglePlayServices: Boolean by lazy {
        rootProject
            .subprojects
            .any { project -> project.hasCrashlytics || project.hasGooglePlayServicesPlugin }
    }

    override val hasKsp: Boolean by lazy {
        projectGraph
            .nodesByVariant()
            .any(Project::hasKsp)
    }

    /**
     * Collects all KSP processor dependencies from all projects' ksp configurations.
     * These are aggregated for generating consolidated kt_ksp_plugin rules in root BUILD.bazel.
     * Filters out KSP's own internal dependencies (com.google.devtools.ksp:symbol-processing-*).
     *
     * For each processor:
     * - Extracts processor class from JAR's META-INF/services file
     * - Looks up processor config (generatesJava, targetEmbeddedCompiler) from grazel extension
     */
    override val kspDependencies: Set<KspProcessor> by lazy {
        if (!hasKsp) {
            emptySet()
        } else {
            val kspInternalGroup = "com.google.devtools.ksp"
            val kspProcessorConfigs = grazelExtension.rules.kotlin.ksp.processors

            rootProject.subprojects
                .asSequence()
                .filter(Project::hasKsp)
                .flatMap { project ->
                    variantBuilder.build(project)
                        .asSequence()
                        .filter { !it.variantType.isTest }
                        .flatMap { variant -> variant.kspConfiguration }
                        .flatMap { config ->
                            // Extract processor classes from resolved artifacts
                            val processorClasses = KspProcessorClassExtractor.extractProcessorClasses(config)

                            config.allDependencies
                                .filterIsInstance<ExternalDependency>()
                                .filter { dep -> dep.group != kspInternalGroup }
                                .mapNotNull { dep ->
                                    val key = "${dep.group}:${dep.name}"
                                    val classes = processorClasses[key]
                                    // Only include if we found processor classes
                                    classes?.firstOrNull()?.let { processorClass ->
                                        // Lookup config from ksp.processors
                                        val processorConfig = kspProcessorConfigs[key] ?: KspProcessorConfig()
                                        KspProcessor(
                                            group = dep.group ?: "",
                                            name = dep.name,
                                            version = dep.version,
                                            processorClass = processorClass,
                                            generatesJava = processorConfig.generatesJava,
                                            targetEmbeddedCompiler = processorConfig.targetEmbeddedCompiler
                                        )
                                    }
                                }
                        }
                }.toSortedSet()
        }
    }

    override val rootLintXml: File by lazy {
        rootProject.file("lint.xml")
    }
}
