/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.bazel.starlark

import com.grab.grazel.gradle.buildTargetName
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency


sealed class BazelDependency {
    data class ProjectDependency(val project: Project) : BazelDependency() {

        override fun toString(): String {
            val relativeProjectPath = project.rootProject.relativePath(project.projectDir)
            return if (relativeProjectPath.contains("/")) {
                val path = relativeProjectPath.split("/").dropLast(1).joinToString("/")
                "//" + path + "/" + project.buildTargetName()
            } else {
                "//" + project.buildTargetName()
            }
        }
    }

    data class StringDependency(val dep: String) : BazelDependency() {
        override fun toString() = dep
    }

    data class MavenDependency(val dependency: Dependency) : BazelDependency() {

        private fun String.toBazelPath(): String {
            return replace(".", "_").replace("-", "_")
        }

        override fun toString(): String {
            val group = dependency.group?.toBazelPath() ?: ""
            val name = dependency.name.toBazelPath()
            return "@maven//:${group}_$name"
        }
    }
}