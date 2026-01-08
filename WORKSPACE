workspace(name = "grazel")

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

http_archive(
    name = "io_bazel_rules_kotlin",
    sha256 = "3b772976fec7bdcda1d84b9d39b176589424c047eb2175bed09aac630e50af43",
    url = "https://github.com/bazelbuild/rules_kotlin/releases/download/v1.9.6/rules_kotlin-v1.9.6.tar.gz",
)

KOTLIN_VERSION = "1.9.25"

KOTLINC_RELEASE_SHA = "6ab72d6144e71cbbc380b770c2ad380972548c63ab6ed4c79f11c88f2967332e"

KSP_VERSION = "1.9.25-1.0.20"

KSP_COMPILER_RELEASE_SHA = "3a2d24623409ac5904c87a7e130f5b39ce9fd67ca8b44e4fe5b784a6ec102b81"

load("@io_bazel_rules_kotlin//kotlin:repositories.bzl", "kotlin_repositories", "kotlinc_version", "ksp_version")

KOTLINC_RELEASE = kotlinc_version(
    release = KOTLIN_VERSION,
    sha256 = KOTLINC_RELEASE_SHA,
)

KSP_COMPILER_RELEASE = ksp_version(
    release = KSP_VERSION,
    sha256 = KSP_COMPILER_RELEASE_SHA,
)

kotlin_repositories(
    compiler_release = KOTLINC_RELEASE,
    ksp_compiler_release = KSP_COMPILER_RELEASE,
)

register_toolchains("//:kotlin_toolchain")

load("@bazel_tools//tools/build_defs/repo:git.bzl", "git_repository")

git_repository(
    name = "grab_bazel_common",
    commit = "d17b05487ec15d1959dd8592757f4bc88741bf3e",
    remote = "https://github.com/grab/grab-bazel-common.git",
)

load("@grab_bazel_common//rules:repositories.bzl", "bazel_common_dependencies")

bazel_common_dependencies()

load("@grab_bazel_common//rules:setup.bzl", "bazel_common_setup")

bazel_common_setup(
    buildifier_version = "6.3.3",
    patched_android_tools = True,
)

load("@grab_bazel_common//rules:maven.bzl", "pin_bazel_common_dependencies")

pin_bazel_common_dependencies()

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
    sha256 = "d31e369b854322ca5098ea12c69d7175ded971435e55c18dd9dd5f29cc5249ac",
    strip_prefix = "rules_jvm_external-5.3",
    url = "https://github.com/bazelbuild/rules_jvm_external/releases/download/5.3/rules_jvm_external-5.3.tar.gz",
)

load("@rules_jvm_external//:repositories.bzl", "rules_jvm_external_deps")

rules_jvm_external_deps()

load("@rules_jvm_external//:setup.bzl", "rules_jvm_external_setup")

rules_jvm_external_setup()

load("@rules_jvm_external//:defs.bzl", "maven_install")
load("@rules_jvm_external//:specs.bzl", "maven")

maven_install(
    name = "android_test_maven",
    artifacts = [
        "androidx.annotation:annotation-experimental:1.1.0",
        "androidx.annotation:annotation:1.2.0",
        "androidx.test:annotation:1.0.1",
        "androidx.test:monitor:1.6.1",
        "androidx.tracing:tracing:1.0.0",
    ],
    excluded_artifacts = ["androidx.test.espresso:espresso-contrib"],
    fail_if_repin_required = False,
    fail_on_missing_checksum = False,
    jetify = True,
    jetify_include_list = [
        "com.android.support:cardview-v7",
        "com.android.support:support-annotations",
        "com.android.support:support-compat",
        "com.android.support:support-core-ui",
        "com.android.support:support-core-utils",
    ],
    maven_install_json = "//:android_test_maven_install.json",
    override_targets = {
        "androidx.annotation:annotation": "@maven//:androidx_annotation_annotation_jvm",
        "androidx.annotation:annotation-experimental": "@maven//:androidx_annotation_annotation_experimental",
        "androidx.test:annotation": "@maven//:androidx_test_annotation",
        "androidx.tracing:tracing": "@maven//:androidx_tracing_tracing",
    },
    repositories = [
        "https://dl.google.com/dl/android/maven2/",
    ],
    resolve_timeout = 1000,
    version_conflict_policy = "pinned",
)

load("@android_test_maven//:defs.bzl", android_test_maven_pinned_maven_install = "pinned_maven_install")

android_test_maven_pinned_maven_install()

