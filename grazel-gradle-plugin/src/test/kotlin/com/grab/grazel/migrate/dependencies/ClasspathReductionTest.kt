package com.grab.grazel.migrate.dependencies

import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.bazel.starlark.BazelDependency.ProjectDependency
import com.grab.grazel.buildProject
import com.grab.grazel.util.truth
import org.junit.Test

class ClasspathReductionTest {
    @Test
    fun `test with project dependencies`() {
        val rootProject = buildProject("root")
        val subproject = buildProject("sub", parent = rootProject)
        val deps: List<ProjectDependency> = listOf(
            ProjectDependency(dependencyProject = rootProject),
            ProjectDependency(dependencyProject = subproject),
        )
        calculateDirectDependencyTags("self", deps).truth {
            containsExactly(
                "@direct//root:root",
                "@direct//sub:sub",
                "@self//self"
            )
        }
    }

    @Test
    fun `test with maven dependencies`() {
        val deps: List<BazelDependency> = listOf(
            MavenDependency(group = "com.example", name = "lib1"),
            MavenDependency(group = "org.test", name = "lib2", repo = "custom_repo")
        )
        calculateDirectDependencyTags("self", deps).truth {
            containsExactly(
                "@maven//:com_example_lib1",
                "@maven//:org_test_lib2",
                "@self//self"
            )
        }
    }

    @Test
    fun `test with mixed dependencies`() {
        val rootProject = buildProject("root")
        val deps: List<BazelDependency> = listOf(
            ProjectDependency(dependencyProject = rootProject),
            MavenDependency(group = "com.example", name = "lib1")
        )
        calculateDirectDependencyTags("self", deps).truth {
            containsExactly(
                "@direct//root:root",
                "@maven//:com_example_lib1",
                "@self//self",
            )
        }
    }
}