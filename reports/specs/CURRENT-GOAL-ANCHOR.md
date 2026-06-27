# Current Goal Anchor — Altitude Layering

> **Read this first for the next long-running goal.** This file is the compact execution
> anchor. The detailed source of truth is `ALTITUDE-LAYERING-ROADMAP.md` plus the active item
> spec. Do not load old long logs into main context; use focused subagents for historical or
> PAX-heavy lookup.

## Source Of Truth

1. `reports/specs/CURRENT-GOAL-ANCHOR.md`
2. `reports/specs/ALTITUDE-LAYERING-ROADMAP.md`
3. The active item spec:
   - `2026-06-27-item10-pax-size-guard-design.md`
   - `2026-06-26-item9-reachability-target-edges-design.md`
   - `2026-06-27-item11-contain-scc-design.md`
   - `2026-06-27-item12-extract-bucket-ownership-planner-design.md`
   - `2026-06-27-item13-test-lint-delta-ownership-design.md`
   - `2026-06-27-item14-slim-compute-workspace-dependencies-design.md`
   - `2026-06-27-item15-rendering-purity-hygiene-design.md`
   - `2026-06-27-item16-simplify-review-verification-design.md`

`2026-06-27-altitude-layering-refactor-plan.md` is superseded architectural input only. Do
not execute from it directly.

## Execution Order

```text
10 -> 9 -> 11 -> 12 -> 13 -> 14 -> 15 -> 16
```

- Item 10 creates the machine-enforced PAX size guard.
- Item 9 adds typed graph nodes/edges and `com.android.test -> app` reachability ordering.
- Item 11 proves false SCCs are eliminated; SCC remains only as typed diagnostic fallback if
  a genuine typed cycle is proven.
- Item 12 extracts `BucketOwnershipPlanner` preserving output.
- Item 13 is the only planned output change: test/androidTest delta ownership.
- Item 14 slims `ComputeWorkspaceDependencies` preserving output.
- Item 15 performs rendering purity/hygiene cleanup.
- Item 16 runs simplify-pass, adversarial review, broad verification, and final docs/waiver
  cleanup.

## PAX Baseline

PAX baseline source:

```text
repo:   /Users/arun.sampathkumar/work/pax-android
branch: arun/grazel-refactor
sha:    05d2b4801530726ab722133c2ba32cbba9afeb67
```

Never commit PAX changes. Use PAX `git diff` after migration as the generated-output impact
loop.

## Hard Invariants

- Root app and `com.android.test` Gradle-resolved classpaths are the resolved value source.
- Do not return to old per-module full resolution as default behavior.
- Gradle-resolved versions/artifacts/transitive closure are authoritative.
- Declared metadata is cheap provenance only: excludes, direct declarations, project edges,
  KSP/test ownership.
- Coursier/rules_jvm_external must be constrained through complete
  `maven_install.artifacts` for each materialized repo.
- Do not add `--force-version` or equivalent conflict-masking shortcuts.
- Do not drop closure artifacts only to shrink pinfiles.
- Maven compile-filter tags use normalized `@maven//:` labels only.
- Candidate Maven repos and materialized repos are separate.
- Active generated targets must be strictly reachable from configured app/`com.android.test`
  roots.
- No PAX-only hacks. Do not push. Commit Grazel locally only at clean green checkpoints.

## Complexity And Performance Anchor

The point of layering is not cosmetic. The current PAX-verified baseline gives the refactor
room to replace compensating machinery with better models while preserving correctness.

Prefer:

- typed graph projections over SCC/fixpoint fallback;
- aggregated declared metadata over downstream inference;
- `BucketOwnershipPlanner` over scattered ownership predicates;
- mechanical `ComputeWorkspaceDependencies` value/index computation over policy;
- renderers consuming plans over target/output feedback.

Each item should try to delete or simplify the special-case logic its new layer makes
unnecessary. If complexity stays or grows, document why it is still required.

## Guard Semantics

- Preserving items exact-match generated output and per-repo artifact identity.
- Item 9 Stage 2 may have a classified correctness diff only if the new target edge exposes
  real under-collection.
- Item 13 may change only test/androidTest scoped repos.
- Lint is out of scope for Item 13 and must exact-match.
- Item 13 has no internal increase waiver. If it increases guarded totals, abandon/redesign
  the item.
- Item 13 may move roots between `test_maven` and `android_test_maven` only when the move is
  classified as more precise typed ownership and the scoped aggregate plus guarded totals
  stay flat or shrink.
- Accepted reductions monotonically lower the machine-readable PAX baseline.
- SCC is not a modeling strategy. The known PAX `deliveries-model-food ↔ food-testkit` case
  is presumed false until typed graph projection proves a genuine same-projection cycle.

## Temporary Harness

Use the temporary parity/diff property while the old path for that item exists:

```text
-Pgrazel.internal.parity=ownership|cwd
```

For Item 13, `-Pgrazel.internal.parity=delta` is valid only if a pre-Item-13 path still
exists. Otherwise use the frozen Item 10 PAX baseline JSON plus generated diff
classification as the parity source. Remove each temporary parity mode and old path before
completing its item.

## Verification Loop

Before expensive Gradle/Bazel commands, check disk, memory, and process pressure. PAX is
large; long runs are expected. Avoid concurrent huge builds.

Grazel checks as changes mature:

```text
./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon
./gradlew migrateToBazel --console=plain
reports/scripts/verify-default-task-graph.sh
reports/scripts/verify-sample-bucket-labels.sh
git diff --check
```

PAX loop after meaningful non-doc changes:

```text
cd /Users/arun.sampathkumar/work/pax-android
./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks
./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk
./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test
git diff --check
```

Then run the Grazel PAX size guard from Item 10.

## Resource Hygiene

- Watch `~/.gradle/caches`, PAX `bazel-cache`, any `bazel-ccache`, and
  `/private/var/tmp/_bazel_*` or `/private/var/bazel`-like dirs.
- If storage is genuinely low, prefer `bazelisk shutdown` and `bazelisk clean --expunge` in
  the relevant repo.
- Remove PAX `bazel-cache` only as a last resort.
- Stop stale Gradle daemons, Bazel processes, Coursier children, or high-RAM `python3.12`
  processes only when clearly stale/problematic.

## Logging And Compaction Survival

Keep `reports/specs/EXECUTION-LOG.md` current after major milestones/failures:

- active item and current commit,
- decisions made,
- commands and results,
- failures/root causes,
- remaining risk.

Also maintain concise item-specific logs under `reports/specs/execution-log/` for the active
item. On context compaction, reload the current anchor, roadmap, active item spec, and the
active item execution-log file before touching code.

Use subagents deliberately for wide reads, historical master/PAX comparisons, audit scripts,
and final adversarial review. Spot-check important claims.
