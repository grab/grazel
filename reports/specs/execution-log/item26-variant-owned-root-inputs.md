# Item 26: Variant-Owned Workspace Dependency Root Inputs

## Status

- Active slice after Item 23 local commit `4210235`.
- Grazel branch: `arun/dependencies-refactor`; changes must stay local and must not be pushed.
- PAX baseline workspace: `/Users/arun.sampathkumar/work/pax-android` on
  `arun/grazel-refactor` at `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`; do not commit PAX.

## Decisions

- `WorkspaceDependencyInputsRegistrar` must not own AGP type checks or configuration-name
  semantics. It now collects variants lazily through `VariantBuilder.onVariants`, delegates
  root-input intent to `WorkspaceDependencyRootInputPlanner`, and wires only task inputs.
- Variant-layer APIs own workspace classpath roles:
  main variant classpaths, unit-test classpaths, android-test classpaths, lint classpaths,
  build type/flavor metadata, and hierarchy/leaf predicates.
- KSP sidecar configuration-name semantics are also variant-layer knowledge. The task remains
  responsible for cacheable KSP processor root inputs and artifact/class extraction only.
- No production change was needed for no-flavor `onVariants` parity: a focused test proved the
  lazy variant set matches eager `build()` for a no-flavor Android project.

## Fixed In This Slice

- Added `WorkspaceDependencyRootInputPlanner` as the dependency-layer planner for workspace
  resolution roots.
- Refactored `WorkspaceDependencyInputsRegistrar` away from ad hoc `BaseVariant`, `BuildType`,
  `findByName("lintChecks")`, and unit/android-test classpath string construction.
- Added source guards proving the registrar does not own those configuration semantics.
- Added lazy/eager no-flavor Android variant parity coverage.
- Moved `CollectKspProcessorDependenciesTask` KSP declaration-bucket scanning and
  `grazelKspProcessorClasspath` construction into `gradle.variant.WorkspaceKspConfigurations`.
- Added a task-layer guard proving KSP configuration-name predicates are not in the task.

## Broad Diff Scan Reconciliation

Subagent audits intentionally scanned the changed Kotlin branch diff for similar altitude
violations. Reconciliation:

- Fixed now: workspace dependency root registration and KSP sidecar configuration wiring.
- Accepted home: `ConfigurationParsingVariant` and new variant helpers may contain AGP/KSP
  configuration-name parsing because this package is the variant/configuration shape layer.
- Known/future architecture work, not safe to fix inside preserving Item 26 without widening the
  slice: dependency metadata collectors still import AGP variant types; resolver and placement
  logic still reconstruct some facts from display/configuration/bucket strings; target
  reachability and target-reference facts still have known render/data-model cleanup work;
  repository naming and Maven render calculation remain in migrate/dependencies. These are
  broader Item 24/25/future cleanup concerns, not regressions introduced by this specific
  registrar refactor.

## Verification

- Red check observed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.tasks.internal.CollectKspProcessorDependenciesTaskTest.ksp processor dependency task does not own ksp configuration name semantics" --console=plain --no-daemon`
  failed before moving KSP configuration semantics out of the task.
- Focused checks passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.variant.DefaultVariantBuilderTest.lazy android variants match eager variants for project without flavors" --console=plain --no-daemon`
  and
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.tasks.internal.CollectKspProcessorDependenciesTaskTest" --console=plain --no-daemon`.
- Item 26 focused suite passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.variant.VariantTest" --tests "com.grab.grazel.gradle.variant.DefaultVariantBuilderTest" --tests "com.grab.grazel.gradle.dependencies.WorkspaceDependencyRootInputPlannerTest" --tests "com.grab.grazel.tasks.internal.ResolveWorkspaceDependenciesTaskTest" --tests "com.grab.grazel.tasks.internal.CollectKspProcessorDependenciesTaskTest" --console=plain --no-daemon`.
- Full plugin unit tests passed:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`.
- Local Grazel generation passed:
  `./gradlew migrateToBazel --console=plain --no-daemon`.
- Preserving checks passed:
  `reports/scripts/verify-default-task-graph.sh`,
  `reports/scripts/verify-pax-size-guard.sh --mode preserving`,
  `git diff --check`, and `git diff --check master...HEAD`.
- Known unchanged waiver:
  `reports/scripts/verify-sample-bucket-labels.sh` failed only on the documented
  pre-existing appcompat/constraintlayout exclude-union case.
- PAX baseline verification passed without committing PAX:
  - `cd /Users/arun.sampathkumar/work/pax-android`
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
    passed in 12m26s.
  - PAX `git status --short --branch` stayed clean on `arun/grazel-refactor`.
  - PAX `git diff --check` passed.
  - Grazel size guard after PAX migrate passed unchanged:
    `bucketCount=11`, `pinfileCount=11`, `totalArtifactRoots=1945`.
  - `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
    passed in 221.378s.
  - `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test`
    passed in 19.045s with 3/3 tests passing.

## Resource Notes

- PAX resource precheck showed roughly 22 GiB free before the expensive loop; post-run
  free space was roughly 18 GiB.
- No Gradle/Bazel cache deletion was needed.
- No stale Gradle/Bazel/Coursier/python process cleanup was needed.

## Remaining Gates

- Commit Item 26 locally in Grazel only after final status/diff checks.
- Never push Grazel from this goal and never commit PAX.
