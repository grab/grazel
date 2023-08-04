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
    commit = "7415aab81c8cfec7ea05bb9bcb2b16d595bbc88e",
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

DAGGER_TAG = "2.47"

DAGGER_SHA = "154cdfa4f6f552a9873e2b4448f7a80415cb3427c4c771a50c6a8a8b434ffd0a"

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
    name = "android_test_maven",
    artifacts = [
        "androidx.annotation:annotation-experimental:1.1.0",
        "androidx.annotation:annotation:1.7.0-alpha03",
        "androidx.concurrent:concurrent-futures:1.1.0",
        "androidx.lifecycle:lifecycle-common:2.3.1",
        "androidx.test.espresso:espresso-core:3.5.1",
        "androidx.test.espresso:espresso-idling-resource:3.5.1",
        "androidx.test.ext:junit:1.1.5",
        "androidx.test.services:storage:1.4.2",
        "androidx.test:annotation:1.0.1",
        "androidx.test:core:1.5.0",
        "androidx.test:monitor:1.6.1",
        "androidx.test:runner:1.5.2",
        "androidx.tracing:tracing:1.0.0",
        "com.google.code.findbugs:jsr305:2.0.2",
        "com.google.guava:listenablefuture:1.0",
        "com.squareup:javawriter:2.1.1",
        "javax.inject:javax.inject:1",
        "junit:junit:4.13.2",
        "org.hamcrest:hamcrest-core:1.3",
        "org.hamcrest:hamcrest-integration:1.3",
        "org.hamcrest:hamcrest-library:1.3",
        "org.jetbrains.kotlin:kotlin-stdlib-common:1.7.10",
        "org.jetbrains.kotlin:kotlin-stdlib:1.7.10",
        "org.jetbrains:annotations:13.0",
    ],
    excluded_artifacts = ["androidx.test.espresso:espresso-contrib"],
    fail_on_missing_checksum = False,
    jetify = True,
    jetify_include_list = [
        "androidx.annotation:annotation",
        "androidx.annotation:annotation-experimental",
        "androidx.concurrent:concurrent-futures",
        "androidx.lifecycle:lifecycle-common",
        "androidx.test.espresso:espresso-core",
        "androidx.test.espresso:espresso-idling-resource",
        "androidx.test.ext:junit",
        "androidx.test.services:storage",
        "androidx.test:annotation",
        "androidx.test:core",
        "androidx.test:monitor",
        "androidx.test:runner",
        "androidx.tracing:tracing",
        "com.android.support:cardview-v7",
        "com.google.code.findbugs:jsr305",
        "com.google.guava:listenablefuture",
        "com.squareup:javawriter",
        "javax.inject:javax.inject",
        "junit:junit",
        "org.hamcrest:hamcrest-core",
        "org.hamcrest:hamcrest-integration",
        "org.hamcrest:hamcrest-library",
        "org.jetbrains.kotlin:kotlin-stdlib",
        "org.jetbrains.kotlin:kotlin-stdlib-common",
        "org.jetbrains:annotations",
    ],
    override_targets = {
        "androidx.annotation:annotation": "@maven//:androidx_annotation_annotation_jvm",
        "androidx.annotation:annotation-experimental": "@maven//:androidx_annotation_annotation_experimental",
        "androidx.lifecycle:lifecycle-common": "@maven//:androidx_lifecycle_lifecycle_common",
        "javax.inject:javax.inject": "@maven//:javax_inject_javax_inject",
        "org.jetbrains:annotations": "@maven//:org_jetbrains_annotations",
        "org.jetbrains.kotlin:kotlin-stdlib": "@maven//:org_jetbrains_kotlin_kotlin_stdlib",
        "org.jetbrains.kotlin:kotlin-stdlib-common": "@maven//:org_jetbrains_kotlin_kotlin_stdlib_common",
    },
    repositories = [
        "https://dl.google.com/dl/android/maven2/",
        "https://repo.maven.apache.org/maven2/",
    ],
    resolve_timeout = 1000,
    version_conflict_policy = "pinned",
)

