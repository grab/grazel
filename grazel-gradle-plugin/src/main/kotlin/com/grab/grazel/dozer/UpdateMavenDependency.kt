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
