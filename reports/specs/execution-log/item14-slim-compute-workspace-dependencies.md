# Item 14 - Slim ComputeWorkspaceDependencies Execution Log

## 2026-06-28 +08 Start

- Start commit: `a625c98c771ed47bbce2bc985bbb4489fd52e5fe`
  (`Record Item 13 checkpoint`).
- Grazel worktree was clean at start.
- Spec: `reports/specs/2026-06-27-item14-slim-compute-workspace-dependencies-design.md`.
- Goal: preserve output while moving ownership/override policy out of
  `ComputeWorkspaceDependencies` where safe.
- Hard constraints:
  - no behavior/output change;
  - PAX size guard must remain at 11 buckets, 11 pinfiles, 1945 total roots;
  - PAX generated diff should be empty relative to the post-Item-13 baseline;
  - CWD must still produce value indices needed by tags and pinning unless a
    replacement seam is proven by parity.
- Subagents dispatched:
  - CWD responsibility/seam audit;
  - Item 14 verification/parity audit.
- Initial local reading: CWD currently performs classpath grouping,
  dedup-vs-default, flattening, final override-carrier synthesis, transitive
  indices, reachable-main index aggregation, and KSP aggregation. The risky
  preserving points are `variantTransitiveClasspath` shape and override target
  labels.

## 2026-06-28 +08 Seam Extraction

- Read-only subagent findings:
  - CWD should retain value/index responsibilities: grouping, version
    arbitration, flattening, transitive indices, reachable-main merge, KSP.
  - Default duplicate reduction and flattened default override-carrier
    synthesis are policy phases that can move if copied exactly.
  - Override-carrier synthesis cannot move only to `WorkspaceRenderPlanBuilder`
    because `DependencyResolutionService` still consumes
    `WorkspaceDependencies.variantDeps` override targets.
- TDD red:
  - Added `DefaultBucketDependencyReducerTest`.
  - Added `DefaultOverrideCarrierPlannerTest`.
  - Ran focused tests and confirmed compile failure on missing helper classes.
- Green implementation:
  - Added `DefaultBucketDependencyReducer`, moving the existing CWD
    dedup-vs-default logic and declared-placeholder guard.
  - Added `DefaultOverrideCarrierPlanner`, moving the existing final flattened
    default coverage and override-carrier synthesis logic.
  - `ComputeWorkspaceDependencies` now delegates those phases and keeps value
    computation.
- Focused verification passed:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon --tests
  "com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependenciesTest"
  --tests "com.grab.grazel.gradle.dependencies.DefaultBucketDependencyReducerTest"
  --tests "com.grab.grazel.gradle.dependencies.DefaultOverrideCarrierPlannerTest"
  --tests "com.grab.grazel.gradle.dependencies.BucketOwnershipPlannerTest"
  --tests "com.grab.grazel.gradle.dependencies.WorkspacePlanBuilderTest"
  --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest"`.

## 2026-06-28 +08 Local Gates

- Full plugin tests passed:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`.
- Local migration passed:
  `./gradlew migrateToBazel --console=plain --no-daemon`.
  Generated outputs remained unchanged.
- Hygiene/script checks:
  - `git diff --check` passed.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `git diff --check master...HEAD` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails only on the
    documented appcompat/constraintlayout one-sided exclude-union waiver.
  - `./gradlew verifyGrazelGoldenBaseline --console=plain --no-daemon` fails
    only because it wraps the same sample-label waiver after successful local
    generation.
- Next: PAX preserving loop. Because PAX intentionally remains dirty from the
  accepted Item 13 generated baseline, first snapshot the PAX diff hash and
  compare it after `migrateToBazel` to detect any Item 14 drift.

## 2026-06-28 +08 PAX Preservation Loop

- PAX pre-migrate accepted-baseline snapshot:
  - repo: `/Users/arun.sampathkumar/work/pax-android`
  - branch/SHA: `arun/grazel-refactor`
    `05d2b4801530726ab722133c2ba32cbba9afeb67`
  - diff hash:
    `5f05c2380375f16b0c04c6fa5f14d3a1666cf94d6b36a5ce1e0814a1b6e43566`
  - status hash:
    `b9b38774443602baa0adf251daeb236e68cd181e1f4ccdf74ee412a30822c6d6`
  - dirty entries: `2231`
- PAX migration passed:
  `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`.
  Result: successful in 11m 39s; pinning skipped as up-to-date.
- PAX generated-output preservation passed after migration:
  - diff hash unchanged:
    `5f05c2380375f16b0c04c6fa5f14d3a1666cf94d6b36a5ce1e0814a1b6e43566`
  - status hash unchanged:
    `b9b38774443602baa0adf251daeb236e68cd181e1f4ccdf74ee412a30822c6d6`
  - dirty entries unchanged: `2231`
  - `git diff --check` passed.
- PAX size guard passed:
  `reports/scripts/verify-pax-size-guard.sh --mode preserving`.
  Counts stayed `11` buckets, `11` pinfiles, `1945` total artifact roots, with
  no per-repo deltas.
- PAX build gate passed:
  `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
  //app:app-gps-pax-debug-android-test.apk`.
  Result: success in 226.264s.
- PAX focused test gate passed:
  `./bazel.sh test --test_output=errors
  //app-utils:app-utils-gps-pax-debug-test
  //app-test:app-test-gps-pax-debug-test
  //application-initializer:application-initializer-gps-pax-debug-test`.
  Result: 3/3 test targets passed in 19.423s.
- Final PAX check:
  - `git diff --check` passed.
  - diff hash/status hash/dirty count still matched accepted baseline after
    build and tests.
- Parity rationale:
  - The spec mentions temporary CWD parity as an option.
  - This slice did not add a runtime parity flag because the old code was not
    retained as a parallel implementation; instead the exact predicates were
    extracted into focused helpers with direct seam tests.
  - Preservation evidence is unchanged local generated output, unchanged PAX
    generated diff hash, unchanged PAX size guard, and passing PAX build/test
    gates.
- Resource notes:
  - Disk remained about 24Gi free on `/System/Volumes/Data`; no cleanup was
    needed.
  - PAX `bazel-cache` was about 14G.
  - No high-RAM `python3.12` process was present.

## Status

- Item 14 implementation and verification are green.
- Remaining before checkpoint commit: final local `git diff --check`, review
  staged diff, then commit Grazel changes only. Do not commit PAX.
