# Item 30 - Workspace Resolution Input Boundary Execution Log

## 2026-06-29 Start

- Grazel branch: `arun/dependencies-refactor`.
- Starting commit: `47fe3e7d2b79a0c9860037487e52cf16f677c6ec`.
- PAX regression workspace: `/Users/arun.sampathkumar/work/pax-android`, branch
  `arun/grazel-refactor`, commit `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`.
- PAX worktree is currently dirty with maintainer/local baseline changes. Do not commit or push
  PAX.
- Goal: remove JSON model payloads from workspace-root task wiring, pass root metadata through
  Gradle file inputs/outputs, keep master-like `ResolvedComponentResult` task inputs, inventory all
  production JSON encode/decode sites, and keep generated output empty-diff.
- Initial decision: `ResolvedComponentResult` remains a cacheable Gradle task input by design.
  This item targets metadata transport and phase boundaries, not root-component replacement.

## 2026-06-29 Implementation Checkpoint

- Added `CollectWorkspaceDependencyRootMetadataTask` as a cacheable file-producing task for
  workspace root metadata. `ResolveWorkspaceDependenciesTask` now consumes
  `workspaceDependencyRootMetadata` as an `@InputFile`; `workspaceDependencyRootComponents` remains
  the master-like `@Input ListProperty<ResolvedComponentResult>`.
- `WorkspaceDependencyInputsRegistrar` now plans root inputs once, wires root components to the
  resolver, and wires provider-backed managed metadata inputs to the metadata task. It no longer
  imports `Json` or stores `workspaceDependencyRootMetadataJsons`.
- First implementation attempted `ListProperty<AggregatedDependencyRootMetadata>` under `@Input`.
  Gradle failed to fingerprint it in `collectWorkspaceDependencyRootMetadata`. Root cause:
  arbitrary custom DTO lists are not Gradle input-snapshot friendly. Fixed by adding
  `WorkspaceDependencyRootMetadataInput`, a managed nested Gradle input model using scalar/list/set
  properties and encoded exclude-rule strings, then converting back to
  `AggregatedDependencyRootMetadata` only inside the metadata task action.
- KSP sidecar was also tightened because Item 30 called out absolute-path strings: replaced
  `kspArtifactMapping: MapProperty<String, String>` with nested `KspArtifactInput` entries carrying
  `shortId` plus `RegularFileProperty`. `KspProcessorClassExtractor` now receives
  `Map<String, File>` rather than path strings.
- Added `reports/specs/execution-log/item30-json-phase-inventory.tsv` and
  `reports/scripts/verify-json-phase-inventory.sh`. The script fails on missing/stale production
  JSON encode/decode inventory rows. It was manually checked against a temporary missing-row copy
  and failed closed as expected.
- Known remaining JSON payload site is explicitly inventoried as owned by Item 29:
  `CollectDeclaredDependencyMetadataTask.declaredDependencyMetadataJson`.

### Verification So Far

- Red checks observed:
  - focused task-shape tests failed on missing `CollectWorkspaceDependencyRootMetadataTask`;
  - KSP task-shape test failed on missing `kspArtifacts`;
  - `collectWorkspaceDependencyRootMetadata` failed before the managed-input rewrite because Gradle
    could not serialize `AggregatedDependencyRootMetadata` list inputs.
- Passing checks:
  - focused task/extractor tests for resolver/compute/KSP/extractor passed;
  - `collectWorkspaceDependencyRootMetadata` passed and was `UP-TO-DATE` on rerun;
  - `collectKspProcessorDependencies` passed and was `UP-TO-DATE` on rerun;
  - `reports/scripts/verify-json-phase-inventory.sh` passed;
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed;
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed with no generated BUILD/WORKSPACE
    diff;
  - `reports/scripts/verify-default-task-graph.sh` passed;
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails on the documented pre-existing
    one-sided appcompat/constraintlayout exclude waiver;
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed with bucket count `11`,
    pinfile count `11`, total artifact roots `1945`;
  - `git diff --check` and `git diff --check master...HEAD` passed.
- Resource checkpoint before local generation: about `28 GiB` free, `~/.gradle/caches` `19G`,
  PAX `bazel-cache` `14G`, private Bazel root `61G`; no cleanup performed.

### Next

- Item 30 is ready for local commit unless final diff review finds a source-shape issue.

## 2026-06-29 PAX Gate

- PAX resource checkpoint before migrate: about `27 GiB` free. Private Bazel root was `62G`;
  PAX `bazel-cache` was `14G`; no cache deletion performed.
- Stopped stale non-PAX Bazel servers from Grazel/JUnit temp workspaces before the PAX Bazel build.
  Kept the PAX Bazel server warm.
- PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks` passed in
  `12m 6s`. The changed tasks `collectKspProcessorDependencies` and
  `collectWorkspaceDependencyRootMetadata` ran successfully before `resolveWorkspaceDependencies`.
- PAX generated status after migrate stayed at the maintainer/local baseline shape:
  modified `build-logic/project/src/main/kotlin/grazel/Constants.kt`,
  `build-logic/project/src/main/kotlin/grazel/Grazel.kt`,
  `build-logic/project/src/main/kotlin/grazel/task/ModuleLoggerTask.kt`,
  `generated/dependency_graph.json`, and untracked
  `build-logic/project/src/main/kotlin/grazel/task/Buildifier.kt`.
  No PAX commit was made.
- PAX `git diff --check` passed.
- PAX `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
  passed in `216.504s`.
- PAX size guard after build stayed unchanged: bucket count `11`, pinfile count `11`, total artifact
  roots `1945`.
- Resource checkpoint after build: about `24 GiB` free; memory recovered to about `704k` free pages.
