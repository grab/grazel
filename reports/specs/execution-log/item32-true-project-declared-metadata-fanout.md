# Item 32 - True Project Declared Metadata Fanout Log

## 2026-06-29 +08

Active spec: `reports/specs/2026-06-29-item32-true-project-declared-metadata-fanout-design.md`.

Goal: move `PROJECT_TASK_FANOUT` shard tasks from root-flat task registration
to true source-project task registration while preserving aggregate metadata
and generated output.

Baseline from prior Item 28/31 PAX run:

- full PAX `migrateToBazel --rerun-tasks`: `12m 12s`
- `mode=PROJECT_TASK_FANOUT projects=2327 shards=2327 aggregateJsonBytes=35247531 elapsedMs=554`
- root-flat shard tasks were registered on the root project.

Implementation decisions:

- `CollectProjectDeclaredDependencyMetadataTask.register` now registers on the
  source project with fixed task name `collectProjectDeclaredDependencyMetadata`.
- Shard output lives under the source project build directory:
  `build/grazel/declared-dependency-metadata/project.json`.
- `MergeDeclaredDependencyMetadataTask` remains the root aggregate task and
  consumes shard output providers via `RegularFileProperty`.
- Removed root-flat task-name helpers (`toSafeFileName`, `toPascalTaskName`).
- Merge no longer sorts shard files by absolute path before decoding; semantic
  determinism comes from the merged metadata content and merger ordering.
- `reports/scripts/verify-default-task-graph.sh` now requires
  `:sample-android:collectProjectDeclaredDependencyMetadata` and rejects
  `:collectSampleAndroidDeclaredDependencyMetadata`.

Red/green evidence:

- Added unit test
  `CollectDeclaredDependencyMetadataTaskTest.fanout shard task is owned by the source project`.
  The red run failed against the old root-flat path, then passed after moving
  registration to the source project.
- Updated `BuildVariantTest` expectations so default fanout checks source
  project shard tasks, merge up-to-date behavior, and SINGLE_TASK vs
  PROJECT_TASK_FANOUT aggregate JSON parity.

Commands/results:

- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.tasks.internal.CollectDeclaredDependencyMetadataTaskTest" --console=plain --no-daemon`
  passed.
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesDoesNotScheduleLegacyResolveTasksByDefault" --console=plain --no-daemon`
  passed.
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesIsUpToDateWithoutInputChanges" --tests "com.grab.grazel.migrate.BuildVariantTest.projectTaskFanoutDeclaredMetadataModeProducesStableWorkspaceDependencies" --tests "com.grab.grazel.migrate.BuildVariantTest.declaredMetadataAggregationModesProduceSameAggregateJson" --console=plain --no-daemon`
  passed.
- `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed.
- `./gradlew migrateToBazel --console=plain --no-daemon` passed.
  Sample metric from the run:
  `mode=PROJECT_TASK_FANOUT projects=10 shards=10 aggregateJsonBytes=145401 elapsedMs=18`.
- `reports/scripts/verify-default-task-graph.sh` passed.
- `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed:
  11 buckets, 11 pinfiles, 1945 total artifact roots, no per-repo deltas.
- `git diff --check` passed.
- `git diff --check master...HEAD` passed.
- `reports/scripts/verify-sample-bucket-labels.sh` failed on the known
  pre-existing appcompat/constraintlayout exclude-union assertion.

Resource notes:

- Before local Gradle gates, disk was genuinely low (`/System/Volumes/Data`
  around 97%, about 13 GiB free). Used the goal-approved cleanup path:
  `bazelisk shutdown` and `bazelisk clean --expunge` in both Grazel and PAX.
  PAX `bazel-cache` was preserved.
- Free space after cleanup was about 17 GiB; after local migrate about 16 GiB.

Remaining before Item 32 commit:

- Check current diff shape one more time.
- Commit the preserving Item 32 change locally if no unexpected generated diff
  appears.
- PAX full loop may run after Item 33 or before final combined checkpoint unless
  Item 32 diff raises concern.
