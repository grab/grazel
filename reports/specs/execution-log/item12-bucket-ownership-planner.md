# Item 12 Execution Log — Bucket Ownership Planner Extraction

## Current Slice

- Active item: `reports/specs/2026-06-27-item12-extract-bucket-ownership-planner-design.md`
- Start commit: `2d159bd` (`Fail closed on typed dependency cycles`).
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
