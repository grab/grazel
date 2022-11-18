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

package com.grab.grazel.gradle.dependencies

import com.grab.grazel.GrazelExtension
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.util.GradleProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import org.gradle.api.Project
import javax.inject.Singleton

@Module
internal interface DependenciesModule {
    @Binds
    fun DefaultDependenciesDataSource.dependenciesDataSource(): DependenciesDataSource

    @Module
    companion object {
        @Provides
        @Singleton
        fun GrazelExtension.provideArtifactsConfig(): ArtifactsConfig = artifactsConfig()

        @Singleton
        @Provides
        fun dependencyResolutionCacheService(
            @RootProject rootProject: Project
        ): GradleProvider<@JvmSuppressWildcards DefaultDependencyResolutionService> =
            DefaultDependencyResolutionService.register(rootProject)
    }
}


private fun GrazelExtension.artifactsConfig() = ArtifactsConfig(
    excludedList = rules.mavenInstall.excludeArtifacts.get(),
    ignoredList = dependencies.ignoreArtifacts.get()
)