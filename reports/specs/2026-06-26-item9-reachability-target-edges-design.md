# Item 9 — Typed Reachability Graph Nodes + test→app Ordering (Design)

> **Status:** Approved 2026-06-26 (grounded against codebase). Follow-up spec.
> **Executor:** Codex. **Behaviour change:** Stage 1 none (empty-diff); Stage 2 expected
> empty-diff, else a documented correctness fix (re-baseline) — see Validation.
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Depends on:** Item 8 (reachability SCC/consumers-first ordering in place) and Item 10
> (size guard must exist before execution).

> **⚠️ Execution note — delegate to subagents; protect the main context.** Graph/call-site
> verification and PAX runs go to focused subagents returning distilled results.

---

## Goal

Make target-reachability ordering correct-by-construction for `com.android.test` modules.
The reachability graph currently carries only Gradle **Configuration** edges, so the
`com.android.test` `targetProjectPath → app` relation (not a configuration dependency) is
absent. Without it, ordering falls back to project-path order and a target-only consumer can
be processed after the project it references, too late to seed the referenced app target.

Ordering semantics are intentionally **consumer-first**:

```text
edge: testProject -> targetApp
processing order: testProject before targetApp
```

This is correct because `ProjectReachabilityOrder.consumersFirstGroups` reverses dependency-
first component order with `.asReversed()`, and `CollectTargetMavenRepoReferencesTask`
builds target references by publishing accumulated references before building the next
project's targets. The
`com.android.test` project must run first so its associate/instrumentation target can mark
the app target as referenced; then the app project can emit the app target and its Maven/
project references. The new edge is an ordering/reachability edge, not a Maven ownership
edge.

This item also fixes a deeper modeling issue: project-level graph collapse can fabricate
cycles by merging main/test realities into one node. The known PAX
`deliveries-model-food ↔ food-testkit` cycle is not assumed genuine:

```text
Collapsed project graph:
  deliveries-model-food -> food-testkit
  food-testkit -> deliveries-model-food

Typed source-set graph:
  deliveries-model-food:test -> food-testkit:main
  food-testkit:main -> deliveries-model-food:main
  deliveries-model-food:test -> deliveries-model-food:main
```

The typed graph is acyclic. Therefore this item must preserve source-set/variant identity in
reachability projections instead of relying on SCC to normalize false cycles.

## Decisive finding (shapes the whole design)

The `Configuration` edge **value is write-only — zero readers.** `grep edgeValue|
edgeValueOrDefault` across main+test = 0 hits; `DependencyGraphs.mergeToProjectGraph`
(`DependencyGraphs.kt:110-126`) discards values and returns `Map<Project, Set<Project>>`;
both consumers (`ProjectReachabilityOrder.consumersFirstGroups`, `TopologicalSorter.sort`)
use topology only. Therefore widening the edge value type is nearly free.

However, typed **edges** alone are not enough if graph nodes stay as plain `Project`.
Reachability must either use typed nodes or projection-aware typed graph APIs so
`main`, `test`, and `androidTest` edges are not collapsed into one project-level cycle.

## Design decisions

- **Typed graph model, not Project-only collapse.** Introduce a typed graph node or equivalent
  projection API, for example `DependencyGraphNode(projectPath, variantType/sourceSet)`.
  `VariantGraphKey` remains useful, but `mergeToProjectGraph` must not flatten all edge types
  into `Map<Project, Set<Project>>` before ordering. The graph service should expose a
  reachability projection that preserves `main`, `test`, and `androidTest` semantics until
  after ordering.
- **Sealed edge value.** Use `sealed interface DependencyGraphEdge` for edge meaning. Keeping
  typed edges in the graph service is better altitude than parallel ad hoc reachability maps.
- **Initial edge set = TWO variants only:**
  - `ConfigurationEdge(configuration)` — wraps today's edges (keeps write sites expressive).
  - `AndroidTestTargetProjectEdge(testProject → targetApp)` — the one new edge that fixes the
    bug.
  - **Do NOT add `TargetReferenceEdge` or associates/instruments/lint edges** — no near-term
    reader; associates/instruments resolve to the same app node the target edge already
    covers; lint isn't built into graphs. Defer all.
- **Edge source:** `TestExtension.targetProjectPath`, a config-time AGP property already read
  at `AndroidTestDataExtractor.kt:169-176` and resolvable via `findProject`. The builder can
  synthesize the edge from the Gradle model — no extracted target data needed.

## Where it lives

- `DependenciesGraphsBuilder.kt` — the three `putEdgeValue` sites (`:78-83`, `:120-125`,
  `:135-140`), the map helper (`:177-187`), `buildGraph` (`:199-205`): wrap writes in
  `ConfigurationEdge`; add the test→app edge wiring; preserve the source-set/variant node
  identity needed for typed reachability projections.
