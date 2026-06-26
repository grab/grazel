# Item 8 — Reachability Single-Pass (Design)

> **Status:** Approved 2026-06-26 (grounded against codebase). Follow-up spec beyond the
> original 6-item set.
> **Executor:** Codex. **Behaviour change:** none (provably equivalent; golden empty-diff).
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Depends on:** Items 1–6 (the planning seam + reachability collector in place).

> **⚠️ Execution note — delegate to subagents; protect the main context.** Graph/ordering
> verification and PAX parity runs go to focused subagents returning distilled results.

---

## Goal

Collapse the `O(depth×N)` fixpoint loop in `CollectTargetMavenRepoReferencesTask` into a
single `O(N)` pass. The loop (`CollectTargetMavenRepoReferencesTask.kt:142-163`,
`repeat(projectList.size + 1)`) re-evaluates the full target-builder pipeline for every
project on every iteration until the accumulated reachability set stabilizes; a single pass
in the right order computes the same fixpoint once.

## Mechanism

Replace the `repeat(...)` loop with a single iteration over the project list in
**reverse-topological (consumers-first) order**:

```
for project in TopologicalSorter.sort(graphs).reversed():
    populateRenderPlan(accumulated)            // so this project's gating sees its referrers
    accumulated = merge(accumulated, TargetMavenRepoReferencesCollector.fromTargets(targetsForProject(project)))
```

**The reversal is mandatory and is the one trap.** Reachability gating
(`KotlinLibraryTargetBuilder.isReachableJvmProject` `:80-85`,
`AndroidBinary/AndroidTestTargetBuilder.referencedTargetNames`) emits a library only after a
consumer has marked it reachable; reachability seeds from binaries/apps and flows to
dependencies. So a **consumer must be processed before its dependencies**. But
`TopologicalSorter.sort` returns **dependencies-first** (leaves first — `TopologicalSorter.kt:27,50-54`;
edges are consumer→dependency on a `directed()` graph, `DependenciesGraphsBuilder.kt:120-125`).
Using the raw sort order would process leaves while still unmarked and under-collect exactly
like the fixpoint's iteration 0. **Iterate `.reversed()`.**

## Equivalence rationale (why one pass equals the fixpoint)

Reachability is monotone set-union over a DAG (`mergeTargetMavenRepoReferences` only unions).
In consumers-first order, every consumer of a project P is processed before P, so by the
time P is visited all projects that could reference it have already deposited that reference
into `accumulated`. No node processed after P can introduce a new reference to P (that would
require a consumer-of-P ordered after P, contradicting the order). Diamonds (A→B, A→C, B→D,
C→D) are safe: D is processed last among its ancestors and sees the union of B's and C's
references. Therefore the single reverse pass reaches the identical fixpoint.

## Mandatory pre-implementation guard

Verify that the graph used for ordering (`mergeToProjectGraph`'s `variantTypeFilter`,
`DependencyGraphs.kt:62`) **includes the test/androidTest variant edges**, so
`AndroidTestTargetBuilder`'s reference to its target-under-test is present in the ordering
DAG. If those edges are filtered out, the reverse-topo order could omit a
consumer→dependency relationship the brute-force fixpoint catches, causing under-collection.
If the default filter excludes them, the ordering must be built on a graph that includes
them. **Confirm this before removing the loop.**

## Safety mechanism — temporary parity assertion vs the old fixpoint

Behind an off-by-default Gradle property (`-Pgrazel.internal.reachabilityParity=true`),
**keep the old fixpoint temporarily** and run both: assert the single-pass `accumulated` set
is **identical** to the fixpoint's, failing with a diff on mismatch.
- **Codex enables it for PAX verification runs** — an exact check against the
  proven-correct fixpoint where no content golden exists (PAX = build + count audit only).
  This directly catches a missing-edge under-collection from the `variantTypeFilter` caveat.
- Normal runs pay nothing (flag off).
- **Remove the old fixpoint once parity is confirmed green on PAX + sample.**

## Out of scope (irreducible here)

**Double target-builder evaluation is NOT addressed.** `GenerateBazelScriptsTask` re-evaluates
the target builders against the **final** render plan (only exists after
`FinalizeWorkspacePlanTask`), so its evaluation reads a strictly newer input than any
collect-phase evaluation and cannot be safely shared. Caching only the final-iteration
outputs is feasible in principle but changes the per-project task I/O + configuration-cache
contract and requires `BazelTarget` serialization across a BuildService boundary — a
separate, carefully-designed change. Item 8 collapses only the in-task fixpoint loop; the
`GenerateBazelScripts` re-evaluation stays intact. (Recorded as a deferred follow-up.)

## Validation

- **Sample golden EMPTY-diff** (`git diff --exit-code` on committed sample outputs) — the
  top-level behaviour-preservation proof.
- **PAX parity run** with `-Pgrazel.internal.reachabilityParity=true` — single-pass set ==
  fixpoint set, no mismatch.
- **PAX acceptance** (migrate + both APKs) green.
- **Perf note (record before/after):** collect-task target-builder evaluations drop from
  `~(depth+1)×N` to `N`.
- **Regression test:** a diamond / multi-level project graph where the single reverse pass
  yields the same reachability set as the fixpoint.

## Acceptance criteria

- The `repeat(...)` fixpoint loop is replaced by a single reverse-topological pass.
- `variantTypeFilter` edge coverage verified before loop removal.
- Parity green on PAX + sample golden empty-diff; old fixpoint removed after parity confirmed.
- Double-eval left intact; documented as deferred follow-up.

## Non-goal

Variant compression; any change to placement/ownership (that is Item 7).

## Grounding provenance

Direction (reverse-topological), equivalence (monotone union over DAG; diamonds safe), the
`variantTypeFilter` caveat, and the double-eval irreducibility were established by a
codebase-grounded analysis before this spec was written.
