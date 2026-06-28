# Item 29 - Declared Metadata Aggregation Modes

## 2026-06-29 Progress

- Added `DeclaredDependencyMetadataAggregationMode` experiment with default `SINGLE_TASK` and
  alternate `PROJECT_TASK_FANOUT`.
- Removed the old `declaredDependencyMetadataJson: Property<String>` and
  `dependencyDeclarationFiles` / `dependencyDeclarationFileTree` task shape.
- `SINGLE_TASK` is now explicitly untracked because it reads evaluated Gradle/AGP model objects.
  It writes the same aggregate `build/grazel/declared-dependency-metadata.json` contract.
- `PROJECT_TASK_FANOUT` registers untracked per-project shard tasks and a cacheable
  `mergeDeclaredDependencyMetadata` task. The merge task writes the same aggregate file contract.
- Extracted `DeclaredProjectMetadataSnapshotter` and `DeclaredDependencyMetadataMerger` so both
  modes share the semantic snapshot/merge implementation.
- Provider API note: provider-based late reads are desirable, but PAX parity proved that
  provider-mapped live Gradle model reads are not a safe cacheable shard input boundary for this
  item. Full `migrateToBazel` can realize/fingerprint those inputs earlier than direct fanout
  tasks, so fanout shards now snapshot live model objects only inside `@TaskAction`.
- Maintainer reminder: keep Provider API as the preferred tool for future late Gradle reads where
  it is safe. Use it to delay reads or produce serializable/file-backed task inputs, but guard
  against repeated live-model realization by memoizing or materializing once before fanout.

## Local Timing Evidence

- Root sample `SINGLE_TASK`: 10 projects, 145281-byte aggregate, about 482 ms in task action on one
  measured run.
- Root sample `PROJECT_TASK_FANOUT`: 10 shard tasks, 145281-byte aggregate, merge about 18 ms after
  shard tasks; byte-identical aggregate hash to `SINGLE_TASK`.
- Functional fixture `SINGLE_TASK`: 9 projects, 84859-byte aggregate, first run about 179 ms, second
  run about 53 ms; downstream `computeWorkspaceDependencies` remained `UP-TO-DATE`.
- Functional fixture `PROJECT_TASK_FANOUT`: 9 shard tasks, 84859-byte aggregate, merge about 14 ms
  in the cacheable-shard attempt. After PAX timing correction, shard tasks are intentionally
  untracked; update this timing after the next focused fanout verification run.

## Focused Verification

- `reports/scripts/verify-json-phase-inventory.sh` passed after updating the inventory with the
  new file-backed declared metadata JSON reads/writes.
- Focused unit tests passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.extension.ExperimentsExtensionTest" --tests "com.grab.grazel.tasks.internal.CollectDeclaredDependencyMetadataTaskTest" --tests "com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadataMergerTest" --console=plain --no-daemon`.
- Focused functional fanout test passed:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.projectTaskFanoutDeclaredMetadataModeProducesStableWorkspaceDependencies" --console=plain --no-daemon`.
- Focused functional default up-to-date test passed:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesIsUpToDateWithoutInputChanges" --console=plain --no-daemon`.

## Remaining Item 29 Gates

- Full plugin tests and root `migrateToBazel` passed.
- Task graph and PAX size guard scripts passed. Sample bucket label verifier still has the known
  pre-existing one-sided exclude waiver.
