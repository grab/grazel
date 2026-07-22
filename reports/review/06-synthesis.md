# Synthesis — adversarial review of `arun/dependencies-refactor`

Inputs: two clean-room designs blind to the code (01-perf-first, 02-graph-projection), three
critics reading the actual diff (03-inversion, 04-buckets/reachability, 05-refcollector). Judged
against four reconciliation constraints the blind agents could not have known: byte-identical
output against a real monorepo baseline (C1); the "generate everything" gate was tried and
reverted as commit `d9ceb30` (C2); reachability has two jobs — per-project attribution
reconstruction *and* generation gating — judge separately (C3); flavor-variant compression to a
single `-debug` target constrains any simplification of name/label handling (C4).

---

## Verdict on the inversion

Bottom-up → top-down was the right call, and both blind designs and all three critics converge on
this independently, which is itself strong evidence. The old model paid one Gradle resolution per
(project × variant); in a monorepo with thousands of modules that is thousands of resolutions to
extract information — direct declared deps, conflict-resolved versions — that a handful of
resolutions rooted at binaries already contains as a byproduct. Both clean-room designs derive the
same bound from first principles (`B` buckets, or `binaries × variants`, independent of module
count `M`) without ever seeing `AggregatedDependencyResolver.kt`, and critic-03 confirms the actual
code achieves exactly that ratio. The one place the clean-room designs and the implementation
disagree — whether module reachability/attribution needs resolution-graph-walking or can be
recovered from cheap declared metadata — is not a flaw in the inversion itself, it's the central
tension explored below.

## Clean-room vs built — the delta

Both blind agents reinvented the seed/aggregate/pin structure (buckets) essentially exactly as
built, and both independently reinvented a generation-closure step functionally equivalent to
reachability's gating job. Neither reinvented anything resembling a reference-collection fixpoint,
and neither reached for anything resembling the ~12 coverage predicates or the 5-layer metadata
merge. That's the core data point: **buckets are essential, reachability's gating half is
essential, but everything the critics call "accidental" is exactly the material the clean-room
designs never needed to invent** — which is consistent with it being real weight, not a strawman.

Concretely:

- **Buckets: converged.** Both 01 and 02 derive "one synthetic resolution per repository bucket,
  seeded by the union of every declared external coordinate across generated modules" as *the*
  forced mechanism — 01 calls it "the theoretical minimum," 02 calls it "the entire trick." This
  is structurally the same shape as `DependencyBucketAccumulator` + the per-bucket resolution in
  `AggregatedDependencyResolver`. Where they diverge from the build: neither clean-room design
  anticipated that buckets also need *placement* — i.e., which of several possible buckets a given
  project's dependency should be attributed to under Android's default/hierarchy/leaf variant
  lattice (`DependencyBucketPlacementEngine`). Both designs implicitly assume a flatter
  group/coordinate-pattern → bucket mapping. That's not a refutation of the placement engine —
  Android product flavors are a real, forced complication neither blind brief mentioned — but it
  means the clean-room designs validate only the *aggregation* half of buckets, not the
  *placement-lattice* half.

- **Reachability's gating job: converged, independently.** 01's "Pass 2: Generation-set closure"
  and 02's "Phase 1: Generation set via closure on G_P" are, almost verbatim, the *generation*
  side of what the codebase's `MainReachabilityTracker` + `TargetVariantReachability` machinery
  does: a closure over declared project edges, all scopes included, specifically called out
  because dropping `testImplementation`-only edges silently starves a library of its BUILD file.
  Both designs treat this as free (declared metadata, no resolution) — and per C3, gating *should*
  be cheap; nothing forces it to ride on the resolution walk.

- **Reachability's attribution job: this is where they diverge from the build, correctly flagged
  by both sides.** Critic-03 names this explicitly as "the road not taken": a cheaper model
  exists — derive each module's direct deps from already-collected `DeclaredDependencyMetadata`
  joined against the aggregated pin union by shortId, no graph-walk attribution at all. Both
  clean-room designs implicitly assume this cheaper model (their BUILD-edge projection is a pure
  function of declared edges + pin/rewrite maps, Phase 3 / Pass 5). Critic-03 also correctly
  identifies *why* the implementation didn't take that road: "direct" in the actual output means
  "declared on a project edge in the resolved graph," which differs from raw declarations by
  excludes, substitution, and conflict outcomes — reproducing that from declared metadata is
  reimplementing Gradle's resolution semantics, which is precisely what both clean-room designs
  also refuse to do ("a homemade version-conflict resolver... too rich to reimplement faithfully").
  So this is not a case of the build being over-engineered relative to a clean-room ideal; it's a
  case where the clean-room designs under-specified a requirement (byte-identical direct-dep
  attribution under real exclude/substitution semantics) that only becomes visible once you have
  to match a real, existing golden baseline (C1). Reconciled verdict: the walk-based attribution
  channel is the defensible tax; the question is whether it needs to run *twice* (see scorecard).

