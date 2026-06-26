# Item 2 Execution Log - Structured Planning Seam

## Status

- Started from committed Item 1 checkpoint `368a21f`.
- Goal: add `WorkspacePlan` and `WorkspaceRenderPlan` beside existing dependency
  generation paths, prove equivalence where practical, and keep generated output
  behavior unchanged.

## Decisions

- Keep this slice additive. Do not rewire project generation, root generation,
  artifact pinning, or tag-producing extractors in Item 2.
- Use pure builders for model decisions; Gradle tasks should only wire inputs,
  outputs, and service publication.
- Split notes by item. Do not paste long legacy logs into this file.
- Correction after bounded subagent audits:
  `WorkspaceDependencies` alone is not sufficient to populate exact target tags.
  Existing tags need target self names, direct project labels, and Android library
  direct-project Maven closure padding. Some direct labels also depend on
  post-compression suffixes. Do not pretend a pre-compression `WorkspacePlan` can
  own exact rendered tags without a target-level input seam.
- Exact `WorkspaceRenderPlan.materializedRepoNames` should be derived from the
  in-memory `BazelTarget` model produced by project target builders, not from
  generated `BUILD.bazel` / `WORKSPACE` text. This keeps the decision at model
  altitude while preserving exact parity with current target rendering.
- `DefaultVariantCompressionService.referencedMavenRepos()` is not enough for exact
  render materialization because it only sees compressed Android library deps; it
  omits plugins, tags, Kotlin targets, binary/test targets, and aggregated repos.

## Progress

- Read Item 2 spec in bounded chunks.
- Spawned a read-only explorer to map current seams, tasks, data models, and tests.
- Added serializable `WorkspacePlan` and `WorkspaceRenderPlan` models.
- Added pure `WorkspacePlanBuilder`:
  - uses existing selected Maven root-artifact projection;
  - preserves configured and resolved override-target decisions;
  - records per-variant provenance for each candidate repo.
- Added pure `WorkspaceRenderPlanBuilder`:
  - supports exact materialization from supplied referenced repo names;
  - includes override-target repo closure;
  - keeps aggregated repos such as `ksp_maven` always materialized when non-empty.
- Added `WorkspacePlanService`, `ComputeWorkspacePlanTask`, and
  `FinalizeWorkspacePlanTask`.
- Added `CollectTargetMavenRepoReferencesTask` after compression and before finalizing
  the render plan. It collects Maven repos from in-memory `BazelTarget` models and writes
  `build/grazel/target-maven-repo-references.json`; it does not parse generated
  `BUILD.bazel` or `WORKSPACE`.
- Wired task order:
  `ComputeWorkspaceDependencies -> ComputeWorkspacePlan -> AnalyzeVariantCompression
  -> CollectTargetMavenRepoReferences -> FinalizeWorkspacePlan
  -> GenerateBazelScripts/GenerateRootBazelScripts`.
- Shared Maven override-target calculation between the new plan builder and the
  current `MavenInstallArtifactsCalculator` so the two paths cannot drift there.

## Verification

- Red check:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.WorkspacePlanBuilderTest" --console=plain`
  failed on missing `WorkspacePlanBuilder` / `WorkspaceRenderPlanBuilder`, as expected.
- Red check:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest" --console=plain`
  failed on missing `WorkspacePlanService`, `ComputeWorkspacePlanTask`, and
  `FinalizeWorkspacePlanTask`, as expected.
- Green check:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.WorkspacePlanBuilderTest" --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest" --console=plain`
  passed.
- Red/green correction:
  changed `WorkspaceRenderPlanBuilder` so empty target refs no longer materialize every
  candidate repo. The red test failed on the broad fallback; the green test now expects
  only explicitly referenced plus always-materialized repos.
- Red/green correction:
  `FinalizeWorkspacePlanTask` now reads `target-maven-repo-references.json` as an input.
  The red test failed on missing `targetMavenRepoReferences`; the green test verifies a
  referenced `debug_maven` repo does not accidentally pull in an unreferenced `free_maven`.
- Existing renderer guard:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.migrate.dependencies.MavenInstallArtifactsCalculatorTest" --console=plain`
  passed.
- Local structural checks:
  `reports/scripts/verify-default-task-graph.sh` passed.
  `reports/scripts/verify-sample-bucket-labels.sh` passed.
