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
     * [collectTargetMavenRepoReferencesByGroup]. This ordering is required for correctness, not
     * just efficiency: fact collection incrementally populates the render plan as it goes (see
     * [collectProjectReferences]), so a consumer must be visited after the projects it depends on
     * have already contributed their facts to the render plan.
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
     * are passed through as intrinsically reachable. See
     * [collectTargetMavenRepoReferencesByGroup]'s KDoc for the fixed-point iteration this feeds.
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
            collectTargetMavenRepoReferencesByGroup(
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
 * Runs the accumulation pass to a fixed point and only *after* it converges normalizes the
 * accumulated facts and republishes them into [workspaceRenderPlanService]. The render plan is
 * also populated incrementally mid-pass (see [collectProjectReferences]) with pre-normalization
 * state; this final normalize-then-republish step is what downstream readers actually rely on as
 * the settled, de-duplicated view - the ordering between raw accumulation and normalization is
 * load-bearing, not incidental.
 *
 * A single consumers-first pass over [projects] is not always enough: a project that is only
 * reachable via a reference recorded mid-pass (e.g. a `testImplementation`-only dependency, see
 * [isIntrinsicallyReachable]) can be visited before the project that references it, so its own
 * transitive references would be silently dropped. [mergeTargetReferenceFacts] /
 * `mergeProjectTargets` is a pure, monotonic set-union - it only ever grows the accumulated facts
 * - so repeating the ordered pass until a round adds nothing new converges to the true transitive
 * closure, and is byte-identical to a single pass wherever a single pass already sufficed (the
 * extra round is then a no-op).
 *
 * @param isIntrinsicallyReachable Marks projects whose inclusion doesn't depend on being
 * referenced first - typically true roots that nothing else in the dependency graph points at
 * (e.g. binaries). Such projects settle - and are skipped in later rounds - as soon as they are
 * visited once. A project for which this returns `false` only settles once it has actually been
 * referenced (per [WorkspaceRenderPlanService.isReferencedProjectPath]) at the time it is
 * visited; until then it is re-visited every round so a late-arriving reference can still
 * activate it. Defaults to `{ true }`, i.e. today's single-pass-settles-everything behaviour.
 */
internal fun collectTargetMavenRepoReferencesByGroup(
    projects: List<Project>,
    canMigrate: (Project) -> Boolean,
    factsForProject: (Project) -> TargetReferenceFacts,
    workspaceRenderPlanService: WorkspaceRenderPlanService,
    reporter: ProgressReporter,
    isIntrinsicallyReachable: (Project) -> Boolean = { true }
): TargetReferenceFacts {
    val referenceFacts = collectTargetMavenRepoReferencesToFixedPoint(
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
 * Repeats [collectTargetMavenRepoReferencesSinglePass] until a round adds no new project targets
 * or repo names to the accumulated facts. `settledProjects` carries forward across rounds so that
 * a project which already produced its final facts (see [collectProjectReferences]) is neither
 * re-visited (no [factsForProject] call) nor reported again - in the common case where every
 * project activates in round one, this keeps the cost at exactly one call per project, matching
 * the pre-fixpoint behaviour. `everVisited` carries forward alongside it so that a project which
 * has been visited once but is still neither settled, referenced, nor intrinsically reachable -
 * i.e. genuinely unreachable-so-far - is skipped in later rounds too (see
 * [collectTargetMavenRepoReferencesSinglePass]) rather than paying its [factsForProject] cost every
 * round until something else finally references it. A hard round cap guarantees termination even
 * if convergence logic were ever broken: at worst one additional project settles per round, plus
 * one final round to observe that nothing changed and stop, so `totalProjects + 1` rounds are
 * always sufficient for a genuinely convergent accumulation.
 */
private fun collectTargetMavenRepoReferencesToFixedPoint(
    projects: List<Project>,
    canMigrate: (Project) -> Boolean,
    factsForProject: (Project) -> TargetReferenceFacts,
    workspaceRenderPlanService: WorkspaceRenderPlanService,
    reporter: ProgressReporter,
    isIntrinsicallyReachable: (Project) -> Boolean
): TargetReferenceFacts {
    val totalProjects = projects.size
    val maxRounds = totalProjects + 1
    val settledProjects = mutableSetOf<String>()
    val everVisited = mutableSetOf<String>()
    var accumulated = TargetReferenceFacts()
    var round = 0
    while (true) {
        round += 1
        check(round <= maxRounds) {
            "Target reference collection did not converge after $maxRounds round(s) across " +
                "$totalProjects project(s); this should be impossible since the accumulated " +
                "facts only ever grow - check mergeTargetReferenceFacts for a regression."
        }
        val beforeRound = accumulated
        accumulated = collectTargetMavenRepoReferencesSinglePass(
            projects = projects,
            totalProjects = totalProjects,
            canMigrate = canMigrate,
            factsForProject = factsForProject,
            workspaceRenderPlanService = workspaceRenderPlanService,
            reporter = reporter,
            accumulated = accumulated,
            settledProjects = settledProjects,
            everVisited = everVisited,
            isIntrinsicallyReachable = isIntrinsicallyReachable
        )
        if (accumulated == beforeRound) break
    }

    return accumulated
}

/**
 * Skips a project this round - no [factsForProject] call, no progress line - unless it is not yet
 * settled AND either it hasn't been visited before, or it has since become eligible to produce new
 * facts (it is now [isIntrinsicallyReachable] or referenced per
 * [WorkspaceRenderPlanService.isReferencedProjectPath]). A project visited once that is neither
 * settled nor active is genuinely unreachable-so-far: re-running [factsForProject] on it every
 * round without a new reference having appeared cannot change the outcome, so it is left alone
 * until a later round's reference actually activates it.
 *
 * The render plan is still republished (cheap - a set union, no [factsForProject] call) for every
 * not-yet-settled project regardless of whether it is skipped, *before* the skip decision is made:
 * [WorkspaceRenderPlanService.isReferencedProjectPath] is otherwise only ever refreshed as a
 * side effect of visiting a project, so without this a reference recorded by a later project in
 * this same fold (e.g. `c` adding a reference to `util1`) would never become visible to the skip
 * check for a project ordered after it, and that project would be skipped forever instead of
 * being activated on the next round.
 */
private fun collectTargetMavenRepoReferencesSinglePass(
    projects: List<Project>,
    totalProjects: Int,
    canMigrate: (Project) -> Boolean,
    factsForProject: (Project) -> TargetReferenceFacts,
    workspaceRenderPlanService: WorkspaceRenderPlanService,
    reporter: ProgressReporter,
    accumulated: TargetReferenceFacts,
    settledProjects: MutableSet<String>,
    everVisited: MutableSet<String>,
    isIntrinsicallyReachable: (Project) -> Boolean
): TargetReferenceFacts {
    var visitedProjects = 0
    return projects.fold(accumulated) { acc, project ->
        if (project.path in settledProjects) {
            return@fold acc
        }
        workspaceRenderPlanService.populateRenderPlan(acc.asRenderPlan())
        val shouldVisit = project.path !in everVisited ||
            workspaceRenderPlanService.isReferencedProjectPath(project.path)
        if (!shouldVisit) {
            acc
        } else {
            everVisited += project.path
            visitedProjects += 1
            reporter.report("collecting ($visitedProjects/$totalProjects): ${project.path}")
            collectProjectReferences(
                accumulated = acc,
                project = project,
                canMigrate = canMigrate,
                factsForProject = factsForProject,
                workspaceRenderPlanService = workspaceRenderPlanService,
                isIntrinsicallyReachable = isIntrinsicallyReachable,
                settledProjects = settledProjects
            )
        }
    }
}

/**
 * Assumes the caller ([collectTargetMavenRepoReferencesSinglePass]'s fold) has already published
 * [accumulated] into [workspaceRenderPlanService] before invoking this function, so the render
 * plan reflects partial/pre-merge state even for a project that turns out to be non-migratable
 * and gets skipped. Downstream lookups against the render plan can therefore observe this
 * project's accumulated-so-far facts despite it contributing nothing further itself - an
 * intentional but easy-to-miss ordering dependency between publishing and filtering.
 *
 * Marks [project] settled - via [settledProjects] - once it has produced facts that cannot
 * change on a later round: either it doesn't need a reference to be included in the first place
 * ([isIntrinsicallyReachable]), or it was already referenced (per
 * [WorkspaceRenderPlanService.isReferencedProjectPath]) at the moment it was visited. A
 * non-migratable project is also settled immediately since migratability can't change between
 * rounds. Otherwise the project is left unsettled so a reference contributed later in this round
 * (or a subsequent one) still gets a chance to activate it.
 */
private fun collectProjectReferences(
    accumulated: TargetReferenceFacts,
    project: Project,
    canMigrate: (Project) -> Boolean,
    factsForProject: (Project) -> TargetReferenceFacts,
    workspaceRenderPlanService: WorkspaceRenderPlanService,
    isIntrinsicallyReachable: (Project) -> Boolean,
    settledProjects: MutableSet<String>
): TargetReferenceFacts {
    if (!canMigrate(project)) {
        settledProjects += project.path
        return accumulated
    }
    val isActive = isIntrinsicallyReachable(project) ||
        workspaceRenderPlanService.isReferencedProjectPath(project.path)
    val merged = mergeTargetReferenceFacts(
        accumulated,
        factsForProject(project)
    )
    if (isActive) {
        settledProjects += project.path
    }
    return merged
}
