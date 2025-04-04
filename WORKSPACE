workspace(name = "grazel")

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

http_archive(
    name = "io_bazel_rules_kotlin",
    sha256 = "34e8c0351764b71d78f76c8746e98063979ce08dcf1a91666f3f3bc2949a533d",
    url = "https://github.com/bazelbuild/rules_kotlin/releases/download/v1.9.5/rules_kotlin-v1.9.5.tar.gz",
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
    commit = "f0b7887ccb4f9c04b24272983b4ec1e5b0fc9509",
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
    fail_if_repin_required = False,
    fail_on_missing_checksum = False,
    jetify = True,
    jetify_include_list = [
        "android.arch.lifecycle:common",
        "com.android.support.test.espresso:espresso-idling-resource",
        "com.android.support.test:monitor",
        "com.android.support.test:runner",
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
        "androidx.concurrent:concurrent-futures": "@maven//:androidx_concurrent_concurrent_futures",
        "androidx.lifecycle:lifecycle-common": "@maven//:androidx_lifecycle_lifecycle_common",
        "androidx.tracing:tracing": "@maven//:androidx_tracing_tracing",
        "com.google.code.findbugs:jsr305": "@maven//:com_google_code_findbugs_jsr305",
        "com.google.guava:listenablefuture": "@maven//:com_google_guava_listenablefuture",
        "javax.inject:javax.inject": "@maven//:javax_inject_javax_inject",
        "org.jetbrains.kotlin:kotlin-stdlib": "@maven//:org_jetbrains_kotlin_kotlin_stdlib",
        "org.jetbrains.kotlin:kotlin-stdlib-common": "@maven//:org_jetbrains_kotlin_kotlin_stdlib_common",
        "org.jetbrains:annotations": "@maven//:org_jetbrains_annotations",
    },
    repositories = [
        "https://dl.google.com/dl/android/maven2/",
        "https://repo.maven.apache.org/maven2/",
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
        "androidx.annotation:annotation-experimental:1.3.0",
        "androidx.annotation:annotation-jvm:1.6.0",
        "androidx.annotation:annotation:1.6.0",
        "androidx.appcompat:appcompat-resources:1.6.1",
        "androidx.appcompat:appcompat:1.6.1",
        "androidx.arch.core:core-common:2.2.0",
        "androidx.arch.core:core-runtime:2.2.0",
        "androidx.autofill:autofill:1.0.0",
        "androidx.collection:collection:1.1.0",
        "androidx.compose.animation:animation-core:1.4.3",
        "androidx.compose.animation:animation:1.4.3",
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
        "androidx.compose.ui:ui-util:1.4.3",
        "androidx.compose.ui:ui:1.4.3",
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
        "androidx.core:core-ktx:1.10.1",
        "androidx.core:core:1.10.1",
        "androidx.cursoradapter:cursoradapter:1.0.0",
        "androidx.customview:customview-poolingcontainer:1.0.0",
        "androidx.customview:customview:1.0.0",
        "androidx.databinding:databinding-adapters:8.1.4",
        "androidx.databinding:databinding-common:8.1.4",
        "androidx.databinding:databinding-ktx:8.1.4",
        "androidx.databinding:databinding-runtime:8.1.4",
        "androidx.databinding:viewbinding:8.1.4",
        "androidx.drawerlayout:drawerlayout:1.0.0",
        "androidx.emoji2:emoji2-views-helper:1.3.0",
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
        "androidx.profileinstaller:profileinstaller:1.3.0",
        "androidx.resourceinspection:resourceinspection-annotation:1.0.1",
        "androidx.savedstate:savedstate-ktx:1.2.1",
        "androidx.savedstate:savedstate:1.2.1",
        "androidx.startup:startup-runtime:1.1.1",
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
        "commons-io:commons-io:2.13.0",
        "javax.inject:javax.inject:1",
        "net.sf.kxml:kxml2:2.3.0",
        "org.checkerframework:checker-qual:3.33.0",
        "org.jetbrains.kotlin:kotlin-reflect:1.9.20",
        "org.jetbrains.kotlin:kotlin-stdlib-common:1.8.20-RC2",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.20",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.20",
        "org.jetbrains.kotlin:kotlin-stdlib:1.9.20",
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4",
        "org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.6.4",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.6.4",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4",
        "org.jetbrains:annotations:20.1.0",
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
        "com.android.databinding:baseLibrary",
        "com.android.databinding:library",
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

maven_install(
    name = "test_maven",
    artifacts = [
        "junit:junit:4.13.2",
        "org.hamcrest:hamcrest-core:1.3",
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
    maven_install_json = "//:test_maven_install.json",
    repositories = [
        "https://repo.maven.apache.org/maven2/",
    ],
    resolve_timeout = 1000,
    version_conflict_policy = "pinned",
)

load("@test_maven//:defs.bzl", test_maven_pinned_maven_install = "pinned_maven_install")

test_maven_pinned_maven_install()

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