- **Reference-collection fixpoint: nobody reinvented it, and for good structural reasons.** Neither
  01 nor 02 has anything resembling `CollectTargetMavenRepoReferencesTask`'s round-based
  fixpoint. This is explained, not damning, once critic-05's own analysis is read carefully:
  the fixpoint exists to solve a *narrower* problem than either clean-room design's generation
  closure — not "which modules get a BUILD file" (both designs solve that with one BFS, matching
  the codebase) but "which maven-repo *references* a target will end up emitting, given that
  variant compression and test-vs-main asymmetry make target-level fact extraction depend on
  *other targets'* already-computed facts, in a graph whose project-level quotient can be
  cyclic." That's a real, additional wrinkle (compression, C4) that a from-scratch clean-room
  brief without the compression detail would not surface. So this is essential-core-plus-real-
  accidental-fat, not slop invented from nothing — critic-05 says as much ("some iteration is also
  essential... a single pass is provably impossible in general").

Net: the clean-room agents independently re-derive the *shape* of buckets and generation-gating
reachability, which vindicates those two mechanisms as essential. They do not re-derive
graph-walk attribution, dual reachability channels, five-layer metadata merge, ~12 coverage
predicates, or the round-based fixpoint — all of which the built code has and all three critics
flag as containing real accidental weight. The delta is exactly where the critics' "accidental"
findings live, which cross-validates them.

## Mechanism scorecard

**Buckets (`bucket/DependencyBucketPlacementEngine`, `MainBucketPlanner`, `TestBucketPlanner`,
`Coverage`, `BucketOwnershipPlanner`) — OVER-BUILT.**
The 3-stage placement algorithm (default → hierarchy → leaf) and test-buckets-as-residuals are
essential: both clean-room designs derive the aggregation half unprompted, and the flavor-lattice
half is a real, forced consequence of Android variants that a from-scratch design without that
domain detail simply wouldn't anticipate — not evidence against it. But critic-04's three findings
survive against the constraints: (1) ~12 coverage/identity predicates duplicating "does bucket A
already provide this dep" across `Coverage.kt`, `DefaultBucketDependencyReducer`, and
`DefaultOverrideCarrierPlanner` is accidental — a data table over named identity-strength levels is
byte-identical-safe (pure refactor, same predicates evaluated) per C1; (2) metadata merged at five
layers where placement runs per-project against global metadata is a real architectural gap papered
over by repair passes, not a requirement; (3) reachability stamped identically into every
per-bucket result then re-unioned (finding 4) is pure plumbing waste with zero semantic content.
None of this is gated-generation logic (C2/C3 job (b)) — it's bucket *attribution* content, so C2's
"don't skip reachability's gating role" doesn't protect it.

**Reachability (`MainReachabilityTracker`, `TargetVariantReachability`) — split verdict, judged
per C3's two jobs.**
- *Job (b), generation-gating*: VINDICATED, essential, and cheap by construction — both clean-room
  designs reinvent this from declared edges alone. C2 is the direct receipt: the branch already
  tried treating generation as unconditional/undifferentiated ("generate every module") and it was
  reverted for over/under-generating, which is exactly the failure mode gating reachability
  prevents. Nothing here should be simplified beyond critic-03's item 2 (stop planning/resolving
  roots that gating already knows will never be walked — pure waste-elimination, not a semantics
  change).
