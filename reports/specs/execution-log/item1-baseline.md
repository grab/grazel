# Item 1 Execution Log - Baseline, Knowledge Consolidation, Hygiene

This item log keeps the detailed evidence for Item 1. Keep
`reports/specs/EXECUTION-LOG.md` short and link here instead of expanding it.

## 2026-06-26 02:43 +08 - Goal Start

- Active item: Item 1 - baseline and safety net.
- Starting commit: `d8d8f72f0216e3d91e7abec612097f16a65209e2`.
- Initial status included two modified production files:
  - `AggregatedDependencyResolver.kt`
  - `AndroidExtractor.kt`
- Spec contract verified present: all six `reports/specs/2026-06-26-item*.md` files.
- Resource check: about 49 GiB free on `/System/Volumes/Data`.
- Decisions:
  - PAX uses a local composite include build, so PAX `migrateToBazel` picks up this
    Grazel checkout without publishing.
  - Do not commit PAX-side changes.
  - Stay in this branch/worktree; use subagents for bounded read-heavy audits and final
    reviews, not uncontrolled parallel writes.
  - Context management is part of the goal.

## 2026-06-26 02:50 +08 - Focused Test Gate

- Command:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --tests "com.grab.grazel.gradle.dependencies.DependencyBucketPlacementEngineTest" --tests "com.grab.grazel.gradle.variant.BucketHierarchyGraphTest" --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --tests "com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependenciesTest" --tests "com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionServiceTest" --tests "com.grab.grazel.gradle.dependencies.ResolvedComponentsVisitorTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallArtifactsCalculatorTest" --tests "com.grab.grazel.migrate.android.DefaultAndroidLibraryDataExtractorTest" --tests "com.grab.grazel.gradle.DefaultDependenciesDataSourceTest"`
- Result:
  - Gradle printed `BUILD SUCCESSFUL in 28s`, `17 actionable tasks: 3 executed, 14 up-to-date`.
  - The PTY session did not exit after success and was interrupted; use the printed
    Gradle result as the evidence for this gate.
- Subagent Item 1 inventory:
  - Existing: Item 1 spec, verification scripts, tracked generated baseline surface, all
    six specs.
  - Missing at that time: named golden verification script/task, PAX bounded-audit script
    and record, `DO-NOT-REVISIT.md`, and old report cleanup.

## 2026-06-26 03:02 +08 - Local Baseline Generation

- Commands:
  - `./gradlew migrateToBazel --console=plain`
  - `reports/scripts/verify-default-task-graph.sh`
  - `reports/scripts/verify-sample-bucket-labels.sh`
- Results:
  - `migrateToBazel` printed `BUILD SUCCESSFUL in 39s`, `46 actionable tasks: 35 executed, 11 up-to-date`.
  - Default task graph verifier passed.
  - Bucket label verifier initially failed with
    `Unexpected dependency buckets: androidTest,debug,debugAndroidTest,debugUnitTest,default,lint,test`.
- Root cause:
  - The verifier oracle was stale. Typed buckets such as `debugUnitTest` are expected in
    this branch.
- Fix:
  - Updated `reports/scripts/verify-sample-bucket-labels.sh` to expect typed buckets and
    assert representative direct ownership in `debugUnitTest` and `debugAndroidTest`.

## 2026-06-26 03:06 +08 - Golden Task Portability Fix

- Command:
  - `./gradlew verifyGrazelGoldenBaseline --console=plain`
- Failure:
  - `reports/scripts/verify-grazel-golden-baseline.sh: line 19: mapfile: command not found`
- Root cause:
  - macOS `/bin/bash` is Bash 3.2 and does not include `mapfile`.
- Fix:
  - Replaced `mapfile` with a Bash 3-compatible `while read` loop.

## 2026-06-26 03:10 +08 - Local Baseline Commit

- Commit: `1188d46` (`Establish dependency refactor baseline checks`).
- Contents:
  - Named golden verification script and Gradle task.
  - PAX bounded-audit script scaffold.
  - Amended specs and `DO-NOT-REVISIT.md`.
  - Fresh generated Grazel baseline outputs after `migrateToBazel`.
- Generated diff shape:
  - `WORKSPACE`, `android_test_maven_install.json`, `test_maven_install.json`, and
    `debug_maven_install.json` contracted after fresh generation.
- Remaining Item 1 work after the commit:
  - Rerun named golden task.
  - Run PAX migrate/build/audit and record the PAX bounded baseline.
  - Delete captured legacy report artifacts.

## 2026-06-26 03:13 +08 - Golden Task Passed

- Command:
  - `./gradlew verifyGrazelGoldenBaseline --console=plain`
- Result:
  - `BUILD SUCCESSFUL in 13s`, `47 actionable tasks: 21 executed, 26 up-to-date`.
  - Script printed: `Grazel golden baseline verified: migrateToBazel, task graph,
    bucket labels, and generated-file diff are clean.`

## 2026-06-26 03:25 +08 - Log Split And Legacy Report Cleanup

- Decision:
  - Keep `reports/specs/EXECUTION-LOG.md` as the short current pointer.
  - Keep item-specific detail in `reports/specs/execution-log/item1-baseline.md`.
- Cleanup:
  - Deleted the legacy dependency-refactor report artifacts listed in Item 1.
  - Kept `reports/scripts/`.
- Remaining Item 1 work:
  - Run PAX migrate/build/audit and record `reports/specs/PAX-BOUNDED-AUDIT-BASELINE.md`.

## 2026-06-26 03:30 +08 - Cleanup Commit And Writer Hygiene

- Commit: `ba99bb8` (`Consolidate dependency refactor reports`).
  - Deleted the legacy reports listed in Item 1.
  - Added this item-specific log and kept `EXECUTION-LOG.md` short.
- Post-cleanup check:
  - `git diff --check master...HEAD` initially failed on `keystore/BUILD.bazel` with a
    blank line at EOF.
- Root cause:
  - The Starlark statement writer appends separator newlines after statements.
  - Keystore `BUILD.bazel` is generated outside the normal formatting path, so the
    trailing separator survived in committed generated output.
- Fix:
  - Commit `10cfa22` (`Fix starlark writer trailing separators`).
  - `List<Statement>.writeToFile` now drops trailing `NewLineStatement` separators only;
    blank lines between statements are preserved.
  - Added `StatementWriterTest`.
  - Regenerated `keystore/BUILD.bazel`.
- Verification:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --tests "com.grab.grazel.bazel.starlark.StatementWriterTest"` passed.
  - `./gradlew migrateToBazel --console=plain` passed.
  - `./gradlew verifyGrazelGoldenBaseline --console=plain` passed with `BUILD SUCCESSFUL in 13s`.
  - `git diff --check master...HEAD` passed.
