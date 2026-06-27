# Item 11 — Eliminate False SCCs; Keep SCC Only As Diagnostic Fallback (Design)

> **Status:** Approved 2026-06-27. **Executor:** Codex.
> **Behaviour change:** preserving unless Item 9 exposed and fixed a real under-collection.
> Golden empty-diff against the post-Item-9 baseline.
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `reports/specs/ALTITUDE-LAYERING-ROADMAP.md`. **Depends on:** Item 9.

> **⚠️ Execution note — delegate to subagents; protect the main context.**

---

## Goal

Make SCC handling a defensive diagnostic, not the normal reachability mechanism.

The previous assumption that PAX has a genuine project cycle is now challenged. The known
case:

```text
:deliveries:deliveries-model-food
  testImplementation project(":food-testkit")

:food-testkit
  implementation project(":deliveries:deliveries-model-food")
```

is cyclic only after collapsing source sets into plain project nodes. Typed correctly:

```text
deliveries-model-food:test -> food-testkit:main
food-testkit:main -> deliveries-model-food:main
deliveries-model-food:test -> deliveries-model-food:main
```

This is a DAG. Item 11 proves Item 9's typed graph projections eliminate this false SCC and
then removes or fails closed on the local cyclic fallback unless a genuine typed SCC remains.

## Current State To Change

Current reachability has the right containment shape but still accepts project-level SCCs:

| Dimension | Current state | New stance |
|---|---|---|
| SCC detection/condensation | Inside `ProjectReachabilityOrder` | Keep only as typed-node diagnostic fallback |
| Task fixpoint | Local to cyclic groups in `CollectTargetMavenRepoReferencesTask` | Remove if PAX has no genuine typed SCC; otherwise require proof |
| Bucket ownership | DAG-only ancestor/descendant math | Must remain DAG-only |
| Render/workspace builders | No generated-file parsing fixpoint | Must remain no generated-file parsing |

The false-SCC lesson belongs in the graph model, not in renderer/task compensation.

## Work

1. **Add typed-SCC audit.** Add a focused diagnostic that reports SCCs using typed graph
   nodes/projections, not plain project paths. It must identify:
   - node identity (`projectPath`, variant/source-set/type);
   - edge type (`ConfigurationEdge`, `AndroidTestTargetProjectEdge`, etc.);
   - whether the SCC is same-projection genuine or caused by projection collapse.
2. **Prove PAX false SCC disappears.** Add a test fixture mirroring
   `deliveries-model-food:test -> food-testkit:main -> deliveries-model-food:main` and assert
   it is acyclic in typed reachability.
3. **Tighten runtime behavior.**
   - If no genuine typed SCC remains in PAX/sample, remove `collectCyclicProjectGroup` and
     make unexpected SCCs fail with the typed diagnostic.
   - If a genuine typed SCC remains, keep the bounded local fallback but require a durable
     explanation in `KNOWN-LIMITATIONS.md` / `DO-NOT-REVISIT.md`, plus a regression test that
     proves the cycle cannot be represented as a typed DAG.
4. **Document the invariant.** Update docs to say:
   > "Bucket ownership and normal reachability are DAG-first. SCC is not a modeling strategy.
   > SCC fallback may exist only for proven genuine typed cycles and must emit typed
   > diagnostics. False SCCs caused by project-level collapse are graph modeling bugs."

## Validation

- Focused typed graph tests pass.
- PAX false SCC case is acyclic under typed projection.
- If fallback is removed, no generated output changes except any Item 9 accepted correctness
  diff.
- If fallback remains, the genuine typed SCC proof is documented and tested.
- No bucket ownership, Maven repo materialization, pin artifact, or generated BUILD/WORKSPACE
  output change is accepted in this item unless it is the already-classified Item 9
  correctness diff.

## Acceptance Criteria

- Known `deliveries-model-food ↔ food-testkit` false SCC is not treated as cyclic after typed
  graph projection.
- SCC handling is either removed from the normal PAX path or retained only with typed proof of
  a genuine same-projection cycle.
- Any remaining SCC failure/error message prints typed nodes and edge types.
- Bucket ownership remains DAG-only and does not call SCC/fixpoint logic.
- Golden empty-diff against the post-Item-9 baseline, or documented reuse of Item 9's
  classified correctness diff.

## Out Of Scope / Non-Goal

- Bucket ownership changes.
- Variant compression.
- Lint ownership.
- Rewriting target builders.
