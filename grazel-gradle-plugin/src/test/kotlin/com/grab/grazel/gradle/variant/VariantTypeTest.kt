package com.grab.grazel.gradle.variant

import com.android.build.gradle.AppExtension
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.variant.VariantType.*
import org.gradle.api.Project
import org.gradle.kotlin.dsl.the
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VariantTypeTest {

    private lateinit var rootProject: Project
    private lateinit var androidProject: Project

    @Before
    fun setup() {
        rootProject = buildProject("root")
        androidProject = buildProject("android", rootProject)
        setupAndroidVariantProject(androidProject)
    }

    @Test
    fun `assert android build variant type is parsed to AndroidBuild`() {
        androidProject.the<AppExtension>()
            .applicationVariants
            .forEach { variant ->
                val variantType = variant.toVariantType()
                assertTrue(
                    "Build variant is parsed for ${variant.name}",
                    variantType == AndroidBuild
                )
            }
    }

    @Test
    fun `assert android test variant type is parsed to AndroidTest`() {
        androidProject.the<AppExtension>()
            .testVariants
            .forEach { variant ->
                val variantType = variant.toVariantType()
                assertTrue(
                    "Android Test variant is parsed for ${variant.name}",
                    variantType == AndroidTest
                )
            }
    }

    @Test
    fun `assert unit test variant type is parsed to Test`() {
        androidProject.the<AppExtension>()
            .unitTestVariants
            .forEach { variant ->
                val variantType = variant.toVariantType()
                assertTrue(
                    "Android Test variant is parsed for ${variant.name}",
                    variantType == Test
                )
            }
    }
}