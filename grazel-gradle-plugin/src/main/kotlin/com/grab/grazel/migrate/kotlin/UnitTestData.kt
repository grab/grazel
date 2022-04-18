package com.grab.grazel.migrate.kotlin

import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.migrate.BazelBuildTarget
import com.grab.grazel.migrate.android.AndroidUnitTestTarget
import com.grab.grazel.migrate.builder.toDirectTranDepTags

data class UnitTestData(
    val name: String,
    val srcs: List<String>,
    val deps: List<BazelDependency>,
    val associates: List<BazelDependency>,
    val hasAndroidJarDep: Boolean = false,
)

internal fun UnitTestData.toUnitTestTarget(
    enabledTransitiveDepsReduction: Boolean = false,
): BazelBuildTarget =
    if (hasAndroidJarDep) {
        AndroidUnitTestTarget(
            name = name,
            srcs = srcs,
            deps = deps,
            associates = associates,
            customPackage = "",
            tags = if (enabledTransitiveDepsReduction) {
                deps.toDirectTranDepTags(self = name)
            } else {
                emptyList()
            },
        )
    } else {
        UnitTestTarget(
            name = name,
            srcs = srcs,
            deps = deps,
            associates = associates,
            tags = if (enabledTransitiveDepsReduction) {
                deps.toDirectTranDepTags(self = name)
            } else {
                emptyList()
            },
        )
    }