maven_install(
    name = "debug_maven",
    artifacts = [
        "androidx.annotation:annotation:1.1.0",
        "androidx.arch.core:core-common:2.1.0",
        "androidx.arch.core:core-runtime:2.1.0",
        "androidx.collection:collection:1.0.0",
        "androidx.core:core-ktx:1.2.0",
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
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.5.2",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.5.2",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2",
        "org.jetbrains:annotations:13.0",
    ],
    excluded_artifacts = ["androidx.test.espresso:espresso-contrib"],
    fail_if_repin_required = False,
    fail_on_missing_checksum = False,
    jetify = True,
    jetify_include_list = [
        "android.arch.core:common",
        "android.arch.core:runtime",
        "android.arch.lifecycle:common",
        "android.arch.lifecycle:livedata",
        "android.arch.lifecycle:livedata-core",
        "android.arch.lifecycle:runtime",
        "android.arch.lifecycle:viewmodel",
        "android.arch.paging:common",
        "com.android.support:cardview-v7",
        "com.android.support:collections",
        "com.android.support:customview",
        "com.android.support:recyclerview-v7",
        "com.android.support:support-annotations",
        "com.android.support:support-compat",
        "com.android.support:support-core-ui",
        "com.android.support:support-core-utils",
        "com.android.support:versionedparcelable",
    ],
    maven_install_json = "//:debug_maven_install.json",
    override_targets = {
        "androidx.annotation:annotation": "@maven//:androidx_annotation_annotation_jvm",
        "androidx.arch.core:core-common": "@maven//:androidx_arch_core_core_common",
        "androidx.arch.core:core-runtime": "@maven//:androidx_arch_core_core_runtime",
        "androidx.collection:collection": "@maven//:androidx_collection_collection",
        "androidx.core:core": "@maven//:androidx_core_core",
        "androidx.core:core-ktx": "@maven//:androidx_core_core_ktx",
        "androidx.customview:customview": "@maven//:androidx_customview_customview",
        "androidx.lifecycle:lifecycle-common": "@maven//:androidx_lifecycle_lifecycle_common",
        "androidx.lifecycle:lifecycle-livedata": "@maven//:androidx_lifecycle_lifecycle_livedata",
        "androidx.lifecycle:lifecycle-livedata-core": "@maven//:androidx_lifecycle_lifecycle_livedata_core",
        "androidx.lifecycle:lifecycle-livedata-core-ktx": "@maven//:androidx_lifecycle_lifecycle_livedata_core_ktx",
        "androidx.lifecycle:lifecycle-runtime": "@maven//:androidx_lifecycle_lifecycle_runtime",
        "androidx.lifecycle:lifecycle-runtime-ktx": "@maven//:androidx_lifecycle_lifecycle_runtime_ktx",
        "androidx.lifecycle:lifecycle-viewmodel": "@maven//:androidx_lifecycle_lifecycle_viewmodel",
        "androidx.lifecycle:lifecycle-viewmodel-ktx": "@maven//:androidx_lifecycle_lifecycle_viewmodel_ktx",
        "androidx.versionedparcelable:versionedparcelable": "@maven//:androidx_versionedparcelable_versionedparcelable",
        "org.jetbrains.kotlin:kotlin-stdlib": "@maven//:org_jetbrains_kotlin_kotlin_stdlib",
        "org.jetbrains.kotlin:kotlin-stdlib-common": "@maven//:org_jetbrains_kotlin_kotlin_stdlib_common",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk7": "@maven//:org_jetbrains_kotlin_kotlin_stdlib_jdk7",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8": "@maven//:org_jetbrains_kotlin_kotlin_stdlib_jdk8",
        "org.jetbrains.kotlinx:kotlinx-coroutines-android": "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_android",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core": "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_core",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm": "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_core_jvm",
        "org.jetbrains:annotations": "@maven//:org_jetbrains_annotations",
    },
    repositories = [
        "https://dl.google.com/dl/android/maven2/",
        "https://repo.maven.apache.org/maven2/",
    ],
    resolve_timeout = 1000,
    version_conflict_policy = "pinned",
)

load("@debug_maven//:defs.bzl", debug_maven_pinned_maven_install = "pinned_maven_install")

debug_maven_pinned_maven_install()

maven_install(
    name = "ksp_maven",
    artifacts = [
        "androidx.room:room-compiler:2.6.1",
    ],
    excluded_artifacts = ["androidx.test.espresso:espresso-contrib"],
    fail_if_repin_required = False,
    fail_on_missing_checksum = False,
    maven_install_json = "//:ksp_maven_install.json",
    repositories = [
        "https://dl.google.com/dl/android/maven2/",
        "https://repo.maven.apache.org/maven2/",
    ],
    resolve_timeout = 1000,
    version_conflict_policy = "pinned",
)

