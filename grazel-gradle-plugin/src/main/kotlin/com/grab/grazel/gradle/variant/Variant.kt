package com.grab.grazel.gradle.variant

import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.LibraryVariant
import com.android.build.gradle.api.TestVariant
import com.android.build.gradle.api.UnitTestVariant
import com.google.common.base.MoreObjects
import com.grab.grazel.gradle.ConfigurationScope
import com.grab.grazel.gradle.ConfigurationScope.ANDROID_TEST
import com.grab.grazel.gradle.ConfigurationScope.BUILD
import com.grab.grazel.gradle.ConfigurationScope.TEST
import com.grab.grazel.gradle.hasKapt
import com.grab.grazel.gradle.isAndroid
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.JvmBuild
import com.grab.grazel.gradle.variant.VariantType.Test
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration

/**
 * Base marker interface that denotes a variant that needs to be migrated and is used to
 * encapsulate both Android and Jvm variants
 *
 * Variants are meant to be the first extracted item from a [Project] instance for migration.
 * @see VariantBuilder
 *
 * @param T The original backing variant type
 */
interface Variant<T> {
    val name: String
    val backingVariant: T

    val project: Project

    val variantType: VariantType

    /**
     * Variants can have a hierarchy and `extendsFrom` denotes the parent variants of this variant.
     *
     * For example, `debugUnitTest` extends from `debug`, 'default', and `test` variant.
     */
    val extendsFrom: Set<String>

    /**
     * Return [Configuration]'s belonging only to this variant
     */
    val variantConfigurations: Set<Configuration>

    val compileConfiguration: Set<Configuration>

    val runtimeConfiguration: Set<Configuration>

    val annotationProcessorConfiguration: Set<Configuration>

    val kotlinCompilerPluginConfiguration: Set<Configuration>
}

enum class DefaultVariants(val variantName: String) {
    Default("default") {
        override fun toString() = variantName
    },
    Test("test") {
        override fun toString() = variantName
    },
    AndroidTest("androidTest") {
        override fun toString() = variantName
    }
}

val DEFAULT_VARIANT = DefaultVariants.Default.toString()
val TEST_VARIANT = DefaultVariants.Test.toString()
val ANDROID_TEST_VARIANT = DefaultVariants.AndroidTest.toString()

enum class VariantType {
    AndroidBuild,
    AndroidTest,
    Test,
    JvmBuild,
}

fun BaseVariant.toVariantType(): VariantType = when (this) {
    is ApplicationVariant, is LibraryVariant -> AndroidBuild
    is TestVariant -> AndroidTest
    is UnitTestVariant -> Test
    else -> error("Cannot parse $name to VariantType")
}

val Variant<*>.isBase get() = name == DEFAULT_VARIANT

/**
 * Bridge function to map [ConfigurationScope] to [VariantType]
 * Not required once fully migrated to [Variant] APIs
 *
 * @return whether this [VariantType] corresponds to [ConfigurationScope]
 */
fun VariantType.isConfigScope(
    project: Project,
    configurationScope: ConfigurationScope
) = when (configurationScope) {
    BUILD -> this == if (project.isAndroid) AndroidBuild else JvmBuild
    TEST -> this == Test
    ANDROID_TEST -> this == AndroidTest
}

val VariantType.isAndroidTest get() = this == AndroidTest
val VariantType.isTest get() = this == Test || isAndroidTest

val VariantType.testSuffix
    get() = when {
        this == Test -> "UnitTest"
        this == AndroidTest -> "AndroidTest"
        else -> error("$this is not a test type!")
    }

/**
 * Return the migratable configurations for this variant. Currently all configurations are merged.
 * TODO("Migrate runtime, annotation processor and Kotlin compiler plugin configuration separately")
 */
val Variant<*>.migratableConfigurations
    get() = (compileConfiguration
        + runtimeConfiguration
        + annotationProcessorConfiguration
        + kotlinCompilerPluginConfiguration).toSet()

enum class Classpath {
    Runtime,
    Compile
}

class JvmVariantData(
    val project: Project,
    val variantType: VariantType,
    val name: String = when (variantType) {
        JvmBuild -> DEFAULT_VARIANT
        else -> TEST_VARIANT
    }
)

fun JvmVariant(project: Project, variantType: VariantType) = JvmVariant(
    JvmVariantData(
        project,
        variantType
    )
)

/**
 * Jvm libraries don't have variants like Android projects do hence this type is used to encapsulate
 * Jvm specific information in `Variant` class.
 *
 * @see DefaultVariants
 */
class JvmVariant(
    private val jvmVariantData: JvmVariantData
) : Variant<JvmVariantData> {
    override val name: String get() = jvmVariantData.name
    override val backingVariant: JvmVariantData get() = jvmVariantData
    override val project: Project get() = jvmVariantData.project
    override val variantType: VariantType get() = jvmVariantData.variantType

    override val variantConfigurations: Set<Configuration>
        get() = project.configurations.filter {
            when (variantType) {
                Test -> it.name.contains("test", true)
                else -> !it.name.contains("test", true)
            }
        }.toSet()

    override val extendsFrom: Set<String> = emptySet()

    // Store name to configurations to avoid lookup cost for below configurations parsing
    private val configurationNameMap = project.configurations.associateBy { it.name }

    override val compileConfiguration: Set<Configuration>
        get() = setOf(
            configurationNameMap.getValue(
                when {
                    variantType.isTest -> "testCompileClasspath"
                    else -> "compileClasspath"
                }
            )
        )

    override val runtimeConfiguration: Set<Configuration>
        get() = setOf(
            configurationNameMap.getValue(
                when {
                    variantType.isTest -> "testRuntimeClasspath"
                    else -> "runtimeClasspath"
                }
            )
        )

    override val annotationProcessorConfiguration: Set<Configuration>
        get() = buildSet {
            add(
                if (project.hasKapt) when (variantType) {
                    JvmBuild -> configurationNameMap.getValue("kapt")
                    else -> configurationNameMap.getValue("kaptTest")
                } else when (variantType) {
                    JvmBuild -> configurationNameMap.getValue("testAnnotationProcessor")
                    else -> configurationNameMap.getValue("annotationProcessor")
                }
            )
        }

    override val kotlinCompilerPluginConfiguration: Set<Configuration>
        get() = buildSet {
            val configName = "kotlinCompilerPluginClasspath"
            add(
                when (variantType) {
                    Test -> configurationNameMap.getValue("${configName}Test")
                    else -> configurationNameMap.getValue("${configName}Main")
                }
            )
        }

    override fun toString(): String = MoreObjects.toStringHelper(this)
        .add("project", project.name)
        .add("name", name)
        .add("variantType", variantType)
        .toString()
}