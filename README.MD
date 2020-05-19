# Grazel - Gradle to Bazel Migration Tool

Grazel is a `Gradle` plugin to automate generation of valid `Bazel` files for a given Android/Kotlin/Java project. 

# Requirements

* `Bazel` is installed and available in the path.
* [Buildifier](https://github.com/bazelbuild/buildtools/tree/master/buildifier) is installed and avaialble in the path. Install with `brew install buildifier`.

# How it works

It works by using already available project information from Gradle and then mapping it Bazel build rules via a Kotlin based [Starlark DSL](grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/bazel/starlark).

## Tasks

The plugin registers the following tasks to your Gradle project. Ordered by sequence.

```
// Todo : Minh update the tasks names
Bazel tasks
-----------
generateBazelBuildFiles - Generate WORKSPACE and BUILD.bazel for this project
formatBazelBuildFiles - Format generated build files
migrateToBazel - Generates Bazel build files for this project
bazelBuildAll - Do a Bazel build from all generated build files
```

## Capabilities

The plugin in current state is able to generate `WORKSPACE` and `BUILD.bazel` files for all Gradle modules in the current project. Currently it handles the following cases:

* `android_binary`/`kt_android_library` for Android application projects
* `android_library`/`kt_android_library` for Android library projects
* Dependency mapping between different Bazel targets
* `kt_jvm_library` for Kotlin library projects.
* `maven_install` with all repos and artifacts for given project.
  * Handle private repos requires `username` and `password`.
  * Jetifier


The generated files would be in valid bazel format but depending on project configuration it may not be complete due to few pending cases listed in [pending section](#pending).

# Download

Grazel packages are published as Maven packages and available at:
```groovy
repositories {
        maven {
            // TODO update/replace the GitLab URL when we publish the library to GitHub
            url "https://gitlab.myteksi.net/api/v4/projects/5062/packages/maven"
            credentials(HttpHeaderCredentials) {
                name = "Private-Token"
                value = grazelDeployToken
            }
            authentication {
                header(HttpHeaderAuthentication)
            }
        }
    }
```
Replace `grazelDeployToken` with private token.

# Usage

Add plugin to root `build.gradle`.

```groovy
buildscript {
  dependencies {
    classpath("com.grab:grazel:0.1.0")
  }
}
apply plugin: "com.grab.grazel"
```

After applying, execute `./gradlew migrateToBazel` to generate `Bazel` build files.

## Note

By default, the plugin generates for all modules, to generate for specific modules alone please specifiy the module path in a Gradle property as shown below.

```bash
./gradlew migrateToBazel -PmodulePath=:transport
```

To also include the dependency graph of the given module, use `-PincludeDependencies=true`.

# Design

The plugin is designed to be fail-safe instead of fail-fast and generates all files ignoring any unexcepted non happy paths. Manual inspection of generated build files is recommended at this stage.

## Correctness

The plugin relies on `buildifier` tool to verify syntax and fix formatting of generated files. This is done in `formatBazelBuildFiles` task.

# Pending
- [ ] Maven repos requiring custom HTTP headers.
- [ ] Use actual package name instead of what's defined in AndroidManifest.xml for `srcs`.
    
# Running locally

This repo contains a sample Android app which can be built with `Bazel` by `./gradlew bazelBuildAll`.
Install the APK to device by `bazel mobile-install //sample-android:sample-android`.