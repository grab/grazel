# Item 11 — Contain SCC in the Graph Layer (Design)

> **Status:** Approved 2026-06-27. **Executor:** Codex.
> **Behaviour change:** none (documentation + a regression guard test; no production logic
> change). Golden empty-diff.
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `reports/specs/ALTITUDE-LAYERING-ROADMAP.md`. **Depends on:** Item 9.

> **⚠️ Execution note — delegate to subagents; protect the main context.**

---

## Goal

Lock in the SCC-containment altitude so it can't regress. **Grounding found the containment
already holds** — this item *verifies, documents, and guards* it rather than relocating
anything.

## Current state (verified — do not re-litigate)

| Dimension | State | Evidence |
|---|---|---|
| SCC detection/condensation | CLEAN — entirely inside `ProjectReachabilityOrder` (Kosaraju) in the graph-layer file | `TopologicalSorter.kt` (`ProjectReachabilityOrder`, `consumersFirstGroups`, `stronglyConnectedComponents`) |
| Task fixpoint | LOCAL to genuine cyclic SCC groups only; acyclic singletons single-pass | `CollectTargetMavenRepoReferencesTask.kt` `collectCyclicProjectGroup` (bounded `repeat`, scoped to one cyclic group) vs the acyclic `fold` |
| Bucket ownership | DAG-only — pure ancestor/descendant math, no SCC/fixpoint | `DependencyBucketPlacementEngine.plan()` (deepest-first single forward pass over `BucketHierarchyGraph.ancestorsOf`/`leafDescendantsOf`); `AggregatedDependencyResolver.ResolutionSession.resolve()` straight pipeline |
| Render / workspace builders | No iterate-to-convergence | `ProjectBazelFileBuilder`, `RootBazelFileBuilder`, `WorkspaceBuilder`, `DefaultWorkspacePlanService` are linear; `WorkspaceRenderPlanBuilder` has only a finite bounded BFS over override labels (not a fixpoint) |

A genuine project cycle exists in PAX (`deliveries-model-food ↔ food-testkit` via test
edges), so SCC handling cannot be removed — only contained. It already is.

## Work (small, behaviour-preserving)

1. **Document the containment invariant** as code-level KDoc and a one-paragraph entry in
   `reports/specs/DO-NOT-REVISIT.md`:
   > "SCC/cycle handling lives only in `ProjectReachabilityOrder` (graph layer). Bucket
   > ownership (`DependencyBucketPlacementEngine`, `AggregatedDependencyResolver`) is DAG
   > ancestor/descendant math only — no SCC, no iterate-to-convergence. The sole fixpoint is
   > the bounded local fixpoint over a genuine cyclic SCC group in
   > `CollectTargetMavenRepoReferencesTask`; it must never become a global fixpoint, and SCC
   > must never enter bucket ownership or the render/workspace builders."
2. **Add a regression guard test** that pins the invariant so a future change can't
   reintroduce a global fixpoint or leak SCC into ownership:
   - assert `DependencyBucketPlacementEngine.plan()` is deterministic in a single pass
     (e.g. running it twice yields identical output; it does not depend on iterate-to-fixpoint);
   - assert `ProjectReachabilityOrder` returns acyclic groups as single-project
     non-cyclic groups and only multi-node SCCs as `cyclic = true` (extends existing
     `TopologicalSorterTest` cases);
   - (optional) a structural assertion that the cyclic fixpoint is reached only for
     `group.cyclic` groups.

## Explicitly NOT done

- **No relocation** — SCC is already in the graph layer.
- **The redundant `populateRenderPlan` publish** inside `collectCyclicProjectGroup`'s inner
  loop is **left as-is.** It is correct (publishing the latest accumulation so the next
  project's gating sees it); moving *when* it publishes would change the fixpoint's
  within-pass propagation and risk convergence/output. It is a perf micro-redundancy, not
  altitude debt — leave for a separate careful perf pass if ever.

## Validation

- Sample golden EMPTY-diff (no production logic change).
- The new guard test passes; existing `TopologicalSorterTest` stays green.
- PAX acceptance unchanged.

## Acceptance criteria

- Containment invariant documented (KDoc + `DO-NOT-REVISIT.md`).
- Regression guard test added and green; golden empty-diff; PAX unchanged.

## Out of scope / Non-goal

- Any SCC relocation (already contained); the `populateRenderPlan` tightening (deferred);
  variant compression.
