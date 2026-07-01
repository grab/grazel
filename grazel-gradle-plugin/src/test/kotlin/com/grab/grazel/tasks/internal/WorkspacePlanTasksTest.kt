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
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.gradle.dependencies.ProjectReachabilityGroup
import com.grab.grazel.gradle.dependencies.TargetReferenceFactsCollector
import com.grab.grazel.gradle.dependencies.WorkspacePlanBuilder
import com.grab.grazel.gradle.dependencies.WorkspacePlanService
import com.grab.grazel.gradle.dependencies.WorkspaceRenderPlanService
import com.grab.grazel.gradle.dependencies.WorkspaceTargetTagPlanService
import com.grab.grazel.gradle.dependencies.WorkspaceTargetTagPlanCollector
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.TargetTagKey
import com.grab.grazel.gradle.dependencies.model.TargetTagPlan
import com.grab.grazel.gradle.dependencies.model.TargetReferenceFacts
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.dependencies.model.WorkspacePlan
import com.grab.grazel.gradle.dependencies.model.WorkspaceRenderPlan
import com.grab.grazel.util.ProgressReporter
import com.grab.grazel.util.fromJson
import com.grab.grazel.util.writeJson
import dagger.Lazy
import org.gradle.api.Project
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
    fun `collect workspace target tag plan task writes stable json`() {
        val rootProject = buildProject("root")
        val workspaceDependenciesFile = rootProject.layout
            .buildDirectory
            .file("grazel/test-dependencies.json")
            .get()
        workspaceDependenciesFile.asFile.parentFile.mkdirs()
        writeJson(
            WorkspaceDependencies(variantDeps = mapOf("default" to emptyList())),
            workspaceDependenciesFile
        )
        val targetTagPlan = listOf(
            TargetTagPlan(
                key = TargetTagKey(
                    variantId = ":lib:debugAndroidBuild",
                    variantType = "AndroidBuild",
                    targetKind = "android_library"
                ),
                tags = listOf("@maven//:com_example_root")
            )
        )
        val task = CollectWorkspaceTargetTagPlanTask.register(
            rootProject = rootProject,
            targetTagPlanCollector = Lazy {
                object : WorkspaceTargetTagPlanCollector {
                    override fun collect(
                        rootProject: Project,
                        reporter: ProgressReporter
                    ): List<TargetTagPlan> = targetTagPlan
                }
            }
        ) {
            workspaceDependencies.set(workspaceDependenciesFile)
            dependencyResolutionService.set(DefaultDependencyResolutionService.register(rootProject))
        }.get()

        task.action()

        assertEquals(targetTagPlan, fromJson<List<TargetTagPlan>>(task.targetTagPlan.get()))
    }

    @Test
    fun `workspace target tag plan service reads target tag plan json`() {
        val rootProject = buildProject("root")
        val targetTagPlanFile = rootProject.layout
            .buildDirectory
            .file("grazel/test-target-tag-plan.json")
            .get()
        targetTagPlanFile.asFile.parentFile.mkdirs()
        val targetTagPlan = listOf(
            TargetTagPlan(
                key = TargetTagKey(
                    variantId = ":lib:debugAndroidBuild",
                    variantType = "AndroidBuild",
                    targetKind = "android_library"
                ),
                tags = listOf("@maven//:com_example_root")
            )
        )
        writeJson(targetTagPlan, targetTagPlanFile)

        val service = WorkspaceTargetTagPlanService.register(rootProject).get()

        service.initTagPlan(targetTagPlanFile.asFile)

        assertEquals(
            listOf("@maven//:com_example_root"),
            service.tagsFor(
                variantId = ":lib:debugAndroidBuild",
                variantType = "AndroidBuild",
                targetKind = "android_library"
            )
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
        targetMavenRepoReferencesFile.asFile.apply {
            parentFile.mkdirs()
            writeText(
                """
                {
                  "repoNames":["debug_maven"],
                  "projectTargets":{
                    ":lint:custom-lint-rules":["custom-lint-rules"],
                    ":ui-tests":["ui-tests-gps-pax-debug_lib"]
                  }
                }
                """.trimIndent()
            )
        }

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
            projectGroups = listOf(uiTestsProject, appProject).map { project ->
                ProjectReachabilityGroup(listOf(project))
            },
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
            projectGroups = listOf(uiTestsProject, appProject, libProject).map { project ->
                ProjectReachabilityGroup(listOf(project))
            },
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
}
