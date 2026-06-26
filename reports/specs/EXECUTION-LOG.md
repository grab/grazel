# Dependency Refactor Execution Log

This is the short continuity pointer for the dependency-refactor goal. Keep detailed
evidence in item-specific logs so context compaction can recover state quickly.

## Active State

- Active item: Item 3 - Consumer Cutover onto `WorkspacePlan`.
- Item 1 baseline/safety-net checkpoint commit: `368a21f`
  (`Record PAX baseline safety gate`).
- Item 2 structured-planning checkpoint commit: `6393de1`
  (`Add workspace dependency planning seam`).
- Item 3 Step 1 pinner cutover checkpoint commit: this commit
  (`Cut pinner over to workspace plan`).
- Latest passed local gate:
  - `./gradlew verifyGrazelGoldenBaseline --console=plain`
  - Result: `BUILD SUCCESSFUL in 14s` after adding
    `collectTargetMavenRepoReferences`.
  - Grazel `git diff --check`
  - Result: clean.
- Latest passed PAX gate:
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`
  - Result: `BUILD SUCCESSFUL in 14m 5s` after the exact target-reference collector
    correction.
  - Current PAX `WORKSPACE`: 3760 lines, 238 below PAX `HEAD`.
  - Current materialized repos: 12. Test/lint pinning still grew versus PAX `HEAD`
    (`android_test_maven` +279 artifacts, `test_maven` +245, `lint_maven` +61);
    keep as an optimization concern after correctness.
  - `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures`
  - Result after collector correction: `INFO: Build completed successfully, 8778 total actions`
    in 455.871s.
  - PAX `git diff --check`: passed.
  - PAX app test query: only app lint test targets.
  - Extra `./bazel.sh test //app:app-gps-pax-debug.lint_test`: failed with lint
    `SerializedNameDefaultValue` findings in existing external artifacts; not a
    dependency compilation/linkage failure. Needs baseline comparison before
    attributing to this refactor.
  - Requires local uncommitted PAX build-logic compatibility edits; do not commit PAX
    changes.
- Current detailed log:
  - `reports/specs/execution-log/item2-structured-planning.md`

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

- Item 3 Step 1 pinner cutover passed focused/local golden checks; decide whether to
  run PAX at this checkpoint or proceed to Step 2 root generation cutover first.
- Continue Items 3-6 in order.
