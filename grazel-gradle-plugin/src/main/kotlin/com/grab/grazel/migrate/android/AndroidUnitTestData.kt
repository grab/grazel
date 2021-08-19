package com.grab.grazel.migrate.android

import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.extension.TestExtension

data class AndroidUnitTestData(
    val name: String,
    val srcs: List<String>,
    val deps: List<BazelDependency>,
    val customPackage: String,
    val associates: List<BazelDependency>
)

internal fun AndroidUnitTestData.toUnitTestTarget(): AndroidUnitTestTarget {
    return AndroidUnitTestTarget(
        name = name,
        srcs = srcs,
        deps = deps,
        associates = associates,
        customPackage = customPackage
    )
}
