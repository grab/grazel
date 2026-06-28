# Item 31 - Declared Metadata Fanout Default Decision

## 2026-06-29 Start

- Starting commit: `4c2eeb1` (`refactor: add declared metadata aggregation modes`).
- Decision evidence from Item 29:
  - Full PAX `SINGLE_TASK` migrate passed with aggregate `35247531` bytes.
  - Full PAX `PROJECT_TASK_FANOUT` migrate passed and produced the exact same aggregate bytes and
    SHA-256 (`81b33d01d3ead2fe4c55fa8a1f4d6214299619e3cbe26b55c7e17a8790c927c5`).
  - PAX generated output stayed at the accepted baseline in both modes.
  - PAX size guard stayed unchanged: `bucketCount=11`, `pinfileCount=11`,
    `totalArtifactRoots=1945`.
  - PAX default `migrateToBazel` after the final Item 29 correction passed in `9m 36s`;
    declared metadata single-task action time was `11351 ms`.
  - PAX fanout `migrateToBazel` after the final Item 29 correction passed in `12m 6s`;
    merge action time was `622 ms`.
  - Fanout shard tasks are intentionally untracked due to live Gradle/AGP late declaration timing;
    fanout merge remains cacheable because it consumes only shard JSON files.
  - Focused functional no-op evidence from Item 29: fanout shard tasks execute, while merge and
    downstream workspace tasks become up-to-date when shard file contents are unchanged.
- Decision: make `PROJECT_TASK_FANOUT` the default. `SINGLE_TASK` remains as an explicit
  compatibility/control override.

## Planned Change

- Change `ExperimentsExtension.declaredDependencyMetadataAggregationMode` convention to
  `PROJECT_TASK_FANOUT`.
- Update the extension default test.
- Add/keep explicit override coverage for both modes.
- Run focused tests, local `migrateToBazel`, task graph checks, PAX default migrate, PAX APK build,
  PAX size guard, and diff checks.

## 2026-06-29 Verification

- Implemented the default flip in `ExperimentsExtension` and updated extension tests:
  - default now asserts `PROJECT_TASK_FANOUT`;
  - explicit `PROJECT_TASK_FANOUT` override remains covered;
  - explicit `SINGLE_TASK` compatibility override is covered.
- Focused test passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.extension.ExperimentsExtensionTest" --console=plain --no-daemon`.
- Local `./gradlew migrateToBazel --console=plain --no-daemon` passed in `10s`.
  `mergeDeclaredDependencyMetadata` confirmed the new default path:
  `mode=PROJECT_TASK_FANOUT projects=10 shards=10 aggregateJsonBytes=145401 elapsedMs=16`.
- `reports/scripts/verify-default-task-graph.sh` initially failed because it still expected the
  old default `:collectDeclaredDependencyMetadata` task. Root cause: Item 31 intentionally changes
  the default task shape. Updated the script to fail if the single-task aggregate is scheduled and
  to require representative per-project shard tasks plus `:mergeDeclaredDependencyMetadata`.
  The updated script passed.
- Additional Grazel checks:
  - `reports/scripts/verify-json-phase-inventory.sh` passed.
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed.
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed in `38s`.
  - `git diff --check` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails only on the known pre-existing
    one-sided appcompat exclude waiver:
    `WORKSPACE must not union one-sided appcompat exclude onto androidx.constraintlayout:constraintlayout`.
- PAX default fanout gate:
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks` passed in
    `12m 11s`.
  - `mergeDeclaredDependencyMetadata` logged
    `mode=PROJECT_TASK_FANOUT projects=2327 shards=2327 aggregateJsonBytes=35247531 elapsedMs=1044`.
  - PAX size guard remained unchanged: `bucketCount=11`, `pinfileCount=11`,
    `totalArtifactRoots=1945`, no per-repo deltas.
  - PAX `git diff --check` passed, and PAX status stayed at the accepted local baseline dirty set.
  - PAX APK build passed:
    `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
    completed successfully in `214.420s`.
- Operational note: disk was tight (`10-11 GiB` free) but stable during the PAX build. The existing
  Bazel output base let the build complete incrementally, so no cache cleanup was performed.
- Provider API note for later: provider-backed late reads remain useful when they produce stable
  serialized/file-backed task inputs. Item 29 showed that provider-mapped live Gradle model inputs
  need full PAX task-graph proof before treating them as cacheable; current fanout shard tasks stay
  intentionally untracked and the merge remains cacheable.

## Status

- Item 31 default decision is implemented and verified.
- Ready for a local Grazel checkpoint commit. Do not push. Do not commit PAX.
