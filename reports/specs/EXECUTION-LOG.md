# Dependency Refactor Execution Log

This is the short continuity pointer for the dependency-refactor goal. Keep detailed
evidence in item-specific logs so context compaction can recover state quickly.

## Active State

- Active item: Item 6 - Simplify, adversarial review, and final verification.
- Item 1 baseline/safety-net checkpoint commit: `368a21f`
  (`Record PAX baseline safety gate`).
- Item 2 structured-planning checkpoint commit: `6393de1`
  (`Add workspace dependency planning seam`).
- Item 3 Step 1 pinner cutover checkpoint commit: `95c1036`
  (`Cut pinner over to workspace plan`).
- Item 3 Step 2 root-generation cutover checkpoint commit: `f5296bd`
  (`Cut root generation over to workspace render plan`).
- Item 3 Step 3 tag-producer cutover checkpoint commit: `8e22c01`
  (`Move target tag planning into workspace plan`).
- Item 4 Step 1 manifest/task-graph decouple checkpoint commit: `e00d404`
  (`Remove generated Maven repo manifests`).
- Item 4 Step 2 pinner regex-discovery deletion checkpoint commit: `a70de87`
  (`Remove pinner workspace repo discovery`).
- Item 4 Step 3 extractor fallback deletion checkpoint commit: `40c6bcd`
  (`Remove extractor Maven tag fallbacks`).
- Item 4 Step 4 parity cleanup checkpoint commit: `72bcc0f`
  (`Remove workspace plan parity flag`).
- Item 5 Step 5a exclude-intersection checkpoint commit: `afa62bc`
  (`Intersect dependency exclude metadata`).
- Item 5 Step 5b variant-provenance checkpoint commit: `e707bf4`
  (`Select Maven roots per variant provenance`).
- Latest passed local gate:
  - Item 6 behavior-preserving `resolve()` extraction focused tests:
    `AggregatedDependencyResolverTest`, `DependencyBucketPlacementEngineTest`,
    `BucketHierarchyGraphTest`, `ComputeWorkspaceDependenciesTest`,
    `WorkspacePlanBuilderTest`, and `MavenInstallArtifactsCalculatorTest`.
  - `./gradlew verifyGrazelGoldenBaseline --console=plain --no-daemon`
  - Grazel `git diff --check`
  - Result: all passed; generated sample diff stayed clean against the Item 5
    baseline.
- Latest passed PAX gate:
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`
  - Result: passed in 11m15s for Item 5b.
  - `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures`
  - Result: passed on retry in 2169.372s after the first attempt failed from
    `No space left on device`.
  - PAX `git diff --check`: passed.
  - Tag-prefix audit: found zero bucket Maven labels inside `tags` arrays.
  - PAX working tree remains dirty from generated output; do not commit PAX changes.
- Current detailed log:
  - `reports/specs/execution-log/item6-simplify-review-verification.md`

## Item Logs

- Item 1: `reports/specs/execution-log/item1-baseline.md`
- Item 2: `reports/specs/execution-log/item2-structured-planning.md`
- Item 3: `reports/specs/execution-log/item3-consumer-cutover.md`
- Item 6: `reports/specs/execution-log/item6-simplify-review-verification.md`

## Standing Constraints

- PAX uses local composite include build wiring; no publish step is required.
- Do not commit PAX-side changes.
- Use subagents for bounded read-heavy audits/final reviews, not uncontrolled parallel
  writes.
- Check storage, CPU, and memory before expensive Gradle/Bazel work.
- If storage is genuinely low, prefer `bazelisk clean --expunge`; in PAX delete
  `bazel-cache` only when necessary.

## Current Remaining Work

- Item 5 is complete at local commit `e707bf4`; do not push without explicit
  instruction.
- Continue Item 6 from
  `reports/specs/2026-06-26-item6-simplify-review-verification-design.md`.
- Keep Item 6 execution notes itemized; do not append long essays to this file.
