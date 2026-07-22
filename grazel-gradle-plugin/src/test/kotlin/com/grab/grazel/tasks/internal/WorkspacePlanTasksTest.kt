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

import com.grab.grazel.buildProject
import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.bazel.starlark.BazelDependency.ProjectDependency
import com.grab.grazel.gradle.dependencies.TargetReferenceFactsCollector
import com.grab.grazel.gradle.dependencies.WorkspacePlanBuilder
import com.grab.grazel.gradle.dependencies.WorkspacePlanService
import com.grab.grazel.gradle.dependencies.WorkspaceRenderPlanService
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.TargetReferenceFacts
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.dependencies.model.WorkspacePlan
import com.grab.grazel.gradle.dependencies.model.WorkspaceRenderPlan
import com.grab.grazel.util.ProgressReporter
import com.grab.grazel.util.fromJson
import com.grab.grazel.util.writeJson
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertTrue

class WorkspacePlanTasksTest {

    @Test
    fun `compute workspace plan task writes plan json and populates service`() {
        val rootProject = buildProject("root")
        val workspaceDependenciesFile = rootProject.layout
            .buildDirectory
            .file("grazel/test-dependencies.json")
            .get()
        workspaceDependenciesFile.asFile.parentFile.mkdirs()
        writeJson(
            WorkspaceDependencies(
                variantDeps = mapOf(
                    "debug" to listOf(ResolvedDependency.fromId("com.example:debug:1.0.0", "repo"))
                )
            ),
            workspaceDependenciesFile
        )

        val workspacePlanService = WorkspacePlanService.register(rootProject)
        val task = ComputeWorkspacePlanTask.register(
            rootProject = rootProject,
            workspacePlanService = workspacePlanService
        ) {
            workspaceDependencies.set(workspaceDependenciesFile)
        }.get()

        task.action()

        val writtenPlan = fromJson<WorkspacePlan>(task.workspacePlan.get())
        assertEquals(
            listOf("com.example:debug:1.0.0"),
            writtenPlan.repoPlan.getValue("debug_maven").pinInputs.map(ResolvedDependency::id)
        )
    }

    @Test
    fun `finalize workspace plan task writes render plan json and populates service`() {
        val rootProject = buildProject("root")
        val workspacePlanFile = rootProject.layout
            .buildDirectory
            .file("grazel/test-workspace-plan.json")
            .get()
        val targetMavenRepoReferencesFile = rootProject.layout
            .buildDirectory
            .file("grazel/test-target-maven-repo-references.json")
            .get()
        workspacePlanFile.asFile.parentFile.mkdirs()
        writeJson(
            WorkspacePlanBuilder().build(
                WorkspaceDependencies(
                    variantDeps = mapOf(
                        "debug" to listOf(ResolvedDependency.fromId("com.example:debug:1.0.0", "repo")),
                        "free" to listOf(ResolvedDependency.fromId("com.example:free:1.0.0", "repo"))
                    )
                )
            ),
            workspacePlanFile
        )
        writeJson(
            TargetReferenceFacts(
                repoNames = setOf("debug_maven"),
                projectTargets = mapOf(
                    ":lint:custom-lint-rules" to setOf("custom-lint-rules"),
                    ":ui-tests" to setOf("ui-tests-gps-pax-debug_lib")
                )
            ),
            targetMavenRepoReferencesFile
        )

        val workspacePlanService = WorkspacePlanService.register(rootProject)
        val workspaceRenderPlanService = WorkspaceRenderPlanService.register(rootProject)
        val task = FinalizeWorkspacePlanTask.register(
            rootProject = rootProject,
            workspacePlanService = workspacePlanService,
            workspaceRenderPlanService = workspaceRenderPlanService
        ) {
            workspacePlan.set(workspacePlanFile)
            targetMavenRepoReferences.set(targetMavenRepoReferencesFile)
        }.get()

        task.action()

        val writtenRenderPlan = fromJson<WorkspaceRenderPlan>(task.workspaceRenderPlan.get())
        assertEquals(
            setOf("debug_maven"),
            writtenRenderPlan.materializedRepoNames
        )
        assertEquals(
            setOf(":lint:custom-lint-rules", ":ui-tests"),
            writtenRenderPlan.referencedProjectPaths
        )
        assertEquals(
            mapOf(
                ":lint:custom-lint-rules" to setOf("custom-lint-rules"),
                ":ui-tests" to setOf("ui-tests-gps-pax-debug_lib")
            ),
            writtenRenderPlan.referencedProjectTargets
        )
        assertTrue(workspaceRenderPlanService.get().isReferencedProjectPath(":lint:custom-lint-rules"))
        assertTrue(workspaceRenderPlanService.get().isReferencedTarget(":ui-tests", "ui-tests-gps-pax-debug_lib"))
        assertEquals(
            setOf("ui-tests-gps-pax-debug_lib"),
            workspaceRenderPlanService.get().referencedTargetNames(":ui-tests")
        )
    }