maven_install(
    name = "debug_maven",
    artifacts = [
        "androidx.annotation:annotation:1.7.0-alpha03",
        "androidx.arch.core:core-common:2.1.0",
        "androidx.arch.core:core-runtime:2.1.0",
        "androidx.collection:collection:1.0.0",
        "androidx.core:core:1.3.2",
        "androidx.customview:customview:1.0.0",
        "androidx.lifecycle:lifecycle-common:2.2.0",
        "androidx.lifecycle:lifecycle-livedata-core-ktx:2.2.0",
        "androidx.lifecycle:lifecycle-livedata-core:2.2.0",
        "androidx.lifecycle:lifecycle-livedata-ktx:2.2.0",
        "androidx.lifecycle:lifecycle-livedata:2.2.0",
        "androidx.lifecycle:lifecycle-runtime-ktx:2.2.0",
        "androidx.lifecycle:lifecycle-runtime:2.2.0",
        "androidx.lifecycle:lifecycle-viewmodel-ktx:2.2.0",
        "androidx.lifecycle:lifecycle-viewmodel:2.2.0",
        "androidx.paging:paging-common-ktx:3.1.1",
        "androidx.paging:paging-common:3.1.1",
        "androidx.paging:paging-runtime:3.1.1",
        "androidx.recyclerview:recyclerview:1.2.0",
        "androidx.versionedparcelable:versionedparcelable:1.1.0",
        "org.jetbrains.kotlin:kotlin-stdlib-common:1.5.31",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.5.30",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.5.30",
        "org.jetbrains.kotlin:kotlin-stdlib:1.5.31",
        "org.jetbrains.kotlinx:atomicfu:0.16.3",
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.5.2",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2",
        "org.jetbrains:annotations:13.0",
    ],
    excluded_artifacts = ["androidx.test.espresso:espresso-contrib"],
    fail_on_missing_checksum = False,
    jetify = True,
    jetify_include_list = [
        "androidx.annotation:annotation",
        "androidx.arch.core:core-common",
        "androidx.arch.core:core-runtime",
        "androidx.collection:collection",
        "androidx.core:core",
        "androidx.customview:customview",
        "androidx.lifecycle:lifecycle-common",
        "androidx.lifecycle:lifecycle-livedata",
        "androidx.lifecycle:lifecycle-livedata-core",
        "androidx.lifecycle:lifecycle-livedata-core-ktx",
        "androidx.lifecycle:lifecycle-livedata-ktx",
        "androidx.lifecycle:lifecycle-runtime",
        "androidx.lifecycle:lifecycle-runtime-ktx",
        "androidx.lifecycle:lifecycle-viewmodel",
        "androidx.lifecycle:lifecycle-viewmodel-ktx",
        "androidx.paging:paging-common",
        "androidx.paging:paging-common-ktx",
        "androidx.paging:paging-runtime",
        "androidx.recyclerview:recyclerview",
        "androidx.versionedparcelable:versionedparcelable",
        "com.android.support:cardview-v7",
        "org.jetbrains.kotlin:kotlin-stdlib",
        "org.jetbrains.kotlin:kotlin-stdlib-common",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk7",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8",
        "org.jetbrains.kotlinx:atomicfu",
        "org.jetbrains.kotlinx:kotlinx-coroutines-android",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core",
        "org.jetbrains:annotations",
    ],
    override_targets = {
        "androidx.annotation:annotation": "@maven//:androidx_annotation_annotation_jvm",
        "androidx.arch.core:core-common": "@maven//:androidx_arch_core_core_common",
        "androidx.arch.core:core-runtime": "@maven//:androidx_arch_core_core_runtime",
        "androidx.collection:collection": "@maven//:androidx_collection_collection",
        "androidx.core:core": "@maven//:androidx_core_core",
        "androidx.customview:customview": "@maven//:androidx_customview_customview",
        "androidx.lifecycle:lifecycle-common": "@maven//:androidx_lifecycle_lifecycle_common",
        "androidx.lifecycle:lifecycle-livedata": "@maven//:androidx_lifecycle_lifecycle_livedata",
        "androidx.lifecycle:lifecycle-livedata-core": "@maven//:androidx_lifecycle_lifecycle_livedata_core",
        "androidx.lifecycle:lifecycle-runtime": "@maven//:androidx_lifecycle_lifecycle_runtime",
        "androidx.lifecycle:lifecycle-runtime-ktx": "@maven//:androidx_lifecycle_lifecycle_runtime_ktx",
        "androidx.lifecycle:lifecycle-viewmodel": "@maven//:androidx_lifecycle_lifecycle_viewmodel",
        "androidx.lifecycle:lifecycle-viewmodel-ktx": "@maven//:androidx_lifecycle_lifecycle_viewmodel_ktx",
        "androidx.versionedparcelable:versionedparcelable": "@maven//:androidx_versionedparcelable_versionedparcelable",
        "org.jetbrains:annotations": "@maven//:org_jetbrains_annotations",
        "org.jetbrains.kotlin:kotlin-stdlib": "@maven//:org_jetbrains_kotlin_kotlin_stdlib",
        "org.jetbrains.kotlin:kotlin-stdlib-common": "@maven//:org_jetbrains_kotlin_kotlin_stdlib_common",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk7": "@maven//:org_jetbrains_kotlin_kotlin_stdlib_jdk7",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8": "@maven//:org_jetbrains_kotlin_kotlin_stdlib_jdk8",
        "org.jetbrains.kotlinx:atomicfu": "@maven//:org_jetbrains_kotlinx_atomicfu",
        "org.jetbrains.kotlinx:kotlinx-coroutines-android": "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_android",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core": "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_core",
    },
    repositories = [
        "https://dl.google.com/dl/android/maven2/",
        "https://repo.maven.apache.org/maven2/",
    ],
    resolve_timeout = 1000,
    version_conflict_policy = "pinned",
)

