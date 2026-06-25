# Dependency Refactor Execution Log

This is the short continuity pointer for the dependency-refactor goal. Keep detailed
evidence in item-specific logs so context compaction can recover state quickly.

## Active State

- Active item: Item 1 - Baseline, Knowledge Consolidation & Hygiene.
- Current baseline commit: `1188d46` (`Establish dependency refactor baseline checks`).
- Latest passed local gate:
  - `./gradlew verifyGrazelGoldenBaseline --console=plain`
  - Result: `BUILD SUCCESSFUL in 13s`.
- Current detailed log:
  - `reports/specs/execution-log/item1-baseline.md`

## Item Logs

- Item 1: `reports/specs/execution-log/item1-baseline.md`
- Items 2-6: create `reports/specs/execution-log/itemN-*.md` when each item starts.

## Standing Constraints

- PAX uses local composite include build wiring; no publish step is required.
- Do not commit PAX-side changes.
- Use subagents for bounded read-heavy audits/final reviews, not uncontrolled parallel
  writes.
- Check storage, CPU, and memory before expensive Gradle/Bazel work.
- If storage is genuinely low, prefer `bazelisk clean --expunge`; in PAX delete
  `bazel-cache` only when necessary.

## Current Remaining Work

- Commit the Item 1 log split and legacy report cleanup.
- Run PAX baseline:
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`
  - `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures`
- Record `reports/specs/PAX-BOUNDED-AUDIT-BASELINE.md` using the committed audit script.
- Continue Items 2-6 in order.
