# Design — Worklist reference collection (adversarial-review item 3)

Source finding: `reports/review/05-critic-refcollector.md` §2 and §S3/S6; ratified in
`reports/review/06-synthesis.md` (backlog item 3 + housekeeping item 9). Branch:
`arun/dependencies-refactor`.

## Problem

`CollectTargetMavenRepoReferencesTask`'s collection pass runs as a round-based fixpoint held
together by five interacting guards (`CollectTargetMavenRepoReferencesTask.kt:238-337`):

1. `settledProjects` — cross-round "produced final facts" set
2. `everVisited` — cross-round "visited while inactive, skip until referenced" set
3. `maxRounds` (`totalProjects + 1`) — termination cap justified by a proof-by-comment `check`
4. per-round whole-`TargetReferenceFacts` equality snapshot (`accumulated == beforeRound`)
5. per-project `populateRenderPlan` republish *before* the skip decision — existing solely so a
   reference recorded later in the same fold becomes visible to an earlier-ordered project's
   skip check ("would be skipped forever" deadlock guard, KDoc lines 278-293)

Critic-05's observation: the mechanism's actual semantics are **"process each project at most
once, at the moment it first becomes active"** — worklist semantics implemented as global
re-rounds. Every round after activation-quiescence is a full re-scan whose only job is to
observe nothing changed, plus a structural comparison of the entire accumulated facts.

Additionally, `ProjectReachabilityGroup` is a list-of-one wrapper: every construction site
(`TopologicalSorter.kt` `consumersFirstGroups`, the task's appended off-graph subprojects, all
tests) builds singleton groups. A "group" abstraction that never groups.

## Decisions (agreed in brainstorm)

- **Scope**: (a) S3 worklist replacing the round protocol + (b) S6 `ProjectReachabilityGroup`
  deletion. The ordering-producer change (topo-sorting the merged project quotient for a
  correct single-pass order, which would also delete `dependedUponProjects`' second graph
  merge) is **out of scope** — it changes visit order, extraction is visit-time-dependent, so
  it is not provably byte-identical even in the happy path; recorded as a follow-up.
- **Drain ordering**: original consumers-first visit index — mirrors what round *n+1* would
  have done, minimizing activation-timing deltas.

## Semantics contract (preserved exactly)

Every project is processed **at most once**, at the moment it first becomes active
(migratable ∧ (intrinsically-reachable ∨ `isReferencedProjectPath` at visit time)).

Two deliberate non-goals:
1. **No target-name-granularity re-extraction.** A late reference to a *different target* of
   an already-processed project does not re-extract (critic-05 noted this; it is today's
   behavior and stays — changing it is a behavioral fix needing its own evidence cycle).
2. **Non-migratable and never-activated projects contribute nothing**, as today.

Existing test contracts in `WorkspacePlanTasksTest` must pass **unchanged**:
- `collect target references uses consumer first single pass` — exactly one `factsForProject`
  call per project in the happy path.
- `collect target references reaches targets activated by prior references` — exact progress
  strings `"(1/2)"`, `"(2/2)"`; no extra progress lines.
- The mis-ordered two-hop fixpoint test — `util2` ends up in `references.projectTargets`.

## The worklist algorithm

Replaces `collectTargetMavenRepoReferencesToFixedPoint` +
`collectTargetMavenRepoReferencesSinglePass`:

- **Pass 1** — identical to today's round 1: walk projects in the existing consumers-first
  order, visiting everyone (no skip exists in pass 1, so the publish-before-skip guard
  collapses away). A project visited while *inactive* is recorded in a `deferred` ordered
  structure keyed by original visit index, instead of `everVisited`.
- **Drain** — repeatedly scan `deferred` in original-index order for the first project that is
  now `isReferencedProjectPath`; process it with the same per-project semantics (merge facts,
  publish, remove from deferred); restart the scan. Stop when a full scan activates nothing.
  Chained activations (the depth-2 fixture shape: processing `util1` references `util2`) are
  handled by the scan restart.
- **Deleted**: `maxRounds` + its `check`, `everVisited`, the per-round snapshot equality, the
  per-round re-scan of settled projects, and the pre-skip republish deadlock guard (the drain
  reads the render plan after each publish, so the visibility problem cannot arise).
  `settledProjects` survives only as "already processed" (removal from `deferred`), not as a
  cross-round guard. Termination is structural: `deferred` only shrinks.
- **Happy path** (everything activates in pass 1): `deferred` empty, drain a no-op — provably
  identical to today minus the extra observation round and the whole-facts comparison.
- The final `normalized()` + `populateRenderPlan` republish in
  `collectTargetMavenRepoReferencesByGroup` is untouched (once, after the drain completes).

## Group-wrapper deletion (S6)

`ProjectReachabilityOrder.consumersFirstGroups(...): List<ProjectReachabilityGroup>` becomes
`consumersFirstProjects(...): List<Project>`; the task's off-graph append and all tests drop
the wrapper; the `ProjectReachabilityGroup` type is deleted. The flattened project sequence is
identical — zero ordering change. (This touches `TopologicalSorter.kt` mechanically — return
type and construction only, no algorithm change.)

## Byte-identity expectation

- Happy path: provably identical.
- Drain path: can differ from round semantics only in referenced-fallback variant timing on
  cyclic/mis-ordered shapes; the golden corpus (incl. `sample-android-test-util*` depth-2 and
  the verified cyclic coverage) arbitrates. Expectation: byte-clean. **Any golden drift = stop
  and report; do not patch the golden.**
- Item-2 lesson applied: samples may never exercise the drain at runtime, so the drain
  semantics are guarded by **unit tests that explicitly construct drain shapes** (single
  deferred activation, chained activation, never-activated project) rather than relying on
  golden/PAX only.

## Verification

1. `./gradlew :grazel-gradle-plugin:test --console=plain` — existing 3 contracts unchanged +
   new drain-shape tests.
2. `./gradlew verifyGrazelGoldenBaseline --console=plain` — byte-clean (documented
   appcompat/constraintlayout bucket-labels waiver only).
3. `bazelisk build --nobuild //...` — clean analysis.
4. No PAX sweep unless the golden moves (it must not).

## Out of scope

- The ordering-producer change (topo-sort `mergeToProjectGraph({true})` directly; would empty
  the deferred queue in practice and delete `dependedUponProjects`' second merge) — follow-up,
  requires its own golden/PAX adjudication since visit order moves.
- `dependedUponProjects` stays as-is (it feeds `isIntrinsicallyReachable`).
- `TargetReferenceFactsExtractor`, `TargetVariantReachability`, `resolution/`, `bucket/`.
- Target-name-granularity settling (see Semantics contract non-goal 1).