- Remaining Item 1 work:
  - Run PAX migrate/build/audit and record `reports/specs/PAX-BOUNDED-AUDIT-BASELINE.md`.

## 2026-06-26 06:20 +08 - PAX migrateToBazel Baseline

- PAX root: `/Users/arun.sampathkumar/work/pax-android`.
- Grazel baseline commit before PAX run: `42d64c2` (`Record Item 1 local baseline status`).
- Local PAX compatibility edits were required and must not be committed:
  - `build-logic/project/src/main/kotlin/grazel/Performance.kt` now tolerates the new
    `ResolveWorkspaceDependenciesTask` class while still tolerating the removed
    `ResolveVariantDependenciesTask`.
  - `build-logic/project/src/main/kotlin/grazel/Constants.kt` and `Grazel.kt` now wire
    `syncPatches` before root `resolveWorkspaceDependencies` instead of removed
    `:app:defaultResolveDependencies`.
- Command:
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`
- Result:
  - `BUILD SUCCESSFUL in 18m 38s`.
  - `4748 actionable tasks: 4609 executed, 118 from cache, 21 up-to-date`.
- Notes:
  - Pinning remained very verbose and slow, especially for bucket maven repos. This is
    accepted as Item 1 baseline evidence, not accepted as final optimization state.
- Remaining Item 1 work:
  - Record PAX bounded audit.
  - Run PAX APK and android-test APK Bazel build.
  - Run PAX diff checks and app test target query.

## 2026-06-26 06:35 +08 - PAX Bounded Audit Baseline

- Command:
  - `PAX_ROOT=/Users/arun.sampathkumar/work/pax-android reports/scripts/audit-pax-bounded-baseline.sh reports/specs/PAX-BOUNDED-AUDIT-BASELINE.md`
- Result:
  - Passed and wrote `reports/specs/PAX-BOUNDED-AUDIT-BASELINE.md`.
- Audit script correction:
  - The first version incorrectly assumed `android_binary(name = "app-gps-pax-debug")`
    owns compile-filter tags. PAX HEAD and current output both have no `tags` attr on
    that binary wrapper.
  - The script now records tag shape separately: `@maven` Maven compile-filter tags,
    legacy `@direct` project tags, and `@self`. It enforces that Maven-shaped tags are
    normalized to `@maven//:` and that no bucket-prefixed Maven labels appear in `tags`.
  - It resolves output paths before `cd` into PAX and appends all sections to the audit
    artifact.
- Baseline facts:
  - `//app:app-gps-pax-debug`: 1452 deps, 0 tags, 6 `@debug_maven` deps.
  - `//app:app-gps-pax-debug-android-test`: 1511 deps, 1957 tags, 616 `@maven` tags,
    1340 `@direct` tags, 1 `@self` tag, 1 `@debug_maven` dep, 12 `@android_test_maven`
    deps.
  - `bug-report-kit-implementation/BUILD.bazel` is still present; strict reachability
    cleanup remains a later item.
- Remaining Item 1 work:
  - Run PAX APK and android-test APK Bazel build.
  - Run PAX diff checks and app test target query.

## 2026-06-26 07:27 +08 - PAX Bazel Gate Hit Disk Exhaustion

- Command:
  - `cd /Users/arun.sampathkumar/work/pax-android && ./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures`