- PAX default-mode migrate passed:
  `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
  completed successfully in 11m 6s.
  `SINGLE_TASK` timing line: 2327 projects, 35247531-byte aggregate, 11524 ms task action.
  PAX status remained at the accepted local baseline dirty set, and PAX `git diff --check` passed.
- Remaining: run PAX `PROJECT_TASK_FANOUT` parity, revert the temporary PAX experiment edit, then
  run the PAX APK build gate if generated output stays unchanged.

## 2026-06-29 Fanout Parity Fix

- PAX default and fanout both produced unchanged generated output, but their aggregate declared
  metadata JSON differed. Saved aggregate comparison showed different variant lists for many
  projects; the issue was task-boundary timing, not resolved dependency values.
- Root cause: `PROJECT_TASK_FANOUT` copied `variantsByProject` into task inputs at
  `projectsEvaluated`, while `SINGLE_TASK` kept a reference to the mutable callback map and read it
  later during task action. If AGP/variant callbacks appended entries between those moments, the two
  modes observed different variant universes.
- Fix: added `DeclaredProjectMetadataPlanner` and `DeclaredProjectMetadataSource`. The registrar now
  builds one sorted immutable project/variant source and wires it to both modes. Single-task still
  snapshots dependency metadata in its task action; fanout snapshots into typed shard inputs from
  the same source.
- Regression coverage:
  - `DeclaredDependencyMetadataMergerTest.project metadata plan freezes variant callback collections`
    first failed because the planner did not exist, then passed.
  - `BuildVariantTest.declaredMetadataAggregationModesProduceSameAggregateJson` proves the sample
    fixture aggregate declared metadata is byte-identical between `SINGLE_TASK` and
    `PROJECT_TASK_FANOUT`.
- A separate attempted cleanup making `VariantBuilder.onVariants()` delegate to `build()` was
  rejected for this item: it caused generated sample `BUILD.bazel`/`WORKSPACE` drift by dropping
  current synthetic hierarchy nodes. That cleanup is a separate architecture question, not an
  Item 29 preserving-mode fix.
- Focused verification passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.extension.ExperimentsExtensionTest" --tests "com.grab.grazel.tasks.internal.CollectDeclaredDependencyMetadataTaskTest" --tests "com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadataMergerTest" --tests "com.grab.grazel.gradle.variant.DefaultVariantBuilderTest" --console=plain --no-daemon`.
  Functional verification passed:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesIsUpToDateWithoutInputChanges" --tests "com.grab.grazel.migrate.BuildVariantTest.projectTaskFanoutDeclaredMetadataModeProducesStableWorkspaceDependencies" --tests "com.grab.grazel.migrate.BuildVariantTest.declaredMetadataAggregationModesProduceSameAggregateJson" --console=plain --no-daemon`.
- Local `./gradlew migrateToBazel --console=plain --no-daemon` and
  `reports/scripts/verify-json-phase-inventory.sh` passed. The transient generated-output drift
  from the rejected variant-builder attempt was removed; current worktree generated output is
  source/docs only.
- Remaining: rerun PAX default/fanout aggregate parity after the planner fix, then continue to the
  PAX APK build gate if generated output remains baseline-only.

## 2026-06-29 PAX Provider-Timing Fix

- A second PAX parity run after the planner fix still failed aggregate parity:
  `SINGLE_TASK` aggregate `35247531` bytes, SHA-256
  `81b33d01d3ead2fe4c55fa8a1f4d6214299619e3cbe26b55c7e17a8790c927c5`;
  `PROJECT_TASK_FANOUT` aggregate `34839457` bytes, SHA-256
  `75f3083ee0f4d224641af17e68dc73cbacffea4808cc445e75dc8939ea100c2f`.
- Structural comparison showed all 2327 projects existed in both modes, but 1167 projects differed.
  Fanout was missing late default-bucket declarations such as `api kotlin-stdlib`,
  `androidx.annotation`, databinding artifacts, and some plugin-added implementation deps.
- Root cause: fanout shard registration eagerly called `DeclaredProjectMetadataSnapshotter.snapshot`
  during Gradle configuration. Single-task snapshots in `@TaskAction`, after PAX/AGP plugins finish
  mutating declarations. The eager fanout path therefore missed late-added dependencies.
- Fix: `CollectProjectDeclaredDependencyMetadataTask.register` now wires shard typed inputs from a
  memoized provider. The provider reads the live project/variant model late, materializes one
  `ProjectDeclaredDependencyMetadata` per shard, and then maps it into typed task inputs. JSON still
  crosses only as shard output files and merge input files.
- Regression test added:
  `CollectDeclaredDependencyMetadataTaskTest.fanout shard input snapshots declared metadata after task registration`.
- Verification after this fix:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.tasks.internal.CollectDeclaredDependencyMetadataTaskTest" --console=plain --no-daemon`
  passed.
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.declaredMetadataAggregationModesProduceSameAggregateJson" --tests "com.grab.grazel.migrate.BuildVariantTest.projectTaskFanoutDeclaredMetadataModeProducesStableWorkspaceDependencies" --console=plain --no-daemon`
  passed.
- Remaining: rerun PAX fanout aggregate parity; do not proceed to Item 31 until the saved
  single-task and fanout aggregate JSONs match byte-for-byte and generated output remains at the
  accepted PAX baseline.

## 2026-06-29 PAX Task-Graph Timing Correction

- PAX fanout full `migrateToBazel` after the provider-timing fix passed in `11m 42s`, but aggregate
  parity still failed:
  - stale saved single aggregate: `35247531` bytes,
    SHA-256 `81b33d01d3ead2fe4c55fa8a1f4d6214299619e3cbe26b55c7e17a8790c927c5`;
  - full-migrate fanout aggregate: `34839727` bytes,
    SHA-256 `b3ba6b39115acbcb4ace206ba32fc0b0e3b303a717231389f8786d3ca2b79e47`.
