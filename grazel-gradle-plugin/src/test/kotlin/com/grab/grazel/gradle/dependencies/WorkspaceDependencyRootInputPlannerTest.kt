package com.grab.grazel.gradle.dependencies

import com.google.common.truth.Truth.assertThat
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.variant.setupAndroidVariantProject
import com.grab.grazel.util.addGrazelExtension
import com.grab.grazel.util.createGrazelComponent
import org.junit.Test

private data class RootInputKey(
    val kind: AggregatedDependencyRootKind,
    val bucketName: String?,
    val metadataVariantName: String?,
    val configurationName: String,
    val traverseProjectNodes: Boolean,
    val targetBuckets: Set<String>
)

class WorkspaceDependencyRootInputPlannerTest {

    @Test
    fun `plans workspace dependency roots from variant-owned classpath facts`() {
        val rootProject = buildProject("root").also { project ->
            project.addGrazelExtension()
        }
        val appProject = buildProject("app", rootProject).also { project ->
            setupAndroidVariantProject(project)
        }
        val variants = rootProject
            .createGrazelComponent()
            .variantBuilder()
            .get()
            .build(appProject)

        val rootInputs = WorkspaceDependencyRootInputPlanner.plan(
            migratableProjects = listOf(appProject),
            variantsByProject = mapOf(appProject to variants)
        )

        assertThat(rootInputs.map(::rootInputKey))
            .containsAtLeast(
                RootInputKey(
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = "default",
                    metadataVariantName = "default",
                    configurationName = "grazelDefaultRuntimeClasspath",
                    traverseProjectNodes = true,
                    targetBuckets = setOf("default")
                ),
                RootInputKey(
                    kind = AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    bucketName = "debug",
                    metadataVariantName = "debug",
                    configurationName = "grazelDebugRuntimeClasspath",
                    traverseProjectNodes = true,
                    targetBuckets = setOf("debug")
                ),
                RootInputKey(
                    kind = AggregatedDependencyRootKind.MAIN_LEAF,
                    bucketName = "paidDebug",
                    metadataVariantName = "paidDebug",
                    configurationName = "paidDebugRuntimeClasspath",
                    traverseProjectNodes = true,
                    targetBuckets = setOf("paidDebug")
                ),
                RootInputKey(
                    kind = AggregatedDependencyRootKind.UNIT_TEST,
                    bucketName = "paidDebug",
                    metadataVariantName = "paidDebug",
                    configurationName = "paidDebugUnitTestRuntimeClasspath",
                    traverseProjectNodes = true,
                    targetBuckets = emptySet()
                ),
                RootInputKey(
                    kind = AggregatedDependencyRootKind.ANDROID_TEST,
                    bucketName = "paidDebug",
                    metadataVariantName = "paidDebug",
                    configurationName = "paidDebugAndroidTestRuntimeClasspath",
                    traverseProjectNodes = true,
                    targetBuckets = emptySet()
                ),
                RootInputKey(
                    kind = AggregatedDependencyRootKind.LINT,
                    bucketName = null,
                    metadataVariantName = null,
                    configurationName = "lintChecks",
                    traverseProjectNodes = false,
                    targetBuckets = emptySet()
                )
            )
    }

    private fun rootInputKey(input: WorkspaceDependencyRootInput): RootInputKey {
        return RootInputKey(
            kind = input.kind,
            bucketName = input.bucketName,
            metadataVariantName = input.metadataVariant?.name,
            configurationName = input.configuration.name,
            traverseProjectNodes = input.traverseProjectNodes,
            targetBuckets = input.targetBuckets
        )
    }
}