- Result:
  - Failed after ~40m49s at final android-test deploy-jar output copy/action-log write.
  - Root cause is infrastructure: `No space left on device`, followed by Bazel sandbox-base crash.
- Positive compile signals before disk failure:
  - `//app:lib_app-gps-pax-debug_kt` compiled.
  - Prior `snp/snp-ui-tests` classpath failure did not recur; it compiled with warnings.
  - Android-test support modules compiled.
  - Main `//app:app-gps-pax-debug-android-test_lib_kt` KAPT and Kotlin compile completed.
- System state:
  - Disk after failure: `/System/Volumes/Data` at 99%, ~7.3 GiB available.
- Next action:
  - Free PAX Bazel storage with `bazelisk clean --expunge`; remove `bazel-cache` only if still genuinely low.
  - Rerun the same PAX Bazel gate. Do not treat the disk-full crash as a dependency/code failure.

## 2026-06-26 08:50 +08 - PAX Bazel Gate Reached Android-Test Dex Merge

- Cleanup before rerun:
  - `cd /Users/arun.sampathkumar/work/pax-android && bazelisk clean --expunge`
  - `cd /Users/arun.sampathkumar/work/pax-android && rm -rf bazel-cache`
  - Stopped idle Grazel Gradle daemon and idle Grazel Bazel server during the run.
- Command:
  - `cd /Users/arun.sampathkumar/work/pax-android && ./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures`
- Result:
  - Failed after 2282.175s at `DexMerger app/dexfiles/app-gps-pax-debug-android-test/5.shard.zip`.
  - Disk dropped to ~2.9 GiB free during final packaging. While the failure did not print an explicit
    `No space left on device` line in captured output, disk pressure is the leading suspect.
- Positive correctness signals:
  - Main app KSP/KAPT/Kotlin compile completed.
  - Android-test support modules compiled.
  - `snp/snp-ui-tests` compiled; the previous missing-class/classpath failure did not recur.
  - Main `//app:app-gps-pax-debug-android-test_lib_kt` compile completed and reached deploy-jar,
    zip filtering, dex assembly, and dex merge.
- Storage recovery:
  - Removed `/Users/arun.sampathkumar/.gradle/caches` after disk dropped to ~2.9 GiB.
  - Disk recovered to ~31 GiB free after cleanup.
- Next action:
  - Rerun the same PAX Bazel gate with recovered disk. A pass completes the APK/android-test build gate;
    a repeat DexMerger failure with healthy disk should be treated as a real dex/content failure.

## 2026-06-26 09:00 +08 - PAX Bazel Gate Passed After Disk Recovery

- Command:
  - `cd /Users/arun.sampathkumar/work/pax-android && ./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures`
- Result:
  - Passed.
  - `INFO: Build completed successfully, 9 total actions`
  - Elapsed: 275.753s; critical path: 12.58s.
- Conclusion:
  - The prior `DexMerger app-gps-pax-debug-android-test/5.shard.zip` failure was infrastructure/disk pressure,
    not a dependency or dex-content correctness failure.
  - Item 1 APK/android-test APK Bazel build gate is green.
- Storage:
  - Disk after pass: ~30 GiB free on `/System/Volumes/Data`.
- Remaining Item 1 work:
  - Run PAX `git diff --check`.
  - Run PAX app test target query.
  - Record current Grazel/PAX status and commit Grazel-only Item 1 artifacts if checks pass.

## 2026-06-26 09:05 +08 - PAX Diff And App Test Query Checks

- Commands:
  - `cd /Users/arun.sampathkumar/work/pax-android && git diff --check`
  - `cd /Users/arun.sampathkumar/work/pax-android && tools/bazel query 'kind(".*test rule", //app:*)'`
  - `cd /Users/arun.sampathkumar/work/grazel && git diff --check`
- Results:
  - PAX `git diff --check`: passed.
  - Grazel `git diff --check`: passed.
  - PAX app test query returned only lint test targets:
    - `//app:app-gps-moveit-debug.lint_test`
    - `//app:app-gps-ovo-debug.lint_test`
    - `//app:app-gps-pax-debug.lint_test`
    - `//app:app-hms-moveit-debug.lint_test`
    - `//app:app-hms-ovo-debug.lint_test`
    - `//app:app-hms-pax-debug.lint_test`
- PAX working tree:
  - Large generated `BUILD.bazel` / `WORKSPACE` / Maven JSON diff exists from `migrateToBazel`.
  - Local PAX build-logic compatibility edits remain in:
    - `build-logic/project/src/main/kotlin/grazel/Constants.kt`
    - `build-logic/project/src/main/kotlin/grazel/Grazel.kt`
    - `build-logic/project/src/main/kotlin/grazel/Performance.kt`
  - Do not commit PAX changes.
- Item 1 baseline status:
  - PAX migrate passed.
  - PAX bounded audit passed.
  - PAX app APK and android-test APK build passed.
  - PAX/Grazel diff checks passed.
  - App test query recorded.
