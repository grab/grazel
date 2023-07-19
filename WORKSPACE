workspace(name = "grazel")

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

http_archive(
    name = "io_bazel_rules_kotlin",
    sha256 = "01293740a16e474669aba5b5a1fe3d368de5832442f164e4fbfc566815a8bc3a",
    url = "https://github.com/bazelbuild/rules_kotlin/releases/download/v1.8/rules_kotlin_release.tgz",
)

KOTLIN_VERSION = "1.8.10"

KOTLINC_RELEASE_SHA = "4c3fa7bc1bb9ef3058a2319d8bcc3b7196079f88e92fdcd8d304a46f4b6b5787"

load("@io_bazel_rules_kotlin//kotlin:repositories.bzl", "kotlin_repositories", "kotlinc_version")

KOTLINC_RELEASE = kotlinc_version(
    release = KOTLIN_VERSION,
    sha256 = KOTLINC_RELEASE_SHA,
)

kotlin_repositories(compiler_release = KOTLINC_RELEASE)

register_toolchains("//:kotlin_toolchain")

load("@bazel_tools//tools/build_defs/repo:git.bzl", "git_repository")

git_repository(
    name = "grab_bazel_common",
    commit = "120019c5290cceda3c795d9ddb2a3253b9b32b3e",
    remote = "https://github.com/grab/grab-bazel-common.git",
)

load("@grab_bazel_common//android:repositories.bzl", "bazel_common_dependencies")

bazel_common_dependencies()

load("@grab_bazel_common//android:initialize.bzl", "bazel_common_initialize")

bazel_common_initialize(
    buildifier_version = "v6.1.2",
    patched_android_tools = True,
)

load("@grab_bazel_common//android:maven.bzl", "pin_bazel_common_artifacts")

pin_bazel_common_artifacts()

DAGGER_TAG = "2.46.1"

DAGGER_SHA = "bbd75275faa3186ebaa08e6779dc5410741a940146d43ef532306eb2682c13f7"

http_archive(
    name = "dagger",
    sha256 = DAGGER_SHA,
    strip_prefix = "dagger-dagger-%s" % DAGGER_TAG,
    url = "https://github.com/google/dagger/archive/dagger-%s.zip" % DAGGER_TAG,
)

load("@dagger//:workspace_defs.bzl", "DAGGER_ARTIFACTS", "DAGGER_REPOSITORIES")
load("@grab_bazel_common//:workspace_defs.bzl", "GRAB_BAZEL_COMMON_ARTIFACTS")

http_archive(
    name = "rules_jvm_external",
    sha256 = "f86fd42a809e1871ca0aabe89db0d440451219c3ce46c58da240c7dcdc00125f",
    strip_prefix = "rules_jvm_external-5.2",
    url = "https://github.com/bazelbuild/rules_jvm_external/releases/download/5.2/rules_jvm_external-5.2.tar.gz",
)

load("@rules_jvm_external//:defs.bzl", "maven_install")
load("@rules_jvm_external//:specs.bzl", "maven")

maven_install(
    artifacts = DAGGER_ARTIFACTS + GRAB_BAZEL_COMMON_ARTIFACTS + [
        "androidx.activity:activity-compose:1.7.2",
        "androidx.activity:activity:1.6.0",
        "androidx.annotation:annotation:1.1.0",
        "androidx.appcompat:appcompat:1.6.1",
        "androidx.compose.compiler:compiler:1.4.3",
        "androidx.compose.foundation:foundation-layout:1.4.3",
        "androidx.compose.foundation:foundation:1.4.3",
        "androidx.compose.material:material:1.4.3",
        "androidx.compose.ui:ui-tooling:1.4.3",
        "androidx.compose.ui:ui:1.4.3",
        "androidx.constraintlayout:constraintlayout-core:1.0.4",
        maven.artifact(
            artifact = "constraintlayout",
            exclusions = [
                "androidx.appcompat:appcompat",
                "androidx.core:core",
            ],
            group = "androidx.constraintlayout",
            version = "2.1.4",
        ),
        "androidx.core:core:1.10.1",
        "androidx.databinding:databinding-adapters:7.2.2",
        "androidx.databinding:databinding-common:7.2.2",
        "androidx.databinding:databinding-compiler:7.2.2",
        "androidx.databinding:databinding-runtime:7.2.2",
        "androidx.databinding:viewbinding:7.2.2",
        "androidx.emoji2:emoji2:1.3.0",
        "androidx.lifecycle:lifecycle-common:2.6.1",
        "androidx.lifecycle:lifecycle-runtime:2.5.1",
        "androidx.lifecycle:lifecycle-viewmodel:2.5.1",
        "androidx.test.espresso:espresso-core:3.5.1",
        "androidx.test.ext:junit:1.1.5",
        "androidx.test:monitor:1.6.1",
        "junit:junit:4.13.2",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.5.31",
    ],
    excluded_artifacts = ["androidx.test.espresso:espresso-contrib"],
    fail_on_missing_checksum = False,
    jetify = True,
    jetify_include_list = [
        "androidx.activity:activity",
        "androidx.activity:activity-compose",
        "androidx.annotation:annotation",
        "androidx.compose.compiler:compiler",
        "androidx.compose.foundation:foundation",
        "androidx.compose.foundation:foundation-layout",
        "androidx.compose.material:material",
        "androidx.compose.ui:ui",
        "androidx.compose.ui:ui-tooling",
        "androidx.constraintlayout:constraintlayout",
        "androidx.constraintlayout:constraintlayout-core",
        "androidx.core:core",
        "androidx.databinding:databinding-adapters",
        "androidx.databinding:databinding-common",
        "androidx.databinding:databinding-compiler",
        "androidx.databinding:databinding-runtime",
        "androidx.databinding:viewbinding",
        "androidx.emoji2:emoji2",
        "androidx.lifecycle:lifecycle-common",
        "androidx.lifecycle:lifecycle-runtime",
        "androidx.lifecycle:lifecycle-viewmodel",
        "androidx.test.espresso:espresso-core",
        "androidx.test.ext:junit",
        "androidx.test:monitor",
        "com.android.support:cardview-v7",
        "junit:junit",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8",
    ],
    maven_install_json = "//:maven_install.json",
    override_targets = {
        "androidx.appcompat:appcompat": "@//third_party:androidx_appcompat_appcompat",
    },
    repositories = [
        "https://dl.google.com/dl/android/maven2/",
        "https://repo.maven.apache.org/maven2/",
    ] + DAGGER_REPOSITORIES,
    resolve_timeout = 1000,
    version_conflict_policy = "pinned",
)

load("@maven//:defs.bzl", "pinned_maven_install")

pinned_maven_install()

android_sdk_repository(
    name = "androidsdk",
    api_level = 33,
    build_tools_version = "33.0.1",
)

android_ndk_repository(
    name = "androidndk",
    api_level = 30,
)

git_repository(
    name = "tools_android",
    commit = "7224f55d7fafe12a72066eb1a2ad1e1526a854c4",
    remote = "https://github.com/bazelbuild/tools_android.git",
)

load("@tools_android//tools/googleservices:defs.bzl", "google_services_workspace_dependencies")

google_services_workspace_dependencies()
