# Grazel

**Grazel** stands for `Gradle` to `Bazel`. A Gradle plugin to migrate Android projects
to [Bazel build](https://bazel.build) system in an incremental and automated fashion.

<p align="center"> 
<!-- <a href="https://github.com/grab/Grazel/releases/latest"><img src="https://img.shields.io/github/release/grab/Grazel.svg?style=flat-square&label=Release&logo=github&colorB=00bdd6"/></a> -->
<img alt="Maven Central" src="https://img.shields.io/maven-central/v/com.grab.grazel/grazel-gradle-plugin?logo=apache-maven&logoColor=%23C71A36&style=flat-square&colorB=00bdd6">
<img src="https://img.shields.io/github/actions/workflow/status/grab/Grazel/ci.yml?logo=github&style=flat-square">
<a href="https://grab.github.io/Grazel/"><img src="https://img.shields.io/badge/Website-%20-lightgrey.svg?color=00bdd6&colorA=00bdd6&style=flat-square&logo=github"/></a>
</p>

<p align="center">
<img src="docs/images/grazel-demo.gif" width="85%">
</p>

## Components

* [Gradle plugin](https://github.com/grab/Grazel/tree/master/grazel-gradle-plugin)
* A Kotlin Starlark DSL to generate Starlark code in a type-safe way.
* [Grab Bazel Common](https://github.com/grab/grab-bazel-common) - Custom rules to bridge the gap
  between Gradle/Bazel.

## Features

* Generate `BUILD.bazel`, `WORKSPACE` for given Android project and reduce the overall migration
  effort.
* Setup [hybrid build](https://grab.github.io/Grazel/hybrid_builds/) to build part of project graph
  to build with Bazel and rest with Gradle.
* Minimal source changes to codebase - supported
  by [Grab Bazel Common](https://github.com/grab/grab-bazel-common).
* Gradle Configuration as source of truth.

For documentation and usage instructions, please visit [website](https://grab.github.io/Grazel/).

## How it works

It works by automatically generating Bazel scripts for given Android project based on your Gradle
configuration. For simple projects, it should be able to migrate, fully build and launch the app
with `bazel mobile-install //<target-name>`.

For example, for the following Gradle configuration:

```groovy
apply plugin: "com.android.library"
apply plugin: "kotlin-android"

android {
    compileSdkVersion rootProject.compileSdk
    defaultConfig {
        minSdkVersion rootProject.minSdk
        targetSdkVersion rootProject.targetSdk
        versionCode 1
        versionName "1.0"
    }
}

dependencies {
    implementation project(":app")
    implementation project(":base")
    implementation "androidx.test.espresso:espresso-idling-resource:3.2.0"
}
```

Grazel's `migrateToBazel` task generates the following build script:

```python
load("@io_bazel_rules_kotlin//kotlin:kotlin.bzl", "kt_android_library")

kt_android_library(
    name = "quiz",
    srcs = glob([
        "src/main/java/**/*.kt",
    ]),
    custom_package = "com.google.samples.apps.topeka.quiz",
    manifest = "src/main/AndroidManifest.xml",
    resource_files = glob([
        "src/main/res/**",
    ]),
    visibility = [
        "//visibility:public",
    ],
    deps = [
        "//app",
        "//base",
        "@maven//:androidx_test_espresso_espresso_idling_resource",
    ],
)
```

See [migration capabilities](https://grab.github.io/Grazel/migration_capabilities) for supported
features. In advanced cases, where entire project might not
be [migratable](https://grab.github.io/Grazel/migration_criteria), it migrates part of the graph and
sets up [hybrid build](https://grab.github.io/Grazel/hybrid_builds) where part of the graph can be
built with Bazel and rest with Gradle.

## Resources

* [Grab's migration journey from Gradle to Bazel via automation](https://www.youtube.com/watch?v=VMkjZAI_sN8) - Build Meetup'21.

## License

```
Copyright 2022 Grabtaxi Holdings PTE LTD (GRAB)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```