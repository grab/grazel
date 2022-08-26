package com.grab.grazel.migrate.android

import com.grab.grazel.bazel.starlark.BazelDependency

data class AndroidUnitTestData(
    val name: String,
    val srcs: List<String>,
    val additionalSrcSets: List<String>,
    val deps: List<BazelDependency>,
    val tags: List<String>,
    val customPackage: String,
    val associates: List<BazelDependency>,
    val resources: List<String>,
)

internal fun AndroidUnitTestData.toUnitTestTarget() = AndroidUnitTestTarget(
    name = name,
    srcs = srcs,
    additionalSrcSets = additionalSrcSets,
    deps = deps,
    associates = associates,
    customPackage = customPackage,
    resources = resources,
    tags = tags,
)
