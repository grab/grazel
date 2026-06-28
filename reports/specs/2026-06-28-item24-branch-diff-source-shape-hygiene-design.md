# Item 24 - Branch-Diff Source Shape Hygiene (Design)

> **Status:** Proposed 2026-06-28.
> **Executor:** Codex.
> **Behaviour change:** none - golden EMPTY-diff.
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`. **Depends on:** Item 23.

> **Execution note - subagent fanout is required.** Static scripts can inventory file
> shapes, but they cannot reliably judge altitude, naming intent, hidden policy, or test-only
> API leaks. Use scoped agents per file or cluster; the parent agent reconciles and owns final
> decisions.

---

## Goal

Clean up the source shape of Kotlin files touched by the dependency refactor, without changing
generated output. This item turns the current branch into code that is easier to reason about
after the major architectural work: explicit parameters over policy-heavy receivers, clearer
intermediate models, stronger test APIs, and less accidental production surface.

This is not a behavior item. The hard requirement is:

```text
Grazel generated output unchanged
PAX generated baseline unchanged
```

## Scope

Start from the Kotlin files changed by this branch diff, including both:

- `grazel-gradle-plugin/src/main/...`
- `grazel-gradle-plugin/src/test/...`

The initial inventory is branch-diff scoped, not repo-wide. Every changed Kotlin file in that
inventory must be visited before exit. Edits may fan out to related files
when needed for a correct rename, interface extraction, call-site cleanup, or type-boundary
improvement. Any fan-out must be recorded in the Item 24 inventory/log with the reason.

Avoid unrelated aesthetic repo-wide churn.

## Cleanup Targets

### 1. Policy-heavy extension functions

Keep extension functions only when they are tiny, obvious domain/DSL conveniences. Convert
non-trivial generic, collection, or policy-heavy extensions into explicit functions or methods
on the owning model/service.

Prefer this shape:

```kotlin
private fun applyDeclaredMetadataByBucket(
    dependenciesByBucket: Map<String, Map<String, ResolvedDependency>>,
    declaredMetadataByOutputBucket: Map<String, Map<String, ResolvedDependency>>,
): Map<String, Map<String, ResolvedDependency>>
```

over this shape:

```kotlin
private fun Map<String, Map<String, ResolvedDependency>>.withDeclaredMetadataByBucket(...)
```

The first parameter should name the role that the old receiver hid.

### 2. Intermediate model/data-class shape

Move private helper model types to a predictable top-of-file section:

```text
imports
public/internal boundary models
private file-local models/state
main class/object
private helper functions
```

Use role suffixes consistently:

- `Input` for data entering a planner/task boundary.
- `Plan` for durable planner output consumed by another layer.
- `Result` for one operation's returned output.
- `State` for mutable or accumulated algorithm-local state.
- `Key` for map/cache/grouping identity.
- `Edge` / `Node` for graph shape.
- `Summary` for diagnostics, logs, and reports.

Rename ambiguous existing types and methods when the new name clarifies the layer contract,
even if many call sites change. Generated output must remain byte-identical.

### 3. Interfaces and layer boundaries

Small interfaces are allowed when they clarify a real boundary. Do not introduce interfaces as
ceremony, but do introduce them when they make ownership, extraction, planning, or rendering
contracts easier to understand and test.

### 4. Test code hygiene

Tests are in scope. Remove or rewrite:

- reflection-based tests that bypass the type system;
- test-only production APIs;
- wrappers kept alive only by tests;
- assertions that depend on accidental implementation details when a typed API can express the
  same contract.

Do not replace these with dynamic access, unchecked casts, reflection, or stringly shortcuts.
Kotlin's type system should carry the test boundary too.

## Execution Model

1. Build a deterministic inventory of changed Kotlin files.
2. Record the inventory and per-file decisions in `reports/specs/EXECUTION-LOG.md` or a focused
   Item 24 inventory file referenced from that log.
3. Use deterministic scripts for the inventory and for any repeated mechanical checks that can be
   encoded safely. Bun/TypeScript/tree-sitter tooling may be committed under `reports/scripts`
   when it becomes a useful guard or repeatable inventory tool. Scratch scripts stay uncommitted.
4. Use scoped subagents by file or cluster for the full changed-file inventory. Ask them to
   classify concrete findings, not to make final scope decisions.
5. Parent agent reconciles findings, applies small output-preserving cleanup slices, and reruns
   focused checks after each meaningful batch.
6. Repeat discovery after cleanup so missed cases surface before final verification.
7. Do not exit until every inventoried file has one of: cleaned findings, explicit "no issue"
   judgment, or a documented problem-essential reason for retained complexity.

## Out of Scope

- Behavior changes to dependency resolution, bucket ownership, reachability, rendering, or
  pinner semantics.
- Generated output re-baselining.
- Broad repo-wide style churn outside branch-diff-related files.
- Fully typed Bazel label modeling unless a local cleanup requires a small typed seam without
  output changes.
- Cacheability changes.

## Verification

Focused verification depends on touched files, but each meaningful source batch should run at
least the relevant unit tests. Before completion, run:

```text
./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon
./gradlew migrateToBazel --console=plain --no-daemon
reports/scripts/verify-pax-size-guard.sh --mode preserving
git diff --check
git diff --check master...HEAD
```

Run PAX after the final batch:

```text
cd /Users/arun.sampathkumar/work/pax-android
./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks
git diff --check
```

If any generated output changes, stop and investigate. Do not classify it away as a cleanup
diff.

## Acceptance Criteria

- Changed-branch Kotlin files have been inventoried.
- Every inventoried Kotlin file has been visited and reconciled.
- Each retained policy-heavy extension, ambiguous helper model, or test-only seam is either
  removed, renamed, reshaped, or explicitly justified as problem-essential.
- No reflection/type-system escape hatches are introduced; existing ones in touched scope are
  removed or justified.
- Any fan-out beyond the initial changed-file inventory is logged with the concrete reason.
- Generated Grazel output is empty-diff.
- PAX generated baseline remains unchanged.
- Verification commands and results are recorded in the execution log.

## Risks / Traps

- **Aesthetic churn:** This item is about branch-diff maintainability, not formatting taste.
  Every edit should clarify a type boundary, layer contract, or test contract.
- **Over-broad rewrites:** A rename that touches many call sites is allowed when it improves the
  contract; unrelated repo-wide renames are not.
- **Hidden behavior changes:** Moving helper models or replacing extension receivers should be
  mechanically equivalent. Generated output is the hard gate.
- **Subagent drift:** Subagents may find useful issues, but the parent must reconcile against
  the spec. Do not let independent review threads redefine the goal.
- **Early exit:** Do not stop after one obvious cleanup batch. Completion requires a reconciled
  file-by-file inventory, final discovery pass, Grazel verification, and PAX final guard.
