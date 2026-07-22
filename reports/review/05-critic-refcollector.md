# Critique 05 — the reference collector (fixpoint pass + facts extraction twins)

Scope: `tasks/internal/CollectTargetMavenRepoReferencesTask.kt`,
`gradle/dependencies/TopologicalSorter.kt` (`ProjectReachabilityOrder`),
`gradle/dependencies/TargetReferenceFactsCollector.kt`, `WorkspaceRenderPlanService.kt`,
`migrate/target/TargetReferenceFactsExtractor.kt`, `migrate/target/TargetVariantReachability.kt`,
and the render-side twins (`AndroidLibraryTargetBuilder.kt`, `AndroidBinaryTargetBuilder.kt`).

Verdict up front: the *existence* of a facts pre-pass is essential (WORKSPACE/pin generation must
know referenced repos before any BUILD file renders). Some iteration is also essential — a single
pass is provably impossible in general (see §2). But the implementation carries three distinct
layers of accidental complexity on top: (1) a **shadow extraction pipeline** duplicated against the
target builders — the worst slop here, and it has *already diverged*; (2) a **round-based global
fixpoint** where a deferred-worklist drain is the direct closure formulation; (3) a **covert
mutable-state channel** (mid-fold `WorkspaceRenderPlanService` mutation) whose ordering rules are
documented in four KDoc essays instead of being visible in any signature.

---

## 1. Essential vs accidental

**Essential:**
- A pre-render pass that computes `{repoNames, projectTargets}` before rendering. Task graph
  (`TaskManager.kt:97-116`) forces it: `collect → finalizeWorkspacePlan → generate`. Cannot be fused
  into generation without restructuring WORKSPACE/pinning.
- Consumers-first ordering. Facts for a variant depend on whether a *consumer* references it
  (`isReferencedGeneratedTarget` fallback), so referrers must be seen before referees.
- *Some* iteration. The typed (project, source-set) graph is acyclic, but its project-level
  quotient can be cyclic (`a.main → b.main`, `b.test → a.main`). When the quotient is cyclic **no
  flattened single-pass project order exists at all**, so "just fix the ordering" is not available
  in general. The fixpoint is answering a real problem.

**Accidental:**
- The entire `TargetReferenceFactsExtractor` selection logic being a hand-maintained copy of the
  four target builders (§3, S1). The problem only requires "the deps each generated target will
  have"; the implementation answers it by re-implementing target generation minus the rendering.
- The round-shaped fixpoint protocol: `settledProjects` + `everVisited` + `maxRounds` +
  whole-structure `accumulated == beforeRound` snapshot per round + per-project
  `populateRenderPlan` republish *before* the skip decision
  (`CollectTargetMavenRepoReferencesTask.kt:238-337`). Five interacting mechanisms; the KDoc at
  lines 278-293 explicitly warns that one of them exists only to keep another from deadlocking
  ("would be skipped forever"). That is a protocol held together by comments.
- `dependedUponProjects` (lines 120-124): an extra full `mergeToProjectGraph({true})` merge whose
  only purpose is to compute "in-degree 0" — i.e. to patch the *known-lossy* dedup in
  `ProjectReachabilityOrder.consumersFirstGroups` (first-occurrence dedup of typed nodes, KDoc
  lines 89-101 admits the ordering it produces is wrong for exactly these projects). Complexity
  spent downstream compensating for a producer that is documented as lossy.
- `ProjectReachabilityGroup`: a list-of-one wrapper. Every construction site — `consumersFirstGroups`
  (`TopologicalSorter.kt:189-197`), the appended off-graph subprojects (task lines 114-118), all
  tests — builds singleton groups. A "group" abstraction that never groups.
- Not a true fixpoint anyway: `collectProjectReferences` settles a project on its first *active*
  visit (line 374), so a reference to a **different target name** of an already-settled project
  arriving in a later round never triggers re-extraction. The KDoc claim "converges to the true
  transitive closure" (lines 189-191) holds only at project-path granularity. Not a bug hunt —
  but it shows the mechanism is neither a simple pass nor a principled closure; it is both at once,
  and its actual semantics ("process once, when first activated") are exactly worklist semantics
  wearing a fixpoint costume.

