# Item 11 - Final Verification Waivers

## 2026-06-26 22:45 SGT - Fresh Broad Gradle Check

- Resource precheck:
  - Disk: about 61 GiB free on the repo, Gradle cache, and private temp
    volume.
  - No high-memory `python3.12` process was present.
- Command:
  `./gradlew check --console=plain --no-daemon`.
- Result: failed in 31s on sample lint, before completing the full repo check.
- Symptom:
  - `:sample-android:lintDemoFreeDebug` failed with `MissingConstraints` at
    `sample-android/src/main/res/layout/activity_main.xml:73`.
- Classification:
  - The failing source file is not modified by this branch.
  - This is a broad sample-app lint issue, not a dependency-refactor failure.
  - Keep as a documented broad-Gradle waiver unless the maintainer wants a
    separate sample lint cleanup.

## 2026-06-26 22:50 SGT - Local Bazel Waiver Audit

- Read-only explorer audit confirmed the local root Bazel failures are
  sample/rule hygiene, not dependency-refactor logic.
- `bazelisk test //... --show_progress_rate_limit=30` status from Item 10:
  - 9 tests passed.
  - `//sample-android:sample-android-demo-paid-debug.lint_test` failed to
    build because the crashlytics generated manifest was missing in Android
    configuration.
  - Four `flavors/sample-android-flavor` lint tests failed on duplicate
    generated `res_values` key `generated_value`.
- Evidence:
  - The generated crashlytics helper targets and duplicate `generated_value`
    `res_values` shape already exist in `origin/master` generated BUILD files.
  - Branch-vs-master sample BUILD diffs inspected here are dependency-label
    movement, not crashlytics or `res_values` structure changes.
- Classification:
  - Local root Bazel is not green.
  - Treat this as a documented local-sample/Bazel waiver for the dependency
    refactor review slice.
  - If root Bazel green is mandatory before merge, fix these as separate
    sample/rule hygiene tasks:
    - crashlytics manifest dependency should be fixed at the Bazel rule
      integration layer;
    - duplicate `generated_value` should be fixed in sample resource
      ownership, not by generator-side de-duping.
