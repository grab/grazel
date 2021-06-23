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

package com.grab.grazel.hybrid

import com.grab.grazel.bazel.starlark.BazelDependency
import org.gradle.api.Project
import java.io.File


interface ArtifactSearcher {
    val project: Project

    val defaultArtifactNames: Collection<String>

    fun findArtifacts(artifactNames: Collection<String> = defaultArtifactNames): List<File>
}

interface ArtifactSearcherFactory {

    fun newInstance(project: Project): ArtifactSearcher
}

// TODO Inject with Dagger
class DefaultArtifactSearcherFactory : ArtifactSearcherFactory {
    override fun newInstance(project: Project) = DefaultArtifactSearcher(project)
}

class DefaultArtifactSearcher(override val project: Project) : ArtifactSearcher {

    private val androidAar = "${project.name}.aar"
    private val androidDatabindingAar = "${project.name}-databinding.aar"

    override val defaultArtifactNames = with(project) {
        setOf(
            "${name}_kt.jar",
            "$name.jar",
            androidAar,
            androidDatabindingAar,
            "$name-res.aar"
        )
    }

    private fun artifactOutputDir(): String {
        fun pathFor(architecture: String) =
            "${project.rootProject.projectDir}/bazel-out/$architecture-fastbuild/bin"

        val darwinPath = pathFor(architecture = "darwin")
        val k8Path = pathFor(architecture = "k8")
        return when {
            File(darwinPath).exists() -> darwinPath
            File(k8Path).exists() -> k8Path
            else -> error("Bazel artifact output directory does not exist!")
        }
    }

    private fun artifactRelativeDir(): String =
        BazelDependency.ProjectDependency(project)
            .toString()
            .substring(2)

    override fun findArtifacts(artifactNames: Collection<String>): List<File> {
        val artifactOutputDir = artifactOutputDir()
        val artifactDir = "$artifactOutputDir/${artifactRelativeDir()}"
        val artifactPaths = artifactNames.map { "$artifactDir/$it" }.toSet()
        val results = project
            .fileTree(artifactOutputDir)
            .files
            .filter { file -> artifactPaths.contains(file.path) }
        return if (results.any { it.name == androidAar } && results.any { it.name == androidDatabindingAar }) {
            results.filter { it.name != androidAar }
        } else results
    }
}