- `gradle/dependencies/model` (or alongside `DependencyGraphs`): the `DependencyGraphEdge`
  sealed type and any `DependencyGraphNode`/projection model.
- `FakeDependencyGraphs.kt:30` — one-line value-type change; fakes drive everything through
  `projectGraph` today, but new tests should prefer typed projections when asserting
  source-set behavior.
- Task layer stays orchestration-only; no BUILD/WORKSPACE scraping.

## Stages (smallest mergeable, PAX-green)

### Stage 1 — Introduce the type (behaviour-preserving)
Add `sealed interface DependencyGraphEdge { data class ConfigurationEdge(val configuration:
Configuration) }`. Re-type `variantGraphs` + all write sites + `FakeDependencyGraphs`; wrap
existing writes in `ConfigurationEdge`. Zero value-readers ⇒ **byte-identical output ⇒
golden EMPTY-diff, PAX green.** Merge alone first.

### Stage 2 — Add the edge + wiring + tests (the only ordering change)
Add `AndroidTestTargetProjectEdge`. In `DependenciesGraphsBuilder.build()`, for each
`com.android.test` subproject read `TestExtension.targetProjectPath`, resolve via
`findProject`, and `putEdgeValue(testProject, targetApp, AndroidTestTargetProjectEdge(...))`
into the AndroidTest variant graph(s). Keep small and isolated so any PAX diff is
attributable. Add the tests below.

### Stage 3 — Preserve source-set identity in reachability projections
Replace project-only reachability flattening with typed node/projection ordering. At minimum,
prove that the PAX-shaped `deliveries-model-food:test -> food-testkit:main ->
deliveries-model-food:main` graph is acyclic and does not enter the cyclic fallback. If a
project-level order is still needed by a task, derive it from the typed topological order
after graph ordering, not before.

## Validation

- **Stage 1:** sample golden EMPTY-diff; focused tests; PAX green.
- **Stage 2:** sample golden **expected** empty-diff. The reachability fixpoint is a monotone
  union, so if PAX already converges correctly the edge only hardens ordering ⇒ empty-diff.
  **If Stage 2 DOES change output, that diff is a documented correctness fix** (the fallback
  order was under-collecting a target-only-reachable project) — classify it diff-by-diff and
  re-baseline. An unexplained diff is stop-and-investigate.
- **Stage 3:** the known PAX false SCC (`deliveries-model-food ↔ food-testkit`) disappears
  under typed projection. If any SCC remains, it must be reported with typed nodes and proved
  as a genuine same-projection cycle before fallback is kept.
- **PAX acceptance** (migrate + both APKs) green either way.

## Tests (prove test→app ordering; prevent regression)

1. **Pure unit (core guard):** `ProjectReachabilityOrder.consumersFirstGroups` with `:test`
   (com.android.test) depending on `:app`, named so the path-order tiebreak
   (`TopologicalSorter.kt:198`) would otherwise put `:app` first — assert `:test` lands in an
   earlier consumers-first group. Uses `FakeDependencyGraphs(projectGraph = …)`, no AGP.
2. **Builder unit:** `DependenciesGraphsBuilder` emits `AndroidTestTargetProjectEdge` from a
   `com.android.test` project to its `targetProjectPath` (assert at `mergeToProjectGraph`
   topology level if mocking `TestExtension` is heavy).
3. **Cycle case:** a test+app with no back-edge stays two acyclic groups — no fabricated SCC
   (mirrors `sort should not detect cycle when test depends on build dependency`).
4. **Functional:** a sample `com.android.test` module whose `targetProjectPath` app is
   alphabetically earlier, through `collectTargetMavenRepoReferences`, asserting the test
   project is processed before the app and the final collected references include the app's
   Maven/project references.
5. **False SCC regression:** model `a:test -> b:main` and `b:main -> a:main`; assert the
   typed reachability order is acyclic and does not produce one cyclic project group.

## Risk

The risky failure mode is flattening typed test/main edges too early and reintroducing false
project cycles. Treat SCC fallback as defensive only. Keeping fallback for a PAX cycle
requires typed-node proof that the cycle is genuine, not a project-level collapse artifact.

## Out of scope / defer

- `TargetReferenceEdge`; associates/instruments/lint edges; `AndroidInstrumentationBinary`
  associates (intra-project).
- A `configurationEdges()` typed accessor (no caller needs it — adding it pre-emptively is a
  broken window).
- Removing all SCC fallback before Item 11 proves no genuine typed SCC remains.

## Non-goal

Variant compression; bucket ownership/pin-size (Item 7).

## Grounding provenance

The zero-readers finding, the `TestExtension.targetProjectPath` config-time availability, the
minimal-edge-set conclusion, and the two-stage plan were established by a codebase-grounded
analysis before this spec was written.