Commit history confirms the shape: `5bff189 fix(resolver): iterate ... to a fixed point` then
`61a2a82 perf(...): skip re-visiting ...` then `cd9fc7b refactor: simplify fixpoint ...` — a patch,
a patch on the patch's cost, then a cleanup of both.

## 2. Is the fixpoint warranted? — partly

- A **single pass cannot be guaranteed** (cyclic project quotient via test edges), so the naive
  answer "replace with one pass" is wrong. Good.
- But the **direct formulation is a worklist closure**, not global re-rounds: do the one ordered
  pass (visiting everyone, exactly as round 1 does today — this preserves the round-1 behaviour
  where even never-referenced projects contribute their bucket-reachable facts); collect projects
  that were visited while inactive into a deferred set; then drain a queue — whenever a merge adds
  a reference to a deferred project, process it once and settle it. This computes the same
  "process once when first activated" semantics with **no rounds, no `everVisited`, no `maxRounds`
  proof-by-comment, no whole-facts equality snapshot per round, and no re-scan of settled projects
  per round**. Today's happy path (everything settles in round 1) still pays a full extra round
  whose only job is to observe nothing changed, plus a structural comparison of the entire
  accumulated facts; the worklist's happy path is "queue empty, done".
- Byte-identity caveat, per the brief: in the mis-ordered/cyclic cases, extraction is
  visit-time-dependent (it reads `referencedTargetNames` at extraction time), and the worklist
  processes an activated project at a slightly different point in the schedule than round *n+1*
  would. Facts are monotone unions so this can only move edge-case *referenced-fallback* variants;
  the golden-baseline gate must arbitrate. In the common all-settle-in-round-1 case it is
  provably identical.
- Secondary: the ordering producer could be less lossy. `consumersFirstGroups` flattens the typed
  topo order by first occurrence — arbitrary. Topo-sorting `mergeToProjectGraph({true})` directly
  (machinery already exists in `TopologicalSorter.sort`) yields a *correct* single-pass order
  whenever the quotient is acyclic, making the deferred queue empty for virtually all real repos,
  and would also delete `dependedUponProjects`' second graph merge (in-degree info falls out of the
  same sort). Fall back to the typed flattening + worklist only on quotient cycles.

## 3. Ranked simplifications

**S1 — Collapse the twin selection sites (biggest win).**
`TargetReferenceFactsExtractor.androidLibraryData`/`androidUnitTestData`/`reachableAndroidLibraryData`
(extractor lines 176-252) duplicate `AndroidLibraryTargetBuilder.build`/`unitTestsTargets`
(builder lines 78-155) nearly line-for-line: `reachableMatchedVariants` → compression-suffix
filtering → `groupBy(resolveSuffix)` + `minBy(variantName)` representative. Same for
`androidBinaryFacts` vs `AndroidBinaryTargetBuilder.buildAndroidBinaryTargets` (identical
`appVariantFilter` predicate duplicated, extractor 99-118 vs builder 94-105), and analogously for
the test/instrumentation pairs. `collect()`'s when-ladder (extractor 73-83) additionally shadows
the `TargetBuilder.canHandle` dispatch.

This is structural slop, not two coincidentally-similar computations — and it has **already
diverged**: when compression is active with non-empty `reachableSuffixes` but zero matching keys in
`targetsBySuffix`, the builder emits *nothing* (`filterKeys` result used as-is, builder line 99-103)
while the extractor *falls back to per-variant extraction* (`takeUnless(List::isEmpty) ?: map{extract}`,
extractor 186-193). So facts can be collected for deps of targets that will never render (or the
reverse if either side is "fixed" independently). This divergence class — facts disagreeing with
render — is precisely what produces dangling/missing labels, and nothing structural prevents it.

Fix: extract per-target-type **selector** functions that return the extracted data objects
(`List<AndroidLibraryData>`, unit-test representatives, binary data per variant); builder maps
data → `BazelTarget`, facts pass maps data → `TargetReferenceFacts` (the `referenceFacts()`
adapters at extractor lines 255-312 already exist and are fine). Facts become *derived from the
same objects the builders render* — the divergence class disappears. Effort: 1-2 days. Risk:
low-medium; the one divergent branch must be reconciled deliberately (adopting the builder's branch
keeps rendered bytes identical; flag the facts-side delta to the golden gate).

