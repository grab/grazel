package com.grab.grazel.extension

import groovy.lang.Closure


private const val DEFAULT_ROBOLECTRIC_VERSION = "4.4"
private const val DEFAULT_ROBOLECTRIC_STRIP_PREFIX = "robolectric-bazel-4.4"
private const val DEFAULT_ROBOLECTRIC_URL = "https://github.com/roscrazy/robolectric-bazel/releases/tag/4.4.2.tar.gz"

data class RobolectricExtension(
    var stripPrefix: String = DEFAULT_ROBOLECTRIC_STRIP_PREFIX,
    var archiveUrl: String = DEFAULT_ROBOLECTRIC_URL,
    var version: String = DEFAULT_ROBOLECTRIC_VERSION
)


data class AndroidTestExtension(
    var robolectric: RobolectricExtension = RobolectricExtension()
) {

    fun robolectric(block: RobolectricExtension.() -> Unit) {
        robolectric.block()
    }

    fun robolectric(closure: Closure<*>) {
        closure.delegate = robolectric
        closure.call()
    }
}

data class TestExtension(
    var enableTestMigration: Boolean = false,
    var androidTest: AndroidTestExtension = AndroidTestExtension()
) {
    fun androidTest(block: AndroidTestExtension.() -> Unit) {
        androidTest.block()
    }

    fun androidTest(closure: Closure<*>) {
        closure.delegate = androidTest
        closure.call()
    }
}

