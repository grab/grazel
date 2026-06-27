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
import com.grab.grazel.gradle.dependencies.DefaultDependencyGraphsService
import com.grab.grazel.gradle.MigrationChecker
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.gradle.dependencies.WorkspacePlanService
import com.grab.grazel.gradle.dependencies.DefaultWorkspacePlanService
import com.grab.grazel.gradle.dependencies.ProjectReachabilityGroup
import com.grab.grazel.gradle.dependencies.ProjectReachabilityOrder
import com.grab.grazel.gradle.dependencies.model.TargetMavenRepoReferences
import com.grab.grazel.gradle.dependencies.model.WorkspaceRenderPlan
import com.grab.grazel.migrate.BazelTarget
import com.grab.grazel.migrate.internal.ProjectBazelFileBuilder
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
    private val bazelFileBuilder: Lazy<ProjectBazelFileBuilder.Factory>,
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

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val compressionResults: RegularFileProperty = objectFactory.fileProperty()

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
                .map { subproject -> ProjectReachabilityGroup(listOf(subproject), cyclic = false) }

        val targetReferences = collectTargetMavenRepoReferencesByGroup(
            projectGroups = orderedGroups,
            canMigrate = { subproject -> migrationChecker.get().canMigrate(subproject) },
            targetsForProject = { subproject -> bazelFileBuilder.get().create(subproject).targets() },
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
            grazelComponent.projectBazelFileBuilderFactory(),
            grazelComponent.dependencyGraphsService(),
            grazelComponent.workspacePlanService(),
            rootProject.objects,
            rootProject.layout
        ).apply {
            configure {
                group = GRAZEL_TASK_GROUP
                description = "Collect Maven repository references from generated target models"
                action(this)
            }
        }
    }
}

internal fun collectTargetMavenRepoReferences(
    projects: Iterable<Project>,
    canMigrate: (Project) -> Boolean,
    targetsForProject: (Project) -> List<BazelTarget>,
    workspacePlanService: WorkspacePlanService
): TargetMavenRepoReferences =
    collectTargetMavenRepoReferencesByGroup(
        projectGroups = projects.map { project -> ProjectReachabilityGroup(listOf(project), cyclic = false) },
        canMigrate = canMigrate,
        targetsForProject = targetsForProject,
        workspacePlanService = workspacePlanService
    )

internal fun collectTargetMavenRepoReferencesByGroup(
    projectGroups: Iterable<ProjectReachabilityGroup>,
    canMigrate: (Project) -> Boolean,
    targetsForProject: (Project) -> List<BazelTarget>,
    workspacePlanService: WorkspacePlanService
): TargetMavenRepoReferences {
    val references = collectTargetMavenRepoReferencesSinglePass(
        projectGroups = projectGroups,
        canMigrate = canMigrate,
        targetsForProject = targetsForProject,
        workspacePlanService = workspacePlanService
    )

    workspacePlanService.populateRenderPlan(references.asRenderPlan())
    return references
}

private fun collectTargetMavenRepoReferencesSinglePass(
    projectGroups: Iterable<ProjectReachabilityGroup>,
    canMigrate: (Project) -> Boolean,
    targetsForProject: (Project) -> List<BazelTarget>,
    workspacePlanService: WorkspacePlanService
): TargetMavenRepoReferences {
    var accumulated = TargetMavenRepoReferences()
    projectGroups.forEach { group ->
        check(!group.cyclic) {
            "Cannot collect target Maven repo references for cyclic project group " +
                group.projects.map(Project::getPath).sorted() +
                ". ProjectReachabilityOrder must fail typed SCCs before target collection."
        }
        accumulated = group.projects.fold(accumulated) { current, project ->
            collectProjectReferences(
                accumulated = current,
                project = project,
                canMigrate = canMigrate,
                targetsForProject = targetsForProject,
                workspacePlanService = workspacePlanService
            )
        }
    }

    return accumulated.normalized()
}

private fun collectProjectReferences(
    accumulated: TargetMavenRepoReferences,
    project: Project,
    canMigrate: (Project) -> Boolean,
    targetsForProject: (Project) -> List<BazelTarget>,
    workspacePlanService: WorkspacePlanService
): TargetMavenRepoReferences {
    workspacePlanService.populateRenderPlan(accumulated.asRenderPlan())
    if (!canMigrate(project)) {
        return accumulated
    }
    return mergeTargetMavenRepoReferences(
        accumulated,
        TargetMavenRepoReferencesCollector.fromTargets(targetsForProject(project))
    )
}

private fun TargetMavenRepoReferences.asRenderPlan(): WorkspaceRenderPlan =
    WorkspaceRenderPlan(
        referencedProjectPaths = projectPaths,
        referencedProjectTargets = projectTargets
    )

private fun mergeTargetMavenRepoReferences(
    left: TargetMavenRepoReferences,
    right: TargetMavenRepoReferences
): TargetMavenRepoReferences =
    TargetMavenRepoReferences(
        repoNames = left.repoNames + right.repoNames,
        projectPaths = left.projectPaths + right.projectPaths,
        projectTargets = mergeProjectTargets(left.projectTargets, right.projectTargets)
    )

private fun mergeProjectTargets(
    left: Map<String, Set<String>>,
    right: Map<String, Set<String>>
): Map<String, Set<String>> {
    return (left.keys + right.keys)
        .associateWith { projectPath ->
            left.getOrDefault(projectPath, emptySet()) +
                right.getOrDefault(projectPath, emptySet())
        }
        .toSortedMap()
}

private fun TargetMavenRepoReferences.normalized(): TargetMavenRepoReferences =
    TargetMavenRepoReferences(
        repoNames = repoNames.toSortedSet(),
        projectPaths = projectPaths.toSortedSet(),
        projectTargets = projectTargets
            .mapValues { (_, targetNames) -> targetNames.toSortedSet() }
            .toSortedMap()
    )
