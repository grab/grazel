workspace(name = "grazel")

load("@bazel_tools//tools/build_defs/repo:git.bzl", "git_repository")

git_repository(
    name = "grab_bazel_common",
    commit = "2dc3a57d24132ea542efb881d13f3beb707b27f5",
    remote = "https://github.com/grab/grab-bazel-common.git",
)

load("@grab_bazel_common//:workspace_defs.bzl", "android_tools")

android_tools(
    commit = "2dc3a57d24132ea542efb881d13f3beb707b27f5",
    remote = "https://github.com/grab/grab-bazel-common.git",
)

DAGGER_TAG = "2.28.1"

DAGGER_SHA = "9e69ab2f9a47e0f74e71fe49098bea908c528aa02fa0c5995334447b310d0cdd"

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

http_archive(
    name = "dagger",
    sha256 = DAGGER_SHA,
    strip_prefix = "dagger-dagger-%s" % DAGGER_TAG,
    url = "https://github.com/google/dagger/archive/dagger-%s.zip" % DAGGER_TAG,
)

load("@dagger//:workspace_defs.bzl", "DAGGER_ARTIFACTS", "DAGGER_REPOSITORIES")
load("@grab_bazel_common//:workspace_defs.bzl", "GRAB_BAZEL_COMMON_ARTIFACTS")

RULES_JVM_EXTERNAL_TAG = "3.3"

RULES_JVM_EXTERNAL_SHA = "d85951a92c0908c80bd8551002d66cb23c3434409c814179c0ff026b53544dab"

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

http_archive(
    name = "rules_jvm_external",
    sha256 = RULES_JVM_EXTERNAL_SHA,
    strip_prefix = "rules_jvm_external-%s" % RULES_JVM_EXTERNAL_TAG,
    url = "https://github.com/bazelbuild/rules_jvm_external/archive/%s.zip" % RULES_JVM_EXTERNAL_TAG,
)

load("@rules_jvm_external//:defs.bzl", "maven_install")
load("@rules_jvm_external//:specs.bzl", "maven")

maven_install(
    artifacts = DAGGER_ARTIFACTS + GRAB_BAZEL_COMMON_ARTIFACTS + [
        "androidx.annotation:annotation:1.1.0",
        "androidx.appcompat:appcompat:1.1.0",
        "androidx.constraintlayout:constraintlayout:1.1.2",
        "androidx.databinding:databinding-adapters:3.4.2",
        "androidx.databinding:databinding-common:3.4.2",
        "androidx.databinding:databinding-compiler:3.4.2",
        "androidx.databinding:databinding-runtime:3.4.2",
        "junit:junit:4.13",
        "org.jetbrains.kotlin:kotlin-annotation-processing-gradle:1.4.31",
        "org.jetbrains.kotlin:kotlin-parcelize-runtime:1.4.31",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.3.72",
        "org.robolectric:robolectric:4.4",
    ],
    excluded_artifacts = ["androidx.test.espresso:espresso-contrib"],
    fail_on_missing_checksum = False,
    jetify = True,
    jetify_include_list = [
        "androidx.annotation:annotation",
        "androidx.constraintlayout:constraintlayout",
        "androidx.databinding:databinding-adapters",
        "androidx.databinding:databinding-common",
        "androidx.databinding:databinding-compiler",
        "androidx.databinding:databinding-runtime",
        "com.android.support:cardview-v7",
        "junit:junit",
        "org.jetbrains.kotlin:kotlin-annotation-processing-gradle",
        "org.jetbrains.kotlin:kotlin-parcelize-runtime",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8",
        "org.robolectric:robolectric",
    ],
    repositories = DAGGER_REPOSITORIES + [
        "https://dl.google.com/dl/android/maven2/",
        "https://jcenter.bintray.com/",
        "https://maven.google.com",
        "https://repo1.maven.org/maven2",
    ],
    resolve_timeout = 1000,
)

load("@bazel_tools//tools/build_defs/repo:git.bzl", "git_repository")

git_repository(
    name = "io_bazel_rules_kotlin",
    commit = "eae21653baad4b403fee9e8a706c9d4fbd0c27c6",
    remote = "https://github.com/bazelbuild/rules_kotlin.git",
)

load("@io_bazel_rules_kotlin//kotlin:dependencies.bzl", "kt_download_local_dev_dependencies")

kt_download_local_dev_dependencies()

KOTLIN_VERSION = "1.4.21"

KOTLINC_RELEASE_SHA = "46720991a716e90bfc0cf3f2c81b2bd735c14f4ea6a5064c488e04fd76e6b6c7"

KOTLINC_RELEASE = {
    "urls": [
        "https://github.com/JetBrains/kotlin/releases/download/v{v}/kotlin-compiler-{v}.zip".format(v = KOTLIN_VERSION),
    ],
    "sha256": KOTLINC_RELEASE_SHA,
}

load("@io_bazel_rules_kotlin//kotlin:kotlin.bzl", "kotlin_repositories")

kotlin_repositories(compiler_release = KOTLINC_RELEASE)

register_toolchains("//:kotlin_toolchain")

android_sdk_repository(
    name = "androidsdk",
    api_level = 30,
    build_tools_version = "30.0.2",
)

android_ndk_repository(
    name = "androidndk",
)

TOOLS_ANDROID_COMMIT = "58d67fd54a3b7f5f1e6ddfa865442db23a60e1b6"

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

http_archive(
    name = "tools_android",
    sha256 = "a192553d52a42df306437a8166fc6b5ec043282ac4f72e96999ae845ece6812f",
    strip_prefix = "tools_android-" + TOOLS_ANDROID_COMMIT,
    url = "https://github.com/bazelbuild/tools_android/archive/%s.tar.gz" % TOOLS_ANDROID_COMMIT,
)

load("@tools_android//tools/googleservices:defs.bzl", "google_services_workspace_dependencies")

google_services_workspace_dependencies()

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

http_archive(
    name = "robolectric",
    strip_prefix = "robolectric-bazel-4.4",
    url = "https://github.com/robolectric/robolectric-bazel/archive/4.4.tar.gz",
)

load("@robolectric//bazel:robolectric.bzl", "robolectric_repositories")

robolectric_repositories()
