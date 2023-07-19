package com.grab.grazel.gradle.variant

import com.grab.grazel.gradle.isAndroid
import com.grab.grazel.gradle.isJvm
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.JvmBuild
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
                    .distinctBy { it.name + it.variantType }
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
            if (project.isAndroid) {
                val flavors = variantDataSource.getFlavors(project)
                val flavorNames = flavors.map { it.name }.toSet()
                val buildTypes = variantDataSource.getBuildTypes(project)
                val buildTypeNames = buildTypes.map { it.name }.toSet()
                val flavorsBuildTypes = (flavorNames + buildTypeNames).toSet()
                action(
                    AndroidDefaultVariant(
                        project = project,
                        variantType = AndroidBuild,
                        ignoreKeywords = flavorsBuildTypes
                    )
                )
                action(
                    AndroidDefaultVariant(
                        project = project,
                        variantType = Test,
                        ignoreKeywords = flavorsBuildTypes
                    )
                )
                action(
                    AndroidDefaultVariant(
                        project = project,
                        variantType = AndroidTest,
                        ignoreKeywords = flavorsBuildTypes
                    )
                )

                variantDataSource.migratableVariants(project) { variant ->
                    action(AndroidVariant(project, variant))
                }


                if (flavors.isNotEmpty()) {
                    // Special case, if this module does not have flavors declared then variants
                    // will be just buildTypes. Since we already would have passed buildType variants
                    // above we don't need to pass it again here.
                    buildTypes
                        .asSequence()
                        .flatMap { buildType ->
                            VariantType.values()
                                .filter { it != JvmBuild }
                                .map { variantType ->
                                    AndroidBuildType(
                                        project = project,
                                        backingVariant = buildType,
                                        variantType = variantType,
                                        flavors = flavorNames
                                    )
                                }
                        }.distinctBy { it.name + it.variantType }
                        .forEach(action)

                    VariantType
                        .values()
                        .asSequence()
                        .filter { it != JvmBuild }
                        .flatMap { variantType ->
                            flavors.map { flavor ->
                                AndroidFlavor(
                                    project,
                                    flavor,
                                    variantType,
                                    buildTypeNames
                                )
                            }
                        }.forEach(action)
                }
            } else if (project.isJvm) {
                action(JvmVariant(project = project, variantType = JvmBuild))
                action(JvmVariant(project = project, variantType = Test))
            }
        }
    }
}