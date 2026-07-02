/*
 * Copyright 2023 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.tasks.internal

import com.grab.grazel.di.GradleServices
import com.grab.grazel.di.GrazelComponent
import com.grab.grazel.gradle.dependencies.LocalMavenProxyService
import com.grab.grazel.gradle.dependencies.LocalMavenResolvedFactsBuilder
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspacePlan
import com.grab.grazel.gradle.dependencies.model.WorkspaceRenderPlan
import com.grab.grazel.gradle.dependencies.model.allDependencies
import com.grab.grazel.maven.MavenCoordinates
import com.grab.grazel.migrate.dependencies.ArtifactPinner
import com.grab.grazel.migrate.dependencies.LocalMavenResolutionPinContext
import com.grab.grazel.migrate.dependencies.LocalMavenResolutionPinContextFactory
import com.grab.grazel.migrate.dependencies.LocalMavenResolutionStats
import com.grab.grazel.migrate.dependencies.LocalMavenResolutionStatsProvider
import com.grab.grazel.migrate.dependencies.MavenInstallRepositoryInputs
import com.grab.grazel.migrate.dependencies.MavenInstallRepositoryRewrite
import com.grab.grazel.migrate.dependencies.activeMavenInstallLockfileFallbackFacts
import com.grab.grazel.migrate.dependencies.repositoryUrls
import com.grab.grazel.proxy.LocalMavenProxyStats
import com.grab.grazel.util.fromJson
import com.grab.grazel.util.GradleProvider
import dagger.Lazy
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.kotlin.dsl.register
import javax.inject.Inject

@UntrackedTask(because = "Up to date check implemented manually")
internal open class PinMavenArtifactsTask
@Inject
constructor(
    private val artifactPinner: Lazy<ArtifactPinner>,
    private val gradleServices: GradleServices,
    private val localMavenProxyService: GradleProvider<LocalMavenProxyService>,
) : DefaultTask() {

    init {
        group = GRAZEL_TASK_GROUP
        description = "Pin maven artifacts"
    }

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val workspaceFile: RegularFileProperty = gradleServices.objectFactory.fileProperty()

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val workspacePlan: RegularFileProperty = gradleServices.objectFactory.fileProperty()

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val workspaceRenderPlan: RegularFileProperty = gradleServices.objectFactory.fileProperty()

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val mavenInstallRepositoryInputs: RegularFileProperty = gradleServices.objectFactory.fileProperty()

    @get:Input
    val localMavenResolutionEnabled: Property<Boolean> = gradleServices.objectFactory
        .property(Boolean::class.java)
        .convention(false)

    @get:Internal
    val localMavenResolutionRootConfigurations: ListProperty<Configuration> = gradleServices
        .objectFactory
        .listProperty(Configuration::class.java)
        .convention(emptyList())

    @get:Input
    val localMavenResolutionAdditionalGavs: ListProperty<String> = gradleServices
        .objectFactory
        .listProperty(String::class.java)
        .convention(emptyList())

    @TaskAction
    fun action() {
        artifactPinner.get().pinArtifacts(
            workspaceFile = workspaceFile.get().asFile,
            workspacePlan = fromJson<WorkspacePlan>(workspacePlan.get()),
            workspaceRenderPlan = fromJson<WorkspaceRenderPlan>(workspaceRenderPlan.get()),
            mavenInstallRepositoryInputs = fromJson<MavenInstallRepositoryInputs>(mavenInstallRepositoryInputs.get()),
            gradleServices = gradleServices,
            logger = logger,
            localMavenResolutionContextFactory = localMavenResolutionContextFactory(),
        )
    }

    private fun localMavenResolutionContextFactory(): LocalMavenResolutionPinContextFactory? {
        if (!localMavenResolutionEnabled.get()) return null
        val service = localMavenProxyService.get()
        val configuredAdditionalGavs = localMavenResolutionAdditionalGavs.get()
        val rootDirectory = project.layout.projectDirectory.asFile
        return LocalMavenResolutionPinContextFactory { pinnableRepos, repositoryInputs ->
            val activeLockfileFacts = activeMavenInstallLockfileFallbackFacts(
                rootDirectory = rootDirectory,
                activeMavenRepos = pinnableRepos.keys
            )
            val facts = LocalMavenResolvedFactsBuilder(project).build(
                configurations = localMavenResolutionRootConfigurations.get(),
                additionalGavs = pinnableRepoResolutionGavs(
                    pinnableRepos = pinnableRepos,
                    additionalGavs = configuredAdditionalGavs + activeLockfileFacts.gavs
                )
            )
            val repositoryMappings = service.configure(
                facts = facts,
                allowedOriginArtifactPaths = activeLockfileFacts.paths,
                canonicalRepositoryUrls = repositoryUrls(repositoryInputs)
            )
            LocalMavenResolutionPinContext(
                repositoryRewrite = MavenInstallRepositoryRewrite(
                    proxyToCanonicalUrl = repositoryMappings.proxyToCanonicalUrl,
                    canonicalToProxyUrl = repositoryMappings.canonicalToProxyUrl
                ),
                metadataOnlyShortIds = facts.metadataOnlyGavs
                    .mapTo(sortedSetOf()) { gav -> MavenCoordinates.parse(gav).shortId },
                stats = LocalMavenResolutionStatsProvider {
                    localMavenResolutionStatsFrom(service.stats())
                }
            )
        }
    }

    companion object {
        private const val TASK_NAME = "pinMavenArtifacts"

        fun register(
            rootProject: Project,
            grazelComponent: GrazelComponent,
            configureTask: PinMavenArtifactsTask.() -> Unit = {}
        ) = rootProject.tasks.register<PinMavenArtifactsTask>(
            TASK_NAME,
            grazelComponent.artifactPinner(),
            GradleServices.from(rootProject),
            grazelComponent.localMavenProxyService()
        ).apply {
            configure {
                localMavenResolutionEnabled.set(
                    grazelComponent.extension().experiments.localMavenResolution
                )
                localMavenResolutionAdditionalGavs.set(
                    grazelComponent.extension().dependencies.overrideArtifactVersions
                )
                configureTask()
            }
            rootProject.afterEvaluate {
                if (grazelComponent.extension().experiments.localMavenResolution.get()) {
                    configure {
                        usesService(grazelComponent.localMavenProxyService())
                    }
                }
            }
        }
    }
}

private fun localMavenResolutionStatsFrom(proxyStats: LocalMavenProxyStats): LocalMavenResolutionStats =
    LocalMavenResolutionStats(
        artifactHits = proxyStats.artifactHits,
        artifactMisses = proxyStats.artifactMisses,
        alternateArtifactMisses = proxyStats.alternateArtifactMisses,
        lockfileArtifactFallbacks = proxyStats.lockfileArtifactFallbacks,
        metadataOnlyArtifactFallbacks = proxyStats.metadataOnlyArtifactFallbacks,
        gradlePomHits = proxyStats.gradlePomHits,
        knownPomFailures = proxyStats.knownPomFailures,
        originFallbacks = proxyStats.originFallbacks,
        originFailures = proxyStats.originFailures,
        requestFailures = proxyStats.requestFailures,
        checksumHits = proxyStats.checksumHits,
        writeThroughCacheHits = proxyStats.writeThroughCacheHits,
        bytesServed = proxyStats.bytesServed
    )

internal fun pinnableRepoResolutionGavs(
    pinnableRepos: Map<String, List<ResolvedDependency>>,
    additionalGavs: Iterable<String> = emptyList(),
): Set<String> {
    val gavs = sortedSetOf<String>()
    pinnableRepos.values.forEach { pinInputs ->
        pinInputs.forEach { rootDependency ->
            rootDependency.allDependencies.forEach { resolvedDependency ->
                gavs += resolvedDependency.id
            }
        }
    }
    gavs += additionalGavs
    return gavs
}
