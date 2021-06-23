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

package com.grab.grazel.gradle

import com.google.common.graph.ImmutableValueGraph
import com.grab.grazel.GrazelExtension
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.multibindings.ElementsIntoSet
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import java.util.concurrent.ConcurrentSkipListMap
import javax.inject.Inject
import javax.inject.Singleton

internal interface MigrationCriteria {
    fun canMigrate(project: Project): Boolean
}

@Module
internal object MigrationCriteriaModule {
    @ElementsIntoSet
    @Provides
    fun migrationCriteria(
        pluginsMigrationCriteria: PluginsMigrationCriteria,
        androidMigrationCriteria: AndroidMigrationCriteria,
        dependenciesMigrationCriteria: DependenciesMigrationCriteria
    ): Set<MigrationCriteria> = setOf(
        pluginsMigrationCriteria,
        androidMigrationCriteria,
        dependenciesMigrationCriteria
    )
}

@Singleton
internal class MigrationChecker @Inject constructor(
    private val projectDependencyGraph: Lazy<ImmutableValueGraph<Project, Configuration>>,
    private val migrationCriteria: Set<@JvmSuppressWildcards MigrationCriteria>
) : MigrationCriteria {
    /**
     * Maintain a cache to not do expensive work when a project is queried again.
     */
    private val migratableProjectCache = ConcurrentSkipListMap<String, Boolean>()

    override fun canMigrate(project: Project): Boolean {

        fun canMigrateInternal(project: Project): Boolean {
            return migratableProjectCache.getOrPut(project.path) {
                migrationCriteria.all { criterion -> criterion.canMigrate(project) }
            }
        }

        return when {
            canMigrateInternal(project) -> true
            else -> project
                .dependenciesSubGraph(projectDependencyGraph.get())
                .all(::canMigrateInternal)
        }
    }
}

/**
 * Validates common plugin usage for migration. Currently checks for following plugins
 * * Java
 * * Kotlin
 * * Android Gradle Plugin
 */
@Singleton
internal class PluginsMigrationCriteria @Inject constructor() : MigrationCriteria {
    override fun canMigrate(project: Project): Boolean {
        return project.isAndroid || project.isJava || project.isKotlin
    }
}

/**
 * Default migrate criteria for Android Project instances
 * * If project uses databinding, allow migration only if user explicitly enabled it.
 */
@Singleton
internal class AndroidMigrationCriteria @Inject constructor(
    private val grazelExtension: GrazelExtension
) : MigrationCriteria {
    override fun canMigrate(project: Project): Boolean {
        return if (!grazelExtension.androidConfiguration.features.dataBinding) {
            !project.hasDatabinding
        } else {
            true
        }
    }
}

/**
 * Calculates if a project is migrateable based on dependencies configuration.
 * For example, if all dependencies of the project are downloaded from supported repository types.
 */
@Singleton
internal class DependenciesMigrationCriteria @Inject constructor(
    private val dependenciesDataSource: DependenciesDataSource
) : MigrationCriteria {
    override fun canMigrate(project: Project): Boolean {
        val hasPrivateDependencies = dependenciesDataSource.hasDepsFromUnsupportedRepositories(project)
        val hasIgnoredArtifacts = dependenciesDataSource.hasIgnoredArtifacts(project)
        return !hasPrivateDependencies && !hasIgnoredArtifacts
    }
}