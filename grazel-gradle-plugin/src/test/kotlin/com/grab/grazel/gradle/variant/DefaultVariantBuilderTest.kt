package com.grab.grazel.gradle.variant

import com.grab.grazel.buildProject
import com.grab.grazel.di.GrazelComponent
import com.grab.grazel.util.createGrazelComponent
import org.gradle.api.Project
import org.junit.Before
import org.junit.Test

class DefaultVariantBuilderTest {
    private lateinit var rootProject: Project
    private lateinit var androidProject: Project
    private lateinit var jvmProject: Project

    private lateinit var grazelComponent: GrazelComponent
    private lateinit var variantBuilder: VariantBuilder

    @Before
    fun setup() {
        rootProject = buildProject("root")
        androidProject = buildProject("android", rootProject)
        jvmProject = buildProject("java", rootProject)

        grazelComponent = rootProject.createGrazelComponent()
        variantBuilder = grazelComponent.variantBuilder().get()
    }

    @Test
    fun `assert default android variants are built for the project`() {
        setupAndroidVariantProject(androidProject)
        val variants = variantBuilder.build(androidProject)
        variants
    }
}