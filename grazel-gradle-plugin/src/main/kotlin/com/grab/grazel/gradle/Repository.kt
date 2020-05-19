/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.gradle

import com.grab.grazel.di.qualifiers.RootProject
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.credentials.Credentials
import org.gradle.api.internal.artifacts.repositories.DefaultMavenArtifactRepository
import org.gradle.api.internal.artifacts.repositories.DefaultMavenLocalArtifactRepository
import org.gradle.api.internal.provider.DefaultProperty
import javax.inject.Inject
import javax.inject.Singleton

internal interface RepositoryDataSource {
    /**
     * All configured Maven repositories in the project.
     */
    val allRepositories: List<DefaultMavenArtifactRepository>

    /**
     * The Maven repositories among `allRepositories` that can be migrated. This is usually list of Maven repositories
     * without any auth or only Basic Auth.
     */
    val supportedRepositories: List<DefaultMavenArtifactRepository>

    /**
     * The repositories which can't be migrated to Bazel due to compatibility.
     */
    val unsupportedRepositories: List<ArtifactRepository>

    /**
     * Same as `unsupportedRepositories` but mapped to their names.
     */
    val unsupportedRepositoryNames: List<String>
}

@Singleton
internal class DefaultRepositoryDataSource @Inject constructor(
    @param:RootProject private val rootProject: Project
) : RepositoryDataSource {

    override val allRepositories: List<DefaultMavenArtifactRepository> by lazy {
        rootProject
            .allprojects
            .asSequence()
            .flatMap { it.repositories.asSequence() }
            .filterIsInstance<DefaultMavenArtifactRepository>()
            .filter { it !is DefaultMavenLocalArtifactRepository }
            .toList()
    }

    override val supportedRepositories: List<DefaultMavenArtifactRepository> by lazy {
        allRepositories
            .asSequence()
            .filter {
                it.configuredCredentials == null || it.configuredCredentials is PasswordCredentials
            }.filter { it.url.scheme.toLowerCase() != "file" }
            .toList()
    }

    override val unsupportedRepositories: List<ArtifactRepository> by lazy {
        allRepositories
            .asSequence()
            .filter {
                val creds = it.configuredCredentials
                val lazyCredentials = (creds as? DefaultProperty<*>)?.isPresent == true
                val nonPasswordCredentials = creds is Credentials && creds !is PasswordCredentials
                lazyCredentials || nonPasswordCredentials
            }.distinctBy { it.name }
            .toList()
    }

    override val unsupportedRepositoryNames: List<String> by lazy {
        unsupportedRepositories.map { it.name }
    }
}