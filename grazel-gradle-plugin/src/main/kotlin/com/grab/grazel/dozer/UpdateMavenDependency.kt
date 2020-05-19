/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.dozer

import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency


internal class AddedMavenDependency(private val project: Project) : DozerUpdate {
    private fun command(bazelDependencyAnalytics: BazelDependencyAnalytics): String {
        return buildString {
            append("add artifacts")
            bazelDependencyAnalytics.getMissingMavenDependencies().forEach {
                project.logger.quiet("Adding ${it.group}:${it.name}:${it.version}")
                append(" ${it.group}:${it.name}:${it.version}")
            }
        }
    }

    override fun update(bazelDependencyAnalytics: BazelDependencyAnalytics) {
        project.dozerCommandToTempFile(command(bazelDependencyAnalytics))
    }
}

internal class ReplaceMavenDependency(private val project: Project) : DozerUpdate {
    override fun update(bazelDependencyAnalytics: BazelDependencyAnalytics) {
        bazelDependencyAnalytics.getDiffVersionDependency().forEach {
            project.logger.quiet("${it.toDozelReplace()}")
            project.dozerCommandToTempFile(it.toDozelReplace())
        }
    }

    private fun Pair<Dependency, Dependency>.toDozelReplace(): String {
        return "replace artifacts ${second.group}:${second.name}:${second.version} ${first.group}:${first.name}:${first.version}"
    }
}
