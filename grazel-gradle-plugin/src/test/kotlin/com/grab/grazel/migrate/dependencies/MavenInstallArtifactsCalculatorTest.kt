package com.grab.grazel.migrate.dependencies

import com.grab.grazel.GrazelExtension
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.variant.setupAndroidVariantProject
import com.grab.grazel.gradle.variant.setupJvmVariantProject
import com.grab.grazel.util.addGrazelExtension
import com.grab.grazel.util.createGrazelComponent
import com.grab.grazel.util.doEvaluate
import org.gradle.api.Project
import org.gradle.kotlin.dsl.repositories
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertNotNull

class MavenInstallArtifactsCalculatorTest {
    private lateinit var rootProject: Project
    private lateinit var androidProject: Project
    private lateinit var jvmProject: Project
    private lateinit var mavenInstallArtifactsCalculator: MavenInstallArtifactsCalculator

    private fun setup(configure: GrazelExtension.() -> Unit = {}) {
        rootProject = buildProject("root")
        rootProject.addGrazelExtension(configure)

        androidProject = buildProject("android", rootProject)
        setupAndroidVariantProject(androidProject)
        androidProject.repositories { mavenCentral() }

        jvmProject = buildProject("java", rootProject)
        setupJvmVariantProject(jvmProject)

        listOf(rootProject, androidProject, jvmProject).forEach { it.doEvaluate() }

        val grazelComponent = rootProject.createGrazelComponent()
        mavenInstallArtifactsCalculator = grazelComponent.mavenInstallArtifactsCalculator().get()
    }

    @Test
    fun `test jetifyExcludeList should remove artifacts from jetifier list`() {
        setup {
            rules {
                mavenInstall {
                    jetifyExcludeList.set(
                        listOf(
                            "androidx.core:core",
                            "com.google.android:material"
                        )
                    )
                }
            }
        }

        val repository = "MavenRepo"

        val workspaceDependencies = WorkspaceDependencies(
            result = mapOf(
                "debug" to listOf(
                    ResolvedDependency.fromId("androidx.core:core:1.0.0", repository)
                        .copy(requiresJetifier = true),
                    ResolvedDependency.fromId("androidx.appcompat:appcompat:1.0.0", repository)
                        .copy(requiresJetifier = true),
                    ResolvedDependency.fromId("com.google.android:material:1.0.0", repository)
                        .copy(requiresJetifier = true),
                    ResolvedDependency.fromId("junit:junit:4.12", repository)
                )
            )
        )

        val result = mavenInstallArtifactsCalculator.get(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        // Verify results
        val debugRepo = result.find { it.name == "debug_maven" }
        assertNotNull(debugRepo, "Debug repository should exist")

        // Verify excluded artifacts are not included
        val jetifiedArtifacts = debugRepo.jetifierConfig.artifacts
        assertFalse(
            "androidx.core:core should be excluded from jetifier list",
            "androidx.core:core" in jetifiedArtifacts
        )
        assertFalse(
            "com.google.android:material should be excluded from jetifier list",
            "com.google.android:material" in jetifiedArtifacts
        )

        // Verify non-excluded artifact is included
        assertTrue(
            "androidx.appcompat:appcompat should be included in jetifier list",
            "androidx.appcompat:appcompat" in jetifiedArtifacts
        )
    }
}