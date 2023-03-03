package com.grab.grazel.gradle.variant

import com.android.build.gradle.AppExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.UnitTestVariant
import com.android.builder.model.BuildType
import com.android.builder.model.ProductFlavor
import com.grab.grazel.gradle.isAndroidApplication
import com.grab.grazel.gradle.isAndroidDynamicFeature
import com.grab.grazel.gradle.isAndroidLibrary
import org.gradle.api.Project
import org.gradle.kotlin.dsl.the
import javax.inject.Inject
import javax.inject.Singleton

internal interface AndroidVariantsExtractor {
    fun allVariants(project: Project): Set<BaseVariant>
    fun getUnitTestVariants(project: Project): Set<BaseVariant>
    fun getTestVariants(project: Project): Set<BaseVariant>
    fun getVariants(project: Project): Set<BaseVariant>
    fun getFlavors(project: Project): Set<ProductFlavor>
    fun getBuildTypes(project: Project): Set<BuildType>
}

@Singleton
internal class DefaultAndroidVariantsExtractor
@Inject
constructor() : AndroidVariantsExtractor {

    private val Project.isAndroidAppOrDynFeature get() = project.isAndroidApplication || project.isAndroidDynamicFeature

    override fun allVariants(project: Project): Set<BaseVariant> {
        return getVariants(project) + getTestVariants(project) + getUnitTestVariants(project)
    }

    override fun getVariants(project: Project): Set<BaseVariant> {
        return when {
            project.isAndroidAppOrDynFeature -> project.the<AppExtension>().applicationVariants
            project.isAndroidLibrary -> project.the<LibraryExtension>().libraryVariants
            else -> emptySet()
        }
    }

    override fun getTestVariants(project: Project): Set<BaseVariant> {
        return when {
            project.isAndroidAppOrDynFeature -> project.the<AppExtension>().testVariants
            project.isAndroidLibrary -> project.the<LibraryExtension>().testVariants
            else -> emptySet()
        }
    }

    override fun getUnitTestVariants(project: Project): Set<UnitTestVariant> {
        return when {
            project.isAndroidAppOrDynFeature -> project.the<AppExtension>().unitTestVariants
            project.isAndroidLibrary -> project.the<LibraryExtension>().unitTestVariants
            else -> emptySet()
        }
    }

    override fun getFlavors(project: Project): Set<ProductFlavor> {
        return when {
            project.isAndroidAppOrDynFeature -> project.the<AppExtension>().productFlavors
            project.isAndroidLibrary -> project.the<LibraryExtension>().productFlavors
            else -> emptySet()
        }
    }

    override fun getBuildTypes(project: Project): Set<BuildType> {
        return when {
            project.isAndroidAppOrDynFeature -> project.the<AppExtension>().buildTypes.toSet()
            project.isAndroidLibrary -> project.the<LibraryExtension>().buildTypes.toSet()
            else -> emptySet()
        }
    }
}