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

import com.grab.grazel.bazel.rules.ANDROIDX_GROUP
import com.grab.grazel.bazel.rules.ANNOTATION_ARTIFACT
import com.grab.grazel.bazel.rules.DAGGER_GROUP
import com.grab.grazel.bazel.rules.DATABINDING_GROUP
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.gradle.dependencies.BuildGraphType
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import com.grab.grazel.gradle.hasDatabinding
import org.gradle.api.Project

internal fun DependenciesDataSource.collectMavenDeps(
    project: Project, vararg buildGraphTypes: BuildGraphType
): List<BazelDependency> = mavenDependencies(project, *buildGraphTypes)
    .filter {
        if (project.hasDatabinding) {
            it.group != DATABINDING_GROUP && (it.group != ANDROIDX_GROUP && it.name != ANNOTATION_ARTIFACT)
        } else true
    }.map {
        if (it.group == DAGGER_GROUP) {
            BazelDependency.StringDependency("//:dagger")
        } else {
            BazelDependency.MavenDependency(it)
        }
    }.distinct()
    .toList()
