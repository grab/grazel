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

import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.gradle.variant.MatchedVariant
import org.gradle.api.Project

/**
 * Sealed class representing the result of resolving a target project for instrumentation.
 *
 * When a test module needs to instrument another project, this sealed class hierarchy
 * represents all possible outcomes of that resolution process.
 */
internal sealed class TargetProjectResolution {
    /**
     * Successful resolution of the target project.
     *
     * @property targetProject The Gradle project that will be instrumented
     * @property targetVariant The matched variant of the target project
     * @property instrumentsDependency The Bazel dependency for the instruments attribute
     * @property associateDependency The Bazel dependency for the associate attribute
     */
    data class Success(
        val targetProject: Project,
        val targetVariant: MatchedVariant,
        val instrumentsDependency: BazelDependency,
        val associateDependency: BazelDependency
    ) : TargetProjectResolution()

    /**
     * The target project specified by path was not found in the Gradle project graph.
     *
     * @property targetProjectPath The project path that could not be resolved
     */
    data class ProjectNotFound(val targetProjectPath: String) : TargetProjectResolution()

    /**
     * The target project was found but is not an Android project.
     *
     * @property targetProject The project that was found but is not Android
     */
    data class NotAndroidProject(val targetProject: Project) : TargetProjectResolution()

    /**
     * The target project is Android but the requested variant could not be matched.
     *
     * @property targetProject The Android project that was found
     * @property requestedVariant The variant name that could not be matched
     */
    data class VariantNotMatched(
        val targetProject: Project,
        val requestedVariant: String
    ) : TargetProjectResolution()
}
