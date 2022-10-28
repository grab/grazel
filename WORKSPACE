workspace(name = "grazel")

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

http_archive(
    name = "io_bazel_rules_kotlin",
    sha256 = "f033fa36f51073eae224f18428d9493966e67c27387728b6be2ebbdae43f140e",
    url = "https://github.com/bazelbuild/rules_kotlin/releases/download/v1.7.0-RC-3/rules_kotlin_release.tgz",
)

KOTLIN_VERSION = "1.6.10"

KOTLINC_RELEASE_SHA = "432267996d0d6b4b17ca8de0f878e44d4a099b7e9f1587a98edc4d27e76c215a"

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
    commit = "f5f196d46406e44c00b64cb767de9d9eb7219c2e",
    remote = "https://github.com/grab/grab-bazel-common.git",
)

load("@grab_bazel_common//android:repositories.bzl", "bazel_common_dependencies")

bazel_common_dependencies()

load("@grab_bazel_common//android:initialize.bzl", "bazel_common_initialize")

bazel_common_initialize(
    buildifier_version = "5.1.0",
    patched_android_tools = True,
)

DAGGER_TAG = "2.37"

DAGGER_SHA = "0f001ed38ed4ebc6f5c501c20bd35a68daf01c8dbd7541b33b7591a84fcc7b1c"

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
    sha256 = "735602f50813eb2ea93ca3f5e43b1959bd80b213b836a07a62a29d757670b77b",
    strip_prefix = "rules_jvm_external-4.4.2",
    url = "https://github.com/bazelbuild/rules_jvm_external/archive/4.4.2.zip",
)

load("@rules_jvm_external//:defs.bzl", "maven_install")
load("@rules_jvm_external//:specs.bzl", "maven")

maven_install(
    artifacts = DAGGER_ARTIFACTS + GRAB_BAZEL_COMMON_ARTIFACTS + [
        "androidx.annotation:annotation:1.1.0",
        "androidx.appcompat:appcompat:1.3.1",
        "androidx.constraintlayout:constraintlayout-core:1.0.1",
        maven.artifact(
            artifact = "constraintlayout",
            exclusions = [
                "androidx.appcompat:appcompat",
                "androidx.core:core",
            ],
            group = "androidx.constraintlayout",
            version = "1.1.2",
        ),
        "androidx.core:core:1.5.0",
        "androidx.databinding:databinding-adapters:7.1.2",
        "androidx.databinding:databinding-common:7.1.2",
        "androidx.databinding:databinding-compiler:7.1.2",
        "androidx.databinding:databinding-runtime:7.1.2",
        "androidx.databinding:viewbinding:7.1.2",
        "androidx.test.espresso:espresso-core:3.4.0",
        "androidx.test.ext:junit:1.1.3",
        "junit:junit:4.13.2",
        "org.jacoco:org.jacoco.ant:0.8.3",
        "org.jetbrains.kotlin:kotlin-annotation-processing-gradle:1.6.21",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.4.32",
        "org.jetbrains.kotlin:kotlin-stdlib:1.4.32",
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
        "androidx.databinding:viewbinding",
        "androidx.test.espresso:espresso-core",
        "androidx.test.ext:junit",
        "com.android.support:cardview-v7",
        "junit:junit",
        "org.jacoco:org.jacoco.ant",
        "org.jetbrains.kotlin:kotlin-annotation-processing-gradle",
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
    version_conflict_policy = "pinned",
)

load("@maven//:defs.bzl", "pinned_maven_install")

pinned_maven_install()

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