- *Job (a), per-project attribution reconstruction*: VINDICATED core (walk-based attribution must
  exist, per critic-03's "road not taken" analysis above) but OVER-BUILT in execution: critic-03's
  finding of **two parallel reachability channels** (declared-edge DFS in `computeScope` seeded
  before the walk, *and* the walk's own `RootVisitOutcome.reachable*`, unioned together with a
  documented ordering invariant) is the clearest concrete case of accidental duplication anywhere
  in this branch. If — as critic-03 flags as an open, not-yet-answered question — one channel is
  provably a subset of the other for MAIN roots in the real baseline, the second channel and its
  seed-before-resolve invariant are pure deletable weight. This is exactly the kind of "two sources
  of truth for one fact" pattern C1 says must be diff-checked, not assumed safe, before removal.
  The referenced-but-unreached fallback (critic-04, `isReferencedGeneratedTarget`) is a separate,
  genuinely forced bridge between variant-name-space reachability and post-compression
  target-label-space references (C4) — keep it; only the duplicated name-spelling knowledge behind
  it (three files independently knowing `{name, _lib, _kt, lib_}`) is accidental.

**Reference-collector (`CollectTargetMavenRepoReferencesTask`, `TargetReferenceFactsExtractor`,
`TopologicalSorter`) — SLOP, with an essential kernel.**
The pre-render pass's existence is essential (task-graph ordering forces it) and *some* iteration
is essential (critic-05's cyclic-project-quotient argument is airtight and neither clean-room
design had to confront it because neither modeled variant compression or test/main cross-edges at
that granularity). But three things are removable slop, not essential residue:
1. The **round-based fixpoint protocol** (`settledProjects`/`everVisited`/`maxRounds`/whole-map
   equality snapshot per round) is provably reducible to a one-pass-plus-deferred-worklist drain
   with identical semantics in the common case and only edge-case fallback-variant timing
   differences in the cyclic case — gated by C1's golden baseline, as critic-05 itself notes.
2. The **shadow extraction pipeline** (`TargetReferenceFactsExtractor` hand-re-deriving what
   `AndroidLibraryTargetBuilder`/`AndroidBinaryTargetBuilder` compute) is unambiguous slop: it has
   *already diverged* on the empty-`targetsBySuffix`-after-compression case (builder emits nothing,
   extractor falls back to per-variant), which is precisely the dangling/missing-label bug class
   this whole branch exists to prevent. This is the single highest-confidence "remove it" finding
   across all five documents.
3. The **covert mutable-state channel** through `WorkspaceRenderPlanService` (ordering enforced by
   four KDoc essays, not the type system) is accidental protocol weight around an otherwise
   necessary shared accumulator.

## Ranked simplification backlog

Ordered by value (bug-prevention and maintenance leverage first, pure hygiene last), each rated
for byte-identity risk against C1.

1. **Collapse the twin selection sites (critic-05 S1).** Extract shared selector functions
   (variant/target existence + representative-picking) that both the target builders and
   `TargetReferenceFactsExtractor` consume, so facts are derived from the same objects that
   render. *Effort: 1-2 days. Risk: low-medium — one already-diverged branch must be reconciled
   deliberately, chosen to match the builder's (rendered) behavior so bytes don't move; the facts
   side changes underneath but output doesn't.* Highest value: this is the one place a live
   correctness bug (facts/render divergence under compression) already exists, not just latent
   risk.

2. **Investigate and likely delete one of the two reachability channels (critic-03, "bigger
   question").** Diff whether `computeScope`'s declared-edge DFS is a strict subset of
   walk-discovered reachability for MAIN roots (and vice versa for TEST/UNIT_TEST/ANDROID_TEST,
   which only fold the walk channel today). *Effort: M-L, needs golden-baseline diffing per C1.
   Risk: real output drift possible — this is exactly what the golden gate exists to catch, so
   treat as "attempt behind the gate, revert if it moves anything."* If it lands, the
   seed-before-resolve ordering invariant and its 17-line doc comment evaporate for free.

3. **Worklist instead of rounds in the reference collector (critic-05 S3).** Replace
   `maxRounds`/`everVisited`/per-round snapshot with one ordered pass + a deferred-activation
   queue; optionally topo-sort the merged project graph directly so the queue is empty whenever
   the quotient is acyclic (deleting `dependedUponProjects`' extra graph merge too).
   *Effort: small-medium. Risk: low-medium; identical in the common all-settle-in-round-1 case,
   edge-case fallback-variant timing must be golden-gate-verified in the cyclic case.*

4. **Hoist `shouldResolveMainHierarchyRoot` to plan time (critic-03 item 2).** Stop resolving
   roots the walk will refuse to visit; the filter's inputs (leaf build-type names) are available
   at plan time. *Effort: S-M. Risk: medium-low — this actually changes real Gradle work (fewer
   resolutions, good), verify `pinMavenArtifactsTask`'s root set intentionally includes/excludes
   these roots before narrowing.* This is the rare simplification that also improves the perf
   numbers the branch was built for.

5. **Unify the ~12 coverage predicates behind one identity-strength table (critic-04 #4).**
   Named levels (shortId < ownerIdentity < artifactIdentity) plus orthogonal flags, expressed as
   rows in `Coverage.kt`, shared by the Reducer/CarrierPlanner pair instead of a parallel
   mini-Coverage. *Effort: ~1 week. Risk: medium, mechanical but wide — behavior-preserving by
   construction if each row reproduces its predicate exactly; golden gate is the safety net.*

6. **Collapse triple-pass test subtraction to one pass (critic-04 #2).** `withoutTestDependenciesCoveredBy`'s
   keep/drop decision is fully determined by its final `canCoverTest` filter; the earlier two
   passes only contribute an annotation. *Effort: 1-2 days. Risk: low-medium — preserving exactly
   when the `overrideTarget` annotation attaches is the one subtlety.*

7. **Stop double-extracting for facts and render (critic-05 S2).** Memoize
   `extract(project, variant)` in a build service shared by the facts task and
   `GenerateBazelScriptsTask` — currently runs the full data extractors twice per project/variant,
   which the critic notes is 2x the dominant per-project cost of a branch built for performance.
   *Effort: small. Risk: low, byte-identical (same extraction, cached).*

8. **Stop stamping reachability into every per-bucket result (critic-04 #1).** Pass
   `reachableMainBucketsByProject` alongside the results list instead of copying it into every
   `ResolveDependenciesResult` and re-unioning. *Effort: hours. Risk: low — verify the intermediate
   JSON shape isn't itself part of the golden gate (it's an internal artifact, not generated
   Bazel output, so it shouldn't be, but confirm).*

9. **Housekeeping batch, no semantic risk:** delete `ProjectReachabilityGroup` (always a
   singleton — critic-05 S6); cache `selectedMainVariantHierarchyNames`'s per-project map instead
   of rebuilding it per edge (critic-03 item 3); accumulate reference facts mutably and sort once
   instead of rebuilding a sorted map on every project visit (critic-05 S5); one canonical
   target-name-spelling helper instead of three private copies of `{name, _lib, _kt, lib_}`
   (critic-04 #6, critic-03 item 6 analog). *Effort: hours each. Risk: none — pure mechanical, no
   consumer-visible change.*

10. **Kill the zip-by-index contract across the three resolver tasks (critic-03 item 4).** Key
    `AggregatedDependencyRootMetadata` by `(projectPath, configurationName, kind)` instead of
    positional pairing protected only by a size check. *Effort: M. Risk: low — no output change,
    removes a silent-misattribution failure mode rather than a performance or size issue.*

**Explicitly not recommended even though byte-identity-safe:** the single-metadata-resolution-
boundary consolidation (critic-04 #5, collapsing five merge layers to one) is real and valuable but
should run *last*, in isolation, specifically because critic-04 flags that placement's coverage
checks read metadata that a restructuring could change mid-decision — highest chance of any item
here to actually move output in an edge case, so it needs its own golden-gate cycle rather than
being bundled with lower-risk items.

## What the critics got wrong

- **Nobody proposed anything the constraints outright kill** — which is itself notable: all three
  code-aware critics were already disciplined about C1 (repeatedly flagging "golden gate must
  verify," "byte-identity caveat," "flag to the golden gate" rather than asserting safety), and
  none proposed reverting to unconditional/undifferentiated generation (C2) or breaking flavor
  compression (C4). The discipline shows in the language: every risky item above is explicitly
  hedged by its own author.
- **The nearest thing to a dead end is critic-03's own "road not taken" (declared-based attribution
  instead of graph-walk attribution)** — and critic-03 correctly declines to recommend it,
  precisely because it would move byte-identical output (C1) by substituting raw declared metadata
  for resolved-graph semantics (excludes, substitutions, conflict outcomes). This is presented as a
  recorded trade-off, not a live proposal, so there's nothing to overrule — but it's worth stating
  explicitly per the task's instructions: if anyone later revives it, it dies on C1.
- **The two clean-room designs' shared blind spot** is the mirror image: both assume BUILD-file
  generation is a pure projection of declared metadata with zero resolution-graph involvement
  (01's Pass 5, 02's Phase 3). That is correct for *what the labels point at* (bucket-versionless
  references) but wrong for *which declared edges count as "direct"* once excludes and
  substitutions are in play — the exact gap critic-03 identifies as forcing the walk. Neither
  clean-room author had visibility into C1's real-baseline requirement, so this isn't a
  proposal to kill so much as evidence that the graph-walk attribution channel is earning its
  keep against a requirement a from-scratch design would not surface unprompted.
- **Critic-04's "slightly too high" flag on `orderedCombinations`** (enumerating all 2^n flavor
  subsets by bitmask) is noted by the critic itself as acceptable at real-world n ≤ 3-4 and not
  worth fixing now — correctly triaged as a non-issue rather than inflated into a finding.
