# Item 1 — Baseline, Knowledge Consolidation & Hygiene (Design)

> **Status:** Approved 2026-06-26. First spec in the dependency-refactor spec set.
> **Executor:** Codex. **Author of spec:** brainstormed with the maintainer.
> **Behaviour change:** none (pure baseline + hygiene).

This is the entry-point spec. It establishes the frozen golden baseline that every
later item diffs against, and it is the canonical home for the **Global Constraints**
and **Verification Playbook** that all later specs reference.

> **⚠️ Execution note — delegate to subagents; protect the main context.**
> Several tasks here (and across the spec set) involve reading large volumes of
> material that will not fit, and should not be loaded, into a single main context
> window — most acutely **Part C consolidation**, which must distill thousands of
> lines of historical `reports/*.md` into a few durable notes. **Do not read those
> files into the orchestrating context.** Dispatch focused subagents instead: give
> each a narrow question (e.g. "extract every documented waiver and its rationale
> from these three files, with citations") and have it return only the distilled
> result. The same applies to wide code reads, multi-file audits, and any
> bounded-audit number gathering on PAX. Keep the main context for sequencing,
> decisions, and verification — not for raw file content.

---

## Spec set index (the six-item backlog)

The dependency refactor is being cleaned up as six sequenced specs. Each is
behaviour-preserving unless marked otherwise.

| # | Spec | Phase | Behaviour |
|---|------|-------|-----------|
| **1** | **Baseline, knowledge consolidation & hygiene** (this doc) | P1 | preserving |
| 2 | Structured planning seam (`WorkspacePlan`) — additive | P2 | preserving |
| 3 | Consumer cutover onto the seam (old paths still present) | P3 | preserving |
| 4 | Remove generated-output feedback paths | P4 | preserving |
| 5 | Provenance + exclude correctness | P5a | **output-changing** |
| 6 | Simplify + adversarial review + full verification | P5b | preserving |

Centrepiece: **one structured planning layer; every renderer (project gen, root gen,
pinner, tag computation) reads from it; the provenance fix is isolated as the single
output-changing step.**

---

## Context

The branch `arun/dependencies-refactor` replaced per-variant `O(P×V)` dependency
resolution with aggregated resolution of app / `com.android.test` binary leaf
classpaths, then set-intersection bucketing. **PAX (`~/work/pax-android`) migrate +
`//app` debug APK + android-test APK currently pass** — the approach is not broken. The
remaining problem is architecture/mergeability: too much planning happens late or is
reconstructed from generated output (BUILD-file tag manifests, regex-parsed WORKSPACE).

This item does no refactoring. It freezes a trustworthy baseline and consolidates
knowledge so the later refactor steps have a meaningful "this changed nothing" signal.

---

## Global Constraints (apply to EVERY spec in this set)

Copied verbatim into each later spec's header by reference to this section.

1. **Gradle-resolved values are the source of truth** — selected versions, selected
   artifacts, and transitive closure come from Gradle resolution of the root/app/test
   classpaths. Declared versions never override resolved versions.
2. **Constrain Coursier via `maven_install.artifacts`, not `--force-version`.**
3. **Maven compile-filter `tags` use `@maven//:artifact_name` labels** (normalized).
   Actual `deps` keep their owning repos (`@debug_maven`, `@android_test_maven`,
   `@lint_maven`, `@gps_maven`, …).
4. **Do not revert to the old per-module expensive resolution model.** Fix the missing
   planner/provenance seams while keeping root resolution as the value source.
5. **Declared per-module metadata stays cheap collection only** — excludes, direct
   declarations, project edges, KSP/test ownership. It must not perform resolution.
6. **Binary-root requirement is accepted and documented** — the architecture is verified
   for app / `com.android.test` roots. Library-only / JVM-only roots are a documented
   limitation (see Deferred follow-ups), not a blocker for this work.
7. **PAX is the acceptance baseline.** Every item must preserve PAX `migrateToBazel` +
   `//app:app-gps-pax-debug.apk` + `//app:app-gps-pax-debug-android-test.apk`.
8. **Strict root reachability is part of correctness.** Active generated targets are only
   for projects/variants reachable from the configured app / `com.android.test` roots.
   Unreachable modules must not get active `BUILD.bazel` output just to mask missing
   classpaths; if an old file exists, it is renamed/ignored using the existing ignore
   mechanism.
9. **Separate candidate repo definitions from materialized repos.** A planner may know how
   to render many candidate Maven repos, but only repos referenced by reachable generated
   deps/plugins/tags, plus their override-target closure and always-materialized repos,
   may be emitted/pinned.

---

## Code-quality stance (apply to EVERY spec in this set)

The maintainer's standing instruction: **the codebase should end simple. Lean on the PAX
baseline + golden + size guard to be ambitious about removing complexity.** Apply this
yardstick, not LOC or surface aesthetics.

1. **Classify complexity three ways before touching it.**
   - **Accidental** — duplication, dead code, one-caller indirection, tool quirks. *Remove on
     sight*; no behaviour budget needed (e.g. Item 21).
   - **Model-essential** — irreducible *only because of the current model* (e.g. the
     set-subtraction ownership math is essential only while ownership = "resolve everything,
     then subtract by set membership"). *This is a legitimate, encouraged target* — attack it
     as an explicit output-changing or output-preserving reshape, never smuggled into an
     empty-diff item.
   - **Problem-essential** — genuinely irreducible: distinct equality contracts, a testability
     seam, the typed projection that prevents false cycles. *Leave it, and document why with
     evidence* — not by assertion.
   A "leave alone" entry is only valid if it is problem-essential. If something is merely
   model-essential, the correct move is to file a reshape item, not to enshrine it.
2. **Measure complexity by what makes change hard, not by size.** Hunt: special-case / branch
   count (fallbacks, fixpoints, compensating mechanisms), fan-out to understand one behaviour
   change, and re-derivation round-trips (stringify-then-regex-parse, resolve-then-subtract,
   compute-then-filter). Shaving a one-line wrapper does not move these; deleting a fallback,
   a round-trip, or a whole special-case class does. Net-neutral LOC with fewer special cases
   is a win.
3. **Calibrate ambition to the strength of the safety net.**
   - **Output-preserving reshapes** are nearly free of risk — golden empty-diff + size-guard
     no-increase prove safety byte-for-byte. *Be maximally ambitious here.*
   - **Intended-diff reshapes** (new behaviour) have a weaker net: byte-equality no longer
     applies, so they rely on adversarial review + size guard + diff-by-diff classification.
     *Be ambitious but careful, and gate hard.*
   The baseline catches *output* regressions, not internal-correctness regressions that happen
   to render identically — so an output-preserving reshape still needs focused unit tests on
   the reshaped logic, not just the golden.

---

## Verification Playbook (canonical; inherited from Codex's proven flow)

### Local grazel loop (in order)
1. Focused dependency tests:
   ```bash
   ./gradlew :grazel-gradle-plugin:test --console=plain --tests "com.grab.grazel.gradle.dependencies.DependencyBucketPlacementEngineTest" --tests "com.grab.grazel.gradle.variant.BucketHierarchyGraphTest" --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --tests "com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependenciesTest" --tests "com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionServiceTest" --tests "com.grab.grazel.gradle.dependencies.ResolvedComponentsVisitorTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallArtifactsCalculatorTest" --tests "com.grab.grazel.migrate.android.DefaultAndroidLibraryDataExtractorTest" --tests "com.grab.grazel.gradle.DefaultDependenciesDataSourceTest"
   ```
2. `./gradlew migrateToBazel --console=plain`
3. `reports/scripts/verify-default-task-graph.sh` (no legacy `*ResolveDependencies` scheduled)
4. `reports/scripts/verify-sample-bucket-labels.sh` (bucket-label oracle)
5. `git diff --check master...HEAD` (merge-base whitespace/conflict scan)
6. **Golden guardrail (this item's deliverable):** `migrateToBazel` then
   `git diff --exit-code` over the committed generated sample outputs.

### PAX acceptance loop (`~/work/pax-android`)
7. `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace` → `BUILD SUCCESSFUL`
8. `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures` → `Build completed successfully`
9. `bazelisk query 'kind(".*test rule", //app:*)'` → only documented lint tests appear

### Oracle definition (NOT byte-identical to master)
"Correct" = bucket semantics preserved + APK & android-test APK build + **target-local
bounded audit** stable + every diff from master *documented*, not eliminated. The
bounded audit covers, per key target — at minimum `//app:app-gps-pax-debug` and
`//app:app-gps-pax-debug-android-test` (the APK-gate targets) — the `deps` count,
`tags` count, and `@debug_maven`/`@android_test_maven` direct-dep counts. For behaviour-preserving items, the stronger local signal
is `git diff --exit-code` against **this item's committed baseline** (not master).

The bounded audit must include a **content audit for compile-filter tags**, not only
counts. For each key target, the emitted `tags` must use `@maven//:` labels only, must
contain the Gradle-resolved Maven closure needed by the target's direct Maven deps and
selected direct project deps, and must not contain unexplained bucket-prefixed labels or
arbitrary global closure. Smaller tag sets are acceptable only when the audited compile
closure is still complete.

The bounded audit must also record strict reachability: active generated outputs are
limited to projects/variants reachable from the app / `com.android.test` roots. Any
previously generated output for an unreachable module is absent or ignored, not kept as an
active target.

### Documented waivers (acceptable failures)
- `//app:app-gps-pax-debug.lint_test` fails on `SerializedNameDefaultValue` inside
  external Maven AARs — pre-existing baseline lint exposure, not a refactor regression.
- rules_jvm_external duplicate-version debug messages for annotation/databinding/Dagger/
  Kotlin artifacts — existing WORKSPACE composition, not duplicated generated JSON rows.
- No generated `gps-pax-debug` app unit-test target under `//app:*` (app-specific
  generated tests are lint tests only).

### Feedback tools (for diagnosing a missing-class failure)
`logger.quiet` diagnostics; small `build/grazel/*` diagnostic files; `jq` over
`build/grazel/dependencies.json`; focused single-target bazel build before the full APK.

---

## Goal (Item 1)

Produce a fresh, green, committed baseline on both grazel and PAX; capture the
load-bearing knowledge into durable docs; delete the report thrash.

## Procedure

### Part A — grazel baseline
1. Commit the two uncommitted perf-only edits (`AndroidExtractor` tag-closure cache,
   `AggregatedDependencyResolver` grouping hoist) — verified no output change.
2. Run the focused dependency test suite (Playbook step 1) → must pass.
3. `./gradlew migrateToBazel --console=plain` → regenerate sample outputs.
4. Run `verify-default-task-graph.sh` and `verify-sample-bucket-labels.sh` → must pass.
5. `git diff --check` on the current worktree → clean. The merge-base form
   `git diff --check master...HEAD` is required after Part C removes captured legacy
   report noise.
6. Commit any regenerated `sample-android/`, `sample-android-tests/`, `sample-*-library/`,
   `flavors/` `BUILD.bazel` + root `WORKSPACE` + `*_install.json`.
   **This committed state is the grazel golden.**

### Part B — PAX baseline (`~/work/pax-android`)
7. `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace` → SUCCESSFUL.
8. `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures` → success.
9. `bazelisk query 'kind(".*test rule", //app:*)'` → only documented lint tests.
10. Record the bounded audit baseline (per key target: `deps` count, `tags` count,
    tag-content closure, strict reachability, and `@debug_maven`/`@android_test_maven`
    direct counts) into a committed audit record and a re-runnable audit script in
    grazel. Do not commit PAX output unless the maintainer explicitly asks for a PAX-side
    baseline commit.
    **This is the PAX golden.**

### Part C — consolidate & clean
11. Distill load-bearing content into durable docs (this spec is the canonical home for
    the Global Constraints and Verification Playbook above; add a short
    `reports/specs/DO-NOT-REVISIT.md` for abandoned-approach lessons and a
    deferred-follow-ups note).
12. Delete the report thrash once captured: `dependencies-refactor-worklog.md`,
    `-goal-log.md`, `-HANDOFF.md`, `-architecture-interview.md`, `-design-notes.md`,
    `-dag-bucketing-goal.md`, `-bucket-planner-goal.md`, `-task-shape-goal.md`,
    `-merge-readiness-goal.md`, `-current-status.md`, `-current-truth.md`,
    `-active-anchor.md`, `-pending-tasks.md`, `dependency-resolution-to-workspace.md`.
    **Keep `reports/scripts/`.**
13. Commit "Consolidate refactor knowledge; remove working-log noise."
14. Run `git diff --check master...HEAD` after the cleanup commit so legacy report
    whitespace no longer hides real merge-readiness issues.

## Deliverables (the safety net)
- Committed fresh grazel golden (sample generated outputs).
- Recorded fresh PAX golden via a committed bounded-audit record + re-runnable audit
  script. PAX generated output may remain an external worktree diff unless separately
  requested.
- A **named verification task** (Codex invokes explicitly; NOT wired into `./gradlew
  check`) that runs `migrateToBazel` + `git diff --exit-code` on committed sample outputs
  + `verify-*.sh`.
- This spec (canonical Global Constraints + Verification Playbook) + `DO-NOT-REVISIT.md`.

## Acceptance criteria
- All local gates green; PAX migrate + both APKs build (waivers as documented).
- Current worktree `git diff --check` clean before baseline; `git diff --check
  master...HEAD` clean after report consolidation.
- Old reports deleted; their load-bearing content provably present in the spec set.
- Re-running the named verification task on the committed baseline yields an empty
  `git diff` (the golden is self-consistent).

## Out of scope
- Any code refactor: no planning seam, no consumer rewiring, no feedback-loop removal.
- The provenance/exclude correctness fix (Item 5) — baseline only captures current
  behaviour, including its known provenance limitation.

## Deferred follow-ups (documented, not done here)
- Library / JVM-only and standalone-library-test classpath roots as first-class nodes.
- Pin-JSON size reduction (test/android-test closures kept as Coursier constraints).
- Cacheability stance on live `ResolvedComponentResult` task inputs vs serialized inputs.
