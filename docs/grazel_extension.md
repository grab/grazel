# Grazel Extension

Grazel aims to infer most of the project data from Gradle and ships with sensible defaults for migration. However it is also configurable via Gradle extensions based on project needs.

## Configuring Grazel Extension

Use the registered `grazel` extension block in root `build.gradle` to configure Grazel

```groovy
grazel {
    // Configuration
}
```

## Android Extension

Configures options for Android targets

```groovy
grazel {
    android {
        dexShards = 10 // Controls android_binary's dex_shards property https://docs.bazel.build/versions/main/be/android.html#android_binary.dex_shards 
        multiDexEnabled = true // default `true`
        variantFilter { variant -> 
            variant.setIgnore(variant.name != "debug" && variant.name != "flavor1Debug")
        }
        features {
            dataBinding = true
        }
        ndkApiLevel = 30 // [Optional] Set android_ndk_repository rule api_level value
    }
    ...
}
```

### Variant Filter

Currently Grazel supports migrating only one variant. `variantFilter {}` can be used to specify the variants that should be excluded. In case the variant filter is not supplied or filter allows more than one variant, Grazel will still generate a single Bazel target by merging all the source sets in which case build might fail with duplicate classes error. 

### Databinding

By default, all modules that use databinding are excluded from migration since Bazel's Android databinding support is an ongoing [effort](https://github.com/bazelbuild/bazel/issues/2694), especially for Kotlin. Setting `dataBinding` to `true` will migrate the project using Grab's [custom macro](https://github.com/grab/grab-bazel-common/tree/master/tools/databinding). See [databinding](databinding.md) for more info.

## Dependencies

Grazel uses Gradle's [dependencies](migration_capabilities.md#dependencies) resolution data to generate Bazel dependencies information. This block can be used to control how dependencies are read or override Gradle information in generated code.

```groovy
grazel {
    dependencies {
        ///...
    }
}
```

### Override versions

Grazel will use the provided artifact version instead of using Gradle data.

```groovy
grazel {
    dependencies {
        ignoreArtifacts.add("com.unsupported.dependency")
        overrideArtifactVersions.add("androidx.preference:preference:1.1.0")
    }
}
```

!!! example
    Here even though Gradle uses `androidx.preference:preference:1.1.1`, due to `overrideArtifactVersions` the generated `maven_install` rule will contain version `1.1.0`.

### Ignore artifacts

Bazel's `rules_jvm_external` does not support all of Gradle's supported repositories such as AWS or private Maven repositories with auth headers. `ignoreArtifacts` can be used to exclude certain dependencies from migration. Alternately for dependencies that can not be fetched from maven, override targets can be 
used to point to a local target. See [override_targets](#override-targets) for more details.

!!! warning
    Any module that uses any of the ignored artifacts will be excluded from migration to not fail the build during dependency resolution by `maven_install` rule.

## Rules

Rules block can be used to configure various rules that are used by Grazel in generated scripts.

```groovy
grazel {
    rules {
        // Rules configuration.
    }
}
```

### Bazel Common

Grazel uses [Grab Bazel Common](https://github.com/grab/grab-bazel-common) to implement Gradle features in Bazel that are not readily available in Bazel. For example, `build config fields` or `res values`. 

```groovy
grazel {
    rules {
        bazelCommon {
            gitRepository {
                commit = "f74ef90479383a38cef1af33d28a3253031e00c1" // Commit hash
                remote = "https://github.com/grab/grab-bazel-common.git"
            }
        }
    }
}
```

`gitRepository` or `httpRepository` can be used to configure the `WORKPSACE` repository target that will be generated. 

!!! example
    For example, the above configuration will generate the following.

    ```python
    load("@bazel_tools//tools/build_defs/repo:git.bzl", "git_repository")

    git_repository(
        name = "grab_bazel_common",
        commit = "f74ef90479383a38cef1af33d28a3253031e00c1",
        remote = "https://github.com/grab/grab-bazel-common.git",
    )
    ```

### Kotlin

Configure options for [rules_kotlin](https://github.com/bazelbuild/rules_kotlin).


```kotlin
rules {
    kotlin {
        // WORKSPACE 
        gitRepository {
            commit = "eae21653baad4b403fee9e8a706c9d4fbd0c27c6"
            remote = "https://github.com/bazelbuild/rules_kotlin.git"
        }
        // WORKSPACE
        compiler {
            version = "1.4.20"
            sha = "46720991a716e90bfc0cf3f2c81b2bd735c14f4ea6a5064c488e04fd76e6b6c7"
        }
        // https://bazelbuild.github.io/rules_kotlin/kotlin#kt_kotlinc_options
        kotlinC {
            useIr = false
        }
        // https://bazelbuild.github.io/rules_kotlin/kotlin#define_kt_toolchain   
        toolchain {
            enabled = true
            apiVersion = "1.4"
            reportUnusedDeps = "off"
            strictKotlinDeps = "off"
            abiJars = true
            multiplexWorkers = true
            languageVersion = "1.4"
            jvmTarget = "1.8"
        }
    }    
}
```

### Maven Install

Grazel uses official [rules_jvm_external](https://github.com/bazelbuild/rules_jvm_external) to resolve maven dependencies. Maven install extension is used to configure options for `maven_install` rule.

```groovy
grazel {
    rules {
        mavenInstall {
            httpArchiveRepository {
                sha256 = "f36441aa876c4f6427bfb2d1f2d723b48e9d930b62662bf723ddfb8fc80f0140"
                stripPrefix = "rules_jvm_external-4.1"
                url = "https://github.com/bazelbuild/rules_jvm_external/archive/4.1.zip"
            }
            resolveTimeout = 1000 // https://github.com/bazelbuild/rules_jvm_external#fetch-and-resolve-timeout
            excludeArtifacts.add("androidx.test.espresso:espresso-contrib")
            jetifyIncludeList.add("com.android.support:cardview-v7")
            jetifyExcludeList.add("androidx.appcompat:appcompat")
            artifactPinning {
                enabled.set(true)
            }
            overrideTargetLabels.putAll(
                    ["androidx.appcompat:appcompat": "@//third_party:androidx_appcompat_appcompat"]
            )
        }
    }
}
```

#### Exclude artifacts

Control globally excluded artifacts as specified [here](https://github.com/bazelbuild/rules_jvm_external#artifact-exclusion). This can be used to filter out unsupported dependencies or dependencies that have issues resolving with `mavenInstall`. This does not affect a module's [migration criteria](migration_criteria.md).

### Override targets

Override targets can be used to point to a local target instead of one present in Maven. [Reference](https://github.com/bazelbuild/rules_jvm_external#overriding-generated-targets).

```groovy
grazel {
    rules {
        mavenInstall {
            overrideTargetLabels.putAll(
                    ["androidx.appcompat:appcompat": "@//third_party:androidx_appcompat_appcompat"]
            )
        }
    }
}
```

### Artifact pinning

Grazel by default enabled `rules_jvm_external`s artifact [pinning](https://github.com/bazelbuild/rules_jvm_external#pinning-artifacts-and-integration-with-bazels-downloader). 
It automatically managed pinning/repinning and is integrated with `migrateToBazel` command. If any changes are present in
Gradle, running `migrateToBazel` would automatically update the `maven_install.json` file. 

#### Jetifier

Jetifier is automatically detected by looking for presence of `android.enableJetifier` in `gradle.properties`.

Configure options for `Jetifier`. 

* `jetifyIncludeList` - Configure artifacts that should be included for Jetification.
* `jetifyExcludeList` - Configure artifacts that should be excluded from Jetification.

With these options, Grazel generates `jetify_include_list` as specified [here](https://github.com/bazelbuild/rules_jvm_external#jetifier) with the formula `jetify_include_list = (allArtifacts + jetifyIncludeList) - jetifyExcludeList`
