# Item 34 - Workspace Tag-Plan Service Shape

## Status

- 2026-07-01: Implemented and committed locally as `85c6136`
  (`refactor: split workspace tag plan service`). Final Item35 verification
  reran the broader Grazel/PAX gates against this shape.

## Decisions

- `WorkspacePlan` is no longer the carrier for target tag data.
- `target-tag-plan.json` is the single persisted home for target tag facts.
- `WorkspacePlanService` is plan-only.
- `WorkspaceTargetTagPlanService` owns read-only target tag lookup keyed by
  `TargetTagKey`.
- Post-Item35 adversarial review removed the last in-memory
  `populateTagPlan(...)` test seam; tests now hydrate this service through
  `target-tag-plan.json` with `initTagPlan(...)`, matching production.
- `WorkspaceRenderPlanService` owns generated target reachability and keeps the
  documented consumer-first back-edge used during target-reference collection.
- Part 3 typed-key cleanup remains deferred unless it can be proven
  byte-identical; the current slice keeps serialized `targetKind` strings.

## Commands And Results

- Resource check before Gradle:
  - `df -h /Users/arun.sampathkumar/work/grazel`: about 43 GiB free, 90% used.
  - process scan showed no stale Gradle/Bazel process; no cleanup performed.
- `./gradlew :grazel-gradle-plugin:compileKotlin :grazel-gradle-plugin:compileTestKotlin --console=plain --no-daemon`
  - Passed.
- First focused test run failed in the new tag-service JSON test with
  `FileNotFoundException`; root cause was test setup writing JSON before
  creating `build/grazel`.
  - Fix: create the parent directory in the test fixture only.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest" --tests "com.grab.grazel.gradle.dependencies.WorkspacePlanBuilderTest" --tests "com.grab.grazel.migrate.android.DefaultAndroidLibraryDataExtractorTest.extract uses target tag plan for maven tag labels" --console=plain --no-daemon`
  - Passed.
- `./gradlew migrateToBazel --console=plain --no-daemon`
  - Passed.
- `git diff --name-only -- '*.bazel' 'WORKSPACE' 'maven_install.json' 'maven_install_*.json'`
  - Empty output; committed generated Bazel output unchanged.
- `git diff --check`
  - Passed.

## Closure

- Item34 is closed. Remaining verification evidence is recorded in the Item35
  log and top-level execution log because the final gates were rerun after
  Item35's source changes.
