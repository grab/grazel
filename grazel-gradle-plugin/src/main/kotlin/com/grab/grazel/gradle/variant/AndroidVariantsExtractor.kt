package com.grab.grazel.gradle.variant

import com.android.build.gradle.AppExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.BaseVariant
import com.android.builder.model.BuildType
import com.android.builder.model.ProductFlavor
import com.grab.grazel.gradle.isAndroidApplication
import com.grab.grazel.gradle.isAndroidDynamicFeature
import com.grab.grazel.gradle.isAndroidLibrary
import org.gradle.api.DomainObjectSet
import org.gradle.api.Project
import org.gradle.kotlin.dsl.the
import javax.inject.Inject
import javax.inject.Singleton

internal interface AndroidVariantsExtractor {
    fun allVariants(project: Project): DomainObjectSet<out BaseVariant>
    fun allVariants(project: Project, variantAction: (BaseVariant) -> Unit)
    fun getUnitTestVariants(project: Project): DomainObjectSet<out BaseVariant>
    fun getTestVariants(project: Project): DomainObjectSet<out BaseVariant>
    fun getVariants(project: Project): DomainObjectSet<out BaseVariant>
    fun getFlavors(project: Project): Set<ProductFlavor>
    fun getBuildTypes(project: Project): Set<BuildType>
}

@Singleton
internal class DefaultAndroidVariantsExtractor
@Inject
constructor() : AndroidVariantsExtractor {

    private val Project.isAndroidAppOrDynFeature get() = project.isAndroidApplication || project.isAndroidDynamicFeature

    override fun allVariants(project: Project): DomainObjectSet<BaseVariant> {
        return project.objects.domainObjectSet(BaseVariant::class.java).apply {
            addAll(getVariants(project) + getTestVariants(project) + getUnitTestVariants(project))
        }
    }

    override fun allVariants(project: Project, variantAction: (BaseVariant) -> Unit) {
        getVariants(project).all(variantAction)
        getTestVariants(project).all(variantAction)
        getUnitTestVariants(project).all(variantAction)
    }


    override fun getVariants(project: Project): DomainObjectSet<out BaseVariant> {
        return when {
            project.isAndroidAppOrDynFeature -> project.the<AppExtension>().applicationVariants
            project.isAndroidLibrary -> project.the<LibraryExtension>().libraryVariants
            else -> project.objects.domainObjectSet(BaseVariant::class.java)
        }
    }

    override fun getTestVariants(project: Project): DomainObjectSet<out BaseVariant> {
        return when {
            project.isAndroidAppOrDynFeature -> project.the<AppExtension>().testVariants
            project.isAndroidLibrary -> project.the<LibraryExtension>().testVariants
            else -> project.objects.domainObjectSet(BaseVariant::class.java)
        }
    }

    override fun getUnitTestVariants(project: Project): DomainObjectSet<out BaseVariant> {
        return when {
            project.isAndroidAppOrDynFeature -> project.the<AppExtension>().unitTestVariants
            project.isAndroidLibrary -> project.the<LibraryExtension>().unitTestVariants
            else -> project.objects.domainObjectSet(BaseVariant::class.java)
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