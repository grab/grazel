/*
 * Copyright 2021 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.di

import com.grab.grazel.GrazelExtension
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.AndroidVariantDataSource
import com.grab.grazel.gradle.AndroidVariantsExtractor
import com.grab.grazel.gradle.ConfigurationDataSource
import com.grab.grazel.gradle.DefaultAndroidVariantDataSource
import com.grab.grazel.gradle.DefaultAndroidVariantsExtractor
import com.grab.grazel.gradle.DefaultConfigurationDataSource
import com.grab.grazel.gradle.DefaultGradleProjectInfo
import com.grab.grazel.gradle.DefaultRepositoryDataSource
import com.grab.grazel.gradle.GradleProjectInfo
import com.grab.grazel.gradle.MigrationChecker
import com.grab.grazel.gradle.MigrationCriteriaModule
import com.grab.grazel.gradle.RepositoryDataSource
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import com.grab.grazel.gradle.dependencies.DependenciesGraphsBuilder
import com.grab.grazel.gradle.dependencies.DependenciesModule
import com.grab.grazel.gradle.dependencies.DependencyGraphs
import com.grab.grazel.migrate.builder.AndroidBinaryTargetBuilderModule
import com.grab.grazel.migrate.builder.AndroidLibTargetBuilderModule
import com.grab.grazel.migrate.builder.KtAndroidLibTargetBuilderModule
import com.grab.grazel.migrate.builder.KtLibTargetBuilderModule
import com.grab.grazel.migrate.dependencies.ArtifactsPinner
import com.grab.grazel.migrate.dependencies.DefaultArtifactsPinner
import com.grab.grazel.migrate.internal.ProjectBazelFileBuilder
import com.grab.grazel.migrate.internal.RootBazelFileBuilder
import com.grab.grazel.migrate.internal.WorkspaceBuilder
import dagger.Binds
import dagger.BindsInstance
import dagger.Component
import dagger.Lazy
import dagger.Module
import dagger.Provides
import org.gradle.api.Project
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

    fun extension(): GrazelExtension
    fun gradleProjectInfo(): GradleProjectInfo
    fun migrationChecker(): Lazy<MigrationChecker>
    fun progressLogger(): Lazy<ProgressLogger>
    fun projectBazelFileBuilderFactory(): Lazy<ProjectBazelFileBuilder.Factory>
    fun workspaceBuilderFactory(): Lazy<WorkspaceBuilder.Factory>
    fun rootBazelFileBuilder(): Lazy<RootBazelFileBuilder>
    fun artifactsPinner(): Lazy<ArtifactsPinner>
    fun dependenciesDataSource(): Lazy<DependenciesDataSource>
}

@Module(
    includes = [
        GrazelModuleBinder::class,
        MigrationCriteriaModule::class,
        DependenciesModule::class
    ]
)
internal object GrazelModule {

    @Singleton
    @Provides
    fun @receiver:RootProject Project.provideGrazelGradlePluginExtension(): GrazelExtension = the()

    @Provides
    @Singleton
    fun @receiver:RootProject Project.provideProgressLoggerFactory(): ProgressLoggerFactory =
        rootProject.serviceOf()

    @Provides
    @Singleton
    fun ProgressLoggerFactory.provideProgressLogger(): ProgressLogger =
        newOperation(GradleProjectInfo::class.java)
            .run {
                start("Generating Bazel scripts", null)
            }

    @Provides
    @Singleton
    fun DependenciesGraphsBuilder.provideDependencyGraphs(): DependencyGraphs = build()

    @Provides
    @Singleton
    fun GrazelExtension.provideAndroidVariantDataSource(
        androidVariantsExtractor: DefaultAndroidVariantsExtractor,
        @RootProject rootProject: Project
    ): AndroidVariantDataSource = DefaultAndroidVariantDataSource(
        variantFilter = android.variantFilter,
        androidVariantsExtractor = androidVariantsExtractor
    )

    @Provides
    @Singleton
    fun GrazelExtension.provideKotlinExtension() = rules.kotlin

    @Provides
    @Singleton
    fun GrazelExtension.provideTestExtension() = rules.test

    @Provides
    @Singleton
    fun GrazelExtension.provideMavenInstallExtension() = rules.mavenInstall
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

    @Binds
    fun DefaultArtifactsPinner.bindArtifactsPinner(): ArtifactsPinner
}



