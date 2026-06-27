# Altitude Layering — Itemized Spec Roadmap (aligned end vision)

> **Status:** Aligned 2026-06-27. This is the index/roadmap for the foundational
> "altitude layering" pass. It reconciles Codex's
> `2026-06-27-altitude-layering-refactor-plan.md` with the grounded findings from this
> session and itemizes the **scoped** work for Codex to execute.
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.

> **⚠️ Execution note — delegate to subagents; protect the main context.** Apply to every
> item below: wide reads, audits, and PAX runs go to focused subagents returning distilled
> results.

---

## Locked decisions (this session)

1. **Scope = high-clarity slices, NOT the full reshape.** Defer the deep
   `AggregatedDependencyResolver` / `DependencyBucketPlacementEngine` algorithm rewrite
   (it's net-neutral LOC and high-risk on a verified branch). Capture only the slices that
   buy "obvious which layer owns the bug" at low risk. **Every scoped item is
   behaviour-preserving.**
2. **Size guard = freeze current PAX as the accepted baseline.** Bucket count, pinfile
   count, and total artifact roots must **never increase** from the current verified PAX
   output. Further reductions are a win. Master is NOT the target (it over-bucketed).
   Correctness gates (PAX builds) stay primary; legitimate version-forcing closure is
   exempt from "bloat."

## Three amendments to Codex's plan (carry into every item)

1. **Success = altitude/clarity, NOT complexity reduction.** Measured: only ~6% (~140–180
   lines) genuinely deletes; added reconciliation/planner glue is comparable or larger;
   ~71% is irreducible or relocated. Judge these items by "which layer owns the bug,"
   never by LOC. Do not promise a code-size win.
2. **`ComputeWorkspaceDependencies` is NOT "the brain."** It's a 328-line post-processor —
   the smallest core file and the cleanest win. The real brain is
   `AggregatedDependencyResolver` (1,440) + `DependencyBucketPlacementEngine` (667) — which
   the scoped plan deliberately does NOT rewrite. Don't optimize the wrong file.
3. **The HYBRID reconciliation is load-bearing and must be a NAMED, TESTED responsibility.**
   "Declarations drive structure" is incomplete: undeclared transitives common to multiple
   leaves have no declared owner (intersection-to-hoist survives), and
   `compileOnly`/api-of-consumed-libs/conflict-resolved deps appear in the resolved closure
   declared elsewhere. Layer 3 owns: declared placement **+** undeclared-transitive
   placement **+** a defined declared-≠-materialized rule. Hand-waving it regresses output.

## The end-vision altitude (reference; from Codex's plan, amended)

```
Layer 0  Variant topology        VariantBuilder / Variant<*> / BucketHierarchyGraph
Layer 1  Cheap declared facts    DeclaredDependencyMetadataCollector + typed graph edges
Layer 2  Resolved value graph    AggregatedDependencyResolver (values+closure ONLY) + slim CWD
Layer 3  Bucket ownership plan    BucketOwnershipPlanner (declared placement + hybrid reconcile)
Layer 4  Workspace/render plans   WorkspacePlan / WorkspaceRenderPlan
Layer 5  Rendering                project/root gen + pinner (format only)
```
SCC is a **contained graph-service primitive for genuine cycles only** (a real one exists in
PAX: `deliveries-model-food ↔ food-testkit` via test edges). It must not drive bucket
ownership or the render workflow.

---

## Itemized specs (scoped; all behaviour-preserving)

Executed in this order; each golden-checked (sample empty-diff) + PAX-baseline-no-regress.

| # | Item | Status | Goal (one line) | Depends on |
|---|------|--------|-----------------|------------|
| **9** | Typed graph edges + test→app | **specced** (`item9`) | Sealed `DependencyGraphEdge`; `AndroidTestTargetProjectEdge`; the plan's Phase-1 foundation | — |
| **10** | Frozen PAX baseline + automated size guard | to spec | Freeze current PAX output; automated guard: bucket/pinfile/total-roots never increase; reductions = win | — (do first) |
| **11** | Contain SCC in the graph layer | to spec | Move the cyclic fixpoint out of `CollectTargetMavenRepoReferencesTask` into `ProjectReachabilityOrder`/graph service; bucket ownership = DAG math only; no global fixpoint in render/workspace tasks | 9 |
| **12** | Extract `BucketOwnershipPlanner` (Layer 3) | to spec | Pull ownership decisions out of `AggregatedDependencyResolver` into a named Layer-3 model **wrapping existing logic** (no algorithm rewrite); name + test the hybrid reconciliation | 10 |
| **13** | Slim `ComputeWorkspaceDependencies` to a value-holder | to spec | Move CWD's duplicate-collapse / override-synthesis into the planner/plan layer; CWD keeps flatten / max-version / transitive / KSP (Layer-2 values) | 12 |
| **14** | Rendering purity + hygiene | to spec | Production-wire or remove `commonAncestorsOf`/`closestCommonAncestorsOf`; delete dead-code residues (discarded `readText`, dead `materializedMavenRepos` fallback); add `WorkspaceRenderPlanBuilder` unit test; refresh stale `PAX-BOUNDED-AUDIT-BASELINE.md`; confirm zero generated-output feedback | last |

**Key property:** Items 10–14 are all expected **golden empty-diff** (Item 10 adds tooling; 11–14 are extractions/cleanups). Any non-empty diff is a stop-and-investigate, not a re-baseline — because the scoped plan changes *where* logic lives, never *what it computes*.

## Out of scope (the deferred deep rewrite)

The full Layer-2/Layer-3 algorithm rewrite (replacing the resolver's set-math with a
purely declaration-driven planner) is **deferred**. It is net-neutral LOC, output-changing,
and carries the hybrid-reconciliation risk. Revisit only if a concrete future bug makes the
current resolver/placement code the obstacle — at which point it gets its own
adversarial-gated spec.

## Cross-checks before Codex executes

- Each item inherits the Item 1 Global Constraints + Verification Playbook.
- Item 12 (the riskiest extraction) should get an adversarial-Opus feasibility gate before
  implementation — "extract without changing behaviour" is exactly where a subtle diff hides.
- The size guard (Item 10) must exist before 11–14 so each extraction is regression-checked.
