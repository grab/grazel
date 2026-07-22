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
import com.grab.grazel.di.GradleServices
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.MigrationChecker
import com.grab.grazel.gradle.dependencies.DefaultDependencyGraphsService
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.gradle.dependencies.ProjectReachabilityOrder
import com.grab.grazel.gradle.dependencies.WorkspaceRenderPlanService
import com.grab.grazel.gradle.dependencies.asRenderPlan
import com.grab.grazel.gradle.dependencies.mergeTargetReferenceFacts
import com.grab.grazel.gradle.dependencies.model.TargetReferenceFacts
import com.grab.grazel.gradle.dependencies.normalized
import com.grab.grazel.migrate.target.TargetReferenceFactsExtractor
import com.grab.grazel.util.GradleProvider
import com.grab.grazel.util.logHeap
import com.grab.grazel.util.ProgressReporter
import com.grab.grazel.util.withProgress
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
    private val workspaceRenderPlanService: GradleProvider<WorkspaceRenderPlanService>,
    objectFactory: ObjectFactory,
    layout: ProjectLayout
) : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val workspaceDependencies: RegularFileProperty = objectFactory.fileProperty()

    @get:Internal
    val dependencyResolutionService: Property<DefaultDependencyResolutionService> =
        objectFactory.property()

    @get:OutputFile
    val targetMavenRepoReferences: RegularFileProperty = objectFactory.fileProperty().apply {
        set(layout.buildDirectory.file("grazel/target-maven-repo-references.json"))
    }

    /**
     * Orders projects consumers-first ([ProjectReachabilityOrder.consumersFirstProjects]) with any
     * subprojects absent from the dependency graph appended afterwards, before delegating to
     * [collectTargetMavenRepoReferences]. This ordering is required for correctness, not
     * just efficiency: fact collection incrementally populates the render plan as it goes, so a
     * consumer must be visited after the projects it depends on have already contributed their
     * facts to the render plan.
     *
     * That ordering is necessary but not sufficient: [ProjectReachabilityOrder.consumersFirstProjects]
     * dedups its typed (project, source-set) nodes down to one slot per project by keeping
     * whichever typed node happens to occur first in the consumers-first walk - which is not
     * necessarily that project's *Main* node. A project whose own `Test`/`AndroidTest` node has
     * edges of its own (e.g. the source-set-inheritance edge back to its own `Main` node, or a
     * further project dependency declared on that test source set) can get a node that sorts
     * earlier than the actual external consumer's node, landing the project before its referrer in
     * the flattened order despite the sort being correct. [dependedUponProjects] - "does *any* typed
     * edge, of *any* variant type, point at this project" - identifies exactly the projects whose
     * settling must therefore wait for an actual render-plan reference rather than being assumed
     * unconditionally safe; projects nothing points at (true roots: binaries, standalone modules)
     * are passed through as intrinsically reachable. See [collectTargetMavenRepoReferences]'s KDoc
     * for the single-pass-plus-drain worklist this feeds.
     */
    @TaskAction
    fun action() {
        logger.logHeap("CollectTargetMavenRepoReferences:start")
        dependencyResolutionService.get().init(workspaceDependencies.get().asFile)

        val orderedProjects = ProjectReachabilityOrder
            .consumersFirstProjects(
                dependencyGraphsService.get().get(),
                variantTypeFilter = { true }
            )
        val reachabilityProjects = orderedProjects.toSet()
        val projects = orderedProjects +
            project.rootProject.subprojects
                .filterNot { subproject -> subproject in reachabilityProjects }
                .sortedBy(Project::getPath)
        val totalProjects = projects.size
        val dependedUponProjects = dependencyGraphsService.get().get()
            .mergeToProjectGraph(variantTypeFilter = { true })
            .values
            .flatten()
            .toSet()

        val startedAt = System.nanoTime()
        val targetReferences = GradleServices.from(project).progressLoggerFactory.withProgress(
            "collecting target Maven repo references"
        ) { reporter ->
            collectTargetMavenRepoReferences(
                projects = projects,
                canMigrate = { subproject -> migrationChecker.get().canMigrate(subproject) },
                factsForProject = { subproject -> targetReferenceFactsExtractor.get().collect(subproject) },
                workspaceRenderPlanService = workspaceRenderPlanService.get(),
                reporter = reporter,
                isIntrinsicallyReachable = { subproject -> subproject !in dependedUponProjects }
            )
        }

        writeJson(targetReferences, targetMavenRepoReferences.get())
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        logger.quiet(
            "Collected target references across $totalProjects modules in ${elapsedMs}ms"
        )
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
            grazelComponent.workspaceRenderPlanService(),
            rootProject.objects,
            rootProject.layout
        ).apply {
            configure {
                group = GRAZEL_TASK_GROUP
                description = "Collect Maven repository references from target reference facts"
                usesService(grazelComponent.dependencyGraphsService())
                usesService(grazelComponent.workspaceRenderPlanService())
                usesService(grazelComponent.dependencyResolutionService())
                action(this)
            }
        }
    }
}

