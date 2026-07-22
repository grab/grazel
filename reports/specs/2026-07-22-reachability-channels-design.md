# Design — Resolve the dual reachability channels (adversarial-review item 2)

Source finding: `reports/review/03-critic-inversion.md` §1 ("two parallel reachability
channels") and §"The bigger question"; ratified in `reports/review/06-synthesis.md`
(backlog item 2). Branch: `arun/dependencies-refactor`.

## Problem

`MainReachabilityTracker` accumulates workspace reachability (`reachableMainProjectPaths`,
`reachableMainBucketNamesByProject`) from **two independent channels**:

1. **Declared-edge DFS (seed)** — `computeScope` walks declared `project(...)` edges from
   serialized metadata; `RootContributionComputer.compute` seeds it for MAIN_HIERARCHY /
   MAIN_LEAF roots *before* the root's closure is resolved
   (`RootContributionComputer.kt:151-156`).
2. **Walk-discovered (fold)** — `resolveRootToDependencyMap` records project edges
   encountered in the *resolved* component graph (`RootVisitOutcome.reachable*`), folded back
   for every non-LINT root via `recordReachable` (`AggregatedDependencyResolver.kt:134-137`).

Both feed the same two maps with union semantics, coupled by a seed-before-resolve ordering
invariant (17-line doc at `AggregatedDependencyResolver.kt:101-117`), a "do not double-seed"
comment, and a deliberately preserved asymmetry (`recordReachable` uses raw `addAll`, not the
blank-filtering `addReachableMainBuckets`, "to match the out-param it replaced").

## Narrowing (what is and is not in question)

- **`computeScope` stays regardless** — it is the sole producer of
  `excludedShortIdsByTargetProject`, consumed by `filterExcludedByEveryReachableRoot` and the
  walk's exclude handling. Only its *reachability* output overlaps the walk.
- **The walk fold stays for TEST roots regardless** — TEST_HIERARCHY / UNIT_TEST /
  ANDROID_TEST roots never seed; walk-discovery is their only reachability source.
- **The deletable candidate** is exactly: the `recordReachable` fold for MAIN_HIERARCHY /
  MAIN_LEAF roots. If walk-discovered reachability is always a subset of the already-seeded
  DFS scope for MAIN roots, that fold is a no-op and can go — taking the double-seed guard,
  the raw-`addAll` asymmetry, and the MAIN half of the ordering invariant with it.

## Decisions (agreed in brainstorm)

1. **Scope: investigate + act in one effort.** The subset question is empirical; the effort
   ships either the deletion or a findings doc + pinning test. Both outcomes close the item.
2. **Deletion bar: empirical + static argument.** PAX/samples evidence alone does not justify
   deletion (grazel is a public plugin; our corpora are not the universe). Deletion requires
   BOTH (a) clean instrumented evidence and (b) an articulable code-level argument for why the
   subset relation holds generally. If the argument cannot be made, the outcome is the
   document branch even with clean evidence.
3. **Instrumentation: temporary, uncommitted working-tree patch.** PAX composite-builds the
   local plugin, so a logging patch flows into a PAX migrate directly. No committed debug
   surface. Patch is reverted after evidence collection; evidence is committed to
   `reports/review/item2-channel-evidence.md`.

## Instrumentation design

A minimal patch in the resolver spine (`AggregatedDependencyResolver.resolve`), logging
structured lines (grep-able prefix, e.g. `GRAZEL-ITEM2:`):

- **Per MAIN root** (before the fold executes): capture
  `walkOnlyPaths = outcome.reachableProjectPaths − tracker.reachableMainProjectPaths` and the
  per-project bucket-name equivalent (`walkOnlyBuckets`); also the reverse direction
  (`seedOnly*`) for completeness. `walkOnly* ≠ ∅` anywhere ⇒ the fold is load-bearing.
- **Per TEST root**: what its fold adds relative to accumulated state at fold time — evidence
  for the critic's converse question (could TEST roots be DFS-seeded?). Recorded, NOT acted
  on in this effort.
- **Blank-bucket counter**: whether any folded bucket name is blank — decides whether the
  raw-`addAll` vs `addReachableMainBuckets` asymmetry ever matters in practice.

Runs: local samples migrate first (sanity + fast signal), then one PAX
`migrateToBazel --rerun-tasks` (~11 min, background). Grep, summarize, revert patch.

## Decision matrix

| Evidence | Static argument | Action |
|---|---|---|
| `walkOnly*` empty for every MAIN root | Articulable (resolved project edges necessarily follow declared edges; excludes/substitutions only remove) | **DELETE branch**: remove the MAIN-root fold; simplify `recordReachable` usage; shrink the ordering-invariant docs |
| Empty empirically, but a counterexample shape is constructible | Fails | **DOCUMENT branch**: keep both channels; KDoc names the exact shape requiring the walk fold; unit test pins it |
| `walkOnly*` non-empty anywhere | n/a | **DOCUMENT branch**: the observed diff IS the semantic difference; capture in doc + test |

Known static-argument risk (to check explicitly): Gradle `dependencySubstitution`
(module → project) creates resolved project edges with **no declared counterpart**. If PAX
(or any supported configuration) uses it, the walk channel is load-bearing by construction
and the outcome is the document branch. The investigation must grep PAX build scripts for
substitution usage as part of the static argument, not rely on instrumentation alone.

**Folded-in either way** (critic-04 item 7): if the blank-bucket counter is zero, unify
`recordReachable`'s raw `addAll` onto `addReachableMainBuckets` and delete the asymmetry
comment — byte-identical, evidence-backed.

## DELETE branch — change shape

- `AggregatedDependencyResolver.resolve`: fold `recordReachable` only for contributions whose
  root kind is not MAIN_HIERARCHY/MAIN_LEAF (exact gating mechanism decided at plan time —
  likely via `RootContribution` exposing its kind rather than the `lintClosure == null`
  nullness check, which critic-03 item 2 already flags; do NOT expand into that refactor
  beyond what the gating needs).
- `MainReachabilityTracker.recordReachable`: KDoc updated; asymmetry resolved per evidence.
- Ordering-invariant doc (`AggregatedDependencyResolver.kt:101-117`): shrinks to the TEST-root
  ordering statement only.
- Tests: a unit test asserting MAIN-root walk outcomes do NOT mutate reachability (pinning
  the new contract), plus existing suites unchanged.

## DOCUMENT branch — change shape

- `reports/review/item2-channel-evidence.md`: evidence + the counterexample shape.
- KDoc on `recordReachable` + the resolver invariant doc: replace "mirrors the in-place
  mutation this replaced" archaeology with the actual domain reason the fold exists.
- A unit test constructing the counterexample shape (e.g. substitution-created project edge)
  and asserting the fold catches what the DFS misses — turning the accident into a spec.
- Blank-bucket asymmetry unified if evidence supports (same as delete branch).

## Verification

- **DELETE branch**: `:grazel-gradle-plugin:test` → `verifyGrazelGoldenBaseline` (byte-clean
  expected — if the fold was a no-op, output cannot move; ANY golden drift disproves the
  subset claim and flips to the document branch) → `bazelisk build --nobuild //...` →
  **full PAX sweep** (`reports/specs/VERIFICATION-GATES.md` §PAX 1-6, mandatory: resolver
  behavior surface changed).
- **DOCUMENT branch**: `:grazel-gradle-plugin:test` + `verifyGrazelGoldenBaseline` only
  (docs + tests, no behavior change). No PAX sweep.
- Instrumentation patch itself: never committed; `git diff` must be clean of it before any
  gate run that feeds a commit.

## Out of scope

- TEST-root DFS seeding (converse question) — evidence recorded, action deferred.
- `shouldResolveMainHierarchyRoot` plan-time hoist (backlog item 4).
- `RootContribution` protocol cleanup (critic-03 item 2) beyond the minimal kind-gating the
  delete branch needs.
- Item 3 (fixpoint → worklist); `bucket/` entirely.

## Risks

- **Instrumented PAX run misread**: the fold happens per-root in order; capturing diffs
  *before* each fold is essential or later roots' accumulated state masks earlier deltas.
  The patch computes deltas inline at the fold site, not post-hoc.
- **Sample corpus too weak**: samples may show empty diffs trivially (few flavors, no
  substitution). PAX is the load-bearing evidence; samples are sanity only.
- **Static argument subtlety**: constraint-based version alignment, capability conflicts, and
  composite-build substitutions all mutate the resolved graph. The argument must address why
  none can *introduce* a project edge absent from declared metadata — or concede and document.
