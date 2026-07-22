# Cohesion cross-check — adversarial review set (01–06)

Scope: verify 06-synthesis faithfully represents 01–05, find unreconciled contradictions between
the five inputs, find gaps the whole set misses, and check whether the two clean-room designs
(01, 02) actually agree with each other and whether 06 represents that correctly.

---

## 1. Do 01 and 02 actually agree? — Partially. 06 overstates the agreement on one material point.

**Where they genuinely agree (06 represents this correctly):**
- The core two-tier split (declaration-level = free, resolution-level = expensive) is identical
  in substance (01 §0, 02 §0 — G_P/G_A framing is just different vocabulary for the same cut).
- Generation-set closure via all-scope BFS over declared project edges, with the
  `testImplementation`-only-library case as the load-bearing example, is near-verbatim between
  01 Pass 2 and 02 Phase 1.
- BUILD-file edges as a pure projection of declared edges (no resolution, direct-deps-only,
  Bazel owns transitivity) — identical in both.
- Identical "what I would NOT build" lists: no per-module resolution/caching thereof, no
  homemade Gradle-conflict-resolver reimplementation, no incrementality between runs, no
  discovering modules from resolved classpaths.

**Where they disagree, and 06 flattens the disagreement:**

01 claims the resolution count is **exactly B** (number of buckets, "a small constant, typically
1–3"), and explicitly asserts this is **independent of variant count**: "B grows with pin files,
never with M×V" (01 §1 Pass 4, §3). Its whole "theoretical minimum" framing rests on this: one
synthetic detached configuration per bucket, seeded purely from the **union of declared
coordinates**, for every bucket including the production one.

02 proposes a materially different mechanism for the production bucket: rather than a synthetic
union-seeded config, it recommends "seed it from (or simply resolve) the **binary roots' variant
classpaths directly**" as a "fidelity refinement," explicitly calling this **forced by the graph
shape, not a style choice**, and reserving the synthetic-union approach only for test buckets,
which "have no such natural root." 02's own stated resolution count is consequently **not** "one
per bucket" — it is `|B|·V_app + k`, i.e. proportional to (binaries × variants) for production
plus a constant for test buckets (02 §1 Phase 2, §3). This is a materially larger and
variant-sensitive count that 01's framing explicitly denies is necessary.

Notably, 02's count — binaries × variants — is what critic-03 reports the **actual code**
achieves ("O(B binaries × V variants × ~2 configs)"), not 01's "B, full stop, independent of V."

06's synthesis (Verdict on the inversion) writes: *"Both clean-room designs derive the same bound
from first principles (`B` buckets, or `binaries × variants`, independent of module count `M`)
... and critic-03 confirms the actual code achieves exactly that ratio."* This sentence is
technically defensible read narrowly (both bounds are independent of *module count* M, which is
true), but it papers over that 01 and 02 propose **two different mechanisms with two different
resolution counts**, and only 02's count matches the real code's confirmed ratio. A reader could
easily come away thinking 01 and 02 arrived at the identical number, when in fact only one of them
did, for a reason (real-classpath fidelity for the production bucket) that 01 explicitly designed
around and rejected implicitly by insisting a synthetic seed suffices everywhere. This is worth a
one-line patch to 06's synthesis so the "B vs binaries×variants" distinction is stated as a
disagreement resolved in 02's favor by the real code, not a shared derivation.

Secondary, smaller point in the same territory: 02 introduces an explicit `Attribution` data
structure with a global precedence rule (production > androidTest > test) for artifacts that
appear under multiple bucket-eligible scopes on the same coordinate; 01's Pass 3 does not name
this problem or a mechanism for it (it just "partitions" edges by bucket-hint). 06's bucket
section only calls out the flavor-lattice placement gap as what neither design anticipated — that
framing is accurate as far as it goes, but it doesn't note that 02 partially anticipated a
narrower, real form of the same problem (cross-scope attribution precedence) that 01 did not. Not
a misstatement, just an unexploited point of differentiation between the two designs.

---

## 2. Findings present in 01–05 but missing or under-weighted in 06

06's "Ranked simplification backlog" reads as if it collects the actionable items from the
critics, but several concrete, low-risk, low-effort items from 03/04/05 do not appear anywhere in
06 (not in the backlog, not in prose):

- **Critic-03 item 1** — move all tracker mutation into `RootContributionComputer.compute`,
  delete `RootContribution.scope` and the `lintClosure == null` kind-check-via-nullness gate.
  Rated "Effort: S (half-day), Risk: low; pure code motion, byte-identical" — as safe and cheap as
  anything in 06's "housekeeping batch," yet absent from 06 entirely.
- **Critic-03 item 5** — simplify the `ResolvedComponentsVisitor.visit` contract (drop the vestigial
  `Comparable` return-set requirement, compute `shortId` once instead of twice, derive `direct`
  from `directFromProject` instead of a redundant parallel check). Rated low effort/low risk.
  Absent from 06.
- **Critic-03 item 6** — replace refactor-diary comments ("relocated verbatim from…", "matching
  collectRootClosures") with domain-stated invariants. Rated effort S, risk none. Absent from 06,
  even though 06's own prose (Verdict on the inversion, "session layer... preserved the deleted
  god-class's incidental ordering quirks as spec") echoes the *diagnosis* without ever carrying the
  *fix* into the backlog.
- **Critic-04 finding 7 / item 7** — unify `recordMainRoot`/`recordReachable`'s two union paths in
  `MainReachabilityTracker` (one uses a raw `addAll` "to bit-match the out-param it replaced," the
  other filters blanks). Effort: hours, risk: low pending one verification. Not mentioned in 06's
  backlog or prose at all — a genuine drop, distinct from the (correctly captured) "two
  reachability *channels*" finding from critic-03, which is a different and larger claim about the
  same class.
- **Critic-05 S4** — make the reference channel explicit (stop communicating fold state to the
  extractor by mutating `WorkspaceRenderPlanService` mid-pass; pass the accumulated view as a
  parameter instead). This one is *mentioned* in 06's qualitative verdict ("Reference-collector...
  point 3: the covert mutable-state channel... is accidental protocol weight") but never promoted
  into the itemized, ranked backlog (items 1–10) the way S1/S2/S3/S5/S6 all are. Given 06 rates it
  effort "medium plumbing, risk low, byte-identical" in the source document, its absence from the
  actionable list — while lower-value items like S5/S6 make it into backlog item 9 — looks like an
  inconsistency in how thoroughly the backlog was assembled, not a deliberate triage choice (06
  gives no reasoning for excluding S4 specifically).
- Smaller: critic-04's "Big-O" note that `scopedSiblingClosureDependenciesByShortId` is O(D×N) per
  bucket and could invert the loop (not just be computed once, which 06 does capture via backlog
  item 6) is not reflected — 06 only captures the "compute once instead of twice" part of critic-04's
  item 2/3, not the standalone algorithmic-complexity suggestion.

None of these omissions contradict 06's stated verdicts, and 06 never claims the backlog is
exhaustive — but the backlog's own framing ("ordered by value... each rated for byte-identity
risk") reads as comprehensive, and a reader using 06 as the single artifact to act on would miss
several free, low-risk wins that exist in the source critiques.

---

## 3. Contradictions / unreconciled tensions between the five inputs

**MainReachabilityTracker: characterized differently by 03 and 04, and 06 only surfaces one side.**
Critic-03's headline duplication finding is that reachability is computed via **two independent
channels** for MAIN roots — a declared-edge DFS (`computeScope`, seeded before the walk) and the
walk's own `RootVisitOutcome.reachable*` — unioned together, protected only by a documented
ordering invariant. Critic-03 calls this "the clearest concrete case of accidental duplication
anywhere in this branch." Critic-04, examining the same class from the bucket/reachability angle,
lists `MainReachabilityTracker` under **"Keep as-is (plainly)"**: *"...its seed-before-resolve
ordering, and the intersection-of-exclusions semantics in `filterExcludedByEveryReachableRoot` —
correct Gradle edge semantics, **single-pass DFS**, cached edge lookups. Sound."* Critic-04 never
mentions `computeScope` or the dual-channel union at all — its "single-pass DFS" description
reads as if there is exactly one reachability computation, not two unioned ones. This is not a
factual contradiction so much as two critics operating at different resolutions on the same
object without cross-checking each other, but the net effect is that one document (04) implicitly
vouches for the soundness of a mechanism the other (03) flags as its top duplication finding. 06
builds an entire scorecard entry around 03's dual-channel finding (correctly, and without
overclaiming a fix) but never notes that 04 characterized the very same tracker as clean and
complete — a one-line reconciliation ("critic-04's 'keep as-is' on the tracker addresses its
exclude/reachability *semantics*, not the *duplication* critic-03 found in the same class; the two
are not actually in conflict, but 04 didn't surface the second channel independently") would close
this gap. As written, a careful reader who read 04 before 03 could reasonably conclude the tracker
had already been vetted as sound, which slightly undersells how live critic-03's "investigate and
likely delete one channel" recommendation (06 backlog item 2) actually is.

No other direct, unreconciled contradiction was found between 01–05: every other overlap between
critics (buckets, reachability's gating role, the referenced-but-unreached fallback, the
generation-set closure) is corroborating, not conflicting, and 06's reconciliation of those is
accurate to the source documents.

---

## 4. Gaps the whole set misses

1. **No empirical measurement anywhere.** All six documents reason entirely in asymptotic /
   Big-O terms (`B`, `M·V`, `O(E)`, "roughly 2× the dominant per-project cost," etc.). None cites
   an actual wall-clock number from the real monorepo migration this branch was built for. The
   entire adversarial review — clean-room designs, critics, and synthesis alike — validates the
   *shape* of the perf argument (fewer resolutions) without any of the six documents checking that
   the shape translates into the claimed real-world win, or quantifying by how much. This is a
   blind spot the clean-room framing can't fix (it has no code to profile) but the three
   code-reading critics could have addressed and didn't.
2. **The golden-baseline gate (C1) is treated as a reliable oracle by every document, but its own
   coverage is never examined.** Nearly every "byte-identity risk: low" or "gated by the golden
   baseline" qualifier across 03/04/05/06 assumes the golden-baseline test corpus actually
   exercises the scenario in question (cyclic project quotients, test-only-reachable libraries,
   excludes/substitution edge cases, compressed-variant fallback paths). If the golden corpus is
   missing any of these — and nothing in the six documents checks that it isn't — several
   "byte-identical-safe" verdicts (including 06's own "explicitly not recommended... needs its own
   golden-gate cycle" caveats) rest on an unverified assumption about test coverage, not a verified
   property of the codebase.
3. **Configuration-cache / build-cache interaction is asserted, never verified.** Critic-03 states
   the serialization boundary (`DeclaredDependencyMetadata` JSON, task inputs) is "forced by task
   inputs/cacheability" — implying Gradle configuration-cache compatibility is a design driver —
   but no document (clean-room or critic) actually checks whether the new task graph achieves
   configuration-cache compatibility, measures cache hit rates, or considers whether any of the
   proposed simplifications (e.g., critic-03's item 4 rekeying `AggregatedDependencyRootMetadata`,
   or 06 backlog item 8's "pass alongside the results list" which turns a task boundary into an
   in-memory call) would help or hurt cacheability. This sits directly adjacent to material both
   01 and the critics discuss (task boundaries, serialization) but nobody follows through on it.

---

## Overall confidence verdict

The synthesis's core verdicts (inversion vindicated; buckets' aggregation half essential but its
attribution machinery over-built; reachability's gating job essential and its attribution job
carries a real duplicate-channel tax; reference-collector has an essential kernel wrapped in
concrete, already-diverged slop) are well-supported by, and faithful to, the five source documents
— but 06 should not be treated as a complete index of the critics' own backlogs (five low-risk
items from 03/04/05 are absent) or as proof that 01 and 02 agree on resolution *count*, where they
in fact propose different mechanisms and only 02's matches the real code.
