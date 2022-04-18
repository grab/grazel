workspace(name = "grazel")

load("@bazel_tools//tools/build_defs/repo:git.bzl", "git_repository")

git_repository(
    name = "grab_bazel_common",
    commit = "540432b688186b0b6f52d6c49d116bf2cef82f3d",
    remote = "https://github.com/grab/grab-bazel-common.git",
)

load("@grab_bazel_common//:workspace_defs.bzl", "android_tools")

android_tools(
    commit = "540432b688186b0b6f52d6c49d116bf2cef82f3d",
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
load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

http_archive(
    name = "rules_jvm_external",
    sha256 = "f36441aa876c4f6427bfb2d1f2d723b48e9d930b62662bf723ddfb8fc80f0140",
    strip_prefix = "rules_jvm_external-4.1",
    url = "https://github.com/bazelbuild/rules_jvm_external/archive/4.1.zip",
)

load("@rules_jvm_external//:defs.bzl", "maven_install")
load("@rules_jvm_external//:specs.bzl", "maven")

maven_install(
    artifacts = DAGGER_ARTIFACTS + GRAB_BAZEL_COMMON_ARTIFACTS + [
        "androidx.annotation:annotation:1.1.0",
        "androidx.appcompat:appcompat:1.3.1",
        "androidx.constraintlayout:constraintlayout-core:1.0.1",
        maven.artifact(
            group = "androidx.constraintlayout",
            artifact = "constraintlayout",
            version = "1.1.2",
            exclusions = [
                "androidx.appcompat:appcompat",
                "androidx.core:core",
            ],
        ),
        "androidx.core:core:1.5.0",
        "androidx.databinding:databinding-adapters:3.4.2",
        "androidx.databinding:databinding-common:3.4.2",
        "androidx.databinding:databinding-compiler:3.4.2",
        "androidx.databinding:databinding-runtime:3.4.2",
        "junit:junit:4.13.2",
        "org.jetbrains.kotlin:kotlin-annotation-processing-gradle:1.4.31",
        "org.jetbrains.kotlin:kotlin-parcelize-runtime:1.4.31",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.3.72",
        "org.jetbrains.kotlin:kotlin-stdlib:1.3.72",
    ],
    excluded_artifacts = ["androidx.test.espresso:espresso-contrib"],
    fail_on_missing_checksum = False,
    jetify = True,
    jetify_include_list = [
        "androidx.annotation:annotation",
        "androidx.constraintlayout:constraintlayout",
        "androidx.constraintlayout:constraintlayout-core",
        "androidx.core:core",
        "androidx.databinding:databinding-adapters",
        "androidx.databinding:databinding-common",
        "androidx.databinding:databinding-compiler",
        "androidx.databinding:databinding-runtime",
        "com.android.support:cardview-v7",
        "junit:junit",
        "org.jetbrains.kotlin:kotlin-annotation-processing-gradle",
        "org.jetbrains.kotlin:kotlin-parcelize-runtime",
        "org.jetbrains.kotlin:kotlin-stdlib",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8",
    ],
    maven_install_json = "//:maven_install.json",
    override_targets = {
        "androidx.appcompat:appcompat": "@//third_party:androidx_appcompat_appcompat",
    },
    repositories = DAGGER_REPOSITORIES + [
        "https://dl.google.com/dl/android/maven2/",
        "https://repo.maven.apache.org/maven2/",
        "https://maven.google.com",
        "https://repo1.maven.org/maven2",
    ],
    resolve_timeout = 1000,
)

load("@maven//:defs.bzl", "pinned_maven_install")

pinned_maven_install()

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
    api_level = 30,
)

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

http_archive(
    name = "tools_android",
    sha256 = "a192553d52a42df306437a8166fc6b5ec043282ac4f72e96999ae845ece6812f",
    strip_prefix = "tools_android-58d67fd54a3b7f5f1e6ddfa865442db23a60e1b6",
    url = "https://github.com/bazelbuild/tools_android/archive/58d67fd54a3b7f5f1e6ddfa865442db23a60e1b6.tar.gz",
)

load("@tools_android//tools/googleservices:defs.bzl", "google_services_workspace_dependencies")

google_services_workspace_dependencies()
