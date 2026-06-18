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

package com.grab.grazel.tasks.internal

import com.grab.grazel.gradle.MigrationChecker
import com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadataCollector
import com.grab.grazel.gradle.variant.VariantBuilder
import com.grab.grazel.util.logHeap
import com.grab.grazel.util.writeJson
import dagger.Lazy
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

@CacheableTask
internal abstract class CollectDeclaredDependencyMetadataTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dependencyDeclarationFiles: ConfigurableFileCollection

    @get:Internal
    abstract val migrationCheckerProvider: Property<MigrationChecker>

    @get:Internal
    abstract val variantBuilderProvider: Property<VariantBuilder>

    @get:OutputFile
    abstract val declaredDependencyMetadata: RegularFileProperty

    init {
        group = GRAZEL_TASK_GROUP
        description = "Collects declared dependency metadata for aggregated dependency resolution"
    }

    @TaskAction
    fun action() {
        logger.logHeap("CollectDeclaredDependencyMetadata:start")
        val migrationChecker = migrationCheckerProvider.get()
        val variantBuilder = variantBuilderProvider.get()
        val migratableProjects = project.rootProject.subprojects
            .filter { subproject -> migrationChecker.canMigrate(subproject) }
        val variantsByProject = migratableProjects.associateWith { subproject ->
            try {
                variantBuilder.build(subproject)
            } catch (e: Exception) {
                logger.warn("Grazel: Failed to enumerate variants for ${subproject.path}: ${e.message}")
                emptyList()
            }
        }
        val metadata = DeclaredDependencyMetadataCollector().collect(
            variantsByProject = variantsByProject,
            projects = migratableProjects
        )
        writeJson(metadata, declaredDependencyMetadata.get())
        logger.logHeap("CollectDeclaredDependencyMetadata:done")
    }

    companion object {
        private const val TASK_NAME = "collectDeclaredDependencyMetadata"

        internal fun register(
            rootProject: Project,
            variantBuilderProvider: Lazy<VariantBuilder>,
            migrationChecker: Lazy<MigrationChecker>
        ): TaskProvider<CollectDeclaredDependencyMetadataTask> {
            return rootProject.tasks.register<CollectDeclaredDependencyMetadataTask>(TASK_NAME) {
                declaredDependencyMetadata.set(
                    rootProject.layout.buildDirectory.file("grazel/declared-dependency-metadata.json")
                )
                dependencyDeclarationFiles.from(dependencyDeclarationFileTree(rootProject))
                this.migrationCheckerProvider.set(migrationChecker.get())
                this.variantBuilderProvider.set(variantBuilderProvider.get())
            }
        }

        internal fun dependencyDeclarationFileTree(rootProject: Project): ConfigurableFileTree =
            rootProject.fileTree(rootProject.projectDir) {
                include("*.gradle")
                include("*.gradle.kts")
                include("**/*.gradle")
                include("**/*.gradle.kts")
                include("gradle/**/*.toml")
                exclude(".gradle/**")
                exclude("**/.gradle/**")
                exclude("bazel-*/**")
                exclude("build/**")
                exclude("**/build/**")
            }
    }
}
