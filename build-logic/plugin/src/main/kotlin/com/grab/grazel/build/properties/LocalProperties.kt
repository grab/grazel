package com.grab.grazel.build.properties

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import java.util.Properties


internal const val LOCAL_PROPERTIES = "local.properties"

public val Project.localProperties: Provider<Properties>
    get() = provider {
        rootProject
            .file(LOCAL_PROPERTIES)
            .inputStream()
            .use { stream -> Properties().apply { load(stream) } }
    }

public fun Project.localProperty(key: String): Provider<String?> =
    localProperties.map { it.getProperty(key) }

public fun Project.properties(key: String): Provider<String> = providers.gradleProperty(key)
public fun Project.environment(key: String): Provider<String> = providers.environmentVariable(key)

public fun Project.localOrEnvProperty(key: String): Provider<String?> =
    localProperty(key).orElse(environment(key))
