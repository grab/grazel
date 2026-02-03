# Grazel

**Grazel** stands for `Gradle` to `Bazel`. A Gradle plugin to migrate Android projects
to [Bazel build](https://bazel.build) system in an incremental and automated fashion.

<p align="center"> 
<!-- <a href="https://github.com/grab/grazel/releases/latest"><img src="https://img.shields.io/github/release/grab/Grazel.svg?style=flat-square&label=Release&logo=github&colorB=00bdd6"/></a> -->
<img alt="Maven Central" src="https://img.shields.io/maven-central/v/com.grab.grazel/grazel-gradle-plugin?logo=apache-maven&logoColor=%23C71A36&style=flat-square&colorB=00bdd6">
<img src="https://img.shields.io/github/actions/workflow/status/grab/Grazel/ci.yml?logo=github&style=flat-square">
<a href="https://grab.github.io/grazel/"><img src="https://img.shields.io/badge/Website-%20-lightgrey.svg?color=00bdd6&colorA=00bdd6&style=flat-square&logo=github"/></a>
</p>

<p align="center">
<img src="docs/images/grazel-demo.gif" width="85%">
</p>

## Requirements

* Android SDK with `ANDROID_HOME` environment variable set
* [Bazelisk](https://github.com/bazelbuild/bazelisk#installation) - to build generated Bazel files

## Components

* [Gradle plugin](https://github.com/grab/grazel/tree/master/grazel-gradle-plugin)
* A Kotlin Starlark DSL to generate Starlark code in a type-safe way.
* [Grab Bazel Common](https://github.com/grab/grab-bazel-common) - Custom rules to bridge the gap
  between Gradle/Bazel.

## Features

* Generates `BUILD.bazel` and `WORKSPACE` from Gradle configuration
* Powered by [Grab Bazel Common](https://github.com/grab/grab-bazel-common) - custom Bazel rules for Android/Kotlin
* Gradle remains the source of truth and minimal code changes required on Gradle side.

## Usage

Generate Bazel build files from your Gradle configuration:

```bash
./gradlew migrateToBazel
```

Build with Bazel using the generated files:

```bash
# Build all targets
bazelisk build //...

# Build a specific module
bazelisk build //my-library:my-library-debug

# Run tests
bazelisk test //...
```

For documentation and usage instructions, please visit [website](https://grab.github.io/grazel/).

## How it works

Grazel reads your Gradle configuration and generates equivalent Bazel build scripts.
Running `./gradlew migrateToBazel` produces `BUILD.bazel` files for each module.

For example, for the following Gradle configuration:

```groovy
plugins {
    id("com.android.library")
    id("kotlin-android")
}

android {
    namespace = "com.example.mylibrary"

    defaultConfig {
        manifestPlaceholders = [minSdkVersion: "21"]
    }

    sourceSets {
        debug {
            res.srcDirs += "src/debug/res"
        }
    }

    lint {
        baseline = file("lint_baseline.xml")
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.appcompat:appcompat:1.6.1")
}
```

Grazel's `migrateToBazel` task generates the following build script:

```python
load("@grab_bazel_common//rules:defs.bzl", "android_library")

android_library(
    name = "my-library-debug",
    srcs = glob(["src/main/java/**/*.kt"]),
    custom_package = "com.example.mylibrary",
    manifest = "src/main/AndroidManifest.xml",
    manifest_values = {
        "minSdkVersion": "21",
    },
    resource_sets = {
        "main": {
            "res": "src/main/res",
            "manifest": "src/main/AndroidManifest.xml",
        },
        "debug": {
            "res": "src/debug/res",
        },
    },
    lint_options = {
        "enabled": True,
        "baseline": "lint_baseline.xml",
        "config": "//:lint.xml",
    },
    visibility = ["//visibility:public"],
    deps = [
        "//core:core-debug",
        "@maven//:androidx_appcompat_appcompat",
    ],
)
```

Grazel also generates `android_unit_test` and `android_instrumentation_binary` targets for testing.
Other supported features include flavors, mapping correct dependency data to Bazel, Dagger,
Databinding, and Jetpack Compose etc

See the [documentation](https://grab.github.io/grazel/) for the full list of capabilities.

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
