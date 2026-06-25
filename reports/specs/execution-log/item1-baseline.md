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
  - Commit this cleanup.
  - Run PAX migrate/build/audit and record `reports/specs/PAX-BOUNDED-AUDIT-BASELINE.md`.