load("@ksp_maven//:defs.bzl", ksp_maven_pinned_maven_install = "pinned_maven_install")

ksp_maven_pinned_maven_install()

maven_install(
    name = "lint_maven",
    artifacts = [
        "com.google.auto.service:auto-service-annotations:1.1.1",
        "com.slack.lint:slack-lint-checks:0.2.3",
    ],
    excluded_artifacts = ["androidx.test.espresso:espresso-contrib"],
    fail_if_repin_required = False,
    fail_on_missing_checksum = False,
    jetify = True,
    jetify_include_list = [
        "com.android.support:cardview-v7",
        "com.android.support:support-annotations",
        "com.android.support:support-compat",
        "com.android.support:support-core-ui",
        "com.android.support:support-core-utils",
    ],
    maven_install_json = "//:lint_maven_install.json",
    override_targets = {
        "com.google.auto.service:auto-service-annotations": "@maven//:com_google_auto_service_auto_service_annotations",
    },
    repositories = [
        "https://repo.maven.apache.org/maven2/",
    ],
    resolve_timeout = 1000,
    version_conflict_policy = "pinned",
)

load("@lint_maven//:defs.bzl", lint_maven_pinned_maven_install = "pinned_maven_install")

lint_maven_pinned_maven_install()

maven_install(
    name = "maven",
    artifacts = DAGGER_ARTIFACTS + GRAB_BAZEL_COMMON_ARTIFACTS + [
        "androidx.activity:activity-compose:1.7.2",
        "androidx.activity:activity-ktx:1.7.2",
        "androidx.activity:activity:1.7.2",
        "androidx.annotation:annotation-experimental:1.4.1",
        "androidx.annotation:annotation-jvm:1.8.1",
        "androidx.annotation:annotation:1.8.1",
        "androidx.appcompat:appcompat-resources:1.6.1",
        "androidx.appcompat:appcompat:1.6.1",
        "androidx.arch.core:core-common:2.2.0",
        "androidx.arch.core:core-runtime:2.2.0",
        "androidx.autofill:autofill:1.0.0",
        "androidx.collection:collection-jvm:1.4.4",
        "androidx.collection:collection-ktx:1.4.4",
        "androidx.collection:collection:1.4.4",
        "androidx.compose.animation:animation-android:1.7.8",
        "androidx.compose.animation:animation-core-android:1.7.8",
        "androidx.compose.animation:animation-core:1.7.8",
        "androidx.compose.animation:animation:1.7.8",
        "androidx.compose.foundation:foundation-android:1.7.8",
        "androidx.compose.foundation:foundation-layout-android:1.7.8",
        "androidx.compose.foundation:foundation-layout:1.7.8",
        "androidx.compose.foundation:foundation:1.7.8",
        "androidx.compose.material:material-android:1.7.8",
        "androidx.compose.material:material-ripple-android:1.7.8",
        "androidx.compose.material:material-ripple:1.7.8",
        "androidx.compose.material:material:1.7.8",
        "androidx.compose.runtime:runtime-android:1.7.8",
        "androidx.compose.runtime:runtime-saveable-android:1.7.8",
        "androidx.compose.runtime:runtime-saveable:1.7.8",
        "androidx.compose.runtime:runtime:1.7.8",
        "androidx.compose.ui:ui-android:1.7.8",
        "androidx.compose.ui:ui-geometry-android:1.7.8",
        "androidx.compose.ui:ui-geometry:1.7.8",
        "androidx.compose.ui:ui-graphics-android:1.7.8",
        "androidx.compose.ui:ui-graphics:1.7.8",
        "androidx.compose.ui:ui-test-junit4:1.4.3",
        "androidx.compose.ui:ui-test-manifest:1.4.3",
        "androidx.compose.ui:ui-test:1.4.3",
        "androidx.compose.ui:ui-text-android:1.7.8",
        "androidx.compose.ui:ui-text:1.7.8",
        "androidx.compose.ui:ui-tooling-android:1.7.8",
        "androidx.compose.ui:ui-tooling-data-android:1.7.8",
        "androidx.compose.ui:ui-tooling-data:1.7.8",
        "androidx.compose.ui:ui-tooling-preview-android:1.7.8",
        "androidx.compose.ui:ui-tooling-preview:1.7.8",
        "androidx.compose.ui:ui-tooling:1.7.8",
        "androidx.compose.ui:ui-unit-android:1.7.8",
        "androidx.compose.ui:ui-unit:1.7.8",
        "androidx.compose.ui:ui-util-android:1.7.8",
        "androidx.compose.ui:ui-util:1.7.8",
        "androidx.compose.ui:ui:1.7.8",
        "androidx.concurrent:concurrent-futures:1.1.0",
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
        "androidx.core:core-ktx:1.13.1",
        "androidx.core:core:1.13.1",
        "androidx.cursoradapter:cursoradapter:1.0.0",
        "androidx.customview:customview-poolingcontainer:1.0.0",
        "androidx.customview:customview:1.0.0",
        "androidx.databinding:databinding-adapters:8.6.1",
        "androidx.databinding:databinding-common:8.6.1",
        "androidx.databinding:databinding-ktx:8.6.1",
        "androidx.databinding:databinding-runtime:8.6.1",
        "androidx.databinding:viewbinding:8.6.1",
        "androidx.drawerlayout:drawerlayout:1.0.0",
        "androidx.emoji2:emoji2-views-helper:1.3.0",
        "androidx.emoji2:emoji2:1.3.0",
        "androidx.fragment:fragment:1.3.6",
        "androidx.graphics:graphics-path:1.0.1",
        "androidx.interpolator:interpolator:1.0.0",
        "androidx.lifecycle:lifecycle-common-jvm:2.8.3",
        "androidx.lifecycle:lifecycle-common:2.8.3",
        "androidx.lifecycle:lifecycle-livedata-core-ktx:2.8.3",
        "androidx.lifecycle:lifecycle-livedata-core:2.8.3",
        "androidx.lifecycle:lifecycle-livedata:2.8.3",
        "androidx.lifecycle:lifecycle-process:2.8.3",
        "androidx.lifecycle:lifecycle-runtime-android:2.8.3",
        "androidx.lifecycle:lifecycle-runtime-compose-android:2.8.3",
        "androidx.lifecycle:lifecycle-runtime-compose:2.8.3",
        "androidx.lifecycle:lifecycle-runtime-ktx-android:2.8.3",
        "androidx.lifecycle:lifecycle-runtime-ktx:2.8.3",
        "androidx.lifecycle:lifecycle-runtime:2.8.3",
        "androidx.lifecycle:lifecycle-service:2.8.3",
        "androidx.lifecycle:lifecycle-viewmodel-android:2.8.3",
        "androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3",
        "androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.3",
        "androidx.lifecycle:lifecycle-viewmodel:2.8.3",
        "androidx.loader:loader:1.0.0",
        "androidx.profileinstaller:profileinstaller:1.3.1",
        "androidx.resourceinspection:resourceinspection-annotation:1.0.1",
        "androidx.room:room-common:2.6.1",
        "androidx.room:room-runtime:2.6.1",
        "androidx.savedstate:savedstate-ktx:1.2.1",
        "androidx.savedstate:savedstate:1.2.1",
        "androidx.sqlite:sqlite-framework:2.4.0",
        "androidx.sqlite:sqlite:2.4.0",
        "androidx.startup:startup-runtime:1.1.1",
        "androidx.test.espresso:espresso-core:3.5.1",
        "androidx.test.espresso:espresso-idling-resource:3.5.1",
        "androidx.test.ext:junit:1.1.5",
        "androidx.test.services:storage:1.4.2",
        "androidx.test:annotation:1.0.1",
        "androidx.test:core:1.5.0",
        "androidx.test:monitor:1.6.1",
        "androidx.test:rules:1.5.0",
        "androidx.test:runner:1.5.2",
        "androidx.tracing:tracing:1.0.0",
        "androidx.vectordrawable:vectordrawable-animated:1.1.0",
        "androidx.vectordrawable:vectordrawable:1.1.0",
        "androidx.versionedparcelable:versionedparcelable:1.1.1",
        "androidx.viewpager:viewpager:1.0.0",
        "com.android.tools.build:manifest-merger:31.5.0-alpha02",
        "com.android.tools.external.com-intellij:intellij-core:31.5.0-alpha02",
        "com.android.tools.external.com-intellij:kotlin-compiler:31.5.0-alpha02",
        "com.android.tools.external.org-jetbrains:uast:31.5.0-alpha02",
        "com.android.tools.layoutlib:layoutlib-api:31.5.0-alpha02",
        "com.android.tools.lint:lint-api:31.5.0-alpha02",
        "com.android.tools.lint:lint-checks:31.5.0-alpha02",
        "com.android.tools.lint:lint-model:31.5.0-alpha02",
        "com.android.tools:annotations:31.5.0-alpha02",
        "com.android.tools:common:31.5.0-alpha02",
        "com.android.tools:repository:31.5.0-alpha02",
        "com.android.tools:sdk-common:31.5.0-alpha02",
        "com.android.tools:sdklib:31.5.0-alpha02",
        "com.google.ar.sceneform.ux:sceneform-ux:1.15.0",
        "com.google.ar.sceneform:core:1.15.0",
        "com.google.ar.sceneform:filament-android:1.15.0",
        "com.google.ar.sceneform:rendering:1.15.0",
        "com.google.ar.sceneform:sceneform-base:1.15.0",
        "com.google.ar:core:1.15.0",
        "com.google.auto.service:auto-service-annotations:1.1.1",
        "com.google.auto.service:auto-service:1.1.1",
        "com.google.auto:auto-common:1.2.1",
        "com.google.code.findbugs:jsr305:3.0.2",
        "com.google.dagger:dagger:2.47",
        "com.google.errorprone:error_prone_annotations:2.18.0",
        "com.google.guava:failureaccess:1.0.1",
        "com.google.guava:guava:32.0.1-jre",
        "com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava",
        "com.google.j2objc:j2objc-annotations:2.8",
        "com.jakewharton.timber:timber:5.0.1",
        "com.squareup:javawriter:2.1.1",
        "commons-io:commons-io:2.13.0",
        "javax.inject:javax.inject:1",
        "junit:junit:4.13.2",
        "net.sf.kxml:kxml2:2.3.0",
        "org.checkerframework:checker-compat-qual:2.5.5",
        "org.checkerframework:checker-qual:3.33.0",
        "org.hamcrest:hamcrest-core:1.3",
        "org.hamcrest:hamcrest-integration:1.3",
        "org.hamcrest:hamcrest-library:1.3",
        "org.jetbrains.kotlin:kotlin-reflect:1.9.20",
        "org.jetbrains.kotlin:kotlin-stdlib-common:1.9.25",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.20",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.20",
        "org.jetbrains.kotlin:kotlin-stdlib:1.9.25",
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3",
        "org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.7.3",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.7.3",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3",
        "org.jetbrains.kotlinx:kotlinx-coroutines-test-jvm:1.6.4",
        "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.6.4",
        "org.jetbrains:annotations:23.0.0",
        "org.ow2.asm:asm-tree:9.6",
        "org.ow2.asm:asm:9.6",
    ],
    excluded_artifacts = ["androidx.test.espresso:espresso-contrib"],
    fail_if_repin_required = False,
    fail_on_missing_checksum = False,
    jetify = True,
    jetify_include_list = [
        "android.arch.core:common",
        "android.arch.core:runtime",
        "android.arch.lifecycle:common",
        "android.arch.lifecycle:livedata",
        "android.arch.lifecycle:livedata-core",
        "android.arch.lifecycle:runtime",
        "android.arch.lifecycle:viewmodel",
        "android.arch.persistence.room:common",
        "android.arch.persistence.room:runtime",
        "android.arch.persistence:db",
        "android.arch.persistence:db-framework",
        "com.android.databinding:baseLibrary",
        "com.android.databinding:library",
        "com.android.support.test.espresso:espresso-core",
        "com.android.support.test.espresso:espresso-idling-resource",
        "com.android.support.test:monitor",
        "com.android.support.test:runner",
        "com.android.support:animated-vector-drawable",
        "com.android.support:appcompat-v7",
        "com.android.support:cardview-v7",
        "com.android.support:collections",
        "com.android.support:cursoradapter",
        "com.android.support:customview",
        "com.android.support:drawerlayout",
        "com.android.support:interpolator",
        "com.android.support:loader",
        "com.android.support:support-annotations",
        "com.android.support:support-compat",
        "com.android.support:support-core-ui",
        "com.android.support:support-core-utils",
        "com.android.support:support-fragment",
        "com.android.support:support-vector-drawable",
        "com.android.support:versionedparcelable",
        "com.android.support:viewpager",
        "com.google.ar.sceneform.ux:sceneform-ux",
    ],
    maven_install_json = "//:maven_install.json",
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

load("@maven//:defs.bzl", maven_pinned_maven_install = "pinned_maven_install")

maven_pinned_maven_install()

android_sdk_repository(
    name = "androidsdk",
    api_level = 34,
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