maven_install(
    name = "maven",
    artifacts = DAGGER_ARTIFACTS + GRAB_BAZEL_COMMON_ARTIFACTS + [
        "androidx.activity:activity-compose:1.7.2",
        "androidx.activity:activity-ktx:1.7.2",
        "androidx.activity:activity:1.7.2",
        "androidx.annotation:annotation-experimental:1.3.0",
        "androidx.annotation:annotation-jvm:1.6.0",
        "androidx.annotation:annotation:1.7.0-alpha03",
        "androidx.appcompat:appcompat-resources:1.6.1",
        "androidx.appcompat:appcompat:1.6.1",
        "androidx.arch.core:core-common:2.2.0",
        "androidx.arch.core:core-runtime:2.1.0",
        "androidx.collection:collection:1.1.0",
        "androidx.compose.animation:animation-core:1.2.1",
        "androidx.compose.animation:animation:1.2.1",
        "androidx.compose.foundation:foundation-layout:1.4.3",
        "androidx.compose.foundation:foundation:1.4.3",
        "androidx.compose.material:material-icons-core:1.4.3",
        "androidx.compose.material:material-ripple:1.4.3",
        "androidx.compose.material:material:1.4.3",
        "androidx.compose.runtime:runtime-saveable:1.4.3",
        "androidx.compose.runtime:runtime:1.4.3",
        "androidx.compose.ui:ui-geometry:1.4.3",
        "androidx.compose.ui:ui-graphics:1.4.3",
        "androidx.compose.ui:ui-text:1.4.3",
        "androidx.compose.ui:ui-tooling-data:1.4.3",
        "androidx.compose.ui:ui-tooling-preview:1.4.3",
        "androidx.compose.ui:ui-tooling:1.4.3",
        "androidx.compose.ui:ui-unit:1.4.3",
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
        "androidx.core:core-ktx:1.2.0",
        "androidx.core:core:1.10.1",
        "androidx.cursoradapter:cursoradapter:1.0.0",
        "androidx.customview:customview:1.0.0",
        "androidx.databinding:databinding-adapters:7.2.2",
        "androidx.databinding:databinding-common:7.2.2",
        "androidx.databinding:databinding-ktx:7.2.2",
        "androidx.databinding:databinding-runtime:7.2.2",
        "androidx.databinding:viewbinding:7.2.2",
        "androidx.drawerlayout:drawerlayout:1.0.0",
        "androidx.emoji2:emoji2:1.3.0",
        "androidx.fragment:fragment:1.3.6",
        "androidx.interpolator:interpolator:1.0.0",
        "androidx.lifecycle:lifecycle-common:2.6.1",
        "androidx.lifecycle:lifecycle-livedata-core:2.6.1",
        "androidx.lifecycle:lifecycle-livedata:2.6.1",
        "androidx.lifecycle:lifecycle-process:2.6.1",
        "androidx.lifecycle:lifecycle-runtime-ktx:2.6.1",
        "androidx.lifecycle:lifecycle-runtime:2.6.1",
        "androidx.lifecycle:lifecycle-service:2.6.1",
        "androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.1",
        "androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.1",
        "androidx.lifecycle:lifecycle-viewmodel:2.6.1",
        "androidx.loader:loader:1.0.0",
        "androidx.savedstate:savedstate-ktx:1.2.1",
        "androidx.savedstate:savedstate:1.2.1",
        "androidx.startup:startup-runtime:1.1.1",
        "androidx.vectordrawable:vectordrawable-animated:1.1.0",
        "androidx.vectordrawable:vectordrawable:1.1.0",
        "androidx.versionedparcelable:versionedparcelable:1.1.1",
        "androidx.viewpager:viewpager:1.0.0",
        "com.google.dagger:dagger:2.47",
        "javax.inject:javax.inject:1",
        "org.jetbrains.kotlin:kotlin-stdlib-common:1.8.10",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.10",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.10",
        "org.jetbrains.kotlin:kotlin-stdlib:1.8.10",
        "org.jetbrains.kotlinx:atomicfu:0.17.3",
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4",
        "org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.6.4",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4",
        "org.jetbrains:annotations:13.0",
    ],
    excluded_artifacts = ["androidx.test.espresso:espresso-contrib"],
    fail_on_missing_checksum = False,
    jetify = True,
    jetify_include_list = [
        "androidx.activity:activity",
        "androidx.activity:activity-compose",
        "androidx.activity:activity-ktx",
        "androidx.annotation:annotation",
        "androidx.annotation:annotation-experimental",
        "androidx.annotation:annotation-jvm",
        "androidx.appcompat:appcompat-resources",
        "androidx.arch.core:core-common",
        "androidx.arch.core:core-runtime",
        "androidx.collection:collection",
        "androidx.compose.animation:animation",
        "androidx.compose.animation:animation-core",
        "androidx.compose.foundation:foundation",
        "androidx.compose.foundation:foundation-layout",
        "androidx.compose.material:material",
        "androidx.compose.material:material-icons-core",
        "androidx.compose.material:material-ripple",
        "androidx.compose.runtime:runtime",
        "androidx.compose.runtime:runtime-saveable",
        "androidx.compose.ui:ui",
        "androidx.compose.ui:ui-geometry",
        "androidx.compose.ui:ui-graphics",
        "androidx.compose.ui:ui-text",
        "androidx.compose.ui:ui-tooling",
        "androidx.compose.ui:ui-tooling-data",
        "androidx.compose.ui:ui-tooling-preview",
        "androidx.compose.ui:ui-unit",
        "androidx.constraintlayout:constraintlayout",
        "androidx.constraintlayout:constraintlayout-core",
        "androidx.core:core",
        "androidx.core:core-ktx",
        "androidx.cursoradapter:cursoradapter",
        "androidx.customview:customview",
        "androidx.databinding:databinding-adapters",
        "androidx.databinding:databinding-common",
        "androidx.databinding:databinding-ktx",
        "androidx.databinding:databinding-runtime",
        "androidx.databinding:viewbinding",
        "androidx.drawerlayout:drawerlayout",
        "androidx.emoji2:emoji2",
        "androidx.fragment:fragment",
        "androidx.interpolator:interpolator",
        "androidx.lifecycle:lifecycle-common",
        "androidx.lifecycle:lifecycle-livedata",
        "androidx.lifecycle:lifecycle-livedata-core",
        "androidx.lifecycle:lifecycle-process",
        "androidx.lifecycle:lifecycle-runtime",
        "androidx.lifecycle:lifecycle-runtime-ktx",
        "androidx.lifecycle:lifecycle-service",
        "androidx.lifecycle:lifecycle-viewmodel",
        "androidx.lifecycle:lifecycle-viewmodel-ktx",
        "androidx.lifecycle:lifecycle-viewmodel-savedstate",
        "androidx.loader:loader",
        "androidx.savedstate:savedstate",
        "androidx.savedstate:savedstate-ktx",
        "androidx.startup:startup-runtime",
        "androidx.vectordrawable:vectordrawable",
        "androidx.vectordrawable:vectordrawable-animated",
        "androidx.versionedparcelable:versionedparcelable",
        "androidx.viewpager:viewpager",
        "com.android.support:cardview-v7",
        "com.google.dagger:dagger",
        "javax.inject:javax.inject",
        "org.jetbrains.kotlin:kotlin-stdlib",
        "org.jetbrains.kotlin:kotlin-stdlib-common",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk7",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8",
        "org.jetbrains.kotlinx:atomicfu",
        "org.jetbrains.kotlinx:kotlinx-coroutines-android",
        "org.jetbrains.kotlinx:kotlinx-coroutines-bom",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core",
        "org.jetbrains:annotations",
    ],
    override_targets = {
        "androidx.annotation:annotation": "@maven//:androidx_annotation_annotation_jvm",
    },
    repositories = [
        "https://dl.google.com/dl/android/maven2/",
        "https://repo.maven.apache.org/maven2/",
    ] + DAGGER_REPOSITORIES,
    resolve_timeout = 1000,
    version_conflict_policy = "pinned",
)

maven_install(
    name = "test_maven",
    artifacts = [
        "junit:junit:4.13.2",
        "org.hamcrest:hamcrest-core:1.3",
    ],
    excluded_artifacts = ["androidx.test.espresso:espresso-contrib"],
    fail_on_missing_checksum = False,
    jetify = True,
    jetify_include_list = [
        "com.android.support:cardview-v7",
        "junit:junit",
        "org.hamcrest:hamcrest-core",
    ],
    override_targets = {
        "androidx.annotation:annotation": "@maven//:androidx_annotation_annotation_jvm",
    },
    repositories = [
        "https://repo.maven.apache.org/maven2/",
    ],
    resolve_timeout = 1000,
    version_conflict_policy = "pinned",
)

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
