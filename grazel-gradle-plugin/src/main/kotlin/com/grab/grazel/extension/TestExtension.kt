package com.grab.grazel.extension

import com.grab.grazel.bazel.rules.BazelRepositoryRule
import com.grab.grazel.bazel.rules.GitRepositoryRule
import com.grab.grazel.bazel.rules.HttpArchiveRule
import groovy.lang.Closure


private const val DEFAULT_ROBOLECTRIC_VERSION = "4.4"
private const val ROBOLECTRIC = "robolectric"
private const val DEFAULT_ROBOLECTRIC_STRIP_PREFIX = "robolectric-bazel-%s"
private const val DEFAULT_ROBOLECTRIC_URL =
    "https://github.com/robolectric/robolectric-bazel/archive/%s.tar.gz"

private val DEFAULT_ROBOLECTRIC_ARCHIVE = HttpArchiveRule(
    name = ROBOLECTRIC,
    stripPrefix = String.format(DEFAULT_ROBOLECTRIC_STRIP_PREFIX, DEFAULT_ROBOLECTRIC_VERSION),
    url = String.format(DEFAULT_ROBOLECTRIC_URL, DEFAULT_ROBOLECTRIC_VERSION)
)

data class RobolectricExtension(
    var repository: BazelRepositoryRule = DEFAULT_ROBOLECTRIC_ARCHIVE,
    var version: String = DEFAULT_ROBOLECTRIC_VERSION
) {

    fun gitRepository(closure: Closure<*>) {
        repository = GitRepositoryRule(name = ROBOLECTRIC)
        closure.delegate = repository
        closure.call()
    }

    fun gitRepository(builder: GitRepositoryRule.() -> Unit) {
        repository = GitRepositoryRule(name = ROBOLECTRIC).apply(builder)
    }

    fun httpArchiveRepository(closure: Closure<*>) {
        repository = DEFAULT_ROBOLECTRIC_ARCHIVE
        closure.delegate = repository
        closure.call()
    }

    fun httpArchiveRepository(builder: HttpArchiveRule.() -> Unit) {
        repository = DEFAULT_ROBOLECTRIC_ARCHIVE.apply(builder)
    }
}


class AndroidTestExtension {
    // TODO
}

data class TestExtension(
    var enableTestMigration: Boolean = false,
    val androidTest: AndroidTestExtension = AndroidTestExtension(),
    var enabledTransitiveReduction: Boolean = false,
) {
//    fun androidTest(block: AndroidTestExtension.() -> Unit) {
//        androidTest.block()
//    }
//
//    fun androidTest(closure: Closure<*>) {
//        closure.delegate = androidTest
//        closure.call()
//    }
}