    @Test
    fun `collect target references reaches targets activated by prior references`() {
        val rootProject = buildProject("root")
        val appProject = buildProject("app", rootProject)
        val uiTestsProject = buildProject("ui-tests", rootProject)
        val workspaceRenderPlanService = WorkspaceRenderPlanService.register(rootProject).get()
        val progressMessages = mutableListOf<String>()

        val references: TargetReferenceFacts = collectTargetMavenRepoReferencesByGroup(
            projects = listOf(uiTestsProject, appProject),
            canMigrate = { true },
            factsForProject = { project ->
                when (project.path) {
                    ":ui-tests" ->
                        TargetReferenceFactsCollector.from(
                            deps = listOf(ProjectDependency(appProject, suffix = "-gps-pax-debug"))
                        )
                    ":app" -> if (workspaceRenderPlanService.isReferencedTarget(":app", "app-gps-pax-debug")) {
                        TargetReferenceFactsCollector.from(
                            deps = listOf(
                                MavenDependency(
                                    repo = "debug_maven",
                                    group = "com.example",
                                    name = "debug-only"
                                )
                            )
                        )
                    } else {
                        TargetReferenceFactsCollector.from()
                    }
                    else -> TargetReferenceFactsCollector.from()
                }
            },
            workspaceRenderPlanService = workspaceRenderPlanService,
            reporter = ProgressReporter(progressMessages::add)
        )

        assertEquals(setOf("debug_maven"), references.repoNames)
        assertEquals(setOf(":app"), references.projectPaths)
        assertEquals(mapOf(":app" to setOf("app-gps-pax-debug")), references.projectTargets)
        assertEquals(
            listOf(
                "collecting (1/2): :ui-tests",
                "collecting (2/2): :app"
            ),
            progressMessages
        )
    }

