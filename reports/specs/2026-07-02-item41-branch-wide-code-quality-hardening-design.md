# Item 41 — Branch-Wide Code Quality Hardening (Design)

> **Status:** Proposed 2026-07-02.
> **Executor:** Codex. **Behaviour:** **preserving / empty generated diff**.
> **Depends on:** Run after the current code-producing item set for the goal. If later code changes
> land after this item, rerun this item over the delta before final completion.
> **Global Constraints + Verification Playbook + Code-quality stance:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`.

> **Execution note — use subagents deliberately.** This item requires broad changed-file coverage.
> Use deterministic scripts plus scoped subagent fanout for production code, test code, task wiring,
> proxy/pinning code, variant/dependency code, and scripts/spec docs. The parent agent owns final
> reconciliation and must spot-check concrete claims.

---

## Goal

Merge the source-shape rules from Items 24, 27, and 28 into one stricter branch-wide quality gate.
The goal is not cosmetic formatting. The goal is code that is easier to review, safer to maintain,
and aligned with the branch architecture while keeping generated Grazel output and the committed PAX
baseline unchanged.

This item applies to **production code and tests**. Test code is not a dumping ground for reflection,
string assertions, dynamic access, unchecked casts, or production APIs kept alive only for tests.

## Why This Exists

Items 24/27/28 established useful standards:

- explicit role-named parameters over hidden receiver state;
- predictable helper model placement;
- typed seams instead of string/reflection shortcuts;
- branch-diff inventory with subagent reconciliation;
- simplify-pass plus adversarial review;
- no PAX baseline movement.

Those rules are now spread across multiple specs and execution logs. This item is the consolidated
quality contract for the current branch and future long-running goal sessions.

## Non-Goals

- Do not change dependency resolution, bucket ownership, reachability, pinning, proxy behavior, or
  generated output semantics as part of "style" cleanup.
- Do not broaden into the legacy `Dependencies.kt` per-project extractor cutover unless the active
  goal explicitly includes that architectural item.
- Do not perform repo-wide aesthetic churn outside branch-diff-related files.
- Do not reformat generated `BUILD.bazel`, `WORKSPACE`, or `maven_install.json` files unless the
  active non-style item requires regeneration and PAX/Grazel baselines prove no semantic drift.
- Do not commit or push PAX changes.

If a quality finding requires behavior change, output drift, or a larger architectural decision,
record it as a future item or stop for maintainer direction. Do not smuggle it into this preserving
item.

## Scope

Start from every Kotlin file changed by the current Grazel branch relative to master, including
current worktree additions:

```bash
git diff --name-only --diff-filter=ACMR master...HEAD -- '*.kt'
git status --short -- '*.kt'
```

Include:

- `grazel-gradle-plugin/src/main/...`
- `grazel-gradle-plugin/src/test/...`
- `grazel-gradle-plugin/src/functionalTest/...`
- build/test helper Kotlin files touched by the branch

Fan-out edits are allowed when required for a correct rename, interface extraction, call-site
cleanup, or typed boundary improvement. Fan-out files become inventory rows too.

Also review non-Kotlin helper scripts/spec files touched by the branch for stale comments,
execution-log drift, and misleading anchors, but do not apply Kotlin-specific rewrite rules to them.

## Required Artifacts

This item cannot complete without durable, current artifacts:

1. A deterministic inventory tool:
   - Prefer the existing `reports/scripts/source-shape-inventory.sh`.
   - If the existing script cannot detect the required patterns, extend it or add a committed
     Bun/TypeScript/tree-sitter Kotlin checker under `reports/scripts`.
   - Scratch-only scripts are not completion evidence.
2. A current inventory file:
   - `reports/specs/source-shape-inventory.tsv`, or an item-specific replacement linked from
     `reports/specs/EXECUTION-LOG.md`.
   - One row per changed Kotlin file plus fan-out files.
   - Regenerated after final edits.
3. Item-specific execution log:
   - `reports/specs/execution-log/item41-branch-wide-code-quality-hardening.md`.
   - Must record inventory counts, subagent partitions, findings, fixes, retained rationales,
     commands, results, PAX status, and remaining risks.

Minimum inventory columns:

```text
file
area
owner_agent
review_status
receiver_extension_status
helper_model_status
naming_status
type_boundary_status
test_quality_status
comment_status
action_taken
retained_rationale
verification
```

Allowed terminal statuses:

```text
fixed
no_issue
retained_problem_essential
deferred_requires_behavior_change
```

`pending`, blank fields, vague rationales, or "reviewed" without a concrete status fail the item.
Every `no_issue` row still needs a one-line concrete rationale.

## Hard Code-Quality Rules

### 1. Explicit roles over hidden receivers

Generic collection/map/mutable receiver extensions are banned by default in changed files:

```kotlin
private fun Map<...>.withSomething(...)
private fun MutableMap<...>.addSomething(...)
private fun Set<...>.withoutSomething(...)
private fun Collection<...>.toSomething(...)
```

Default rewrite:

```kotlin
private fun applyDeclaredMetadataByBucket(
    dependenciesByBucket: Map<String, Map<String, ResolvedDependency>>,
    declaredMetadataByOutputBucket: Map<String, Map<String, ResolvedDependency>>,
): Map<String, Map<String, ResolvedDependency>>
```

Allowed receiver extensions only when all are true:

- tiny, side-effect-free, algebraic or DSL-like;
- receiver role is obvious from the type alone;
- keeping it materially improves readability;
- inventory row records the concrete rationale.

Policy-heavy transforms, mutating helpers, and helpers where the receiver name carries domain meaning
must use explicit role-named parameters or live on the owning model/service.

### 2. Predictable helper model placement

File shape should be predictable:

```text
package/imports
public/internal boundary models
private file-local models/state
main class/object
private helper functions
```

Private helper data classes buried mid-file are allowed only when tightly local to a small algorithm
and the inventory row explains why moving them would reduce clarity.

### 3. Names must encode layer responsibility

Use role suffixes consistently:

- `Input` for data entering a planner/task boundary.
- `Plan` for durable planner output consumed by another layer.
- `Result` for one operation's returned output.
- `State` for mutable or accumulated algorithm-local state.
- `Key` for map/cache/grouping identity.
- `Edge` / `Node` for graph shape.
- `Summary` for diagnostics, logs, and reports.
- `Facts` only for observed external data that is not yet a policy decision.

Avoid generic names like `Data`, `Info`, `Context`, `Holder`, `Wrapper`, or `Utils` unless there is a
clear local convention and the row explains it.

### 4. Typed seams over stringly shortcuts

Prefer typed values, enums, sealed classes, or small interfaces over:

- suffix parsing where a `VariantType` or graph key is available;
- path/string parsing where a `RegularFile`, `DirectoryProperty`, label type, or model exists;
- source-code string assertions where typed APIs can be asserted;
- unchecked casts and dynamic proxies in tests;
- broad `Any`/`JsonObject` plumbing outside JSON/RJE boundaries.

String keys at real JSON/Starlark boundaries are acceptable when centralized and named. Do not
over-model external wire formats just to avoid a literal key.

### 5. Tests are first-class code

Tests must be readable through the same architectural lens as production code:

- use typed fakes/builders over reflection when possible;
- avoid source-text assertions when a model or task graph can be asserted;
- do not keep production methods public/internal only for tests;
- do not encode implementation accidents unless that is the contract under test;
- use fixture builders with domain names, not anonymous nested maps that hide roles;
- prefer focused regression tests for PAX-derived failures and altitude seams.

Reflection in tests is allowed only for Gradle annotation/API surface assertions or third-party API
failure paths where no typed seam exists. Every retained reflection/dynamic test must be listed in
the inventory with a concrete necessity rationale.

### 6. Comments describe durable behavior only

Remove comments that encode:

- migration diary/history;
- AI/context artifacts;
- stale TODOs;
- "old code did X" without an active compatibility contract;
- local debugging notes.

Keep or add comments only when they explain a durable behavior, invariant, external compatibility
constraint, or non-obvious failure-safety property.

### 7. No fallback complexity without evidence

Fallbacks, compatibility wrappers, and duplicate paths must be classified:

- accidental -> delete;
- model-essential -> reshape or schedule a gated architecture item;
- problem-essential -> retain only with concrete test/PAX/spec evidence.

## Execution Model

### Phase 0 — Baseline and inventory

1. Record current Grazel commit/status and PAX branch/SHA/status in
   `reports/specs/EXECUTION-LOG.md`.
2. Create/update `reports/specs/execution-log/item41-branch-wide-code-quality-hardening.md`.
3. Generate the source-shape inventory.
4. Partition the inventory into file clusters for subagents.
5. Confirm PAX baseline policy: PAX may have a maintainer-approved baseline commit/status, but Codex
   must not commit or push PAX.

### Phase 1 — Scripted suspicious-pattern scan

Run deterministic scans for at least:

- generic receiver extensions;
- private helper models declared after the primary class/object;
- reflection/dynamic proxy/unchecked cast/test source-string assertions;
- comments containing migration diary or context-rot terms;
- duplicated model shapes;
- task/service boundary smells such as string paths where Gradle file properties exist;
- public/internal APIs with only test callers.

Every hit becomes either a fixed row or a retained row with rationale.

### Phase 2 — Subagent review by cluster

Use scoped subagents for independent file clusters. Each subagent must return:

- exact files reviewed;
- exact findings with file/line references;
- suggested fix category;
- whether the finding is behavior-preserving;
- test/gate recommendation.

Broad prose summaries are not enough. Parent agent reconciles every finding into the inventory.

### Phase 3 — Apply preserving fixes

Apply fixes in small batches:

1. receiver/parameter reshapes;
2. helper model relocation and renaming;
3. typed test seam cleanup;
4. comment cleanup;
5. dead wrapper/fallback deletion;
6. small interface extraction only when it clarifies a real boundary.

Run focused tests after each meaningful batch. Generated output movement is a stop-and-investigate
event.

### Phase 4 — Simplify and adversarial review

1. Explicitly invoke the `simplify-pass` skill over this item’s diff or the full branch diff if the
   current goal has touched many files.
2. Run an adversarial correctness/altitude review focused on:
   - hidden behavior changes;
   - typed-vs-string regressions;
   - test quality shortcuts;
   - task/Gradle provider boundary mistakes;
   - PAX baseline drift;
   - generated output changes.
3. Apply confirmed findings or reject only with concrete code evidence in the item log.
4. Rerun the inventory after fixes.

### Phase 5 — Final verification

Run the required Grazel and PAX gates. Do not complete this item based on local unit tests alone.

## Required Verification

Local Grazel:

```bash
reports/scripts/source-shape-inventory.sh
./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon
./gradlew migrateToBazel --console=plain --no-daemon
reports/scripts/verify-default-task-graph.sh
reports/scripts/verify-sample-bucket-labels.sh
reports/scripts/verify-pax-size-guard.sh --mode preserving
git diff --check
git diff --check master...HEAD
```

PAX final guard:

```bash
cd /Users/arun.sampathkumar/work/pax-android
./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks
./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk
./bazel.sh test --test_output=errors \
  //app-utils:app-utils-gps-pax-debug-test \
  //app-test:app-test-gps-pax-debug-test \
  //application-initializer:application-initializer-gps-pax-debug-test
git diff --check
```

Operational constraints from the current goal apply: check disk, memory, process pressure, and Bazel
private output roots before expensive runs; preserve caches unless genuinely low on space; do not add
aggressive `--jobs`; do not commit PAX.

## Acceptance Criteria

- Inventory covers every branch-changed Kotlin file plus fan-out files.
- Inventory has no pending/blank/vague rows after final edits.
- Production and test code both pass the hard code-quality rules or have concrete retained
  rationales.
- Every retained generic receiver extension, reflection/dynamic test, mid-file helper model, and
  fallback/duplicate path has evidence-backed rationale.
- Simplify-pass and adversarial review ran and their findings are fixed or rejected with concrete
  code evidence.
- Generated Grazel output remains empty-diff.
- PAX migrate/build/test gates pass and PAX generated baseline remains unchanged.
- PAX size guard remains unchanged.
- Execution logs record inventory counts, commands, elapsed times, results, fixed categories,
  retained rationales, and remaining risks.
- Grazel may be committed locally only after a clean green checkpoint. Do not push and do not commit
  PAX.
