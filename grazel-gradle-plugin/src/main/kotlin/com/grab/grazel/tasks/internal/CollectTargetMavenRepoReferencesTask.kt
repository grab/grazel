/*
 * Copyright 2026 Grabtaxi Holdings PTE LTD (GRAB)
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

import com.grab.grazel.di.GrazelComponent
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.MigrationChecker
import com.grab.grazel.gradle.dependencies.DefaultDependencyGraphsService
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.gradle.dependencies.DefaultWorkspacePlanService
import com.grab.grazel.gradle.dependencies.ProjectReachabilityGroup
import com.grab.grazel.gradle.dependencies.ProjectReachabilityOrder
import com.grab.grazel.gradle.dependencies.WorkspacePlanService
import com.grab.grazel.gradle.dependencies.asRenderPlan
import com.grab.grazel.gradle.dependencies.mergeTargetReferenceFacts
import com.grab.grazel.gradle.dependencies.model.TargetReferenceFacts
import com.grab.grazel.gradle.dependencies.normalized
import com.grab.grazel.migrate.target.TargetReferenceFactsExtractor
import com.grab.grazel.util.GradleProvider
import com.grab.grazel.util.logHeap
import com.grab.grazel.util.writeJson
import dagger.Lazy
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.register
import javax.inject.Inject

@UntrackedTask(because = "Build target model inputs are not fully declared yet")
internal open class CollectTargetMavenRepoReferencesTask
@Inject
constructor(
    private val migrationChecker: Lazy<MigrationChecker>,
    private val targetReferenceFactsExtractor: Lazy<TargetReferenceFactsExtractor>,
    private val dependencyGraphsService: GradleProvider<DefaultDependencyGraphsService>,
    private val workspacePlanService: GradleProvider<DefaultWorkspacePlanService>,
    objectFactory: ObjectFactory,
    layout: ProjectLayout
) : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val workspaceDependencies: RegularFileProperty = objectFactory.fileProperty()

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val workspacePlan: RegularFileProperty = objectFactory.fileProperty()

    @get:Internal
    val dependencyResolutionService: Property<DefaultDependencyResolutionService> =
        objectFactory.property()

    @get:OutputFile
    val targetMavenRepoReferences: RegularFileProperty = objectFactory.fileProperty().apply {
        set(layout.buildDirectory.file("grazel/target-maven-repo-references.json"))
    }

    @TaskAction
    fun action() {
        logger.logHeap("CollectTargetMavenRepoReferences:start")
        dependencyResolutionService.get().init(workspaceDependencies.get().asFile)
        workspacePlanService.get().initPlan(workspacePlan.get().asFile)

        val reachabilityGroups = ProjectReachabilityOrder
            .consumersFirstGroups(
                dependencyGraphsService.get().get(),
                variantTypeFilter = { true }
            )
        val graphProjects = reachabilityGroups.flatMap(ProjectReachabilityGroup::projects).toSet()
        val orderedGroups = reachabilityGroups +
            project.rootProject.subprojects
                .filterNot { subproject -> subproject in graphProjects }
                .sortedBy(Project::getPath)
                .map { subproject -> ProjectReachabilityGroup(listOf(subproject)) }

        val targetReferences = collectTargetMavenRepoReferencesByGroup(
            projectGroups = orderedGroups,
            canMigrate = { subproject -> migrationChecker.get().canMigrate(subproject) },
            factsForProject = { subproject -> targetReferenceFactsExtractor.get().collect(subproject) },
            workspacePlanService = workspacePlanService.get()
        )

        writeJson(targetReferences, targetMavenRepoReferences.get())
        logger.logHeap("CollectTargetMavenRepoReferences:done")
    }

    companion object {
        private const val TASK_NAME = "collectTargetMavenRepoReferences"

        fun register(
            @RootProject rootProject: Project,
            grazelComponent: GrazelComponent,
            action: CollectTargetMavenRepoReferencesTask.() -> Unit = {}
        ) = rootProject.tasks.register<CollectTargetMavenRepoReferencesTask>(
            TASK_NAME,
            grazelComponent.migrationChecker(),
            grazelComponent.targetReferenceFactsExtractor(),
            grazelComponent.dependencyGraphsService(),
            grazelComponent.workspacePlanService(),
            rootProject.objects,
            rootProject.layout
        ).apply {
            configure {
                group = GRAZEL_TASK_GROUP
                description = "Collect Maven repository references from target reference facts"
                action(this)
            }
        }
    }
}

internal fun collectTargetMavenRepoReferences(
    projects: Iterable<Project>,
    canMigrate: (Project) -> Boolean,
    factsForProject: (Project) -> TargetReferenceFacts,
    workspacePlanService: WorkspacePlanService
): TargetReferenceFacts =
    collectTargetMavenRepoReferencesByGroup(
        projectGroups = projects.map { project -> ProjectReachabilityGroup(listOf(project)) },
        canMigrate = canMigrate,
        factsForProject = factsForProject,
        workspacePlanService = workspacePlanService
    )

internal fun collectTargetMavenRepoReferencesByGroup(
    projectGroups: Iterable<ProjectReachabilityGroup>,
    canMigrate: (Project) -> Boolean,
    factsForProject: (Project) -> TargetReferenceFacts,
    workspacePlanService: WorkspacePlanService
): TargetReferenceFacts {
    val referenceFacts = collectTargetMavenRepoReferencesSinglePass(
        projectGroups = projectGroups,
        canMigrate = canMigrate,
        factsForProject = factsForProject,
        workspacePlanService = workspacePlanService
    )
    val references = referenceFacts.normalized()

    workspacePlanService.populateRenderPlan(references.asRenderPlan())
    return references
}

private fun collectTargetMavenRepoReferencesSinglePass(
    projectGroups: Iterable<ProjectReachabilityGroup>,
    canMigrate: (Project) -> Boolean,
    factsForProject: (Project) -> TargetReferenceFacts,
    workspacePlanService: WorkspacePlanService
): TargetReferenceFacts {
    var accumulated = TargetReferenceFacts()
    projectGroups.forEach { group ->
        accumulated = group.projects.fold(accumulated) { current, project ->
            collectProjectReferences(
                accumulated = current,
                project = project,
                canMigrate = canMigrate,
                factsForProject = factsForProject,
                workspacePlanService = workspacePlanService
            )
        }
    }

    return accumulated.normalized()
}

private fun collectProjectReferences(
    accumulated: TargetReferenceFacts,
    project: Project,
    canMigrate: (Project) -> Boolean,
    factsForProject: (Project) -> TargetReferenceFacts,
    workspacePlanService: WorkspacePlanService
): TargetReferenceFacts {
    workspacePlanService.populateRenderPlan(accumulated.asRenderPlan())
    if (!canMigrate(project)) {
        return accumulated
    }
    return mergeTargetReferenceFacts(
        accumulated,
        factsForProject(project)
    )
}
