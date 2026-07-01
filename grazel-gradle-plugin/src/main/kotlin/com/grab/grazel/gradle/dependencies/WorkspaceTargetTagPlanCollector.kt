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

package com.grab.grazel.gradle.dependencies

import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.gradle.MigrationChecker
import com.grab.grazel.gradle.dependencies.model.TargetTagKey
import com.grab.grazel.gradle.dependencies.model.TargetTagPlan
import com.grab.grazel.gradle.isAndroid
import com.grab.grazel.gradle.isAndroidApplication
import com.grab.grazel.gradle.isAndroidTest
import com.grab.grazel.gradle.isKotlin
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.gradle.variant.TEST_VARIANT
import com.grab.grazel.gradle.variant.Variant
import com.grab.grazel.gradle.variant.VariantBuilder
import com.grab.grazel.gradle.variant.VariantGraphKey
import com.grab.grazel.gradle.variant.VariantMatcher
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.migrate.dependencies.calculateMavenDependencyTags
import com.grab.grazel.util.GradleProvider
import com.grab.grazel.util.ProgressReporter
import dagger.Lazy
import org.gradle.api.Project
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

internal interface WorkspaceTargetTagPlanCollector {
    fun collect(rootProject: Project, reporter: ProgressReporter): List<TargetTagPlan>
}

internal object TargetTagKinds {
    const val ANDROID_LIBRARY = "android_library"
    const val ANDROID_UNIT_TEST = "android_unit_test"
    const val ANDROID_INSTRUMENTATION = "android_instrumentation"
    const val KOTLIN_LIBRARY = "kotlin_library"
    const val KOTLIN_UNIT_TEST = "kotlin_unit_test"
}

private data class MavenTagClosureKey(
    val projectPath: String,
    val variantId: String,
    val variantType: VariantType,
)

