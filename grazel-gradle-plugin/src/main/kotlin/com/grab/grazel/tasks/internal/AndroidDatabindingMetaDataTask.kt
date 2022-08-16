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

package com.grab.grazel.tasks.internal

import com.grab.grazel.di.GrazelComponent
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import com.grab.grazel.util.ansiGreen
import dagger.Lazy
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.konan.file.File
import java.util.zip.ZipFile
import javax.inject.Inject

internal open class AndroidDatabindingMetaDataTask
@Inject
constructor(
    private val dependenciesDataSource: Lazy<DependenciesDataSource>
) : DefaultTask() {

    init {
        outputs.upToDateWhen { false } // Run always until we figure out up to date checks
    }

    @TaskAction
    fun action() {
        val databindingPackageInfo = dependenciesDataSource.get()
            .dependencyArtifactMap(rootProject = project.rootProject, fileExtension = "aar")
            .map { (artifact, file) ->
                val mavenId = listOf(".", "_", ":", "-").fold(artifact.id) { acc, s ->
                    acc.replace(s, "_")
                }
                val databindingPackage = ZipFile(file).use { zip ->
                    zip.entries()
                        .asSequence()
                        .filter { it.name.endsWith(BR_BIN) }
                        // Databinding package is in the databinding/*-br.bin file
                        .map { it.name.split(BR_BIN).first().split(File.separator).last() }
                        .firstOrNull()
                }
                mavenId to databindingPackage
            }.filter { it.second != null }
            .sortedBy { it.first }
            .distinctBy { it.first }
            .joinToString(separator = ",") { "${it.first}=${it.second}" }

        project.rootProject.file("databinding_info.bazelrc").apply {
            writeText(
                """
            |# Generated file. DO NOT MODIFY.
            |build --android_databinding_package_info=$databindingPackageInfo
            """.trimMargin()
            )
            logger.quiet("Generated $name".ansiGreen)
        }
    }

    companion object {
        private const val TASK_NAME = "generateDatabindingMetaData"

        private const val BR_BIN = "-br.bin"

        fun register(
            @RootProject rootProject: Project,
            grazelComponent: GrazelComponent,
            configureAction: AndroidDatabindingMetaDataTask.() -> Unit = {},
        ) = rootProject.tasks.register<AndroidDatabindingMetaDataTask>(
            TASK_NAME,
            grazelComponent.dependenciesDataSource()
        ).apply {
            configure {
                description = "Generates databinding metadata"
                group = GRAZEL_TASK_GROUP
                configureAction(this)
            }
        }
    }
}