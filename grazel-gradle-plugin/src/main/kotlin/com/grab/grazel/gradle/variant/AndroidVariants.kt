package com.grab.grazel.gradle.variant

import com.android.build.gradle.api.BaseVariant
import com.android.builder.model.BaseConfig
import com.android.builder.model.BuildType
import com.android.builder.model.ProductFlavor
import com.google.common.base.MoreObjects
import com.grab.grazel.gradle.variant.Classpath.Compile
import com.grab.grazel.gradle.variant.Classpath.Runtime
import com.grab.grazel.gradle.variant.DefaultVariants.Default
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.JvmBuild
import com.grab.grazel.gradle.variant.VariantType.Test
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration

/**
 * [Variant] implementation used to represent a Variant created by Android Gradle plugin which is
 * usually a mix of flavor + build type.
 */
class AndroidVariant(
    override val project: Project,
    override val backingVariant: BaseVariant,
) : ConfigurationParsingVariant<BaseVariant> {

    override val name: String = backingVariant.name
    override val variantType: VariantType = backingVariant.toVariantType()

    /**
     * Calculate the base name from variant. Useful for parsing since configuration
     * names are typically in camel case.
     *
     * Eg:
     *    androidTestPaidDebug -> PaidDebug
     */
    override val baseName = backingVariant.baseName
        .split('-')
        .dropLast(1)
        .joinToString(separator = "", transform = String::capitalize)

    override val extendsFrom: Set<String> by lazy {
        buildList {
            add(Default.toString())
            addAll(backingVariant.productFlavors.map { it.name })
            add(backingVariant.buildType.name)
            if (variantType.isTest) {
                add(DefaultVariants.Test.toString())
                add(backingVariant.buildType.name + variantType.testSuffix)
            }
        }.filter { it != name }.toSet()
    }

    override val compileConfiguration get() = setOf(backingVariant.compileConfiguration)

    override val runtimeConfiguration get() = setOf(backingVariant.runtimeConfiguration)

    override val annotationProcessorConfiguration
        get() = parseAnnotationProcessorConfigurations(
            fallback = backingVariant.annotationProcessorConfiguration
        )

    override fun toString() = MoreObjects.toStringHelper(this)
        .add("project", project.path)
        .add("name", name)
        .add("variantType", variantType)
        .toString()
}

/**
 * Type to represent a non Android's [BaseVariant] type. Both flavors and buildTypes are permuted
 * to create variants, but we also need non-variant type that represents each [BuildType] and
 * [ProductFlavor] but not the variants created by them, this type represents that.
 *
 * For parsing, [toIgnoreKeywords] is used to filter out any configuration that appears to configuration
 * from a variant. For [BuildType] it will be all productFlavors and vice versa.
 */
abstract class AndroidNonVariant<T>(
    override val project: Project,
    override val backingVariant: T,
    override val variantType: VariantType,
    private val toIgnoreKeywords: Set<String>
) : ConfigurationParsingVariant<T> where T : BaseConfig {

    override val name
        get() = backingVariant.name + when (variantType) {
            AndroidTest -> AndroidTest.name
            Test -> Test.name
            else -> ""
        }

    override val baseName get() = backingVariant.name.capitalize()

    override val variantConfigurations: Set<Configuration>
        get() = super.variantConfigurations
            .asSequence()
            .filter { config -> toIgnoreKeywords.none { config.name.contains(it, true) } }
            .toSet()

    override val compileConfiguration by lazy { classpathConfiguration(classpath = Compile) }

    override val runtimeConfiguration by lazy { classpathConfiguration(classpath = Runtime) }

    override val annotationProcessorConfiguration: Set<Configuration>
        get() {
            val apConfig = "%sAnnotationProcessor"
            val buildTypeConfigs = parseAnnotationProcessorConfigurations(
                fallback = project.configurations[apConfig.format(baseName.toLowerCase())]!!,
            )
            val flavorConfig = toIgnoreKeywords.flatMap { flavor ->
                val namePattern = flavor + baseName
                project.configurations[apConfig.format(namePattern)]?.let { fallback ->
                    parseAnnotationProcessorConfigurations(
                        fallback = fallback,
                        namePattern = namePattern,
                        basePattern = namePattern
                    )
                } ?: emptySet()
            }
            return (buildTypeConfigs + flavorConfig).toSet()
        }

    override val kotlinCompilerPluginConfiguration get() = emptySet<Configuration>()

    override fun toString() = MoreObjects.toStringHelper(this)
        .add("project", project.path)
        .add("name", name)
        .add("variantType", variantType)
        .toString()
}

