# Item 1 — Baseline, Knowledge Consolidation & Hygiene (Design)

> **Status:** Approved 2026-06-26. First spec in the dependency-refactor spec set.
> **Executor:** Codex. **Author of spec:** brainstormed with the maintainer.
> **Behaviour change:** none (pure baseline + hygiene).

This is the entry-point spec. It establishes the frozen golden baseline that every
later item diffs against, and it is the canonical home for the **Global Constraints**
and **Verification Playbook** that all later specs reference.

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
bounded audit** stable (per key target: `deps` count, `tags` count,
`@debug_maven`/`@android_test_maven` direct counts) + every diff from master
*documented*, not eliminated. For behaviour-preserving items, the stronger local signal
is `git diff --exit-code` against **this item's committed baseline** (not master).

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
5. `git diff --check master...HEAD` → clean.
6. Commit any regenerated `sample-android/`, `sample-android-tests/`, `sample-*-library/`,
   `flavors/` `BUILD.bazel` + root `WORKSPACE` + `*_install.json`.
   **This committed state is the grazel golden.**

### Part B — PAX baseline (`~/work/pax-android`)
7. `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace` → SUCCESSFUL.
8. `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures` → success.
9. `bazelisk query 'kind(".*test rule", //app:*)'` → only documented lint tests.
10. Record the bounded audit baseline (per key target: `deps` count, `tags` count,
    `@debug_maven`/`@android_test_maven` direct counts) into a committed audit record and
    a re-runnable audit script. Commit PAX generated output.
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

## Deliverables (the safety net)
- Committed fresh grazel golden (sample generated outputs).
- Committed fresh PAX golden + bounded-audit record + re-runnable audit script.
- A **named verification task** (Codex invokes explicitly; NOT wired into `./gradlew
  check`) that runs `migrateToBazel` + `git diff --exit-code` on committed sample outputs
  + `verify-*.sh`.
- This spec (canonical Global Constraints + Verification Playbook) + `DO-NOT-REVISIT.md`.

## Acceptance criteria
- All local gates green; PAX migrate + both APKs build (waivers as documented).
- `git diff --check master...HEAD` clean.
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
