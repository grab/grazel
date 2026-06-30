# Current Goal Anchor - Dependency Refactor Follow-Up

> **Read this first for the next long-running goal.** This file is the compact execution
> anchor. The detailed source of truth is `ALTITUDE-LAYERING-ROADMAP.md`, Item 1's global
> constraints/code-quality stance, and the active item spec. Do not load old long logs into main
> context; use focused subagents for wide reads, PAX audits, and historical checks.

## Source Of Truth

1. `reports/specs/CURRENT-GOAL-ANCHOR.md`
2. `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`
3. `reports/specs/ALTITUDE-LAYERING-ROADMAP.md`
4. Active specs:
   - `2026-06-29-item34-workspace-tag-plan-service-shape-design.md`
   - `2026-07-01-item35-task-progress-reporting-design.md`

Recently completed specs that should not be re-opened as active work unless code regresses:

- `2026-06-29-item30-workspace-resolution-input-boundary-design.md`
- `2026-06-29-item31-declared-metadata-fanout-default-decision-design.md`
- `2026-06-29-item32-true-project-declared-metadata-fanout-design.md`
- `2026-06-29-item33-variant-layer-declared-config-roles-design.md`

Superseded input only:

- `2026-06-27-altitude-layering-refactor-plan.md`
- `2026-06-28-next-slices-scc-and-target-references-draft.md`

Do not execute from superseded files directly.

## Execution Order

Current continuation order:

```text
status/docs truth checkpoint -> 34 -> 35 -> simplify/adversarial review -> final Grazel/PAX gates
```

Current checkpoint: Items 30, 31, 32, and 33 are completed. Item 32 is true
source-project declared-metadata fanout and is not pending implementation.
Before code work on Item 34/35, reconcile stale status docs and commit a local
docs-only checkpoint if the diff contains only approved status/spec/log updates.

Earlier primary order, now historical:

```text
17 -> 18 -> 19 -> 21
```

Stretch:

```text
22 Phase 1 measurement after the primary checkpoint is green.
22 Phase 2 reshape only if the Item 22 rubric proves exact shadow parity and real complexity
reduction.
```

Item 21 is mostly independent, but its `compressionResults` input removal must wait until Item
19 decides whether target-reference facts need compression data.

## PAX Baseline

PAX baseline source:

```text
repo:   /Users/arun.sampathkumar/work/pax-android
branch: arun/grazel-refactor
sha:    cfa1057ed58ccb2a795a5f679f072a8f604ff48e
```

The maintainer may create a local PAX generated-output baseline commit before this goal so
`git diff` is a direct regression signal. Do not push PAX. Do not commit PAX again during the
goal unless the maintainer explicitly asks for a new local baseline commit.

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

## Code-Quality Anchor

Inherit Item 1's code-quality stance:

- accidental complexity: remove on sight;
- model-essential complexity: reshape only as an explicit item with gates;
- problem-essential complexity: leave only after evidence is documented.

Measure complexity by special-case count, fan-out, fallback/fixpoint machinery, and
re-derivation round-trips, not by LOC.

## Item Intent

- **34:** Clean the workspace tag-plan service shape without changing emitted tags. Stop routing
  the tag plan through `WorkspacePlanBuilder`/`ComputeWorkspacePlanTask`; load it from
  `target-tag-plan.json`; split read-only tag lookup from mutable render-plan state; isolate the
  consumer-first render-plan back-edge. Empty generated diff.
- **35:** Add user-facing progress reporting for long-running dependency-refactor tasks. Progress
  uses `ProgressLogger` through a pure-JVM `ProgressReporter` lambda seam; permanent summaries use
  `logger.quiet` with capitalized plain messages; diagnostics stay on `info`/`warn`. Generated
  output remains empty-diff.

## Operational Constraints

Before every expensive Gradle/Bazel run, check disk, memory, and process pressure. PAX is
large; long runs are expected. Do not disable disk cache with `--disk_cache=`. Do not add
aggressive `--jobs` unless diagnosing a specific resource issue. Prefer default wrapper
behavior.

Watch `~/.gradle/caches`, `pax-android/bazel-cache`, any `bazel-ccache`, and
`/private/var/tmp/_bazel_*` or `/private/var/bazel`-like dirs. If Bazel private output roots
grow very large, for example above 90 GiB, or disk becomes genuinely low, clean deliberately
instead of letting runs fail:

- first use `bazelisk shutdown` and `bazelisk clean --expunge` in the relevant repo;
- remove stale private Bazel output roots only when clearly needed and after checking they are
  stale/not active;
- in PAX, `rm -rf bazel-cache` is allowed only as a last resort because preserving it keeps
  verification fast.

Stop stale Gradle daemons, Bazel processes, Coursier children, or high-RAM `python3.12`
processes only when clearly stale/problematic. Avoid running huge Gradle/Bazel jobs
concurrently.

## Verification Loop

Grazel checks as changes mature:

```text
./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon
./gradlew migrateToBazel --console=plain --no-daemon
reports/scripts/verify-default-task-graph.sh
reports/scripts/verify-sample-bucket-labels.sh
reports/scripts/verify-pax-size-guard.sh --mode preserving
git diff --check
git diff --check master...HEAD
```

PAX loop after meaningful non-doc changes:

```text
cd /Users/arun.sampathkumar/work/pax-android
./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks
./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk
./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test
git diff --check
```

For preserving items, any generated PAX/sample diff is stop-and-investigate unless explicitly
classified by the active spec.

## Performance Hygiene

Record in `reports/specs/EXECUTION-LOG.md` after meaningful runs:

- active item and current commit,
- commands and results,
- PAX size guard counts,
- `migrateToBazel` elapsed time when measured,
- Item 19 target-builder invocation count during reference collection,
- failures/root causes/fixes,
- remaining risks.

## Compaction Survival

Keep `reports/specs/EXECUTION-LOG.md` current after major milestones/failures. Also maintain
concise item-specific logs under `reports/specs/execution-log/` for the active item. On context
compaction, reload this anchor, roadmap, Item 1, the active item spec, and the active item
execution-log file before touching code.

Use subagents deliberately for wide reads, historical master/PAX comparisons, audit scripts,
PAX diff/count checks, and final adversarial review. Spot-check important claims.

## 2026-06-28 Historical Continuation Addendum

This section is historical. It records the completed Item 23-25 continuation and must not override
the current Item 34/35 execution order above. Historical hard execution order was:

```text
23 -> 26 -> 24 -> 27 -> 25
```

Item 25 remained last for that completed slice. Do not use this section as current guidance.

Because Item 27 can add or reshape Kotlin code after the Item 24 source-shape pass, rerun an
Item 24-style changed-file source-shape inventory after Item 27 fixes and before exiting the
cleanup phase. The rerun must visit/reconcile every Kotlin file changed by the branch/current
worktree, including new files added during adversarial fixes. Do not stop at broad tests alone;
individual changed files must be accounted for.

That historical goal completed. Current completion requirements are the Item34/35 requirements
listed above. Keep Grazel changes local only. Never push. Do not commit PAX.

During long runs, regularly optimize the durable context files: keep current truth near the top,
mark stale legacy checkpoints as historical/superseded, and condense noisy status into concise
milestone evidence. Do this especially after commits, verification gates, failures, and context
compaction so future continuations do not follow obsolete state.
