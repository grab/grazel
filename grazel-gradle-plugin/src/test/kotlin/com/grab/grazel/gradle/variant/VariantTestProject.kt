package com.grab.grazel.gradle.variant

import com.android.build.gradle.AppExtension
import com.grab.grazel.gradle.ANDROID_APPLICATION_PLUGIN
import com.grab.grazel.gradle.JAVA_LIBRARY_PLUGIN
import com.grab.grazel.gradle.KOTLIN_ANDROID_PLUGIN
import com.grab.grazel.gradle.KOTLIN_KAPT
import com.grab.grazel.gradle.KOTLIN_PLUGIN
import com.grab.grazel.util.doEvaluate
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.repositories

const val TEST_FLAVOR_DIMENSION = "service"
const val TEST_FLAVOR_FREE = "paid"
const val TEST_FLAVOR_PAID = "free"
const val TEST_DEBUG = "debug"
const val TEST_RELEASE = "release"

fun setupAndroidVariantProject(androidProject: Project) {
    with(androidProject) {
        with(plugins) {
            apply(ANDROID_APPLICATION_PLUGIN)
            apply(KOTLIN_ANDROID_PLUGIN)
            apply(KOTLIN_KAPT)
        }
        repositories {
            google()
            mavenCentral()
        }
        configure<AppExtension> {
            defaultConfig {
                compileSdkVersion(32)
            }
            flavorDimensions(TEST_FLAVOR_DIMENSION)
            productFlavors {
                create("paid") {
                    dimension = TEST_FLAVOR_DIMENSION
                }
                create("free") {
                    dimension = TEST_FLAVOR_DIMENSION
                }
            }
            dataBinding.isEnabled = true
        }
        dependencies {
            add(
                "implementation",
                "androidx.appcompat:appcompat:1.5.1"
            )
            add(
                "freeImplementation",
                "androidx.constraintlayout:constraintlayout:2.1.3"
            )
            add(
                "paidImplementation",
                "androidx.constraintlayout:constraintlayout:2.1.2"
            )
            add(
                "kapt",
                "com.google.dagger:dagger:2.37"
            )
        }
    }

    androidProject.doEvaluate()
}

fun setupJvmVariantProject(project: Project) {
    with(project) {
        with(plugins) {
            apply(JAVA_LIBRARY_PLUGIN)
            apply(KOTLIN_PLUGIN)
            apply(KOTLIN_KAPT)
        }
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            add(
                "implementation",
                "com.google.dagger:dagger:2.37"
            )
            add(
                "kapt",
                "com.google.dagger:dagger:2.37"
            )
        }
    }
    project.doEvaluate()
}