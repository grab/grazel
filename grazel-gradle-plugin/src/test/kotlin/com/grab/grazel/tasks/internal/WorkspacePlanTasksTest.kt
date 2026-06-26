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
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.gradle.dependencies.WorkspacePlanBuilder
import com.grab.grazel.gradle.dependencies.WorkspacePlanService
import com.grab.grazel.gradle.dependencies.WorkspaceTargetTagPlanCollector
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.TargetTagKey
import com.grab.grazel.gradle.dependencies.model.TargetTagPlan
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.dependencies.model.WorkspacePlan
import com.grab.grazel.gradle.dependencies.model.WorkspaceRenderPlan
import com.grab.grazel.util.fromJson
import com.grab.grazel.util.writeJson
import dagger.Lazy
import org.gradle.api.Project
import org.junit.Assert.assertEquals
import org.junit.Test

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
        assertEquals(writtenPlan, workspacePlanService.get().getPlan())
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
                    override fun collect(rootProject: Project): List<TargetTagPlan> = targetTagPlan
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
    fun `compute workspace plan task reads target tag plan json`() {
        val rootProject = buildProject("root")
        val workspaceDependenciesFile = rootProject.layout
            .buildDirectory
            .file("grazel/test-dependencies.json")
            .get()
        val targetTagPlanFile = rootProject.layout
            .buildDirectory
            .file("grazel/test-target-tag-plan.json")
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
        writeJson(targetTagPlan, targetTagPlanFile)

        val workspacePlanService = WorkspacePlanService.register(rootProject)
        val task = ComputeWorkspacePlanTask.register(
            rootProject = rootProject,
            workspacePlanService = workspacePlanService
        ) {
            workspaceDependencies.set(workspaceDependenciesFile)
            this.targetTagPlan.set(targetTagPlanFile)
        }.get()

        task.action()

        val writtenPlan = fromJson<WorkspacePlan>(task.workspacePlan.get())
        assertEquals(targetTagPlan, writtenPlan.tagPlan)
    }

    @Test
    fun `finalize workspace plan task writes render plan json and populates service`() {
        val rootProject = buildProject("root")
        val workspacePlanFile = rootProject.layout
            .buildDirectory
            .file("grazel/test-workspace-plan.json")
            .get()
        val compressionResultsFile = rootProject.layout
            .buildDirectory
            .file("grazel/test-compression-results.json")
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
        compressionResultsFile.asFile.apply {
            parentFile.mkdirs()
            writeText("""{"projectCount":0,"projects":[]}""")
        }
        targetMavenRepoReferencesFile.asFile.apply {
            parentFile.mkdirs()
            writeText("""{"repoNames":["debug_maven"]}""")
        }

        val workspacePlanService = WorkspacePlanService.register(rootProject)
        val task = FinalizeWorkspacePlanTask.register(
            rootProject = rootProject,
            workspacePlanService = workspacePlanService
        ) {
            workspacePlan.set(workspacePlanFile)
            compressionResults.set(compressionResultsFile)
            targetMavenRepoReferences.set(targetMavenRepoReferencesFile)
        }.get()

        task.action()

        val writtenRenderPlan = fromJson<WorkspaceRenderPlan>(task.workspaceRenderPlan.get())
        assertEquals(
            setOf("debug_maven"),
            writtenRenderPlan.materializedRepoNames
        )
        assertEquals(writtenRenderPlan, workspacePlanService.get().getRenderPlan())
    }
}