**S2 — Stop extracting everything twice.**
The facts task runs the full data extractors (`AndroidLibraryDataExtractor`,
`AndroidBinaryDataExtractor`, unit-test, instrumentation, kotlin) for every project/variant; then
`GenerateBazelScriptsTask` runs the *same* extractors again to render. No memoization exists
(`AndroidExtractor.kt` has none). On a large monorepo that is 2× the dominant per-project cost of
this whole phase — notable given the branch exists for performance. Fix: memoize
`extract(project, matchedVariant)` in a build service keyed by `(projectPath, variantName)`
(both tasks already share services), or have S1's selectors cache their outputs for the render
pass. Effort: small. Risk: low (data classes are plain; both tasks run in the same build
invocation). Byte-identical.

**S3 — Worklist instead of rounds** (as argued in §2): delete
`collectTargetMavenRepoReferencesToFixedPoint` + `everVisited` + `maxRounds` + per-round equality
snapshot; keep one full ordered pass + a deferred-activation queue. Optionally order by the merged
project graph when acyclic so the queue is empty in practice, deleting `dependedUponProjects` too.
Effort: small-medium (one file + its tests; the two scenario tests in `WorkspacePlanTasksTest`
port directly). Risk: low-medium — edge-case referenced-fallback variants may move; golden gate
must validate.

**S4 — Make the reference channel explicit.**
The fold communicates with the extractor exclusively by mutating the shared
`WorkspaceRenderPlanService` before each visit (task line 314), which is why the code needs the
"republish before the skip decision or projects are skipped forever" rule and the "publish even for
non-migratable projects" rule — both enforced only by KDoc. Pass the accumulated reference view as
a parameter (`factsForProject(project, referencesSoFar)` down through
`reachableMatchedVariants`/`isReachableJvmProject`), and let the service be written once at the end
(it must still exist for the cross-task `initRenderPlan` path in `GenerateBazelScriptsTask`).
The fold becomes pure and the ordering dependency moves from comments into types. Effort: medium
plumbing. Risk: low. Byte-identical.

**S5 — Accumulate mutably, sort once.** `mergeTargetReferenceFacts`/`mergeProjectTargets` rebuild
the full merged map (`(left.keys+right.keys).associateWith{...}.toSortedMap()`) on **every project
visit** — O(P × total-facts), quadratic-ish at monorepo scale — and sorts during accumulation even
though `normalized()` re-sorts everything at the end anyway. Use a mutable
`MutableMap<String, MutableSet<String>>` accumulator during the pass; normalize once. Effort: tiny.
Risk: none. Byte-identical (final `normalized()` governs output).

**S6 — Delete `ProjectReachabilityGroup`.** Always singleton; replace `List<ProjectReachabilityGroup>`
with `List<Project>`. Effort: trivial. Risk: none.

## 4. Altitude

Wrong in both directions at once. The fixpoint loop is a **heavy general mechanism** deployed to
patch a narrow defect (a lossy order-flattening plus rare quotient cycles) — the general machinery
runs for every build to cover cases that are empty almost always. Meanwhile the facts/builder
duplication is **special-cases piled beside shared infrastructure**: the selection logic that
should be the shared core exists as four parallel hand-synced copies. The right altitude is:
selection (which variants/targets exist) as the single shared layer (S1); reference closure as a
plain worklist over its outputs (S3); the render-plan service as a write-once artifact rather than
a mid-pass mailbox (S4).

## 5. Keep as-is

- **The pre-pass itself** — genuinely required by the task ordering; not manufactured complexity.
- **`TopologicalSorter`** — Kahn + shared `dependencyFirstOrder` + honest cycle diagnostics
  (typed-edge printout). Clean, tested, right size. The flaw is in `consumersFirstGroups`' dedup
  policy, not the sorter.
- **`TargetReferenceFactsCollector.from`** and the merge/normalize helpers — small, pure,
  well-tested (incl. the label-pattern and tag-inference subtleties). Right altitude.
- **`isReferencedGeneratedTarget` / compressed-suffix retry** in `TargetVariantReachability.kt` —
  the macro-spelling set and compression retry encode real Bazel-macro facts; the KDocs earn their
  keep. Fine.
- **Iteration in some form** — do not remove entirely; quotient cycles are real (test-fixture
  modules depending back on consumers). The critique is the *shape* of the iteration, not its
  existence.