/**
 * Collects reference facts with a single consumers-first pass plus a deferred-activation drain,
 * then normalizes and republishes the settled view into [workspaceRenderPlanService] — the
 * ordering between raw accumulation and normalization is load-bearing, not incidental (the
 * render plan is also populated incrementally mid-pass with pre-normalization state).
 *
 * Semantics (unchanged from the round-based fixpoint this replaces): every project is extracted
 * at most once while inactive (pass 1) and at most once when it first becomes active — either
 * immediately in pass 1, or by the drain once a later-visited project's reference activates it.
 * A reference to a *different target name* of an already-processed project never re-extracts;
 * activation is project-path-granular by design.
 *
 * @param isIntrinsicallyReachable Marks projects whose inclusion doesn't depend on being
 * referenced first — typically true roots that nothing else in the dependency graph points at
 * (e.g. binaries). Anything else stays deferred until a recorded reference activates it.
 * Defaults to `{ true }`, i.e. everything settles in pass 1.
 */
internal fun collectTargetMavenRepoReferences(
    projects: List<Project>,
    canMigrate: (Project) -> Boolean,
    factsForProject: (Project) -> TargetReferenceFacts,
    workspaceRenderPlanService: WorkspaceRenderPlanService,
    reporter: ProgressReporter,
    isIntrinsicallyReachable: (Project) -> Boolean = { true }
): TargetReferenceFacts {
    val referenceFacts = collectToQuiescence(
        projects = projects,
        canMigrate = canMigrate,
        factsForProject = factsForProject,
        workspaceRenderPlanService = workspaceRenderPlanService,
        reporter = reporter,
        isIntrinsicallyReachable = isIntrinsicallyReachable
    )
    val references = referenceFacts.normalized()

    workspaceRenderPlanService.populateRenderPlan(references.asRenderPlan())
    return references
}

/**
 * Pass 1 visits every project in consumers-first order — publishing the accumulated facts
 * before each visit so extraction observes the current render plan, exactly as the old round 1
 * did. A migratable project that is inactive at visit time (neither [isIntrinsicallyReachable]
 * nor referenced yet) still contributes its extraction, and is parked in [deferred] in visit
 * order. The drain then repeatedly activates the first deferred project that has become
 * referenced (references only ever grow, so scanning in original order mirrors what the old
 * round n+1 would have processed first) until a full scan activates nothing. Termination is
 * structural: [deferred] only shrinks.
 */
private fun collectToQuiescence(
    projects: List<Project>,
    canMigrate: (Project) -> Boolean,
    factsForProject: (Project) -> TargetReferenceFacts,
    workspaceRenderPlanService: WorkspaceRenderPlanService,
    reporter: ProgressReporter,
    isIntrinsicallyReachable: (Project) -> Boolean
): TargetReferenceFacts {
    val totalProjects = projects.size
    var accumulated = TargetReferenceFacts()
    val deferred = LinkedHashSet<Project>()

    projects.forEachIndexed { index, project ->
        workspaceRenderPlanService.populateRenderPlan(accumulated.asRenderPlan())
        reporter.report("collecting (${index + 1}/$totalProjects): ${project.path}")
        if (!canMigrate(project)) return@forEachIndexed
        val isActive = isIntrinsicallyReachable(project) ||
            workspaceRenderPlanService.isReferencedProjectPath(project.path)
        accumulated = mergeTargetReferenceFacts(accumulated, factsForProject(project))
        if (!isActive) {
            deferred += project
        }
    }

    // Each pull publishes the latest accumulated facts, then scans for the first deferred
    // project that has become referenced; the sequence ends when a scan finds none.
    generateSequence {
        workspaceRenderPlanService.populateRenderPlan(accumulated.asRenderPlan())
        deferred.firstOrNull { project ->
            workspaceRenderPlanService.isReferencedProjectPath(project.path)
        }
    }.forEach { activated ->
        deferred.remove(activated)
        reporter.report("collecting (activated): ${activated.path}")
        accumulated = mergeTargetReferenceFacts(accumulated, factsForProject(activated))
    }

    return accumulated
}
