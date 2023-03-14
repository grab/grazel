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

package com.grab.grazel.hybrid

import com.grab.grazel.bazel.starlark.BazelDependency.ProjectDependency
import com.grab.grazel.di.qualifiers.RootProject
import org.gradle.api.Project
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton


interface ArtifactSearcher {
    val defaultArtifactNames: Collection<String>

    fun findArtifacts(artifactNames: Collection<String> = defaultArtifactNames): List<File>
}

@Singleton
class DefaultArtifactSearcher
@Inject
constructor(
    @param:RootProject val rootProject: Project
) : ArtifactSearcher {
    private val androidAar = "${rootProject.name}.aar"
    private val androidDatabindingAar = "${rootProject.name}-databinding.aar"

    override val defaultArtifactNames = with(rootProject) {
        setOf(
            "${name}_kt.jar",
            "$name.jar",
            androidAar,
            androidDatabindingAar,
            "$name-res.aar"
        )
    }

    private fun artifactOutputDir(): String {
        fun pathFor(architecture: String) = "${rootProject.rootProject.projectDir}" +
            "/bazel-out/$architecture-fastbuild/bin"

        val darwinPath = pathFor(architecture = "darwin")
        val darwinArm64Path = pathFor(architecture = "darwin_arm64")
        val k8Path = pathFor(architecture = "k8")
        return when {
            File(darwinPath).exists() -> darwinPath
            File(k8Path).exists() -> k8Path
            File(darwinArm64Path).exists() -> darwinArm64Path
            else -> error("Bazel artifact output directory does not exist!")
        }
    }

    private fun artifactRelativeDir(): String = ProjectDependency(rootProject)
        .toString()
        .substring(2)

    override fun findArtifacts(artifactNames: Collection<String>): List<File> {
        val artifactOutputDir = artifactOutputDir()
        val artifactDir = "$artifactOutputDir/${artifactRelativeDir()}"
        val artifactPaths = artifactNames.map { "$artifactDir/$it" }.toSet()
        val results = rootProject
            .fileTree(artifactOutputDir)
            .files
            .filter { file -> artifactPaths.contains(file.path) }
        return if (results.any { it.name == androidAar } && results.any { it.name == androidDatabindingAar }) {
            results.filter { it.name != androidAar }
        } else results
    }
}