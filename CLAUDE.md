# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Grazel is a Gradle plugin that automates migration of Android projects from Gradle to Bazel build system. It generates `BUILD.bazel` and `WORKSPACE` files based on existing Gradle configuration.

## Build Commands

```bash
# Run all tests (unit + functional)
./gradlew check

# Run only unit tests for the plugin
./gradlew :grazel-gradle-plugin:test

# Run a specific test class
./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.GrazelPluginTest"

# Run functional tests
./gradlew :grazel-gradle-plugin:functionalTest

# Generate Bazel scripts to test migration
./gradlew migrateToBazel

# Clean generated Bazel files
./gradlew bazelClean

# Build and run bazel build on generated files
./gradlew bazelBuildAll

# Publish to local maven (for local testing)
./gradlew :grazel-gradle-plugin:publishToMavenLocal
```

## Architecture

### Plugin Structure (`grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/`)

- **`GrazelGradlePlugin.kt`**: Entry point. Applies only to root project, creates `GrazelExtension`, initializes Dagger component, and configures tasks.

- **`GrazelExtension.kt`**: DSL configuration for users. Contains nested extensions: `android`, `dependencies`, `rules`, `hybrid`, `test`, `experiments`.

- **`di/`**: Dagger dependency injection setup. `GrazelComponent` is the main component providing all dependencies.

- **`tasks/internal/`**: Gradle task implementations. `TasksManager.kt` registers all tasks and wires their dependencies.

- **`bazel/`**: Bazel-specific code generation:
  - `starlark/`: Type-safe Kotlin DSL for generating Starlark code (Bazel's build language)
  - `rules/`: Implementations for various Bazel rules (Android, Kotlin, Dagger, Maven, etc.)
  - `exec/`: Bazel command execution

- **`migrate/`**: Migration logic organized by target type:
  - `android/`: Android library, binary, test, instrumentation targets
  - `kotlin/`: Pure Kotlin library targets
  - `dependencies/`: Maven artifact handling, artifact pinning
  - `internal/`: `ProjectBazelFileBuilder`, `RootBazelFileBuilder`, `WorkspaceBuilder`
  - `target/`: Target builders that construct Bazel targets from extracted data

- **`gradle/`**: Gradle project analysis:
  - `dependencies/`: Dependency resolution and graph building
  - `variant/`: Android variant handling and filtering
  - `MigrationCriteria.kt`: Determines if a module can be migrated

- **`extension/`**: Individual extension classes for each configuration block (Android, Kotlin, Maven, Dagger, etc.)

- **`hybrid/`**: Experimental hybrid build support (not currently active)

### Key Patterns

1. **Data Extractors**: Classes like `AndroidLibraryDataExtractor` analyze Gradle projects and produce data classes (e.g., `AndroidLibraryData`).

2. **Target Builders**: Classes like `AndroidLibraryTargetBuilder` transform data classes into `BazelTarget` implementations.

3. **Starlark DSL**: The `starlark/` package provides a type-safe way to generate Bazel build files. Use `StarlarkType` interface and `StatementsBuilder` to construct rules.

4. **Migration Flow**: `TasksManager` orchestrates: resolve dependencies -> compute workspace deps -> generate root scripts -> generate project scripts -> format with buildifier -> post-generation tasks.

### Test Structure

- **Unit tests**: `grazel-gradle-plugin/src/test/kotlin/` - Test individual components
- **Functional tests**: `grazel-gradle-plugin/src/functionalTest/kotlin/` - End-to-end plugin tests
- **Test projects**: `grazel-gradle-plugin/src/test/projects/` - Sample Gradle projects used in tests

### Sample Modules

The root project includes sample modules for testing migration:
- `sample-android/`: Android application
- `sample-android-library/`: Android library
- `sample-kotlin-library/`: Pure Kotlin library
- `flavors/`: Modules with product flavors

## Configuration

The plugin is configured via the `grazel` extension in root `build.gradle`. Key blocks:
- `android {}`: Android-specific settings (variant filtering, dex shards, NDK level)
- `rules {}`: Bazel rule versions and configurations (Kotlin, Dagger, Maven)
- `dependencies {}`: Artifact overrides and ignore lists

## External Dependencies

- Uses [grab-bazel-common](https://github.com/grab/grab-bazel-common) for custom Bazel rules
- Uses rules_kotlin, rules_jvm_external, tools_android from Bazel ecosystem