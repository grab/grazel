package com.grab.grazel.gradle.variant

import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.LibraryVariant
import com.android.build.gradle.api.TestVariant
import com.android.build.gradle.api.UnitTestVariant
import com.android.builder.model.BuildType
import com.google.common.base.MoreObjects
import com.grab.grazel.gradle.hasKapt
import com.grab.grazel.gradle.hasKsp
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.JvmBuild
import com.grab.grazel.gradle.variant.VariantType.Lint
import com.grab.grazel.gradle.variant.VariantType.Test
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration

/**
 * Base marker interface that denotes a variant that needs to be migrated and is used to encapsulate
 * both Android and Jvm variants
 *
 * Variants are meant to be the first extracted item from a [Project] instance for migration.
 *
 * @param T The original backing variant type
 * @see VariantBuilder
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

    /** Return [Configuration]'s belonging only to this variant */
    val variantConfigurations: Set<Configuration>

    val compileConfiguration: Set<Configuration>

    val runtimeConfiguration: Set<Configuration>

    val annotationProcessorConfiguration: Set<Configuration>

    val kspConfiguration: Set<Configuration>

    val kotlinCompilerPluginConfiguration: Set<Configuration>

    val workspaceVariantClasspathConfigurations: Set<Configuration>
        get() = safeWorkspaceRuntimeConfigurations + safeWorkspaceCompileConfigurations

    val workspaceUnitTestClasspathConfigurations: Set<Configuration>
        get() = matchingWorkspaceConfigurationNames(
            configurations = project.configurations,
            names = arrayOf(
                "${name}UnitTestRuntimeClasspath",
                "${name}UnitTestCompileClasspath"
            )
        )

    val workspaceAndroidTestClasspathConfigurations: Set<Configuration>
        get() = matchingWorkspaceConfigurationNames(
            configurations = project.configurations,
            names = arrayOf(
                "${name}AndroidTestRuntimeClasspath",
                "${name}AndroidTestCompileClasspath"
            )
        )

    val workspaceLintClasspathConfigurations: Set<Configuration>
        get() = matchingWorkspaceConfigurationNames(
            configurations = project.configurations,
            names = arrayOf("lintChecks")
        )
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
    },
    Lint("lint") {
        override fun toString() = variantName
    }
}

val DEFAULT_VARIANT = DefaultVariants.Default.toString()
val TEST_VARIANT = DefaultVariants.Test.toString()
val ANDROID_TEST_VARIANT = DefaultVariants.AndroidTest.toString()
val LINT_VARIANT = DefaultVariants.Lint.toString()

enum class VariantType {
    AndroidBuild,
    AndroidTest,
    Test,
    JvmBuild,
    Lint;

    /**
     * Returns true if this variant type represents build-time dependencies (not test). Only build
     * graphs should be included when merging for topological sorting to avoid artificial cycles
     * from test dependencies.
     */
    val isBuildGraph: Boolean
        get() = this == AndroidBuild || this == JvmBuild
}

fun BaseVariant.toVariantType(): VariantType = when (this) {
    is ApplicationVariant, is LibraryVariant -> AndroidBuild
    is TestVariant -> AndroidTest
    is UnitTestVariant -> Test
    else -> error("Cannot parse $name to VariantType")
}

val Variant<*>.isBase get() = name == DEFAULT_VARIANT

val Variant<*>.isWorkspaceAndroidLeaf: Boolean
    get() = backingVariant is BaseVariant

val Variant<*>.isWorkspaceMainHierarchyRoot: Boolean
    get() = isBase || backingVariant is BuildType

val Variant<*>.workspaceBuildTypeName: String?
    get() = (backingVariant as? BaseVariant)?.buildType?.name

val Variant<*>.workspaceProductFlavorNames: List<String>
    get() = (backingVariant as? BaseVariant)?.productFlavors?.map { flavor -> flavor.name }.orEmpty()

private val Variant<*>.safeWorkspaceRuntimeConfigurations: Set<Configuration>
    get() = try {
        runtimeConfiguration
    } catch (e: Exception) {
        emptySet()
    }

private val Variant<*>.safeWorkspaceCompileConfigurations: Set<Configuration>
    get() = try {
        compileConfiguration
    } catch (e: Exception) {
        emptySet()
    }

private fun matchingWorkspaceConfigurationNames(
    configurations: Iterable<Configuration>,
    names: Array<String>
): Set<Configuration> {
    val requestedNames = names.toSet()
    return configurations.filter { configuration -> configuration.name in requestedNames }.toSet()
}

/**
 * Returns true if this variant only extends from default variants (default, test, androidTest).
 * Such variants define the hierarchy structure and must always resolve dependencies to create
 * proper maven buckets for downstream composite variants.
 */
val Variant<*>.extendsOnlyFromDefaultVariants: Boolean
    get() = extendsFrom.isEmpty() || extendsFrom.all {
        it == DEFAULT_VARIANT || it == TEST_VARIANT || it == ANDROID_TEST_VARIANT
    }

val Variant<*>.id get() = name + variantType.toString()

val VariantType.isAndroidTest get() = this == AndroidTest
val VariantType.isTest get() = this == Test || isAndroidTest

val VariantType.testSuffix
    get() = when {
        this == Test -> "UnitTest"
        this == AndroidTest -> "AndroidTest"
        else -> error("$this is not a test type!")
    }

/**
 * Maps an Android-oriented VariantType to its JVM equivalent.
 * - AndroidBuild -> JvmBuild
 * - Test -> Test (unchanged)
 * - AndroidTest -> JvmBuild (not applicable to JVM)
 * - Lint -> Lint (unchanged)
 * - JvmBuild -> JvmBuild (unchanged)
 */
val VariantType.toJvmVariantType: VariantType
    get() = when (this) {
        AndroidBuild -> JvmBuild
        Test -> Test
        else -> JvmBuild
    }

/** Returns the default variant name for JVM projects based on variant type. */
val VariantType.jvmVariantName: String
    get() = when (this.toJvmVariantType) {
        JvmBuild -> DEFAULT_VARIANT
        Test -> TEST_VARIANT
        else -> DEFAULT_VARIANT
    }

/**
 * Returns the compile and runtime configurations that participate in the current migration model.
 */
val Variant<*>.migratableConfigurations
    get() = (compileConfiguration
        + runtimeConfiguration).toSet()

enum class Classpath {
    Runtime,
    Compile
}

class JvmVariantData(
    val project: Project,
    val variantType: VariantType,
    val name: String = when (variantType) {
        JvmBuild -> DEFAULT_VARIANT
        Lint -> LINT_VARIANT
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

    override val kspConfiguration: Set<Configuration>
        get() = buildSet {
            if (project.hasKsp) {
                // KSP creates *KotlinProcessorClasspath configs that are resolvable
                val configName = when (variantType) {
                    JvmBuild -> "kspKotlinProcessorClasspath"
                    else -> "kspTestKotlinProcessorClasspath"
                }
                configurationNameMap[configName]?.let(::add)
            }
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
