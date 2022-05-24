# Bazel Configuration

Once Grazel's `migrateToBazel` generates Bazel scripts, `bazel` can be used to run the build.
Additional configuration files are required for optimal builds and currently Grazel does not
generate these (may change in the future).

Recommended usage is via [bazelisk](https://github.com/bazelbuild/bazelisk) which is wrapper
for `bazel` similar to `gradlew` for `gradle`.

## .bazelrc

`.bazelrc` is used to specifiy additional properties and flags to Bazel similar
to `gradle.properties`. A basic configuration is given below but it may vary depending on project
needs.

```python
# Disk cache
build --disk_cache=bazel-cache

# Env config
build --incompatible_strict_action_env
build --incompatible_disable_depset_items

# Error config
build --verbose_failures

# D8 and Dexing flags
build --define=android_incremental_dexing_tool=d8_dexbuilder
build --define=android_standalone_dexing_tool=d8_compat_dx
build --nouse_workers_with_dexbuilder
build --strategy=Desugar=sandboxed

# Databinding flags
build --experimental_android_databinding_v2
build --android_databinding_use_v3_4_args
build --android_databinding_use_androidx

# Flags to enable latest android providers in rules
build --experimental_google_legacy_api
query --experimental_google_legacy_api

build --strict_java_deps=off # Turn off strict java deps (when databinding enabled)

# Resource Workers
# build --persistent_android_resource_processor # Disabled due to resource merging error (when databinding enabled)
build --strategy=AARGenerator=standalone # https://github.com/bazelbuild/bazel/issues/9207#issuecomment-522727482

# Java Workers
build --strategy=KotlinCompile=worker
build --strategy=Javac=worker
```

## .bazelversion

```python
4.1.0
```

Used to specify the `bazel` version that will be used to build the project. Used by `bazelisk`.

## Custom Android Tools

Grazel also ships patched Android tools used in `bazel` as part
of [grab-bazel-common](https://github.com/grab/grab-bazel-common) to fix few performance and build
related issues. See [patches](https://github.com/grab/grab-bazel-common/tree/master/patches) for
more info. 