    @Test
    fun `collect target references uses consumer first single pass`() {
        val rootProject = buildProject("root")
        val appProject = buildProject("app", rootProject)
        val libProject = buildProject("lib", rootProject)
        val uiTestsProject = buildProject("ui-tests", rootProject)
        val workspaceRenderPlanService = WorkspaceRenderPlanService.register(rootProject).get()
        val callsByProject = mutableMapOf<String, Int>()

        val references: TargetReferenceFacts = collectTargetMavenRepoReferencesByGroup(
            projects = listOf(uiTestsProject, appProject, libProject),
            canMigrate = { true },
            factsForProject = { project ->
                callsByProject[project.path] = callsByProject.getOrDefault(project.path, 0) + 1
                when (project.path) {
                    ":ui-tests" ->
                        TargetReferenceFactsCollector.from(
                            deps = listOf(ProjectDependency(appProject, suffix = "-gps-pax-debug"))
                        )
                    ":app" -> if (workspaceRenderPlanService.isReferencedTarget(":app", "app-gps-pax-debug")) {
                        TargetReferenceFactsCollector.from(
                            deps = listOf(
                                MavenDependency(
                                    repo = "debug_maven",
                                    group = "com.example",
                                    name = "app-debug"
                                ),
                                ProjectDependency(libProject, suffix = "-gps-pax-debug")
                            )
                        )
                    } else {
                        TargetReferenceFactsCollector.from()
                    }
                    ":lib" -> if (workspaceRenderPlanService.isReferencedTarget(":lib", "lib-gps-pax-debug")) {
                        TargetReferenceFactsCollector.from(
                            deps = listOf(
                                MavenDependency(
                                    repo = "lint_maven",
                                    group = "com.example",
                                    name = "lib-lint"
                                )
                            )
                        )
                    } else {
                        TargetReferenceFactsCollector.from()
                    }
                    else -> TargetReferenceFactsCollector.from()
                }
            },
            workspaceRenderPlanService = workspaceRenderPlanService,
            reporter = ProgressReporter.NoOp
        )

        assertEquals(setOf("debug_maven", "lint_maven"), references.repoNames)
        assertEquals(setOf(":app", ":lib"), references.projectPaths)
        assertEquals(
            mapOf(
                ":app" to setOf("app-gps-pax-debug"),
                ":lib" to setOf("lib-gps-pax-debug")
            ),
            references.projectTargets
        )
        assertEquals(
            mapOf(
                ":ui-tests" to 1,
                ":app" to 1,
                ":lib" to 1
            ),
            callsByProject
        )
    }

    @Test
    fun `collect target references reaches transitively referenced targets across rounds`() {
        // Reproduces Bug 2: a project reachable only via a reference recorded mid-pass (e.g. a
        // testImplementation-only dependency) can be ordered before its referrer, so a single
        // consumers-first pass drops its own transitive references. `util1` is only referenced by
        // `c`, and `util2` is only referenced by `util1` - both are ordered before `c` here to
        // simulate the mis-ordering, so a single pass would leave `util2` (and even `util1`)
        // unresolved. Only `c` is intrinsically reachable; `util1`/`util2` must be activated by a
        // recorded reference.
        val rootProject = buildProject("root")
        val cProject = buildProject("c", rootProject)
        val util1Project = buildProject("util1", rootProject)
        val util2Project = buildProject("util2", rootProject)
        val workspaceRenderPlanService = WorkspaceRenderPlanService.register(rootProject).get()

        val references: TargetReferenceFacts = collectTargetMavenRepoReferencesByGroup(
            projects = listOf(util2Project, util1Project, cProject),
            canMigrate = { true },
            factsForProject = { project ->
                when (project.path) {
                    ":c" ->
                        TargetReferenceFactsCollector.from(
                            deps = listOf(ProjectDependency(util1Project, suffix = "-gps-pax-debug"))
                        )
                    ":util1" -> if (workspaceRenderPlanService.isReferencedProjectPath(":util1")) {
                        TargetReferenceFactsCollector.from(
                            deps = listOf(ProjectDependency(util2Project, suffix = "-gps-pax-debug"))
                        )
                    } else {
                        TargetReferenceFactsCollector.from()
                    }
                    ":util2" -> if (workspaceRenderPlanService.isReferencedProjectPath(":util2")) {
                        TargetReferenceFactsCollector.from(
                            deps = listOf(
                                MavenDependency(
                                    repo = "debug_maven",
                                    group = "com.example",
                                    name = "util2-debug"
                                )
                            )
                        )
                    } else {
                        TargetReferenceFactsCollector.from()
                    }
                    else -> TargetReferenceFactsCollector.from()
                }
            },
            workspaceRenderPlanService = workspaceRenderPlanService,
            reporter = ProgressReporter.NoOp,
            isIntrinsicallyReachable = { project -> project.path == ":c" }
        )

        assertTrue(":util2" in references.projectTargets)
        assertEquals(
            setOf(":util1", ":util2"),
            references.projectPaths
        )
    }

}
