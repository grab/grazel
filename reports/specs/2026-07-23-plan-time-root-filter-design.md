# Design — Plan-time root filtering (adversarial-review item 4, evidence-first)

> **STATUS: CLOSED-NOT-WORTH-IT at GATE 0 (2026-07-23).** Phase 0 measured zero dropped
> roots on both corpora (`reports/review/item4-plan-time-filter-evidence.md`); the premise
> (wasted resolutions) is empirically false and near-structurally impossible. Phases 1-2
> were never built. The resolver-side filter stays as-is — it guards a constructible shape
> (see the evidence doc's advisory section). This spec is retained as the record of the
> methodology and the go/no-go reasoning.

Source finding: `reports/review/03-critic-inversion.md` item 3 ("wasted resolution work in
`shouldResolveMainHierarchyRoot`"); ratified in `reports/review/06-synthesis.md` (backlog
item 4). Branch: `arun/dependencies-refactor`.

## Problem

`shouldResolveMainHierarchyRoot` (`MainReachabilityTracker.kt:166-173`) rejects
MAIN_HIERARCHY/AndroidBuild roots whose bucket name is not a real leaf build-type. It runs at
**task-execution time**, inside `AggregatedDependencyResolver.resolve()`
(`AggregatedDependencyResolver.kt:121-123`) — but by then the cost is already paid:
`WorkspaceDependencyInputsRegistrar` wired every planned root's
`configuration.incoming.resolutionResult.rootComponent` into
`ResolveWorkspaceDependenciesTask`'s `@Input ListProperty` at configuration time
(`WorkspaceDependencyInputsRegistrar.kt:113-119`). The wiring is lazy (Providers), but the
`@Input` contract materializes every provider when the task executes — forcing a full Gradle
dependency resolution per root — *before* the task body's filter refuses to walk some of them.
Doomed roots therefore cost a full resolution each and pollute the task's input fingerprint,
for zero contribution.

Phase timeline (the fix moves the DECISION, not the resolution, across phases):

```
 CONFIGURATION (projectsEvaluated)         EXECUTION (ResolveWorkspaceDependenciesTask)
 planner enumerates ALL roots       →      1. @Input materializes → Gradle resolves EVERY root  $$$
 registrar wires ALL providers             2. resolver filter drops some roots (too late)
                        ── becomes ──
 planner filters (live Variant<*>) →       1. @Input materializes ONLY kept roots
 registrar wires kept providers            2. resolver filter = assertion (no-op)
```

## Structural facts (verified in code)

- Planning runs inside `gradle.projectsEvaluated` with the **live variant model**
  (`variantsByProject: Map<Project, Iterable<Variant<*>>>`) in hand
  (`WorkspaceDependencyInputsRegistrar.kt:88-96`); `RootInput` already carries
  `metadataVariant: Variant<*>?`. The filter's inputs (per-project leaf build-type names) are
  derivable at plan time — but today's filter reads them from `DeclaredDependencyMetadata`
  **JSON produced by an earlier task**, which does not exist at configuration time. The hoist
  therefore substitutes data sources (JSON → live variants); that substitution is the central
  correctness risk and is what the phases prove.
- `rootInputs` feeds **three consumers** (`WorkspaceDependencyInputsRegistrar.kt:113-135`):
  resolve components (ListProperty), metadata items (a parallel list the resolve task later
  **zips by index** — critic-03 item 4), and `pinMavenArtifactsTask` (raw configurations).
  Any filtering MUST apply to resolve+metadata as a pair or the zip silently misaligns.
  Filtering in the planner keeps all three consistent by construction.

## Decisions (agreed in brainstorm)

- **One effort, three phases, escalate at gates.** Controller adjudicates mechanical gates;
  the user rules on (a) the phase-0 worth-it threshold and (b) nothing else unless evidence
  surprises. Each phase is independently shippable; each has a kill switch.
- **Phase-2 filter lives in the planner** (single source, zip-safe), with the pin-task
  consumer decision selected by phase-0 evidence.

## Phase 0 — Measure (uncommitted instrumentation, one PAX migrate)

Temporary working-tree patch (PAX composite build picks it up; grep-able prefix
`GRAZEL-ITEM4`; never committed):

1. **Payoff counter** — resolver logs each rejected root
   (`DROP kind= project= bucket=`) plus kept/dropped totals. Registrar wraps each
   `rootComponent` provider in `.map { }` logging a materialization timestamp; successive
   deltas approximate per-root resolution cost (materialization is sequential). Primary
   number: total `resolveWorkspaceDependenciesTask` duration + dropped count.
2. **Parity probe** — planner logs a live-variant verdict per MAIN_HIERARCHY candidate
   (`PLAN keep|drop`, from `Variant<*>` leaf build-types); resolver logs today's JSON verdict.
   Offline diff by root key. Divergence found here blocks phase 1 until explained.
3. **Pin-coupling probe** — log the configurations `pinMavenArtifactsTask` receives vs the
   dropped set; record whether the intersection is empty.
4. **Configuration-phase baseline** — `System.nanoTime` around
   `WorkspaceDependencyRootInputPlanner.plan(...)` (and around the whole `projectsEvaluated`
   callback), logged as `GRAZEL-ITEM4 PLAN-TIME <ms>`. This is the before-number for the
   config-phase budget: the phase-2 predicate must not measurably grow it.

Evidence committed to `reports/review/item4-plan-time-filter-evidence.md`; patch reverted.

**Gate 0 (USER decides):** dropped count × estimated per-root cost. Worth-it → phase 1.
Not worth it → tag backlog item 4 `[CLOSED-NOT-WORTH-IT]` with the numbers; effort ends.
Mechanical sub-gates (controller): parity mismatch → blocked pending explanation; pin
intersection non-empty → phase 2 uses the mark-not-drop shape.

## Phase 1 — Parity shadow (committed, behavior-neutral)

- Planner computes its verdict and **stamps it into `AggregatedDependencyRootMetadata`**
  (new nullable field, e.g. `plannedMainLeafBuildType: Boolean?` — null for kinds the filter
  never applies to, so serialization stays backward-compatible).
- Resolver keeps filtering exactly as today, plus a `check` that the JSON-derived verdict
  equals the stamp for every MAIN root — loud failure on mismatch, byte-identical output
  otherwise.
- Gates: full local (unit + golden + bazel analysis) + full PAX sweep (§PAX 1-6).

**Gate 1 (controller, mechanical):** sweep green, zero parity failures → phase 2. Any
failure → stop; the mismatch becomes a documented finding (item-2 pattern) and the effort
ends at phase 1 — still net-positive, the equivalence is now continuously guarded.

## Phase 2 — The switch

- Filter in `WorkspaceDependencyRootInputPlanner.plan`: MAIN_HIERARCHY/AndroidBuild
  candidates whose bucket is neither `DEFAULT_VARIANT` nor a live leaf build-type are
  dropped (expected shape, pin intersection empty) or marked-not-dropped with the registrar
  wiring resolve+metadata from the kept set and pin from the full set (fallback shape, only
  if phase-0 evidence demands).
- Resolver's filter degrades to an assertion against the stamp ("every root received is
  walked") — kept as a tripwire; its deletion is a follow-up after a stable release.
- Gates: full local + full PAX sweep + **before/after migrate timing** — the effort ships
  with a measured number (closing the cohesion report's "no empirical measurement" gap).
- **Configuration-phase budget check**: re-measure `plan(...)` duration (same probe as
  phase 0 item 4) with the predicate live. Pass condition: delta within noise of the phase-0
  baseline (the predicate is O(variants-per-project) in-memory metadata reads and computes
  `mainLeafBuildTypeNames` once per project, reusing the already-sorted variant list — no
  resolution, no I/O). A measurable regression here is a stop-and-investigate, not a
  shrug — configuration time is paid by every Gradle invocation, not just migrate.
- Byte-identity expectation: generated output unchanged (the dropped roots contributed
  nothing — that is the premise phase 1 proved). Any golden/PAX output drift = stop, revert,
  report; do not patch baselines.

## Correctness spine

- Phase 0 only logs. Phase 1 can only fail loudly (check), never change output. Phase 2's
  substitution has by then been proven empirically (phase-0 diff) and continuously (phase-1
  check across every intervening run).
- The zip-by-index invariant is protected by filtering at the single source (planner).
- The resolver-side assertion converts any missed case into a migrate failure instead of
  silent under-resolution.
- Golden + PAX sweeps arbitrate at every committed step.

## Out of scope

- Fixing zip-by-index itself (backlog item 10) — this effort only avoids worsening it.
- S2 memoization (backlog 7); LINT-root planning residue; `bucket/`; deleting the
  resolver-side assertion (post-release follow-up).

## Risks

- **Live-vs-JSON divergence** — the central risk; phases 0-1 exist to surface it before it
  can move output. If real, the effort ends with a documented invariant instead of a switch.
- **Pin-task coupling** — measured in phase 0; drives the phase-2 shape rather than being
  assumed.
- **Sample corpus weakness** (item-2 lesson) — samples may drop zero roots; PAX is the
  load-bearing evidence for every phase, hence mandatory sweeps on both committed phases.
