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

package com.grab.grazel.migrate.dependencies

import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.bazel.starlark.BazelDependency.ProjectDependency
import com.grab.grazel.gradle.dependencies.BASE_MAVEN_REPO
import com.grab.grazel.gradle.dependencies.MAVEN_COMPILE_FILTER_TAG_PREFIX
import java.util.TreeSet

private fun MavenDependency.mavenTag(): String = copy(repo = BASE_MAVEN_REPO).toString()

fun calculateMavenDependencyTags(
    deps: Iterable<MavenDependency>
) = deps.asSequence()
    .map { it.mavenTag() }
    .sorted()
    .toList()

fun calculateDirectDependencyTags(
    self: String,
    deps: List<BazelDependency>
) = deps.asSequence().mapNotNull {
    when (it) {
        is ProjectDependency -> "@direct${it}"
        is MavenDependency -> it.mavenTag()
        else -> null
    }
}.toMutableList()
    .also { it.add("@self//$self") }
    .sorted()

fun calculateCompileFilterTags(
    self: String,
    directDependencies: List<BazelDependency>,
    transitiveMavenDependencies: Iterable<MavenDependency>
): List<String> {
    return TreeSet<String>().apply {
        directDependencies.mapNotNullTo(this) {
            when (it) {
                is ProjectDependency -> "@direct${it}"
                else -> null
            }
        }
        add("@self//$self")
        transitiveMavenDependencies.forEach { add(it.mavenTag()) }
    }.toList()
}

fun replaceMavenDependencyTags(
    existingTags: Iterable<String>,
    mavenDependencies: Iterable<MavenDependency>
): List<String> {
    return TreeSet<String>().apply {
        existingTags.filterNotTo(this) { tag -> tag.startsWith(MAVEN_COMPILE_FILTER_TAG_PREFIX) }
        mavenDependencies.forEach { add(it.mavenTag()) }
    }.toList()
}
