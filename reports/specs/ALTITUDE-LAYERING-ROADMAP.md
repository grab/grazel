# Altitude Layering — Itemized Spec Roadmap (aligned end vision)

> **Status:** Aligned 2026-06-27 (full-layering scope). Cross-cutting index/roadmap for the
> foundational "altitude layering" pass. Reconciles Codex's
> `2026-06-27-altitude-layering-refactor-plan.md` with this session's grounded findings.
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.

> **⚠️ Execution note — delegate to subagents; protect the main context.** Applies to every
> item: wide reads, audits, and PAX runs go to focused subagents returning distilled results.

---

## Locked decisions (this session)

1. **Scope = FULL layering** (the deep planner rewrite is IN — not deferred). It is done as
   **two split steps to protect the verified build**:
   - **Relocate (behaviour-preserving):** build the Layer-3 `BucketOwnershipPlanner` by
     moving the *existing* ownership logic as-is → **golden empty-diff**. Proves the new
     layer is sound before any behaviour changes.
   - **Improve (output-changing):** replace the set-math inference with declaration-driven
     placement + the ownership corrections (test/lint deltas, common-owner de-dup) that
     actually reduce buckets → **parity-gated, re-baselined, diff-classified**.
2. **Transition safety for the improve step = parity old-vs-new, flag-gated.** Keep the old
   planner temporarily; behind a flag, run both and diff bucket-by-bucket on PAX, classifying
   every difference as intended-reduction vs regression, until confident; then remove the old
   path. (Same pattern as Item 8's parity scaffolding.)
3. **Size guard = freeze current PAX as the accepted baseline.** Bucket count, pinfile count,
   and total artifact roots must **never increase** from current PAX. Further reductions are a
   win. Master is NOT the target (it over-bucketed). Correctness gates (PAX builds) primary;
   legitimate version-forcing closure is exempt from "bloat."

## Three amendments to Codex's plan (carry into every item)

1. **Success = altitude/clarity + real bucket reduction, NOT a code-size win.** Measured: the
   rewrite is ~net-neutral LOC (~6% deletes, comparable added). Judge it by "obvious which
   layer owns the bug" and by *reduced buckets on PAX* — never by LOC.
2. **`ComputeWorkspaceDependencies` is NOT "the brain."** It's a 328-line post-processor — the
   smallest core file and cleanest win. The real brain is `AggregatedDependencyResolver`
   (1,440) + `DependencyBucketPlacementEngine` (667) — which Item 13 rewrites. Don't optimize
   the wrong file.
3. **The HYBRID reconciliation is load-bearing and must be a NAMED, TESTED Layer-3
   responsibility (Item 13).** "Declarations drive structure" is incomplete: undeclared
   transitives common to multiple leaves have no declared owner (intersection-to-hoist
   survives), and `compileOnly`/api-of-consumed-libs/conflict-resolved deps appear in the
   resolved closure declared elsewhere. Layer 3 owns: declared placement **+**
   undeclared-transitive placement **+** a defined declared-≠-materialized rule. Hand-waving
   it regresses output.

## End-vision altitude (from Codex's plan, amended)

```
Layer 0  Variant topology        VariantBuilder / Variant<*> / BucketHierarchyGraph
Layer 1  Cheap declared facts     DeclaredDependencyMetadataCollector + typed graph edges
Layer 2  Resolved value graph     AggregatedDependencyResolver (values+closure ONLY) + slim CWD
Layer 3  Bucket ownership plan     BucketOwnershipPlanner (declared placement + hybrid reconcile)
Layer 4  Workspace/render plans    WorkspacePlan / WorkspaceRenderPlan
Layer 5  Rendering                 project/root gen + pinner (format only)
```
SCC is a **contained graph-service primitive for genuine cycles only** (a real one exists in
PAX: `deliveries-model-food ↔ food-testkit` via test edges). It must not drive bucket
ownership or the render workflow.

---

## Itemized specs (full layering)

Executed in order; each golden-checked + PAX-baseline-no-regress (except Item 13, the one
intended output change).

| # | Item | Status | Behaviour | Goal (one line) | Depends on |
|---|------|--------|-----------|-----------------|------------|
| **9** | Typed graph edges + test→app | specced (`item9`) | preserving | Sealed `DependencyGraphEdge`; `AndroidTestTargetProjectEdge`; Phase-1 foundation | — |
| **10** | Frozen PAX baseline + size guard | to spec | preserving (tooling) | Freeze current PAX; automated guard: bucket/pinfile/total-roots never increase; reductions = win | — (first) |
| **11** | Contain SCC in the graph layer | to spec | preserving | Move the cyclic fixpoint out of `CollectTargetMavenRepoReferencesTask` into `ProjectReachabilityOrder`/graph service; bucket ownership = DAG math only | 9 |
| **12** | Extract `BucketOwnershipPlanner` (Layer 3) — RELOCATE | to spec | **preserving (empty-diff)** | Move existing ownership logic as-is into a named Layer-3 model; proves the layer before any behaviour change (Step A) | 10 |
| **13** | Declaration-driven ownership — IMPROVE | to spec | **OUTPUT-CHANGING** | Replace set-math inference with declared placement + test/lint deltas + common-owner de-dup; define+test the hybrid reconciliation; parity-gated, re-baselined (Step B). The deep rewrite. | 12 |
| **14** | Slim `ComputeWorkspaceDependencies` to value-holder | to spec | preserving | Move CWD's duplicate-collapse / override-synthesis into the planner/plan layer; CWD keeps flatten / max-version / transitive / KSP | 13 |
| **15** | Rendering purity + hygiene | to spec | preserving | Wire-or-remove `commonAncestorsOf`/`closestCommonAncestorsOf`; delete dead-code residues; add `WorkspaceRenderPlanBuilder` test; refresh stale baseline doc; confirm zero generated-output feedback | last |

**Behaviour property:** Items 10, 11, 12, 14, 15 are expected **golden empty-diff** (tooling /
extractions / cleanups). **Item 13 is the single intended output change** — guarded by
parity-old-vs-new on PAX + the frozen size guard, re-baselined with diffs classified.

## Cross-checks before Codex executes

- Each item inherits the Item 1 Global Constraints + Verification Playbook.
- **Item 13 gets an adversarial-Opus feasibility gate** before its spec is finalized — it
  defines the hybrid reconciliation (the riskiest design) and is the only output-changing step.
- Item 12 must come before 13 so the planner exists as a proven empty-diff layer before its
  algorithm changes.
- Item 10's size guard must exist before 11–15 so each step is regression-checked.
