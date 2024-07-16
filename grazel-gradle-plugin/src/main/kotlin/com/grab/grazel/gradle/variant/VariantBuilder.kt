package com.grab.grazel.gradle.variant

import com.grab.grazel.gradle.isAndroid
import com.grab.grazel.gradle.isJvm
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.JvmBuild
import com.grab.grazel.gradle.variant.VariantType.Lint
import com.grab.grazel.gradle.variant.VariantType.Test
import org.gradle.api.Project
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [VariantBuilder] is used to construct unified [Set] of [Variant] types for Android/Jvm [Project]
 *
 * [VariantBuilder.onVariants] caches constructed Variants and can be called multiple times for a project.
 *
 * For lazy construction and safe to call during configuration phase use [VariantBuilder.onVariants]
 *
 * @see Variant
 */
internal interface VariantBuilder {
    fun build(project: Project): Set<Variant<*>>
    fun onVariants(project: Project, action: (Variant<*>) -> Unit)
}

@Singleton
internal class DefaultVariantBuilder
@Inject
constructor(
    private val variantDataSource: AndroidVariantDataSource
) : VariantBuilder {

    /**
     * [Variant] specific APIs can be often invoked at multiple places during migration hence
     * we cache constructed [Variant]s and reuse when needed.
     */
    private val variantCache = ConcurrentHashMap<String, Set<Variant<*>>>()

    override fun build(project: Project): Set<Variant<*>> {
        if (variantCache.contains(project.path)) return variantCache.getValue(project.path) else {
            val variants = if (project.isAndroid) {
                val migratableVariants = variantDataSource.getMigratableVariants(project)
                val flavors = migratableVariants
                    .flatMap { it.productFlavors }
                    .map { it.name }
                    .toSet()
                val buildTypes = migratableVariants
                    .map { it.buildType.name }
                    .toSet()
                val flavorsBuildTypes = (flavors + buildTypes).toSet()

                val defaultVariants = listOf<Variant<*>>(
                    AndroidDefaultVariant(
                        project = project,
                        variantType = AndroidBuild,
                        ignoreKeywords = flavorsBuildTypes
                    ),
                    AndroidDefaultVariant(
                        project = project,
                        variantType = Test,
                        ignoreKeywords = flavorsBuildTypes
                    ),
                    AndroidDefaultVariant(
                        project = project,
                        variantType = AndroidTest,
                        ignoreKeywords = flavorsBuildTypes
                    ),
                    AndroidDefaultVariant(
                        project = project,
                        variantType = Lint,
                        ignoreKeywords = flavorsBuildTypes
                    )
                )

                val parsedAndroidVariants: List<Variant<*>> =
                    migratableVariants.flatMap { baseVariant ->
                        listOf(
                            AndroidVariant(project, baseVariant),
                            AndroidBuildType(
                                project,
                                baseVariant.buildType,
                                baseVariant.toVariantType(),
                                flavors
                            )
                        ) + baseVariant.productFlavors.map { flavor ->
                            AndroidFlavor(
                                project,
                                flavor,
                                baseVariant.toVariantType(),
                                buildTypes
                            )
                        }
                    }
                (parsedAndroidVariants + defaultVariants)
                    .asSequence()
                    .distinctBy { it.id }
                    .sortedBy { it.name.length }
                    .toSet()
            } else if (project.isJvm) {
                setOf<Variant<*>>(
                    JvmVariant(
                        project = project,
                        variantType = JvmBuild
                    ),
                    JvmVariant(
                        project = project,
                        variantType = Test
                    )
                )
            } else emptySet()
            variantCache[project.path] = variants
            return variants
        }
    }

    override fun onVariants(project: Project, action: (Variant<*>) -> Unit) {
        project.afterEvaluate {
            val variantCache = ConcurrentHashMap<String, Variant<*>>()
            if (project.isAndroid) {
                val allFlavors = variantDataSource.getFlavors(project)
                val allFlavorNames = allFlavors.map { it.name }.toSet()
                val allBuildTypes = variantDataSource.getBuildTypes(project)
                val allBuildTypeNames = allBuildTypes.map { it.name }.toSet()
                val allFlavorBuildTypes = (allFlavorNames + allBuildTypeNames).toSet()

                fun variantAction(variant: Variant<*>) {
                    if (!variantCache.containsKey(variant.id)) {
                        action(variant)
                        variantCache[variant.id] = variant
                    }
                }

                variantAction(
                    AndroidDefaultVariant(
                        project = project,
                        variantType = AndroidBuild,
                        ignoreKeywords = allFlavorBuildTypes
                    )
                )
                variantAction(
                    AndroidDefaultVariant(
                        project = project,
                        variantType = Test,
                        ignoreKeywords = allFlavorBuildTypes
                    )
                )
                variantAction(
                    AndroidDefaultVariant(
                        project = project,
                        variantType = AndroidTest,
                        ignoreKeywords = allFlavorBuildTypes
                    )
                )
                variantAction(
                    AndroidDefaultVariant(
                        project = project,
                        variantType = Lint,
                        ignoreKeywords = allFlavorBuildTypes
                    )
                )

                variantDataSource.migratableVariants(project) { variant ->
                    action(AndroidVariant(project, variant))
                    if (allFlavors.isNotEmpty()) {
                        VariantType.values()
                            .asSequence()
                            .filter { it != JvmBuild && it != Lint }
                            .forEach { variantType ->
                                variantAction(
                                    AndroidBuildType(
                                        project = project,
                                        backingVariant = variant.buildType,
                                        variantType = variantType,
                                        flavors = allFlavorNames
                                    )
                                )
                                variant.productFlavors.forEach { flavor ->
                                    variantAction(
                                        AndroidFlavor(
                                            project = project,
                                            backingVariant = flavor,
                                            variantType = variantType,
                                            buildTypes = allBuildTypeNames
                                        )
                                    )
                                }
                            }
                    }
                }
            } else if (project.isJvm) {
                action(JvmVariant(project = project, variantType = JvmBuild))
                action(JvmVariant(project = project, variantType = Test))
                action(JvmVariant(project = project, variantType = Lint))
            }
        }
        variantCache.clear()
    }
}