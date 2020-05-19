/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.migrate.internal

import com.grab.grazel.bazel.starlark.Statement
import com.grab.grazel.migrate.BazelFileBuilder
import com.grab.grazel.migrate.TargetBuilder
import org.gradle.api.Project
import javax.inject.Inject
import javax.inject.Singleton

class ProjectBazelFileBuilder(
    private val project: Project,
    private val targetBuilders: Set<TargetBuilder>
) : BazelFileBuilder {

    @Singleton
    class Factory @Inject constructor(private val targetBuilders: Set<@JvmSuppressWildcards TargetBuilder>) {
        fun create(project: Project) = ProjectBazelFileBuilder(project, targetBuilders)
    }

    override fun build(): List<Statement> {
        return targetBuilders
            .filter { it.canHandle(project) }
            .flatMap { it.build(project) }
            .flatMap { it.statements() }
    }
}