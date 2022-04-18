package com.grab.grazel.migrate.android

import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.migrate.builder.toDirectTranDepTags

data class AndroidUnitTestData(
    val name: String,
    val srcs: List<String>,
    val deps: List<BazelDependency>,
    val customPackage: String,
    val associates: List<BazelDependency>,
    val resources: List<String>,
)

internal fun AndroidUnitTestData.toUnitTestTarget(
    enabledTransitiveDepsReduction: Boolean = false,
): AndroidUnitTestTarget {
    return AndroidUnitTestTarget(
        name = name,
        srcs = srcs,
        deps = deps,
        associates = associates,
        customPackage = customPackage,
        resources = resources,
        tags = if (enabledTransitiveDepsReduction) {
            deps.toDirectTranDepTags(self = name)
        } else {
            emptyList()
        },
    )
}
