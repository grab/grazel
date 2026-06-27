# Item 12 Execution Log — Bucket Ownership Planner Extraction

## Current Slice

- Active item: `reports/specs/2026-06-27-item12-extract-bucket-ownership-planner-design.md`
- Start commit: `2d159bd` (`Fail closed on typed dependency cycles`).
- Resume commit for implementation: `f667db5d237b7d8866cc705acd90b1436813c591`
  (`Record bucket ownership planner handoff`); worktree was clean before code changes.
- Goal: move the existing ownership/placement result assembly out of
  `AggregatedDependencyResolver.ResolutionSession` into a first-class Layer-3
  `BucketOwnershipPlanner` without changing behavior.
- Behavior expectation: preserving, golden empty-diff, PAX size guard unchanged.

## Decisions

- Do not change the ownership algorithm in this item. Item 13 owns test/androidTest delta
  improvements.
- Keep Layer 2 value resolution in `AggregatedDependencyResolver`: root closure collection,
  Gradle result traversal, declared metadata closure expansion, and immutable handoff inputs.
- Move only the cross-project ownership/result assembly layer into a pure planner that
  consumes maps and declared metadata snapshots.
- Use the temporary parity mode `-Pgrazel.internal.parity=ownership` only while proving the
  relocation. Remove the old path and parity mode before completing the item.

## Planned Work

1. Audit the exact ownership methods and session fields from the Item 12 spec against current
   code; use focused subagents for read-heavy boundary review if helpful.
2. Add planner-focused tests first, including at least one parity/empty-diff preserving case.
3. Introduce `OwnershipPlannerInput` and `BucketOwnershipPlanner` with the current algorithm
   moved as-is.
4. Wire resolver handoff to the planner, initially with parity support.
5. Run local focused tests, full plugin tests, local `migrateToBazel`, task/diff guards, then
   PAX migrate/build/test/size guard before removing parity.

## Verification To Record

- Focused planner/resolver tests.
- `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`.
- `./gradlew migrateToBazel --console=plain --no-daemon`.
- `reports/scripts/verify-default-task-graph.sh`.
- `reports/scripts/verify-sample-bucket-labels.sh` with known waiver if unchanged.
- `git diff --check` and `git diff --check master...HEAD`.
- PAX migrate, debug APK + android-test APK build, focused PAX unit tests, PAX `git diff
  --check`, and `reports/scripts/verify-pax-size-guard.sh --mode preserving`.

## Current Risk

- `AggregatedDependencyResolver` is large and has ownership/value code interleaved by fields.
  The main risk is accidentally moving value-phase mutation (`addDeclaredMetadataClosures`)
  into the planner. The spec says that must stay in Layer 2 and complete before building the
  immutable planner input.

## 2026-06-28 Resume Notes

- Re-read `CURRENT-GOAL-ANCHOR.md`, `ALTITUDE-LAYERING-ROADMAP.md`, and this Item 12 spec
  before code changes.
- Spawned focused read-only subagents to re-check the extraction boundary and test seam
  against the current worktree. Parent will spot-check and reconcile before implementation.
- Decision for this slice: start with planner unit tests and keep output-changing bucketing
  work out of Item 12. Any generated output drift is a stop-and-investigate event.

## 2026-06-28 Planner Extraction Progress

- TDD red:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon --tests
  "com.grab.grazel.gradle.dependencies.BucketOwnershipPlannerTest"` failed first because
  `BucketOwnershipPlanner` and `OwnershipPlannerInput` did not exist. A test import typo was
  fixed before accepting the red state.
- Added `BucketOwnershipPlannerTest` as a pure Layer-3 unit seam. The tests use sealed maps,
  declared metadata fixtures, and no Gradle fakes/reflection.
- Added `BucketOwnershipPlanner` and `OwnershipPlannerInput`. The planner owns current
  cross-project main bucket merging, declared metadata folding, test/androidTest subtraction,
  lint result emission, KSP-on-default result assembly, and result ordering.
- `AggregatedDependencyResolver` now stops after value collection and declared metadata
  closure expansion, snapshots the mutable maps, and hands an `OwnershipPlannerInput` to the
  planner. `addDeclaredMetadataClosures()` remains entirely in Layer 2 before the handoff.
- Removed the old in-session `planMainBuckets` / `planTestBuckets` / `buildResults` ownership
  path and the resolver-scoped `unionDependencyMaps`; `unionDependencyMaps` is now a shared
  package utility.
- No persistent `-Pgrazel.internal.parity=ownership` mode was added. The old path has been
  removed, so Item 12 proof now rests on focused planner/resolver tests plus exact generated
  output and PAX size/build gates. If those gates show drift, the extraction must be fixed
  rather than hidden behind a parity flag.
- Focused verification passed:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon --tests
  "com.grab.grazel.gradle.dependencies.BucketOwnershipPlannerTest" --tests
  "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --tests
  "com.grab.grazel.gradle.dependencies.DependencyBucketPlacementEngineTest"`.
- Broader local verification:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed in 43s.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed in 16s.
  - Local generated BUILD/WORKSPACE/json diff check had no output after `migrateToBazel`.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails on the known pre-existing
    one-sided appcompat/constraintlayout exclude-union waiver; not caused by Item 12.
  - Grazel `git diff --check` and `git diff --check master...HEAD` passed.
- PAX verification on `/Users/arun.sampathkumar/work/pax-android` branch
  `arun/grazel-refactor` at `05d2b4801530`:
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
    passed in 11m31s.
  - PAX `git diff --check` passed.
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed: 11 buckets,
    11 pinfiles, 2015 total artifact roots, no per-repo deltas.
  - `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed in 221.665s.
  - `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test
    //app-test:app-test-gps-pax-debug-test
    //application-initializer:application-initializer-gps-pax-debug-test` passed with 3/3
    tests in 18.774s.
- Resource note: before PAX build free space was about 30 GiB; after verification it was
  about 31 GiB. No cleanup was required.
