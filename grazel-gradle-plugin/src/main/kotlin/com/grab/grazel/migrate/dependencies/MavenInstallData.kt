package com.grab.grazel.migrate.dependencies

import com.grab.grazel.bazel.rules.MavenInstallArtifact
import com.grab.grazel.bazel.rules.MavenRepository
import com.grab.grazel.migrate.android.JetifierConfig

internal data class MavenInstallData(
    val name: String,
    val artifacts: Set<MavenInstallArtifact>,
    val externalArtifacts: Set<String>,
    val repositories: Set<MavenRepository>,
    val externalRepositories: Set<String>,
    val jetifierData: JetifierConfig,
    val failOnMissingChecksum: Boolean,
    val resolveTimeout: Int,
    val overrideTargets: Map<String, String>,
    val excludeArtifacts: Set<String>,
    val artifactPinning: Boolean,
    val versionConflictPolicy: String?
)