@Singleton
internal class DefaultWorkspaceTargetTagPlanCollector
@Inject
constructor(
    private val migrationChecker: Lazy<MigrationChecker>,
    private val variantMatcher: Lazy<VariantMatcher>,
    private val dependenciesDataSource: DependenciesDataSource,
    private val dependencyGraphsService: GradleProvider<DefaultDependencyGraphsService>,
    private val variantBuilder: VariantBuilder,
) : WorkspaceTargetTagPlanCollector {

    private val transitiveMavenDepsCache =
        ConcurrentHashMap<MavenTagClosureKey, Set<MavenDependency>>()
    private val variantsByProjectPath = mutableMapOf<String, Set<Variant<*>>>()

    override fun collect(rootProject: Project, reporter: ProgressReporter): List<TargetTagPlan> {
        transitiveMavenDepsCache.clear()
        variantsByProjectPath.clear()
        return try {
            val migratableProjects = rootProject.subprojects
                .sortedBy(Project::getPath)
                .filter { project -> migrationChecker.get().canMigrate(project) }
            migratableProjects
                .asSequence()
                .flatMapIndexed { index, project ->
                    reporter.report("tagging (${index + 1}/${migratableProjects.size}): ${project.path}")
                    targetTagPlans(project)
                }
                .filter { tagPlan -> tagPlan.tags.isNotEmpty() }
                .sortedWith(
                    compareBy<TargetTagPlan> { it.key.variantId }
                        .thenBy { it.key.variantType }
                        .thenBy { it.key.targetKind }
                )
                .toList()
        } finally {
            transitiveMavenDepsCache.clear()
            variantsByProjectPath.clear()
        }
    }

    private fun targetTagPlans(project: Project): Sequence<TargetTagPlan> = sequence {
        if (project.isAndroid) {
            if (!project.isAndroidApplication) {
                yieldAll(androidLibraryTagPlans(project))
                if (!project.isAndroidTest) {
                    yieldAll(androidUnitTestTagPlans(project))
                }
            }
            if (project.isAndroidApplication) {
                yieldAll(androidInstrumentationTagPlans(project))
            }
        } else if (project.isKotlin) {
            yield(kotlinLibraryTagPlan(project))
            yield(kotlinUnitTestTagPlan(project))
        }
    }

    private fun androidLibraryTagPlans(project: Project): Sequence<TargetTagPlan> =
        matchedVariantTagPlans(
            project = project,
            variantType = VariantType.AndroidBuild,
            targetKind = TargetTagKinds.ANDROID_LIBRARY
        ) { owningProject, matchedVariant, variantKey ->
            val transitiveMavenDeps = mutableSetOf<MavenDependency>()
            transitiveMavenDeps.addAll(collectTransitiveMavenDeps(owningProject, variantKey))
            dependencyGraphsService
                .get()
                .get()
                .directDependenciesByVariant(owningProject, variantKey)
                .forEach { dependencyProject ->
                    val dependencyVariantKey = bestVariantKeyForTagClosure(
                        project = dependencyProject,
                        matchedVariant = matchedVariant
                    )
                    if (dependencyVariantKey != null) {
                        transitiveMavenDeps.addAll(
                            collectTransitiveMavenDeps(dependencyProject, dependencyVariantKey)
                        )
                    }
                }
            transitiveMavenDeps
        }

    private fun androidUnitTestTagPlans(project: Project): Sequence<TargetTagPlan> =
        matchedVariantTagPlans(
            project = project,
            variantType = VariantType.Test,
            targetKind = TargetTagKinds.ANDROID_UNIT_TEST
        )

    private fun androidInstrumentationTagPlans(project: Project): Sequence<TargetTagPlan> =
        matchedVariantTagPlans(
            project = project,
            variantType = VariantType.AndroidTest,
            targetKind = TargetTagKinds.ANDROID_INSTRUMENTATION
        )

    private fun kotlinLibraryTagPlan(project: Project): TargetTagPlan {
        val variantKey = VariantGraphKey.from(project, DEFAULT_VARIANT, VariantType.JvmBuild)
        return targetTagPlan(
            variantKey = variantKey,
            targetKind = TargetTagKinds.KOTLIN_LIBRARY,
            mavenDeps = collectTransitiveMavenDeps(project, variantKey)
        )
    }

    private fun kotlinUnitTestTagPlan(project: Project): TargetTagPlan {
        val variantKey = VariantGraphKey.from(project, TEST_VARIANT, VariantType.Test)
        return targetTagPlan(
            variantKey = variantKey,
            targetKind = TargetTagKinds.KOTLIN_UNIT_TEST,
            mavenDeps = collectTransitiveMavenDeps(project, variantKey)
        )
    }

    private fun matchedVariantTagPlans(
        project: Project,
        variantType: VariantType,
        targetKind: String,
        mavenDeps: (Project, MatchedVariant, VariantGraphKey) -> Set<MavenDependency> = { owningProject, _, variantKey ->
            collectTransitiveMavenDeps(owningProject, variantKey)
        }
    ): Sequence<TargetTagPlan> = variantMatcher
        .get()
        .matchedVariants(project, variantType)
        .asSequence()
        .sortedBy(MatchedVariant::variantName)
        .map { matchedVariant ->
            val variantKey = VariantGraphKey.from(project, matchedVariant, variantType)
            targetTagPlan(
                variantKey = variantKey,
                targetKind = targetKind,
                mavenDeps = mavenDeps(project, matchedVariant, variantKey)
            )
        }

    private fun targetTagPlan(
        variantKey: VariantGraphKey,
        targetKind: String,
        mavenDeps: Iterable<MavenDependency>
    ): TargetTagPlan = TargetTagPlan(
        key = TargetTagKey(
            variantId = variantKey.variantId,
            variantType = variantKey.variantType.toString(),
            targetKind = targetKind
        ),
        tags = calculateMavenDependencyTags(mavenDeps)
    )

    private fun collectTransitiveMavenDeps(
        project: Project,
        variantKey: VariantGraphKey
    ): Set<MavenDependency> {
        val cacheKey = MavenTagClosureKey(
            projectPath = project.path,
            variantId = variantKey.variantId,
            variantType = variantKey.variantType
        )
        return transitiveMavenDepsCache.computeIfAbsent(cacheKey) {
            dependenciesDataSource.collectTransitiveMavenDeps(
                project = project,
                variantKey = variantKey
            )
        }
    }

    private fun bestVariantKeyForTagClosure(
        project: Project,
        matchedVariant: MatchedVariant
    ): VariantGraphKey? {
        val variantType = if (project.isAndroid) VariantType.AndroidBuild else VariantType.JvmBuild
        val preferredNames = if (project.isAndroid) {
            listOf(matchedVariant.variantName, matchedVariant.buildType, DEFAULT_VARIANT)
        } else {
            listOf(DEFAULT_VARIANT)
        }
        return preferredNames
            .asSequence()
            .distinct()
            .mapNotNull { preferredName ->
                builtVariants(project).firstOrNull { variant ->
                    variant.variantType == variantType && variant.name == preferredName
                }
            }
            .firstOrNull()
            ?.let(VariantGraphKey::from)
    }

    private fun builtVariants(project: Project): Set<Variant<*>> =
        variantsByProjectPath.getOrPut(project.path) { variantBuilder.build(project) }
}
