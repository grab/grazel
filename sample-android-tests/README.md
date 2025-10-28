# Sample Android Tests Module

This module demonstrates the `com.android.test` plugin with Grazel's AndroidTest implementation.

## Purpose

This test module validates that Grazel correctly handles:

1. **`com.android.test` plugin** - Separate test module structure (AGP 7.0+)
2. **Associates field** - Test access to app internals
3. **Android resources** - Custom resources in test module (resourceFiles)
4. **Resource strip prefix** - Correct path handling for resources
5. **Compose support** - Jetpack Compose UI testing
6. **Manifest values** - Complete manifest value extraction
7. **Variant matching** - Test module variants matched with app variants

## Structure

```
sample-android-tests/
├── build.gradle                    # Uses com.android.test plugin
├── src/
│   └── main/
│       ├── AndroidManifest.xml
│       ├── java/com/grab/grazel/android/sample/tests/
│       │   ├── BasicInstrumentationTest.kt     # Basic test
│       │   ├── ComposeUiTest.kt                 # Compose UI test
│       │   └── ResourcesTest.kt                 # Resources test
│       └── res/
│           └── values/
│               ├── strings.xml                  # Custom test strings
│               └── colors.xml                   # Custom test colors
```

## Key Configuration

### build.gradle
```groovy
plugins {
    id 'com.android.test'
    id 'org.jetbrains.kotlin.android'
}

android {
    targetProjectPath = ':sample-android'  // Points to app under test

    buildFeatures {
        compose true  // Enables Compose for UI tests
    }
}
```

## Expected Bazel Output

After running `./gradlew migrateToBazel`, Grazel should generate:

```python
android_instrumentation_binary(
    name = "sample-android-tests-demoFreeDebug",
    srcs = glob([
        "src/main/java/**/*.kt",
    ]),
    deps = [
        # Test dependencies
        "@maven//:androidx_test_ext_junit",
        "@maven//:androidx_compose_ui_ui_test_junit4",
        # ... other deps
    ],
    associates = [
        "//sample-android:lib_sample-android-demoFreeDebug"  # App library
    ],
    instruments = "//sample-android:sample-android-demoFreeDebug",  # App binary
    custom_package = "com.grab.grazel.android.sample.tests",
    target_package = "com.grab.grazel.android.sample",
    resources = glob([
        # Java/Kotlin test resources (if any)
    ]),
    resource_files = glob([
        "src/main/res/**"  # Android resource files
    ]),
    resource_strip_prefix = "sample-android-tests/src/main/res",
    test_instrumentation_runner = "androidx.test.runner.AndroidJUnitRunner",
    enable_compose = True,  # Compose support
    manifest_values = {
        # Manifest values from ManifestValuesBuilder
    },
)
```

## What This Tests

### 1. BasicInstrumentationTest
- Validates test infrastructure
- Checks app and test contexts
- Verifies instrumentation runner

### 2. ComposeUiTest
- Tests Compose UI components
- Validates `enable_compose = True` flag
- Requires Compose dependencies

### 3. ResourcesTest
- Accesses custom Android resources
- Validates `resource_files` field
- Tests `resourceStripPrefix` handling

## Running Tests

### With Gradle
```bash
./gradlew :sample-android-tests:connectedDebugAndroidTest
```

### With Bazel (after migration)
```bash
bazel test //sample-android-tests:sample-android-tests-demoFreeDebug
```

## Validation Checklist

After migration, verify in `sample-android-tests/BUILD.bazel`:

- [x] `associates` field is populated with app library target
- [x] `resource_files` includes res/ directory
- [x] `resource_strip_prefix` is set correctly
- [x] `enable_compose = True` is present
- [x] `manifest_values` contains values from ManifestValuesBuilder
- [x] `instruments` points to app binary target
- [x] Test sources are in `srcs`
- [x] Dependencies are in `deps` (not associates)

## Feature Comparison

This module demonstrates feature parity with AndroidInstrumentationBinary:

| Feature | AndroidInstrumentationBinary | AndroidTest | Status |
|---------|------------------------------|-------------|--------|
| associates | ✅ | ✅ | Equal |
| resourceFiles | ✅ | ✅ | Equal |
| resourceStripPrefix | ✅ | ✅ | Equal |
| compose | ✅ | ✅ | Equal |
| manifestValues | ✅ | ✅ | Equal |
| resources | ✅ | ✅ | Equal |

## Notes

- This module uses AGP 8.1.4's `com.android.test` plugin
- Test code lives in `src/main/` (not `src/androidTest/`)
- Variants must match between test module and app module
- The `targetProjectPath` is required and must point to the app module
