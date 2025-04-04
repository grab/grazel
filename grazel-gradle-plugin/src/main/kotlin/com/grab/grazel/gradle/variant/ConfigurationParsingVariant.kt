package com.grab.grazel.gradle.variant

import com.android.build.api.attributes.AgpVersionAttr
import com.android.builder.model.Version
import com.grab.grazel.gradle.hasKapt
import com.grab.grazel.gradle.variant.Classpath.Compile
import com.grab.grazel.gradle.variant.Classpath.Runtime
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.JvmBuild
import com.grab.grazel.gradle.variant.VariantType.Lint
import com.grab.grazel.gradle.variant.VariantType.Test
import com.grab.grazel.util.addTo
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.ResolutionStrategy.SortOrder
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.Usage.JAVA_RUNTIME
import org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.attributes.java.TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE
import org.gradle.kotlin.dsl.named
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

/**
 * [Variant] extension that builds [Variant] configuration by parsing them via their names.
 *
 * Android Gradle Plugin creates configurations for permutations of build types and flavors, while
 * there are certain patterns followed when naming these variants there is no type safe API to
 * map a particular `variant` to the `configurations` belong to that variant. This class tries to
 * parse this information by manually accounting for the configuration name patterns for known
 * configuration types.
 *
 * @see AndroidVariant
 * @see AndroidBuildType
 * @see AndroidFlavor
 * @see AndroidDefaultVariant
 * @see AndroidNonVariant
 */
interface ConfigurationParsingVariant<VariantData> : Variant<VariantData> {

    /**
     * The base name of [Variant], this is the actual name of the variant without any type
     * information associate with it. For example, [Variant.name] of `androidTestPaidDebug` and
     * `debug` would be `PaidDebug` and `Debug`. `androidTest` in this example is a variant type,
     * implementing classes should accordingly filter types or any non relevant data and return only
     * the actual name of the variant.
     *
     * Can return empty if baseName is not needed for parsing.
     */
    val baseName: String

    operator fun ConfigurationContainer.get(name: String) = findByName(name)

    override val variantConfigurations: Set<Configuration>
        get() = project.configurations.asSequence()
            .filter { config -> matchesVariantConfiguration(config.name) }
            .toSet()

    override val kotlinCompilerPluginConfiguration: Set<Configuration>
        get() = buildList {
            val capitalizedName = name.capitalize()
            project.configurations["kotlinCompilerPluginClasspath$capitalizedName"]?.let(::add)
            project.configurations["kotlin-extension"]?.let(::add)
        }.toSet()


    /**
     * Parses the annotation processor configurations for this variant. This is a workaround for
     * Android Gradle Plugin not providing a type safe API for this.
     *
     * @param fallback The fallback configuration to use if no annotation processor configurations
     *    are found.
     * @param namePattern The name pattern to use for matching configurations. Defaults to the
     *    [Variant.name].
     * @param basePattern The base name pattern to use for matching configurations. Defaults to the
     *    [Variant.baseName].
     */
    fun parseAnnotationProcessorConfigurations(
        fallback: Configuration,
        namePattern: String = name,
        basePattern: String = baseName,
    ) = buildSet {
        if (project.hasKapt) {
            val kaptConfigurations = variantConfigurations.filter { configuration ->
                val configName = configuration.name
                val capitalizedNamePattern = namePattern.capitalize()
                val capitalizedBasePattern = basePattern.capitalize()

                when (variantType) {
                    AndroidBuild -> configName.startsWith("kapt$capitalizedNamePattern")
                    AndroidTest -> configName.startsWith("kaptAndroidTest$capitalizedBasePattern")
                    Test -> configName.startsWith("kaptTest$capitalizedBasePattern")
                    Lint -> false
                    JvmBuild -> error("Invalid variant type ${JvmBuild.name} for Android variant")
                }
            }
            kaptConfigurations.addTo(this)
        } else {
            add(fallback)
        }
    }

