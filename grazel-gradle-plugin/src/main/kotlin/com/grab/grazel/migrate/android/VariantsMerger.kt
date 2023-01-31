/*
 * Copyright 2022 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.migrate.android

import com.android.build.gradle.AppExtension
import com.android.build.gradle.api.BaseVariant
import com.android.builder.model.BuildType
import com.android.builder.model.ProductFlavor
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.ConfigurationScope
import com.grab.grazel.gradle.isAndroidApplication
import com.grab.grazel.gradle.variant.AndroidVariantDataSource
import org.gradle.api.Project
import org.gradle.kotlin.dsl.the
import javax.inject.Inject

internal class VariantsMerger @Inject constructor(
    val androidVariantDataSource: AndroidVariantDataSource,
    @RootProject private val rootProject: Project,
) {

    private val androidApplicationsModules = rootProject.subprojects
        .filter { it.isAndroidApplication }

    fun merge(project: Project, scope: ConfigurationScope): Set<MergedVariant> {
        val moduleVariants = androidVariantDataSource.getMigratableVariants(project, scope)

        val moduleFlavors = moduleVariants.flatMap { it.productFlavors }.toSet()
        val moduleBuildTypes = moduleVariants.map { it.buildType }.toSet()

        val appFlavors =
            androidVariantDataSource.getMigratableVariants(getApp(), scope)
                .flatMap { it.productFlavors }
                .toSet()
        val appBuildTypes =
            androidVariantDataSource.getMigratableVariants(getApp(), scope).map { it.buildType }
                .toSet()

        return if (appFlavors.isNotEmpty()) {
            appFlavors.flatMap { appFlavor ->
                appBuildTypes.map { appBuildType ->
                    val mergedFlavor = mergedFlavor(moduleFlavors, appFlavor, project)
                    val mergedBuildType = mergedBuildType(moduleBuildTypes, appBuildType, project)

                    val moduleVariant = moduleVariant(
                        scope,
                        moduleFlavors.isNotEmpty(),
                        mergedBuildType,
                        mergedFlavor,
                        moduleVariants
                    )

                    MergedVariant(appFlavor.name, appBuildType.name, moduleVariant)
                }
            }
        } else {
            appBuildTypes.map { appBuildType ->
                val mergedBuildType = mergedBuildType(moduleBuildTypes, appBuildType, project)

                val moduleVariant = moduleVariant(
                    scope,
                    moduleFlavors.isNotEmpty(),
                    mergedBuildType,
                    "",
                    moduleVariants
                )

                MergedVariant("", appBuildType.name, moduleVariant)
            }
        }.toSet()
    }

    private fun moduleVariant(
        scope: ConfigurationScope,
        projectHaveFlavor: Boolean,
        mergedBuildType: String,
        mergedFlavor: String,
        moduleVariants: Set<BaseVariant>
    ): BaseVariant {
        val expectedModuleVariantName = if (projectHaveFlavor) {
            mergedFlavor + mergedBuildType.capitalize()
        } else {
            mergedBuildType
        } + scope.scopeName

        return moduleVariants.firstOrNull { variant ->
            variant.name == expectedModuleVariantName
        } ?: throw VariantIsNotPresentException(
            expectedModuleVariantName,
            moduleVariants.map { it.name }
        )
    }

    /**
     * Find if the build type or any of its matching fallbacks exist in the target module
     * throws exception if it cannot find anything
     */
    private fun mergedBuildType(
        moduleBuildTypes: Set<BuildType>,
        appBuildType: BuildType,
        project: Project
    ): String {
        val mergedBuildTypeCandidate = moduleBuildTypes.firstOrNull { moduleBuildType ->
            appBuildType.name == moduleBuildType.name
        }
        return mergedBuildTypeCandidate?.name
            ?: (appBuildType.matchingFallbacks().firstOrNull { fallBackBuildType ->
                moduleBuildTypes.firstOrNull { moduleBuildType ->
                    fallBackBuildType == moduleBuildType.name
                } != null
            } ?: throw BuildTypesIsNotPresentException(project.name, appBuildType.name))
    }

    /**
     * Find if the flavor or any of its matching fallbacks exist in the target module
     *
     * throws exception if it cannot find anything
     */
    private fun mergedFlavor(
        moduleFlavors: Set<ProductFlavor>,
        appFlavor: ProductFlavor,
        project: Project
    ): String {

        if (moduleFlavors.isEmpty()) {
            return appFlavor.name
        }

        val mergedFlavorCandidate =
            moduleFlavors.firstOrNull { moduleFlavor -> moduleFlavor.name == appFlavor.name }

        return mergedFlavorCandidate?.name
            ?: appFlavor.matchingFallbacks().firstOrNull { fallBackFlavor ->
                moduleFlavors.firstOrNull { moduleFlavor ->
                    fallBackFlavor == moduleFlavor.name
                } != null
            } ?: throw FlavorIsNotPresentException(project.name, appFlavor.name)
    }

    private fun getApp(): Project {
        return if (androidApplicationsModules.size == 1) {
            androidApplicationsModules.first()
        } else {
            throw ProjectShouldHaveOnlyOneAppModuleException()
        }
    }

    private fun BuildType.matchingFallbacks(): List<String> {
        return getApp().the<AppExtension>().buildTypes.first { it.name == name }.matchingFallbacks
    }

    private fun ProductFlavor.matchingFallbacks(): List<String> {
        return getApp().the<AppExtension>().productFlavors.first { it.name == name }
            .matchingFallbacks
    }
}

fun <E> Collection<E>.first(errorMessage: String, function: (E) -> Boolean): E {
    return try {
        first { function(it) }
    } catch (e: Exception) {
        throw IllegalStateException(errorMessage, e)
    }
}

data class MergedVariant(val flavor: String, val buildType: String, val variant: BaseVariant) {
    val variantName = flavor + buildType.capitalize()
}

class FlavorIsNotPresentException(project: String, flavor: String) : IllegalStateException(
    "Flavor $flavor its not present in module $project consider" +
        " adding it to the module or add a matchingFallback in the app module"
)

class BuildTypesIsNotPresentException(project: String, buildType: String) : IllegalStateException(
    "BuildType $buildType doesn't exist in module $project consider " +
        "adding it to the module or add a matching fallback to the " +
        "$buildType in app module"
)

class ProjectShouldHaveOnlyOneAppModuleException :
    IllegalStateException("Root project should have at least and just one application module")

class VariantIsNotPresentException(
    expectedModuleVariantName: String,
    moduleVariants: List<String>
) : IllegalStateException(
    "ExpectedModuleVariantName=$expectedModuleVariantName is not existed in " +
        "moduleVariants= $moduleVariants"
)