# Dependency Refactor Execution Log

This is the short continuity pointer for the dependency-refactor goal. Keep detailed
evidence in item-specific logs so context compaction can recover state quickly.

## Active State

- Active item: Item 4 - Remove Generated-Output Feedback Paths.
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
- Latest passed local gate:
  - Focused plan/task/collector/extractor tests for Item 3 Step 3.
  - `reports/scripts/verify-default-task-graph.sh`
  - `reports/scripts/verify-sample-bucket-labels.sh`
  - `./gradlew verifyGrazelGoldenBaseline -Pgrazel.internal.planParity=true --console=plain`
  - Grazel `git diff --check`
  - Result: all passed; generated sample diff stayed clean.
- Latest passed PAX gate:
  - `./gradlew migrateToBazel -Pgrazel.internal.planParity=true --no-daemon --console=plain --stacktrace`
  - Result: passed in about 11m01s.
  - `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures`
  - Result: passed in about 5m00s with 41 actions after cache checking.
  - PAX `git diff --check`: passed.
  - Tag-prefix audit: scanned 2208 changed Bazel files and found zero bucket Maven
    labels inside `tags` arrays.
  - PAX working tree remains dirty from generated output; do not commit PAX changes.
- Current detailed log:
  - `reports/specs/execution-log/item4-remove-feedback-paths.md`

## Item Logs

- Item 1: `reports/specs/execution-log/item1-baseline.md`
- Item 2: `reports/specs/execution-log/item2-structured-planning.md`
- Item 3: `reports/specs/execution-log/item3-consumer-cutover.md`
- Items 4-6: create `reports/specs/execution-log/itemN-*.md` when each item starts.

## Standing Constraints

- PAX uses local composite include build wiring; no publish step is required.
- Do not commit PAX-side changes.
- Use subagents for bounded read-heavy audits/final reviews, not uncontrolled parallel
  writes.
- Check storage, CPU, and memory before expensive Gradle/Bazel work.
- If storage is genuinely low, prefer `bazelisk clean --expunge`; in PAX delete
  `bazel-cache` only when necessary.

## Current Remaining Work

- Start Item 4. Delete generated-output feedback paths in the spec order:
  manifest/task-graph decouple, pinner WORKSPACE-regex discovery, extractor-side tag
  derivation, then parity code last.
- Continue Items 4-6 in order.
