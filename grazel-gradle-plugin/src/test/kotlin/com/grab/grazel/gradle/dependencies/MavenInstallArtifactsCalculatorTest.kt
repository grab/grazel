package com.grab.grazel.gradle.dependencies

import com.android.build.gradle.AppExtension
import com.grab.grazel.buildProject
import com.grab.grazel.fake.FLAVOR1
import com.grab.grazel.fake.FLAVOR2
import com.grab.grazel.gradle.ANDROID_APPLICATION_PLUGIN
import com.grab.grazel.util.addGrazelExtension
import com.grab.grazel.util.createGrazelComponent
import com.grab.grazel.util.doEvaluate
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.repositories
import org.junit.After
import org.junit.Before
import org.junit.Test

class MavenInstallArtifactsCalculatorTest {
    private lateinit var rootProject: Project
    private lateinit var androidBinary: Project
    private lateinit var mavenInstallArtifactsCalculator: MavenInstallArtifactsCalculator

    @Before
    fun setUp() {
        rootProject = buildProject("root")
        rootProject.addGrazelExtension()
        val grazelComponent = rootProject.createGrazelComponent()
        mavenInstallArtifactsCalculator = grazelComponent.mavenInstallArtifactsCalculator().get()
        androidBinary = buildProject("app-binary", parent = rootProject)
        configureAndroidBinary()
    }

    private fun configureAndroidBinary() {
        androidBinary.run {
            plugins.apply {
                apply(ANDROID_APPLICATION_PLUGIN)
            }
            repositories {
                mavenCentral()
                google()
            }
            extensions.configure<AppExtension> {
                defaultConfig {
                    compileSdkVersion(29)
                    buildToolsVersion("29.0.3")
                }
                flavorDimensions("service")
                productFlavors {
                    create(FLAVOR1) {
                        dimension = "service"
                    }
                    create(FLAVOR2) {
                        dimension = "service"
                    }
                }
            }
            dependencies {
                add(
                    "implementation",
                    "androidx.appcompat:appcompat:1.3.0"
                )
            }
            doEvaluate()
        }
    }

    @After
    fun tearDown() {
    }

    @Test
    fun `test`() {
    }
}