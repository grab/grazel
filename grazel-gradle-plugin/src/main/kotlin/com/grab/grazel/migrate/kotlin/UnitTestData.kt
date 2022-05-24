package com.grab.grazel.migrate.kotlin

import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.migrate.BazelBuildTarget
import com.grab.grazel.migrate.android.AndroidUnitTestTarget

data class UnitTestData(
    val name: String,
    val srcs: List<String>,
    val deps: List<BazelDependency>,
    val tags: List<String>,
    val associates: List<BazelDependency>,
    val hasAndroidJarDep: Boolean = false,
)

internal fun UnitTestData.toUnitTestTarget(): BazelBuildTarget =
    if (hasAndroidJarDep) {
        AndroidUnitTestTarget(
            name = name,
            srcs = srcs,
            deps = deps,
            associates = associates,
            customPackage = "",
            tags = tags,
        )
    } else {
        UnitTestTarget(
            name = name,
            srcs = srcs,
            deps = deps,
            associates = associates,
            tags = tags,
        )
    }
