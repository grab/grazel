package com.grab.grazel.migrate.dependencies

import com.grab.grazel.buildProject
import com.grab.grazel.gradle.variant.setupAndroidVariantProject
import com.grab.grazel.gradle.variant.setupJvmVariantProject
import com.grab.grazel.util.addGrazelExtension
import com.grab.grazel.util.createGrazelComponent
import com.grab.grazel.util.doEvaluate
import org.gradle.api.Project
import org.junit.Before
import org.junit.Test

class MavenInstallArtifactsCalculatorTest {
    private lateinit var rootProject: Project
    private lateinit var androidProject: Project
    private lateinit var jvmProject: Project
    private lateinit var mavenInstallArtifactsCalculator: MavenInstallArtifactsCalculator
    private val projectsToMigrate by lazy { listOf(rootProject, androidProject, jvmProject) }

    @Before
    fun setUp() {
        rootProject = buildProject("root").also {
            it.addGrazelExtension()
        }
        androidProject = buildProject("android", rootProject).also {
            setupAndroidVariantProject(it)
        }
        jvmProject = buildProject("java", rootProject).also {
            setupJvmVariantProject(it)
        }
        projectsToMigrate.forEach { it.doEvaluate() }
        val grazelComponent = rootProject.createGrazelComponent()
        mavenInstallArtifactsCalculator = grazelComponent.mavenInstallArtifactsCalculator().get()
    }

    @Test
    fun `test`() {
    }
}