- Local golden guard:
  `./gradlew verifyGrazelGoldenBaseline --console=plain` passed with generated-file
  diff clean.
- Generated during local migration:
  `build/grazel/workspace-plan.json` and
  `build/grazel/workspace-render-plan.json`.
- Generated during local migration after collector correction:
  `build/grazel/target-maven-repo-references.json` contained
  `android_test_maven`, `debug_maven`, `maven`, and `test_maven`;
  `workspace-render-plan.json` materialized those plus always-materialized
  `ksp_maven` and `lint_maven`.
- Grazel `git diff --check` passed after collector correction.
- PAX migration gate:
  `cd /Users/arun.sampathkumar/work/pax-android && ./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`
  passed in 12m 2s.
  Generated `build/grazel/workspace-plan.json` and
  `build/grazel/workspace-render-plan.json` in PAX.
- PAX migration gate after exact target-reference collector correction:
  `cd /Users/arun.sampathkumar/work/pax-android && ./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`
  passed in 14m 5s.
  The run exercised
  `resolveWorkspaceDependencies -> computeWorkspaceDependencies -> computeWorkspacePlan
  -> analyzeVariantCompression -> collectTargetMavenRepoReferences
  -> finalizeWorkspacePlan`.
  PAX `git diff --check` passed.
- PAX render-plan metrics after collector correction:
  `build/grazel/workspace-render-plan.json` materialized 12 repos:
  `android_test_maven`, `debug_maven`, `gps_maven`, `gps_moveit_debug_maven`,
  `gps_ovo_debug_maven`, `gps_pax_debug_maven`, `hms_maven`,
  `hms_pax_debug_maven`, `ksp_maven`, `lint_maven`, `maven`, `test_maven`.
  `build/grazel/target-maven-repo-references.json` referenced 11 repos; `ksp_maven`
  remains always-materialized sidecar.
- PAX size metrics after collector correction versus PAX `HEAD`:
  `WORKSPACE` is 3760 lines, 238 lines smaller than baseline.
  Maven install artifact-count deltas:
  `android_test_maven` +279, `test_maven` +245, `lint_maven` +61,
  `debug_maven` +31, `gps_maven` +21, `hms_maven` -1,
  product/debug repos -23 each, `ksp_maven` unchanged.
  This keeps correctness unblocked but leaves test/lint pinning size as a real
  follow-up optimization concern.
- PAX Bazel gate:
  `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures`
  passed in 500.185s with 117 total actions.
- PAX Bazel gate after exact target-reference collector correction:
  `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures`
  passed in 455.871s with 8778 total actions
  (`2791` disk-cache hits, `6002` remote-cache hits).
  This is the current correctness baseline for Item 2.
- Diff hygiene:
  PAX `git diff --check` passed.
  Grazel `git diff --check` passed.
- PAX app test discovery:
  `./bazel.sh query "'kind(\".*test rule\", //app:*)'"`
  found only `//app:app-gps-*-debug.lint_test` targets for `//app`.
  First query attempt failed because `./bazel.sh` eval stripped query quotes; rerun
  used wrapper-safe quoting.
- Extra PAX app lint gate:
  `./bazel.sh test //app:app-gps-pax-debug.lint_test`
  failed after 867.370s. Failure mode was lint findings, not dependency
  compilation/linkage:
  `SerializedNameDefaultValue` errors in existing external artifacts including
  `com_grab_geo_mapcomponent`, `com_grab_geo_kampung_map_kampungmap_sdk`,
  `com_grab_mapsdk_mapsdk_legacy_common`, and `com.grab.logger:logsdk`.
  Treat as requiring baseline comparison before attributing to this refactor.

## Risks / Follow-Ups

- `tagPlan` model exists but is not populated yet. This is intentional for the
  additive seam until Item 3 moves extractor tag decisions to plan-owned inputs.
- `CollectTargetMavenRepoReferencesTask` is currently `@UntrackedTask` because target
  model inputs are not fully declared. This matches generation task altitude today, but
  it is not yet the final cacheable shape.
- `CollectTargetMavenRepoReferencesTask` duplicates target-model construction before
  project generation. This is acceptable as a correctness seam but should be watched on
  PAX timing and optimized/reused in later consumer cutover.
- `collectTargetMavenRepoReferences` currently emits repeated "No compression result"
  fallback messages while scanning target models in PAX. This is noisy but not a
  correctness failure; de-noise or memoize before final cleanup.
