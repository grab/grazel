# Item 9 — Reachability Target Edges (test→app ordering) (Design)

> **Status:** Approved 2026-06-26 (grounded against codebase). Follow-up spec.
> **Executor:** Codex. **Behaviour change:** Stage 1 none (empty-diff); Stage 2 expected
> empty-diff, else a documented correctness fix (re-baseline) — see Validation.
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Depends on:** Item 8 (reachability SCC/consumers-first ordering in place).

> **⚠️ Execution note — delegate to subagents; protect the main context.** Graph/call-site
> verification and PAX runs go to focused subagents returning distilled results.

---

## Goal

Make target-reachability ordering correct-by-construction for `com.android.test` modules.
The reachability graph currently carries only Gradle **Configuration** edges, so the
`com.android.test` `targetProjectPath → app` relation (not a configuration dependency) is
absent. Without it, ordering falls back to project-path order and a target-only consumer can
be processed before the project it references.

## Decisive finding (shapes the whole design)

The `Configuration` edge **value is write-only — zero readers.** `grep edgeValue|
edgeValueOrDefault` across main+test = 0 hits; `DependencyGraphs.mergeToProjectGraph`
(`DependencyGraphs.kt:110-126`) discards values and returns `Map<Project, Set<Project>>`;
both consumers (`ProjectReachabilityOrder.consumersFirstGroups`, `TopologicalSorter.sort`)
use topology only. Therefore widening the edge value type is nearly free, and the "preserve
Configuration consumers" concern is moot — there are none.

## Design decisions

- **Sealed edge value, not a separate graph.** Change `variantGraphs` value type to
  `ImmutableValueGraph<Project, DependencyGraphEdge>` where
  `sealed interface DependencyGraphEdge`. Keeping one graph as the single source of truth is
  strictly better on altitude than a parallel reachability graph that can silently diverge.
  Because there are zero value-readers, this is low-risk.
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
  `ConfigurationEdge`; add the test→app edge wiring.
- `gradle/dependencies/model` (or alongside `DependencyGraphs`): the `DependencyGraphEdge`
  sealed type.
- `FakeDependencyGraphs.kt:30` — one-line value-type change; fakes drive everything through
  `projectGraph`, so no test logic changes.
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

## Validation

- **Stage 1:** sample golden EMPTY-diff; focused tests; PAX green.
- **Stage 2:** sample golden **expected** empty-diff. The reachability fixpoint is a monotone
  union, so if PAX already converges correctly the edge only hardens ordering ⇒ empty-diff.
  **If Stage 2 DOES change output, that diff is a documented correctness fix** (the fallback
  order was under-collecting a target-only-reachable project) — classify it diff-by-diff and
  re-baseline. An unexplained diff is stop-and-investigate.
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
   alphabetically earlier, through `collectTargetMavenRepoReferences`, asserting the app's
   Maven repo references are visible to the test module (app processed first).

## Risk

The new edge enters the `{ true }`-filtered reachability set
(`CollectTargetMavenRepoReferencesTask.kt:96`). If an app transitively depends back on the
test module, this forms an SCC — but `consumersFirstGroups` already condenses SCCs and the
cyclic local fixpoint handles them (`:212-249`), so the failure mode is *slower convergence,
not a crash*. Covered by test #3 (acyclic assertion).

## Out of scope / defer

- `TargetReferenceEdge`; associates/instruments/lint edges; `AndroidInstrumentationBinary`
  associates (intra-project).
- A `configurationEdges()` typed accessor (no caller needs it — adding it pre-emptively is a
  broken window).
- Removing the build-vs-test `variantTypeFilter` split.

## Non-goal

Variant compression; bucket ownership/pin-size (Item 7).

## Grounding provenance

The zero-readers finding, the `TestExtension.targetProjectPath` config-time availability, the
minimal-edge-set conclusion, and the two-stage plan were established by a codebase-grounded
analysis before this spec was written.