/**
 * A [Variant] implementation to denote a [BuildType] with [toIgnoreKeywords] set to product flavors
 *
 * @param project The project this build type belongs to
 * @param backingVariant The [BuildType] of this variant
 * @param variantType [BuildType] typically is not associated with certain source set alone however
 * this is used to hint the type of source set for parsing.
 * @param flavors The set of [ProductFlavor] names contained in the project, used for parsing to filter
 * out any permutation of buildType + flavors
 */
class AndroidBuildType(
    override val project: Project,
    override val backingVariant: BuildType,
    override val variantType: VariantType,
    private val flavors: Set<String>
) : AndroidNonVariant<BuildType>(
    project = project,
    backingVariant = backingVariant,
    variantType = variantType,
    toIgnoreKeywords = flavors
) {
    override val extendsFrom: Set<String> = buildList {
        add(Default.toString())
        if (variantType.isTest) add(backingVariant.name)
        if (variantType == Test) add(DefaultVariants.Test.toString())
    }.toSet()
}

/**
 * A [Variant] implementation to denote a [ProductFlavor] with [toIgnoreKeywords] set to buildTypes
 *
 * @param project The project this flavor belongs to
 * @param backingVariant The [ProductFlavor] of this variant
 * @param variantType [ProductFlavor] typically is not associated with certain source set type alone however
 * this is used to hint the type of source set for parsing.
 * @param buildTypes The set of [BuildType] names contained in the project, used for parsing to filter
 * out any permutation of buildType + flavors
 */
class AndroidFlavor(
    override val project: Project,
    override val backingVariant: ProductFlavor,
    override val variantType: VariantType,
    private val buildTypes: Set<String>
) : AndroidNonVariant<ProductFlavor>(
    project = project,
    backingVariant = backingVariant,
    variantType = variantType,
    toIgnoreKeywords = buildTypes
) {
    override val extendsFrom: Set<String> = buildList {
        add(Default.toString())
        if (variantType.isTest) add(backingVariant.name)
        if (variantType == Test) add(DefaultVariants.Test.toString())
    }.toSet()
}

data class DefaultVariantData(
    val project: Project,
    val variantType: VariantType,
    val ignoreKeywords: Set<String>,
    val name: String = when (variantType) {
        AndroidBuild -> Default.toString()
        else -> DefaultVariants.Test.toString()
    },
)

fun AndroidDefaultVariant(
    project: Project,
    variantType: VariantType,
    ignoreKeywords: Set<String>,
) = AndroidDefaultVariant(DefaultVariantData(project, variantType, ignoreKeywords))

/**
 * A [Variant] implementation to denote the default type i.e. without any build type or variant
 * specific data.
 */
class AndroidDefaultVariant(
    private val defaultVariantData: DefaultVariantData
) : ConfigurationParsingVariant<DefaultVariantData> {
    override val name: String get() = defaultVariantData.name
    override val baseName: String get() = ""
    override val backingVariant: DefaultVariantData get() = defaultVariantData
    override val project: Project get() = defaultVariantData.project
    override val variantType: VariantType get() = defaultVariantData.variantType
    override val extendsFrom: Set<String> = setOf(Default.toString())

    private val ignoreKeywords get() = defaultVariantData.ignoreKeywords

    override val variantConfigurations: Set<Configuration>
        get() = project.configurations
            .asSequence()
            .filter { config ->
                val name = config.name
                ignoreKeywords.none { ignore -> name.contains(ignore, true) }
            }.filter {
                val configName = it.name
                when (variantType) {
                    AndroidBuild -> !configName.isTest()
                    AndroidTest -> configName.isAndroidTest()
                    Test -> configName.isUnitTest()
                    JvmBuild -> error("Invalid variant type ${JvmBuild.name} for Android variant")
                }
            }.toSet()

    override val compileConfiguration: Set<Configuration> by lazy {
        classpathConfiguration(Compile, namePattern = "", basePattern = "")
    }

    override val runtimeConfiguration: Set<Configuration> by lazy {
        classpathConfiguration(Runtime, namePattern = "", basePattern = "")
    }

    override val annotationProcessorConfiguration: Set<Configuration>
        get() = parseAnnotationProcessorConfigurations(
            fallback = project.configurations["annotationProcessor"]!!,
            "",
            ""
        )

    override val kotlinCompilerPluginConfiguration: Set<Configuration>
        get() = buildList {
            project.configurations["kotlinCompilerPluginClasspath"]?.let(::add)
            project.configurations["kotlin-extension"]?.let(::add)
        }.toSet()

    override fun toString(): String = MoreObjects.toStringHelper(this)
        .add("project", project.path)
        .add("name", name)
        .add("variantType", variantType)
        .toString()
}