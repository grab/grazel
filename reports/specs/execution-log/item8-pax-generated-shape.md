# Item 8 - PAX Generated Shape After Green Gate

## 2026-06-26 17:53 SGT - Baseline Snapshot

- Verification baseline:
  - PAX `migrateToBazel` passed.
  - PAX `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk` passed.
  - `git diff --check` passed in both Grazel and PAX.
- PAX generated diff shape:
  - `WORKSPACE`: 3,998 lines at `HEAD`, 5,156 lines generated.
  - `BUILD.bazel` files: 2,168 modified, 40 deleted.
  - `tis-grabid-ui-tests/BUILD.bazel` is active again and app references the
    generated `_lib` targets through tags/deps.
- Maven pin file line counts after generation:
  - `android_test_maven_install.json`: 10,401 lines.
  - `test_maven_install.json`: 12,847 lines.
  - `debug_maven_install.json`: 4,387 lines.
  - `lint_maven_install.json`: 3,510 lines.
  - Total across `*_maven_install.json`: 39,962 lines.
- Read-only generated-output audit:
  - PAX has 2,230 changed paths: 2,185 modified, 45 deleted.
  - `BUILD.bazel` files: 2,168 modified, 40 deleted.
  - `maven_install(...)` calls dropped from 28 to 24 and JSON files from 17
    to 12.
  - Total Maven JSON content still grew from 41,396 to 54,550 lines.
  - Biggest growth is in `android_test_maven_install.json`,
    `test_maven_install.json`, and `lint_maven_install.json`.
- Important interpretation:
  - The latest state is build-correct for the main PAX APK/android-test gate.
  - Workspace and test/android-test pin files remain larger than desired. Treat
    this as an optimization/correctness-shape concern to revisit from the
    bucket-placement/value-layer architecture, not as permission for shortcuts.

## Next

- Wait for subagent generated-diff and Grazel-diff audits.
- Decide whether to commit this verified baseline before further bucket-size
  optimization.

## 2026-06-26 17:54 SGT - Code Audit Follow-Up

- Read-only Grazel code audit flagged three risks:
  - Target-reference collection reads target deps/plugins/lint checks, but may
    miss rendered fields such as Android test `associates` and `instruments`.
  - `ProjectDependency` target identity is currently derived from rendered text
    using `toString().substringAfterLast(":")`; prefer structured label data if
    available.
  - Formatting skip behavior may risk stale project `BUILD.bazel` files if a
    generated input disappears; verify before changing because PAX did delete
    40 generated BUILD files in this run.
- Decision:
  - Fix the first two if code inspection confirms the model exposes structured
    data.
  - Investigate the stale-output concern before making changes.
  - Add focused tests first for any behavior change.

## 2026-06-26 17:57 SGT - Collector Gap Fixed

- TDD red:
  - Added `TargetMavenRepoReferencesCollectorTest` coverage for Android test
    `associates` and `instruments`.
  - Focused collector test failed as expected because those fields were not
    collected.
- Fix:
  - `TargetMavenRepoReferencesCollector` now includes Android test
    `associates` and `instruments` in dependency reference collection.
  - `ProjectDependency` target names now use structured
    `prefix + dependencyProject.name + suffix` instead of parsing
    `toString()`.
- TDD green:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon --tests "com.grab.grazel.tasks.internal.TargetMavenRepoReferencesCollectorTest"`
  - Result: passed.
- Stale-output audit:
  - `GenerateBazelScriptsTask.disableProjectBuildFile` deletes staged output
    and renames active generated `BUILD.bazel` to `BUILD.bazelignore` for
    concrete Android/Java/Kotlin projects.
  - PAX generated diff includes 40 deleted `BUILD.bazel` files, so this is not
    currently blocking.

## 2026-06-26 18:09 SGT - PAX Regenerate After Collector Fix

- PAX regenerate:
  - `cd /Users/arun.sampathkumar/work/pax-android`
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`
  - Result: passed in 10m 31s.
- Hygiene:
  - Grazel `git diff --check`: passed.
  - PAX `git diff --check`: passed.
- Generated shape after rerun:
  - `WORKSPACE`: 3,998 lines at PAX `HEAD`, 5,156 lines generated.
  - `BUILD.bazel` files: 2,168 modified, 40 deleted.
  - `tis-grabid-ui-tests/BUILD.bazel` is active.
  - Maven pin file total from current `wc -l *_maven_install.json`: 45,118
    lines because the pruned unit-test bucket JSON files are deleted from the
    worktree.
- Biggest remaining generated pin-file growth versus PAX `HEAD`:
  - `android_test_maven_install.json`: +8,642/-1,029.
  - `test_maven_install.json`: +7,974/-1,255.
  - `lint_maven_install.json`: +3,283/-17.
- Interpretation:
  - The collector fix did not regress the earlier PAX generated-output shape.
  - The APK/android-test Bazel build gate still needs to be rerun after this
    production-code change.
  - Test/android-test/lint pin size remains the main generated-shape concern.

## 2026-06-26 18:25 SGT - PAX Bazel Gate After Collector Fix

- PAX Bazel gate:
  - `cd /Users/arun.sampathkumar/work/pax-android`
  - `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
  - Result: passed in 530.181s.
  - Actions: 54,549 total; 37,656 disk cache hits; 6,165 remote cache hits;
    10,728 internal.
- Observations:
  - The previous strict package reachability failure is still fixed.
  - The earlier `Too many open files`/missing-class android-test symptom did
    not recur in this gate.
  - Coursier still reports duplicate artifact versions during analysis. That
    remains a generated value-layer cleanup concern, but it did not block this
    gate.

## Next

- Run final diff hygiene after the PAX gate.
- Run broader Grazel tests if system pressure allows.
- Keep test/android-test/lint pin-file growth as the next generated-shape
  optimization item.

## 2026-06-26 18:29 SGT - Local Grazel Verification

- Full plugin unit tests:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`
  - Result: passed in 41s.
- Lightweight refactor gates:
  - `reports/scripts/verify-default-task-graph.sh`: passed.
  - `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- Final hygiene after PAX gate:
  - Grazel `git diff --check`: passed.
  - PAX `git diff --check`: passed.
- System state:
  - Disk remained at roughly 23 GiB free after the PAX build.
  - No `python3.12` process was present during the final checks.
