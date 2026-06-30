# Item 35 - Task Progress Reporting

## Status

- 2026-07-01: Implementation, simplify pass, adversarial review, and full
  Grazel/PAX verification are green. Final local commit is pending.

## Changes So Far

- Added pure-JVM `ProgressReporter` with explicit test-only `NoOp`.
- Added `ProgressLoggerFactory.withProgress` adapter.
- Added transient progress and `logger.quiet` summaries to:
  - `ResolveWorkspaceDependenciesTask` / `AggregatedDependencyResolver`
  - `CollectDeclaredDependencyMetadataTask`
  - `MergeDeclaredDependencyMetadataTask`
  - `CollectKspProcessorDependenciesTask`
  - `CollectWorkspaceTargetTagPlanTask`
  - `CollectTargetMavenRepoReferencesTask`
  - `AnalyzeVariantCompressionTask`
- Moved declared-metadata summaries from `logger.lifecycle("Grazel: ...")` to
  plain `logger.quiet(...)`.
- Post-review fixes:
  - `CollectDeclaredDependencyMetadataTask` no longer calls Gradle
    `ProgressLogger` from worker coroutines. Worker coroutines send completed
    snapshot results through a channel; the task-thread coroutine emits progress.
  - Added a regression test proving single-task declared-metadata progress is
    emitted on the caller thread while snapshots run in parallel.
  - `CollectTargetMavenRepoReferencesTask` no longer accepts a separate
    progress-only project count; it derives progress totals from the group list.
  - `WorkspaceTargetTagPlanService` no longer exposes the mutable
    `populateTagPlan(...)` seam; the remaining test hydrates from JSON via
    `initTagPlan(...)`.
  - Declared-metadata summaries were changed from metric-style
    `mode=... projects=...` text to prose-style quiet summaries.

## Local Verification

- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.util.ProgressLoggerTest" --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest" --tests "com.grab.grazel.gradle.dependencies.WorkspaceTargetTagPlanCollectorTest" --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --console=plain --no-daemon`
  - Passed.
- `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`
  - Passed.
- `./gradlew migrateToBazel --console=plain --no-daemon`
  - Passed.
  - Visible new summaries included:
    - `Scanned 1 KSP processors across 3 roots`
    - `Collected declared dependency metadata mode=PROJECT_TASK_FANOUT ...`
    - `Resolved 45 deps across 54 roots ...`
    - `Collected target tags for 32 targets ...`
    - `Analyzed variant compression for 2 projects ...`
    - `Collected target references across 10 modules ...`
- `git diff --name-only -- '*.bazel' 'WORKSPACE' 'maven_install.json' 'maven_install_*.json'`
  - Empty output; generated Bazel output unchanged.
- `git diff --check`
  - Passed.
- `reports/scripts/verify-default-task-graph.sh`
  - Passed.
- `reports/scripts/verify-pax-size-guard.sh --mode preserving`
  - Passed with unchanged baseline counts: bucketCount 11, pinfileCount 11,
    totalArtifactRoots 1945.
- `reports/scripts/verify-sample-bucket-labels.sh`
  - Failed only on the documented pre-existing one-sided
    appcompat/constraintlayout exclude assertion.
- Simplify/adversarial review:
  - Applied efficiency findings for KSP streaming and target-reference count
    handling.
  - Applied correctness/altitude findings for worker-thread progress emission,
    missing thread-safety coverage, mutable tag-plan service test seam, and
    declared-metadata summary wording.
  - Rejected default `ProgressReporter.NoOp` production API suggestions because
    Item35 explicitly requires reporters to be passed rather than silently
    defaulted.

## PAX Verification

- `/Users/arun.sampathkumar/work/pax-android`
  - Branch: `arun/grazel-refactor`
  - Commit: `cfa1057ed58c`
  - Dirty baseline after run remained the accepted files only:
    `Constants.kt`, `Grazel.kt`, `ModuleLoggerTask.kt`,
    `generated/dependency_graph.json`, and untracked `Buildifier.kt`.
- `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
  - Initial Item35 run passed in `11m 9s`.
  - Final post-review run passed in `10m 47s`.
  - Visible new summaries included:
    - `Collected declared dependency metadata for 2327 projects across 2327 shards in 536ms (35247531 bytes, mode PROJECT_TASK_FANOUT)`
    - `Resolved 496 deps across 2451 roots in 20866ms`
    - `Collected target tags for 17090 targets in 16159ms`
    - `Analyzed variant compression for 2096 projects in 45189ms`
    - `Collected target references across 2327 modules in 33708ms`
- `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
  - Initial Item35 run passed in `255.947s`.
  - Final post-review run passed in `227.480s`.
- `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test`
  - Initial Item35 run passed in `20.096s`; 3/3 test targets passed.
  - Final post-review run passed in `16.418s`; 3/3 test targets passed.
- `git diff --check`
  - Passed after final PAX migrate.
- `reports/scripts/verify-pax-size-guard.sh --mode preserving`
  - Passed after final PAX migrate: bucketCount 11, pinfileCount 11,
    totalArtifactRoots 1945, no per-repo deltas.
