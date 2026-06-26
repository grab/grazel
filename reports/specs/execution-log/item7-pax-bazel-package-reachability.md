# Item 7 - PAX Bazel Package Reachability

## 2026-06-26 20:05 SGT - Failure Captured

- Gate:
  - `cd /Users/arun.sampathkumar/work/pax-android`
  - `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
- Result:
  - Failed during Bazel analysis, before Kotlin/Java compilation.
- Symptom:
  - `app/BUILD.bazel` references `//tis-grabid-ui-tests`.
  - Bazel reports no package at `tis-grabid-ui-tests` because no active
    `BUILD.bazel` exists there.
- Immediate debugging rule:
  - No fix until we trace why the app android-test target references this
    project and why strict generation excluded it.
  - This may be a reachability data-flow bug, not a request to restore broad
    generation for every module.

## Next

- Inspect the generated `app/BUILD.bazel` reference.
- Inspect PAX Gradle declarations for `tis-grabid-ui-tests`.
- Trace Grazel reachability decisions for android-test project dependencies.

## 2026-06-26 20:35 SGT - Root Cause and Local Fix

- Root cause:
  - `app` declares `androidTestImplementation project(':tis-grabid-ui-tests')`.
  - App android-test target generation correctly emits both tags and deps for
    `//tis-grabid-ui-tests:tis-grabid-ui-tests-gps-pax-debug_lib`.
  - `collectTargetMavenRepoReferences` and `workspace-render-plan.json`
    already marked `:tis-grabid-ui-tests` as a referenced project path.
  - `AndroidTestTargetBuilder` did not use the referenced-project fallback
    that Kotlin/JVM generation has. It gated `com.android.test` target
    emission only on dependency bucket reachability, so it could return no
    targets and leave the referenced package inactive.
- Fix shape:
  - Carry exact referenced project target names through
    `TargetMavenRepoReferences` and `WorkspaceRenderPlan`.
  - Expose exact target names from `WorkspacePlanService`.
  - Let `AndroidTestTargetBuilder` keep a matched test-module variant if either
    its bucket is reachable or its normalized generated target name is
    referenced. `_lib` references keep the owning instrumentation macro target
    alive.
- Focused verification:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon --tests "com.grab.grazel.tasks.internal.TargetMavenRepoReferencesCollectorTest" --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest" --tests "com.grab.grazel.migrate.target.TargetVariantReachabilityTest"`
  - Result: passed.

## Next

- Re-run PAX `migrateToBazel`.
- Check that `tis-grabid-ui-tests/BUILD.bazel` exists and contains
  `tis-grabid-ui-tests-gps-pax-debug`.
- Re-run the PAX Bazel APK/android-test build gate.

## 2026-06-26 17:52 SGT - Verification Green

- PAX regenerate:
  - `cd /Users/arun.sampathkumar/work/pax-android`
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`
  - Result: passed in 10m 15s.
- Generated reachability evidence:
  - `tis-grabid-ui-tests/BUILD.bazel` is active.
  - `app/BUILD.bazel` references:
    - `//tis-grabid-ui-tests:tis-grabid-ui-tests-gps-ovo-debug_lib`
    - `//tis-grabid-ui-tests:tis-grabid-ui-tests-gps-pax-debug_lib`
  - `build/grazel/workspace-render-plan.json` carries the same two referenced
    target names under `:tis-grabid-ui-tests`.
- PAX Bazel gate:
  - `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
  - Result: passed in 397.049s.
  - Build actions: 54,549 total; 37,197 disk cache hits; 6,170 remote cache
    hits.
- Conclusion:
  - The strict package reachability bug for `com.android.test` modules is fixed
    without restoring broad BUILD generation for unreachable modules.

## Next

- Run diff hygiene checks in Grazel and PAX.
- Inspect the diff shape and keep this as a verified baseline before any
  further architecture or bucket-size work.