- A fresh direct PAX `collectDeclaredDependencyMetadata` on the same checkout produced
  `37082273` bytes, SHA-256
  `3b837d08a6055e363359bd7b0ca21ccfcbe013dc528a457efa0df6df74b5a5df`, in `10031 ms`
  task-action time.
- A fresh direct PAX `mergeDeclaredDependencyMetadata --rerun-tasks` produced the exact same
  aggregate (`37082273` bytes, same SHA-256) with merge time `549 ms` and wall time `3m 1s`.
- Interpretation: provider-mapped typed shard inputs can be realized/fingerprinted at different
  points under the full `migrateToBazel` task graph versus the direct fanout task graph. That makes
  the cacheable shard-input contract semantically unstable for late PAX/AGP dependency mutations,
  even though the shared snapshotter/merger logic is correct.
- Decision: change `CollectProjectDeclaredDependencyMetadataTask` to match the single-task safety
  boundary: `@UntrackedTask`, action-time snapshot of the assigned project/variant source, stable
  shard JSON output. Keep `MergeDeclaredDependencyMetadataTask` cacheable because it consumes only
  shard files.
- Regression coverage updated:
  `CollectDeclaredDependencyMetadataTaskTest.fanout project shard task has no build-file or json-string payload inputs`
  now asserts shard tasks are explicitly untracked, and
  `fanout shard input snapshots declared metadata after task registration` still proves late
  declarations are observed.
- Focused verification after the correction:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.tasks.internal.CollectDeclaredDependencyMetadataTaskTest" --console=plain --no-daemon`
  passed.
- Remaining: rerun PAX direct single/fanout if needed, then full PAX fanout `migrateToBazel` and
  compare against the fresh single aggregate/generation baseline before Item 31.

## 2026-06-29 Full PAX Fanout Verification

- Full PAX fanout command:
  `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
  with temporary `declaredDependencyMetadataAggregationMode=PROJECT_TASK_FANOUT`.
- Result: passed in `12m 6s`; `mergeDeclaredDependencyMetadata` logged
  `projects=2327 shards=2327 aggregateJsonBytes=35247531 elapsedMs=622`.
- Saved aggregate:
  `/tmp/pax-fanout-declared-dependency-metadata-after-untracked-full-migrate.json`.
  It matched the earlier full-migrate single-task aggregate exactly:
  - bytes: `35247531` vs `35247531`;
  - SHA-256:
    `81b33d01d3ead2fe4c55fa8a1f4d6214299619e3cbe26b55c7e17a8790c927c5` for both;
  - `cmp -s` passed.
- The larger direct-task aggregate
  `/tmp/pax-single-declared-dependency-metadata-current-item29.json` remains
  `37082273` bytes. This is not the generation-path oracle because direct metadata tasks and full
  `migrateToBazel` realize different task graph scopes in PAX. Keep future comparisons scoped to
  like-for-like task graphs.
- PAX `git diff --check` passed. The temporary PAX `build.gradle` fanout mode toggle was reverted;
  PAX status is back to the accepted local baseline files only.
- Provider API note for later: providers are still the right Gradle tool for deferring reads, but
  provider-mapped live Gradle model task inputs must be proven under full PAX task graph timing
  before being treated as cacheable. For this item, action-time untracked shard snapshots are the
  safer boundary.

## 2026-06-29 Final Item 29 Gates

- Grazel:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed in `45s`.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed in `10s`.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-json-phase-inventory.sh` passed after refreshing this item's JSON
    inventory line numbers.
  - `reports/scripts/verify-sample-bucket-labels.sh` still reports the known pre-existing
    appcompat/constraintlayout one-sided exclude waiver.
  - `git diff --check` passed.
- PAX default mode:
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks` passed in
    `9m 36s`.
  - `collectDeclaredDependencyMetadata` logged `mode=SINGLE_TASK projects=2327
    aggregateJsonBytes=35247531 elapsedMs=11351`.
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed with unchanged totals:
    `bucketCount=11`, `pinfileCount=11`, `totalArtifactRoots=1945`.
  - `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed in `243.304s`.
  - PAX `git diff --check` passed, and status remained the accepted local baseline dirty set only.
- Item 29 known tradeoff:
  - `PROJECT_TASK_FANOUT` improves scheduling shape and has a cacheable merge, but per-project
    shard tasks are intentionally untracked because PAX/AGP late declaration mutations made
    cacheable live-model shard inputs unsafe in the full migrate graph.
