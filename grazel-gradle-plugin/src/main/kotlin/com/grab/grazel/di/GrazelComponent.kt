/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.di

import com.google.common.graph.ImmutableValueGraph
import com.grab.grazel.GrazelExtension
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.AndroidBuildVariantDataSource
import com.grab.grazel.gradle.AndroidVariantsExtractor
import com.grab.grazel.gradle.ConfigurationDataSource
import com.grab.grazel.gradle.DefaultAndroidBuildVariantDataSource
import com.grab.grazel.gradle.DefaultAndroidVariantsExtractor
import com.grab.grazel.gradle.DefaultConfigurationDataSource
import com.grab.grazel.gradle.DefaultGradleProjectInfo
import com.grab.grazel.gradle.DefaultRepositoryDataSource
import com.grab.grazel.gradle.GradleProjectInfo
import com.grab.grazel.gradle.MigrationChecker
import com.grab.grazel.gradle.MigrationCriteriaModule
import com.grab.grazel.gradle.ProjectDependencyGraphBuilder
import com.grab.grazel.gradle.RepositoryDataSource
import com.grab.grazel.gradle.dependencies.DependenciesModule
import com.grab.grazel.migrate.builder.AndroidBinaryTargetBuilderModule
import com.grab.grazel.migrate.builder.AndroidLibTargetBuilderModule
import com.grab.grazel.migrate.builder.KtAndroidLibTargetBuilderModule
import com.grab.grazel.migrate.builder.KtLibTargetBuilderModule
import com.grab.grazel.migrate.internal.ProjectBazelFileBuilder
import com.grab.grazel.migrate.internal.RootBazelFileBuilder
import com.grab.grazel.migrate.internal.WorkspaceBuilder
import dagger.Binds
import dagger.BindsInstance
import dagger.Component
import dagger.Module
import dagger.Provides
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.internal.logging.progress.ProgressLogger
import org.gradle.internal.logging.progress.ProgressLoggerFactory
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.kotlin.dsl.the
import javax.inject.Singleton

@Component(
    modules = [
        GrazelModule::class,
        KtLibTargetBuilderModule::class,
        KtAndroidLibTargetBuilderModule::class,
        AndroidLibTargetBuilderModule::class,
        AndroidBinaryTargetBuilderModule::class
    ]
)
@Singleton
internal interface GrazelComponent {

    @Component.Factory
    interface Factory {
        fun create(@BindsInstance @RootProject rootProject: Project): GrazelComponent
    }

    fun migrationChecker(): MigrationChecker
    fun progressLogger(): ProgressLogger
    fun gradleProjectInfo(): GradleProjectInfo
    fun projectBazelFileBuilderFactory(): ProjectBazelFileBuilder.Factory
    fun workspaceBuilderFactory(): WorkspaceBuilder.Factory
    fun rootBazelFileBuilder(): RootBazelFileBuilder
}

@Module(
    includes = [
        GrazelModuleBinder::class,
        MigrationCriteriaModule::class,
        DependenciesModule::class
    ]
)
internal object GrazelModule {

    @Provides
    @Singleton
    fun @receiver:RootProject Project.provideGrazelGradlePluginExtension(): GrazelExtension = the()

    @Provides
    @Singleton
    fun @receiver:RootProject Project.provideProgressLoggerFactory(): ProgressLoggerFactory = rootProject.serviceOf()

    @Provides
    @Singleton
    fun ProgressLoggerFactory.provideProgressLogger(): ProgressLogger = newOperation(GradleProjectInfo::class.java)
        .run {
            start("Generating Bazel scripts", null)
        }

    @Provides
    @Singleton
    fun provideDependencyGraph(builder: ProjectDependencyGraphBuilder): ImmutableValueGraph<Project, Configuration> {
        return builder.build()
    }

    @Provides
    @Singleton
    fun GrazelExtension.provideAndroidBuildVariantDataSource(): AndroidBuildVariantDataSource =
        DefaultAndroidBuildVariantDataSource(variantFilter = androidConfiguration.variantFilter)

    @Provides
    @Singleton
    fun GrazelExtension.provideKotlinConfiguration() = rulesConfiguration.kotlin
}

@Module
internal interface GrazelModuleBinder {
    @Binds
    fun DefaultGradleProjectInfo.bindGradleProjectIndo(): GradleProjectInfo

    @Binds
    fun DefaultConfigurationDataSource.bindConfigurationDataSource(): ConfigurationDataSource

    @Binds
    fun DefaultRepositoryDataSource.bindRepositoryDataSource(): RepositoryDataSource

    @Binds
    fun DefaultAndroidVariantsExtractor.bindAndroidVariantsExtractor(): AndroidVariantsExtractor
}