    fun classpathConfiguration(
        classpath: Classpath,
        namePattern: String = name,
        basePattern: String = baseName,
    ): Set<Configuration> {
        if (variantType == Lint) return setOf(project.configurations["lintChecks"]!!)

        val classpathName = "grazel${name.capitalize()}${classpath.name.capitalize()}Classpath"
        val classpathConfiguration = project.configurations.maybeCreate(classpathName)
        val onlyConfig = when (classpath) {
            Runtime -> "RuntimeOnly"
            Compile -> "CompileOnly"
        }
        val metadata = "DependenciesMetadata"
        val baseConfigurations = variantConfigurations
            .filter {
                val configName = it.name.lowercase()
                when (variantType) {
                    AndroidBuild -> configName == "${namePattern}${onlyConfig}$metadata".lowercase()
                        || configName == "${namePattern}Implementation$metadata".lowercase()

                    AndroidTest -> configName == "androidTest${basePattern}${onlyConfig}$metadata".lowercase()
                        || configName == "androidTest${basePattern}Implementation$metadata".lowercase()

                    Test -> configName == "test${basePattern}${onlyConfig}$metadata".lowercase()
                        || configName == "test${basePattern}Implementation$metadata".lowercase()

                    Lint -> configName == "lintChecks".lowercase()
                    else -> error("$JvmBuild invalid for build type runtime configuration")
                }
            }.flatMap { it.hierarchy }
            .distinctBy { it.name }
            .filter { !it.name.contains(metadata) }

        classpathConfiguration.applyAttributes(baseConfigurations, classpath)
        return setOf(classpathConfiguration)
    }

    private fun Configuration.applyAttributes(
        baseConfigurations: List<Configuration>,
        classpath: Classpath
    ) {
        val objects = project.objects
        apply {
            isCanBeResolved = true
            isVisible = false
            isCanBeConsumed = false
            resolutionStrategy.sortArtifacts(SortOrder.CONSUMER_FIRST)
            description = "Resolved configuration for $name ${classpath.name} classpath"
            setExtendsFrom(baseConfigurations)
            attributes {
                attribute(
                    AgpVersionAttr.ATTRIBUTE,
                    objects.named<AgpVersionAttr>(Version.ANDROID_GRADLE_PLUGIN_VERSION)
                )
                attribute(
                    TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                    objects.named<TargetJvmEnvironment>(TargetJvmEnvironment.ANDROID)
                )
                attribute(USAGE_ATTRIBUTE, objects.named<Usage>(JAVA_RUNTIME))
                attribute(
                    KotlinPlatformType.attribute,
                    KotlinPlatformType.androidJvm
                )
            }
        }
    }

    /** Determines if a configuration name matches the current variant type. */
    private fun matchesVariantConfiguration(configName: String): Boolean {
        val variantNameMatches = configName.contains(name) || configName.contains(name.capitalize())

        val androidTestMatches = configName.contains("AndroidTest$baseName", true)
        val testMatches = configName.contains("UnitTest$baseName", true) ||
            configName.startsWith("test$baseName") ||
            configName.startsWith("kaptTest$baseName") ||
            (configName.contains("TestFixtures", true) && baseName in configName)

        return when (variantType) {
            AndroidBuild -> !configName.isTest && variantNameMatches
            AndroidTest -> configName.isAndroidTest && (variantNameMatches || androidTestMatches)
            Test -> configName.isUnitTest && (variantNameMatches || testMatches)
            else -> variantNameMatches
        }
    }

    val String.isAndroidTest
        get() = startsWith("androidTest")
            || contains("androidTest", true)
    val String.isUnitTest
        get() = startsWith("test")
            || startsWith("kaptTest")
            || contains("UnitTest")
    val String.isLint get() = startsWith("lintChecks")
    val String.isTest get() = isAndroidTest || isUnitTest || contains("Test")
}