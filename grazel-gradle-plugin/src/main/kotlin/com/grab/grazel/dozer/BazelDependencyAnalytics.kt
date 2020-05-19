/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.dozer

import com.grab.grazel.GrazelExtension
import com.grab.grazel.gradle.DefaultGradleProjectInfo
import com.grab.grazel.hybrid.bazelCommand
import org.gradle.api.artifacts.Dependency
import java.io.ByteArrayOutputStream

interface BazelDependencyAnalytics {
    fun getMissingMavenDependencies(): List<Dependency>
    fun getDiffVersionDependency(): List<Pair<Dependency, Dependency>>
}

internal class QueryBazelDependencyAnalytics(
    private val gradleProjectInfo: DefaultGradleProjectInfo,
    extension: GrazelExtension
) : BazelDependencyAnalytics {
    private val mavenDeps = gradleProjectInfo.projectGraph
        .nodes()
        .asSequence()
        .flatMap(gradleProjectInfo.dependenciesDataSource::mavenDependencies)
        .filter { artifact ->
            !(extension.dependenciesConfiguration.ignoreArtifacts.get()
                .any { ignore -> "${artifact.group}:${artifact.name}" == ignore })
        }.distinct()

    private val bazelMavenDeps = mavenDependenciesForTarget().distinct()
    override fun getMissingMavenDependencies(): List<Dependency> {
        return mavenDeps.filter { gradleItem ->
            !bazelMavenDeps.any { bazelItem ->
                gradleItem.name == bazelItem.name
                        && gradleItem.group?.run {
                    bazelItem.mavenPath.replace('/', '.').contains(this)
                } ?: false
            }
        }.toList()
    }

    override fun getDiffVersionDependency(): List<Pair<Dependency, Dependency>> {
        return mavenDeps.map { gradleItem ->
            val diffVersionItem = bazelMavenDeps.find { bazelItem ->
                gradleItem.name == bazelItem.name
                        && gradleItem.group?.run {
                    bazelItem.mavenPath.replace('/', '.').contains(this)
                } ?: false
                        && gradleItem.version != bazelItem.version
            }
            if (diffVersionItem != null) gradleItem to DependencyVersionDecorator(
                gradleItem,
                diffVersionItem.version
            ) else gradleItem to null
        }.filter { it.second != null }
            .map { it.first to it.second!! }
            .toList()
    }

    private fun mavenDependenciesForTarget(target: String = "..."): List<BazelQueryDependency> {
        val stdout = ByteArrayOutputStream()
        gradleProjectInfo.rootProject.bazelCommand(
            "query",
            "deps(//${target})",
            outputstream = stdout
        )
        return stdout.toString()
            .lines()
            .asSequence()
            .filter { it.startsWith("@maven//:v1/https") }
            .map { toDependency(it) }
            .toList()
    }

    private fun toDependency(mavenPath: String): BazelQueryDependency {
        // @maven//:v1/https/dl.google.com/dl/android/maven2/androidx/appcompat/appcompat/1.1.0/appcompat-1.1.0.aar
        val segment = mavenPath.split("/")
        return BazelQueryDependency(
            segment[segment.size - 3],
            segment[segment.size - 2],
            mavenPath
        )
    }
}

private data class BazelQueryDependency(
    val name: String,
    val version: String,
    val mavenPath: String
)

class DependencyVersionDecorator(dependency: Dependency, private val differentVersion: String) :
    Dependency by dependency {
    override fun getVersion(): String? = differentVersion
}