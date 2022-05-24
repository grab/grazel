# Grazel

**Grazel** stands for `Gradle` to `Bazel`. A Gradle plugin to migrate Android projects
to [Bazel build](https://bazel.build) system in an incremental and automated fashion.

<p align="center">
<img src="images/grazel-demo.gif" width="100%">
</p>

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

Grazel's `migrateToBazel` task generates the following Bazel build script:

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

See [migration capabilities](migration_capabilities.md) for supported features. In advanced cases,
where entire project might not be [migratable](migration_criteria.md), it migrates part of the graph
and sets up [hybrid build](hybrid_builds.md) where part of the graph can be built with Bazel and
rest with Gradle.

## Features

* Generate `BUILD.bazel`, `WORKSPACE` for given Android project and reduce the overall migration
  effort.
* Setup [hybrid build](hybrid_builds.md) to build part of project graph to build with Bazel and rest
  with Gradle.
* Minimal source changes to codebase - supported
  by [Grab Bazel Common](https://github.com/grab/grab-bazel-common).
* Gradle Configuration as source of truth.

## Components

* [Gradle plugin](https://github.com/grab/Grazel/tree/master/grazel-gradle-plugin)
* A Kotlin Starlark DSL to generate Starlark code in a type-safe way.
* [Grab Bazel Common](https://github.com/grab/grab-bazel-common) - Custom rules to bridge the gap
  between Gradle/Bazel.

## Getting Started

### Requirements

* [Buildifier](https://github.com/bazelbuild/buildtools/tree/master/buildifier) is installed and
  available in the path.

=== "Mac"
    Install via [homebrew](https://brew.sh/). 
    ```bash
    brew install buildifier
    ```
=== "Linux"
    Install via `apt` and `npm`. 
    ```bash 
    sudo apt-get install nodejs npm
    npm i -g @bazel/buildifier
    ```

## Apply Grazel plugin

Grazel is available on Maven Central.

<img alt="Maven Central" src="https://img.shields.io/maven-central/v/com.grab.grazel/grazel-gradle-plugin?logo=apache-maven&logoColor=%23C71A36&style=flat-square&colorB=00bdd6">

In root `build.gradle`:

```groovy
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath "com.grab.grazel:grazel-gradle-plugin:0.2.0"
    }
}
apply plugin: "com.grab.grazel"

// Grazel configuration
grazel {
    // DSL
}
```

!!! Note 
    Grazel registers `migrateToBazel` lifecycle task that can be used to generate Bazel build
    scripts. By default, it filters out modules based on a set
    of [migration criteria](migration_criteria.md) and generates scripts only for supported modules.

To run Grazel, execute

```
./gradlew migrateToBazel
```

For more advanced configuration options, see [Configuration](grazel_extension.md).

<!-- # Demo

[Topeka](https://github.com/android/topeka) project migrated with Grazel. (Note: dynamic feature modules are not supported yet)

<video width="100%" controls>
  <source src="video/grazel-demo.mp4" type="video/mp4">
  Your browser does not support the video tag.
</video>
<br> -->

# License

```
Copyright 2021 Grabtaxi Holdings PTE LTE (GRAB)

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