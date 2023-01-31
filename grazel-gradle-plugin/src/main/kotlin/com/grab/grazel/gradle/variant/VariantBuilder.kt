package com.grab.grazel.gradle.variant

import com.grab.grazel.gradle.isAndroid
import com.grab.grazel.gradle.isJvm
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.JvmBuild
import com.grab.grazel.gradle.variant.VariantType.Test
import org.gradle.api.Project
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [VariantBuilder] is used to construct unified [Set] of [Variant] types for Android/Jvm [Project]
 *
 * [VariantBuilder.build] caches constructed Variants and can be called multiple times for a project.
 *
 * @see Variant
 */
internal interface VariantBuilder {
    fun build(project: Project): Set<Variant<*>>
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
}