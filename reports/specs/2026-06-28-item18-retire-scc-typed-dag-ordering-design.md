# Item 18 — Retire SCC From Reachability Ordering; Use Typed DAG Topo Sort (Design)

> **Status:** Draft for final review 2026-06-28 (grounded against codebase).
> **Executor:** Codex.
> **Behaviour change:** none — ordering output must be byte-identical. Golden EMPTY-diff.
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`. **Depends on:** Items 9 + 11 (typed graph +
> fail-closed contract landed) and Item 17 (executes after the set-math consolidation).
> **Supersedes** Slice 17 of `2026-06-28-next-slices-scc-and-target-references-draft.md`.

> **⚠️ Execution note — delegate to subagents; protect the main context.** Graph/ordering
> verification and PAX runs go to focused subagents returning distilled results.

---

## Goal

Items 9 and 11 made the reachability graph a **typed DAG** (`DependencyGraphNode(project,
sourceSet)`), proved the known PAX false cycle acyclic by construction, removed the task-local
fixpoint, and made ordering **fail-closed** on any real cycle. As a result, Kosaraju SCC +
condensation in `ProjectReachabilityOrder` is now **vestigial dead weight**: every component is
a singleton, the condensation does topological work over trivial 1-node components, and the
`cyclic` flag it feeds is permanently `false`. Replace SCC ordering with a direct typed-DAG
topological sort, keep the fail-closed typed cycle diagnostic, and delete the now-dead cyclic
plumbing. **Golden empty-diff** — this is dead-code/altitude cleanup, not a behaviour change.

## Grounded current state (verified)

In `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/TopologicalSorter.kt`:

- `ProjectReachabilityOrder.consumersFirstGroups` (`:174`) is the sole reachability-ordering
  entry point. It unconditionally runs `stronglyConnectedComponents(...)` (`:183`), filters
  cyclic components (`:184`), and **fails closed**: `check(cyclicComponents.isEmpty()) { … }`
  with `typedCycleDiagnostic(...)` (`:187–188`).
- Every emitted group is hardcoded `cyclic = false` (`:227`); given the no-cycle guarantee,
  condensation only ever produces singleton components.
- Consumers-first order = component topo order tie-broken by `project.path` (`:211`), then
  `.asReversed()` (`:213`). **This ordering and tie-break are golden-diff-sensitive and must be
  preserved byte-for-byte.**
- The SCC machinery to remove: `stronglyConnectedComponents` (`:270`), `finishOrder` (`:303`),
  `reverseGraph` (`:330`), `sortComponentIndexes` (`:342`), and the condensation logic inside
  `consumersFirstGroups`.
- **Already present and reusable:** generic `dependencyFirstOrder(graph, comparator)` (`:135`)
  — a Kahn-style DAG topo sort already used by the legacy `sort()` path (`:44`).
- **Keep:** `typedCycleDiagnostic` (`:233`), `findCycle` (`:71`)/`reconstructCyclePath`
  (`:115`), `displayName` (`:259`), the node comparator (`:354`) — the fail-closed contract
  from Item 11 stays.
- `ProjectReachabilityGroup` (`:168`) has a `cyclic: Boolean` field (`:170`) that is now
  permanently `false`. Its only reader is
  `tasks/internal/CollectTargetMavenRepoReferencesTask.kt:178` (`check(!group.cyclic)`), a
  structurally-unreachable assertion.

## Desired change

1. **Add an order-parity fixture before removing SCC.** Capture the current
   `ProjectReachabilityOrder.consumersFirstGroups` output for representative typed graphs:
   - the PAX false-cycle shape;
   - `com.android.test -> app` path-order trap;
   - a multi-ready-node graph where project path tie-break matters;
   - multiple source-set nodes for the same project.
   The fixture should assert project-group order, not just membership. Keep it green before and
   after the rewrite. This is the guard against accidental reference-collection order changes.
2. **Replace SCC ordering with direct typed Kahn sort.** Rewrite `consumersFirstGroups` to:
   - run `dependencyFirstOrder` over the typed reachability graph
     (`Map<DependencyGraphNode, Set<DependencyGraphNode>>`) using the existing node comparator;
   - detect cycles directly when Kahn ordering returns fewer nodes than the normalized graph;
   - **fail closed** via a typed diagnostic that does not depend on SCC components. Implement
     either `typedCycleDiagnostic(unprocessedNodes, diagnosticEdges)` or
     `typedCycleDiagnostic(cycleNodes, diagnosticEdges)` and keep the message shape:
     typed node identity plus diagnostic edge labels;
   - produce the consumers-first grouping by reversing the dependency-first typed order and
     deduping to projects, **preserving the exact `project.path` tie-break and `.asReversed()`
     semantics** so the emitted order is byte-identical to today's.
3. **Delete the SCC machinery:** `stronglyConnectedComponents`, `finishOrder`, `reverseGraph`,
   `sortComponentIndexes`, and the condensation code path — once no production caller remains.
4. **Delete the vestigial cyclic plumbing:** remove `ProjectReachabilityGroup.cyclic` (always
   `false`) and the now-dead `check(!group.cyclic)` in `CollectTargetMavenRepoReferencesTask`
   (`:178`). If `ProjectReachabilityGroup` becomes a thin single-project wrapper, keep it for
   API stability but drop the dead field.
5. **Drop the redundant `normalized()` rebuild** (`TopologicalSorter.kt:261`). The simplify
   audit found `consumersFirstGroups` calls `normalized()` unconditionally, rebuilding the
   whole graph map, but both of its effects are already provided by `reachabilityProjection`:
   every edge target is added as a key via `getOrPut(target)`, and the projection already emits
   `sortedMapOf`/`sortedSetOf`. **Medium risk** — verify the claim before removing: confirm no
   node appears in a value set without also being a key (the subtle case is a pure-sink node
   reached only via `graph.edges()`), and that the comparator order is unchanged. If the new
   Kahn rewrite naturally consumes the projection directly, this redundancy disappears with it;
   either way the order-parity fixture (item 1) is the guard.

The legacy `sort()`/`findCycle` project-graph path (`:34`–`:133`) is a separate API; touch it
only if it shares helpers being removed. Do not widen its behaviour.

## The load-bearing constraint

This item **must not change generated output**. The consumers-first ordering drives the
single-pass reference collection in `CollectTargetMavenRepoReferencesTask`; any reordering
would change collected references and break the golden. The new topo sort must reproduce the
current order **exactly**, including:

- the `project.path` tie-break at component/node selection (`TopologicalSorter.kt:211`/`:148`);
- the `.asReversed()` consumers-first inversion (`:213`);
- the project-dedup that collapses source-set nodes back to one group per project.

If the new sort produces any different order on PAX or sample, that is a bug in the rewrite,
not an accepted diff — STOP and reconcile.

## Required tests

- **False-SCC regression (the proof that guards the removal):** model
  `deliveries-model-food:test → food-testkit:main`, `food-testkit:main →
  deliveries-model-food:main`, `deliveries-model-food:test → deliveries-model-food:main`;
  assert the typed order is acyclic and processed single-pass (no cyclic group). **Confirm
  whether this test already exists** (Item 11 may have added it) before adding — if present,
  ensure it now asserts against the SCC-free path. This test must exist and pass *before* the
  SCC machinery is deleted.
- **Genuine cycle fails closed with typed diagnostic:** a real typed cycle (e.g. `a:main →
  b:main`, `b:main → a:main`) throws via `typedCycleDiagnostic` printing typed node identity
  (`project.path[sourceSet]`) and edge labels (`ConfigurationEdge`,
  `AndroidTestTargetProjectEdge`).
- **`com.android.test → app` ordering stays consumer-first:** the existing Item 9 guard
  (`ProjectReachabilityOrder.consumersFirstGroups` with a `com.android.test` `:test` depending
  on `:app`, named so the path-order tie-break would otherwise put `:app` first) still asserts
  `:test` lands in an earlier consumers-first group under the new sort.
- **Single-pass collection unchanged:** `CollectTargetMavenRepoReferencesTask` still processes
  each acyclic project exactly once and collects identical references.
- **Order parity for graph shapes that can reorder:** direct typed Kahn ordering emits the same
  project-group order as the pre-removal implementation for multi-ready-node and same-project
  multi-source-set fixtures. This test must fail if a comparator/tie-break change would alter
  reference collection order.

## Safety mechanism

- **Sample golden EMPTY-diff** + **PAX generated diff stable** against the frozen Item 10
  baseline. Any PAX diff is stop-and-investigate.
- **Order-preservation is the gate:** the rewrite is correct only if the emitted group order is
  byte-identical; the golden + explicit project-group order parity tests are the proof.
- **Delete only the provably-unreachable:** confirm zero production callers of each SCC helper
  and of `ProjectReachabilityGroup.cyclic` before removing.
- **Size guard (Item 10):** no change expected.

## Win

The normal model is a typed DAG, so ordering becomes a typed DAG topo sort with a typed
cycle diagnostic — the code says what it means. ~150 lines of SCC/condensation machinery and a
permanently-false flag plus its dead assertion are removed; the fail-closed contract is kept.

## Acceptance criteria

- `consumersFirstGroups` uses direct typed Kahn ordering (reusing `dependencyFirstOrder`); no
  Kosaraju/condensation remains on the production path.
- `stronglyConnectedComponents`, `finishOrder`, `reverseGraph`, `sortComponentIndexes` deleted
  (no production caller); `typedCycleDiagnostic`/`findCycle` retained as the fail-closed branch.
- `ProjectReachabilityGroup.cyclic` and the `check(!group.cyclic)` in
  `CollectTargetMavenRepoReferencesTask` removed.
- False-SCC regression test exists and passes against the SCC-free path; genuine-cycle
  fail-closed test prints typed nodes + edge labels without SCC component input;
  `com.android.test → app` consumer-first ordering test green; explicit order-parity fixtures
  prove no project-group reordering.
- Sample golden empty-diff; PAX generated diff stable; PAX builds green; size guard no-increase.

## Out of scope / Non-goal

- The legacy `sort()` project-graph API beyond shared-helper cleanup.
- Bucket ownership / set-math (Item 17).
- Target-reference facts (Item 19) and task cacheability (candidate future Item 20).
- Variant compression.
