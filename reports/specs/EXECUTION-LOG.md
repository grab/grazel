# Dependency Refactor Execution Log

This is the short continuity pointer for the dependency-refactor goal. Keep detailed
evidence in item-specific logs so context compaction can recover state quickly.

## Active State

- Active item: Item 6 - Simplify, adversarial review, and final verification.
- Final verification checkpoint commit: `db05a6d`
  (`Finalize dependency refactor verification`).
- Current Grazel worktree after checkpoint: clean, branch ahead of origin.
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
- Item 4 Step 4 parity cleanup checkpoint commit: `97d907c`
  (`Remove workspace plan parity flag`).
- Item 5 Step 5a exclude-intersection checkpoint commit: `afa62bc`
  (`Intersect dependency exclude metadata`).
- Item 5 Step 5b variant-provenance checkpoint commit: `e707bf4`
  (`Select Maven roots per variant provenance`).
- Latest passed local gates:
  - Item 10 full plugin unit tests:
    `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`
    passed in 36s.
  - Item 10 focused dependency/refactor tests passed.
  - `./gradlew verifyGrazelGoldenBaseline --console=plain --no-daemon`
    passed in 41s.
  - Item 8 full plugin unit tests:
    `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`
    passed in 41s.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` passed.
  - Grazel `git diff --check` passed after the latest log/doc update.
- Latest passed PAX gates:
  - Item 10 PAX `./gradlew migrateToBazel --no-daemon --console=plain
    --stacktrace` passed in 10m19s.
  - Item 10 bounded audit passed: no bucket-prefixed Maven tags,
    `bug-report-kit-implementation` active BUILD output absent, WORKSPACE 5327
    lines / 24 `maven_install` entries.
  - Item 10 PAX `./bazel.sh build --jobs=4 --disk_cache=
    --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed after an automatic
    transient remote-cache retry; the successful retry took 3020.485s.
  - Item 10 PAX `git diff --check` passed.
  - Item 10 PAX focused unit-test gate passed:
    `//app-utils:app-utils-gps-pax-debug-test`,
    `//app-test:app-test-gps-pax-debug-test`, and
    `//application-initializer:application-initializer-gps-pax-debug-test`.
  - Item 10 Grazel `git diff --check` and
    `git diff --check master...HEAD` passed.
  - Item 11 fresh broad `./gradlew check --console=plain --no-daemon`
    failed on unchanged sample-app lint:
    `sample-android/src/main/res/layout/activity_main.xml:73 MissingConstraints`.
  - Final post-checkpoint Grazel `git diff --check` and
    `git diff --check master...HEAD` passed.
  - PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`
    passed after strict reachability and collector fixes.
  - PAX `./bazel.sh build //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed after those fixes.
  - PAX focused unit-test gate passed for `app-utils`, `app-test`, and
    `application-initializer` GPS PAX debug test targets.
  - PAX `git diff --check` passed.
  - PAX working tree remains dirty from generated output; do not commit PAX
    changes.
  - PAX generated diff shape at final checkpoint: 2230 generated files changed,
    705176 insertions, 772265 deletions.
- Current detailed logs:
  - `reports/specs/execution-log/item6-simplify-review-verification.md`
  - `reports/specs/execution-log/item7-pax-bazel-package-reachability.md`
  - `reports/specs/execution-log/item8-pax-generated-shape.md`
  - `reports/specs/execution-log/item9-maven-pinfile-bloat.md`
  - `reports/specs/execution-log/item10-adversarial-followups.md`
  - `reports/specs/execution-log/item11-final-verification-waivers.md`

## Item Logs

- Item 1: `reports/specs/execution-log/item1-baseline.md`
- Item 2: `reports/specs/execution-log/item2-structured-planning.md`
- Item 3: `reports/specs/execution-log/item3-consumer-cutover.md`
- Item 4: `reports/specs/execution-log/item4-remove-feedback-paths.md`
- Item 5: `reports/specs/execution-log/item5-provenance-exclude.md`
- Item 6: `reports/specs/execution-log/item6-simplify-review-verification.md`
- Follow-up strict reachability: `reports/specs/execution-log/item7-pax-bazel-package-reachability.md`
- Follow-up generated shape: `reports/specs/execution-log/item8-pax-generated-shape.md`
- Follow-up pin-file bloat/backout: `reports/specs/execution-log/item9-maven-pinfile-bloat.md`
- Follow-up adversarial fixes: `reports/specs/execution-log/item10-adversarial-followups.md`
- Follow-up final verification waivers:
  `reports/specs/execution-log/item11-final-verification-waivers.md`

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
- Item 6 is review-ready for the dependency-refactor slice with documented
  local waivers:
  - root `./gradlew check` is blocked by unchanged sample-app lint;
  - root `bazelisk build //...` / `bazelisk test //...` are blocked by
    sample/rule hygiene issues: crashlytics generated manifest output missing
    in Android configuration, plus sample-flavor duplicate generated
    `res_values`;
  - PAX dependency-refactor gates are green.
- Keep Item 6 and follow-up execution notes itemized; do not append long
  essays to this file.
