# Critic 04 — buckets + reachability

Angle: `gradle/dependencies/bucket/` (placement engine, Main/TestBucketPlanner, Coverage,
BucketPlacementGraph, BucketOwnershipPlanner) and reachability
(`resolution/MainReachabilityTracker.kt`, `migrate/target/TargetVariantReachability.kt`,
referenced-but-unreached fallback). All paths under
`/Users/arun.sampathkumar/work/grazel/grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/`.

## Verdict in one paragraph

The *core* placement algorithm (`DependencyBucketPlacementEngine.plan`, the 3-stage
default → hierarchy → leaf set math over the variant lattice) is essential complexity, solved at
the right altitude, and well documented — keep it. Reachability is load-bearing, not removable:
top-down seeding from binaries means "which modules/variants exist at all" must be reconstructed,
and both channels (resolution-time bucket reachability + render-plan reference fallback) are
architecturally forced. The accidental complexity is concentrated in three places: (1) roughly a
dozen subtly-different "is X covered by Y" identity predicates grown case-by-case across four
files; (2) test-bucket subtraction that runs three passes where one predicate decides the outcome,
plus repeated recomputation of invariant grouping/closure maps; (3) declared-metadata merging
re-applied at five different pipeline layers to repair what earlier layers lost. A fourth, smaller
one: reachability facts are stored as bare strings and translated between variant-name, bucket-name
and target-label spaces by suffix surgery in at least three places.

## Essential vs accidental

**Essential (forced by the top-down architecture and the byte-identical output gate):**

- Reconstructing per-project, per-bucket ownership from aggregated leaf closures at all. Once you
  resolve only binary roots, "which bucket declares okhttp for `:lib`" is no longer a Gradle fact —
  it must be recomputed. The lattice model (default/hierarchy/leaf, `extendsFrom`, descendant-leaf
  coverage) mirrors real AGP variant semantics; there is no simpler correct model.
- Test buckets as *residuals* of main (`TestBucketPlanner` class doc, TestBucketPlanner.kt:32-52).
  A test source set really does see main's classpath for free, and the "identity match is not
  enough, closure superset is required" insight (a test root can share main's identity yet pull a
  larger closure) is a genuine correctness requirement, not gold-plating.
- Reachability tracking (`MainReachabilityTracker`). It feeds four independent consumers:
  declared-metadata scoping (`DeclaredMetadataMerger.merge` filters on `isReachableMainBucket`),
  per-edge exclude intersection (`filterExcludedByEveryReachableRoot`, correct
  intersection-not-union Gradle semantics), variant filtering at target generation
  (`reachableMatchedVariants`), and JVM-project fact collection (`isReachableJvmProject`).
  Removing it would regenerate unreachable modules/variants and change output.
- The referenced-but-unreached fallback (`isReferencedGeneratedLibraryTarget`,
  TargetVariantReachability.kt:110-132). Bucket reachability lives in variant-name space and is
  computed from *declared* `project(...)` edges; emitted references live in target-label space
  after variant compression. The fallback bridges a real representational gap; dropping it drops
  legitimately-referenced targets. Keep — but see the altitude note below.

**Accidental (this implementation's own weight):**

1. **Coverage-predicate proliferation.** Count the distinct coverage/identity relations:
   `canCover`, `canCoverDeclaredPlaceholder`, `canCoverTest` → (`canCoverDeclaredTestMetadata`,
   `canCoverInheritedTestRoot`, `canCoverDeclaredTestRoot`), `rootsSupersetClosureOf`
   (all Coverage.kt), `hasSameResolvedOwnerIdentityAs`, `hasSameResolvedArtifactIdentityAs`
   (model), `hasSameDefaultOwnerIdentityAs` + `containsDefaultOwnerEquivalent`
   (DefaultBucketDependencyReducer.kt:51-62), `hasSameDefaultDirectOwnerIdentityAs`,
   `isDirectDependencyCoveredBy`, `isCoveredByDefaultFlatClasspath`
   (DefaultOverrideCarrierPlanner.kt:80-127). That is ~12 relations differing in which of
   {shortId, version, repository, excludeRules, requiresJetifier, jetifierSource, direct-ness,
   closure-superset, declared-placeholder-ness} they compare, each with a paragraph of KDoc
   explaining why its particular combination is load-bearing. Every predicate individually is
   defensible; collectively this is the signature of rules grown one golden-baseline diff at a
   time rather than derived from a definition. The *same conceptual operation* — "default/other
   bucket already provides this; drop it or point an override at it" — is implemented twice at two
   pipeline stages (Coverage.subtract inside placement; the Reducer/CarrierPlanner pair inside
   `ComputeWorkspaceDependencies`) with different identity relations.

2. **Triple-pass test subtraction.** `withoutTestDependenciesCoveredBy`
   (TestBucketPlanner.kt:450-476) runs (a) generic `Coverage.subtract`, (b) a restore pass for
   direct deps that fail `canCoverTest`, then (c) a final `filterNot` of the union — again keyed on
   `canCoverTest`. Trace it: for every dependency the *keep/drop decision* is fully determined by
   pass (c) alone; passes (a)+(b) only contribute the `overrideTarget` annotation `subtract`
   attaches to surviving direct deps. Three full passes (plus the restore's own `canCoverTest`
   evaluation, redundantly repeated in (c)) express what is a single map: "drop iff
   canCoverTest-covered; if kept and subtract would have annotated it, carry the annotation".

3. **Metadata merging at five layers.** Declared/user metadata (excludes, overrides) is reconciled:
   per-project during placement (`withResolvedLeafMetadata`, `withInferredClosure` —
   DependencyBucketPlacementEngine.kt:337-428), again per-project post-placement
   (`withDeclaredMainMetadata` — MainBucketPlanner.kt:182-224), again per merged *output* bucket
   (`declaredMetadataByOutputBucket` + `applyDeclaredMetadataByBucket` — MainBucketPlanner.kt:82-133),
   again for leaves via ancestor backfill (`withGlobalAncestorResolvedMetadata` —
   MainBucketPlanner.kt:258-291), and once more downstream in `ComputeWorkspaceDependencies`'s
   `maxVersionReducer`. The per-project application (`withDeclaredMainMetadata`) and the
   per-output-bucket application apply the *same* declared entries twice; the leaf backfill exists
   only to repair the staleness the per-project application created. Root cause: placement runs on
   per-project views while metadata is global, and the code repairs instead of restructuring.

4. **Reachability smuggled through per-bucket results.** `BucketOwnershipPlanner.buildResults`
   stamps the identical `reachableMainBucketsSnapshot` map into *every* `ResolveDependenciesResult`
   (BucketOwnershipPlanner.kt:167-181); `ComputeWorkspaceDependencies.reachableMainBucketsByProject`
   (ComputeWorkspaceDependencies.kt:120-129) then unions all those identical copies back into one
   map. A resolver-global fact is serialized N-buckets times purely because the per-bucket data
   type was the only available carrier. Pure plumbing accident.

5. **String-space reachability translation.** Reachability is stored as bucket-name strings, then
   re-derived into other name spaces by suffix surgery: `removeTypedTestSuffix`
   (TargetVariantReachability.kt:64-71), `outputBucketNameForTestBucket`'s `testSuffix` mapping
   (TestBucketPlanner.kt:403-416), compressed-suffix retry (TargetVariantReachability.kt:110-132),
   and the hardcoded macro-spelling set `{name, name_lib, name_kt, lib_name}`
   (TargetVariantReachability.kt:93-98) — knowledge that also lives, independently, in
   `GradleDependencyToBazelDependency.kt:56` and `AndroidInstrumentationBinaryDataExtractor.kt:84,131`.
   Three files each privately know how a logical target spells its physical names.

## Ranked simplifications

1. **Stop stamping reachability into every result** (finding 4). Pass
   `reachableMainBucketsByProject` alongside the results list (resolver → compute is an in-memory
   call now — `computeFromResults` already receives the list directly) and keep the field only on
   `WorkspaceDependencies`. Deletes the per-result field (ResolveDependenciesResult.kt:34), the
   snapshot copy, and the re-union pass. *Effort: hours. Risk: low* — the intermediate JSON shape
   changes but generated Bazel output does not; verify the intermediate isn't itself part of the
   golden gate.

2. **Collapse `withoutTestDependenciesCoveredBy` to one pass** (finding 2). Single iteration:
   compute `coveredForTest = canCoverTest-match`, drop if covered; else keep, attaching the
   `overrideTarget` that `Coverage.subtract` would have produced (extract that annotation decision
   from `subtract` into a small shared helper). Also hoist `scopedSiblingClosureDependenciesByShortId`
   so it's computed once per bucket, not once in `withoutTestDependenciesCoveredBy` *and* once in
   `withoutTestDependenciesCoveredByEveryLeaf` (TestBucketPlanner.kt:456,492). *Effort: 1-2 days
   with tests. Risk: low-medium* — pure refactor of a pure function, unit tests +
   golden gate cover it; the subtle bit is preserving exactly when the annotation is attached.

3. **Memoize/hoist invariant grouping in `plannedTestBuckets`/`planTestBucket`.**
   `aggregateMainCoveredDeps + inheritedTestCoveredDeps` are re-grouped by bucket name once per
   project (TestBucketPlanner.kt:226-231) though they never change across the loop; group once
   outside, merge the per-project part in. `leafCoveredDepsByShortId` recomputes the same
   per-leaf covered map once per *ancestor bucket* that shares the leaf (TestBucketPlanner.kt:314-331);
   cache per (project, leafName). Note also `aggregateMainCoveredDeps` already *contains* each
   project's own coverage (it's built from the merged buckets), so `mainCoveredDeps + aggregate`
   double-lists most entries — harmless under any-match semantics but doubles every candidate list.
   *Effort: hours. Risk: low* — outcome of any-match over a multiset is insensitive to duplicates
   and grouping location.

4. **Unify the coverage predicates behind an explicit identity-strength table** (finding 1).
   Define named identity levels (shortId < ownerIdentity < artifactIdentity; orthogonal flags:
   direct-ness rule, closure-superset, declared-placeholder handling, exclude-rule mode
   {ignore, agree-or-absent, must-agree}) and express each of the ~12 relations as a row in one
   table in Coverage.kt. Behaviour-preserving; the win is that the *differences* between rules
   become diffable data instead of prose. Also lets `DefaultBucketDependencyReducer` /
   `DefaultOverrideCarrierPlanner` (currently a parallel mini-Coverage) share the same vocabulary.
   *Effort: ~1 week. Risk: medium* — mechanical but wide; golden gate is the safety net.

5. **Single metadata-resolution boundary** (finding 3). Drop the per-project
   `withDeclaredMainMetadata` application and apply declared metadata exactly once, after
   cross-project merge (the output-bucket application already exists and largely re-does it);
   `withGlobalAncestorResolvedMetadata` should then become unnecessary since leaves would resolve
   against already-merged ancestors. *Effort: ~1 week. Risk: medium-high* — max-version merge is
   order-sensitive and the placement engine's coverage checks read metadata (e.g. excludeRules) of
   the per-project entries, so byte-identity must be re-proven; do it last, behind the golden gate,
   and abandon if diffs appear. Flagging honestly: this may move output in edge cases where a
   declared exclude changes a coverage decision mid-placement.

6. **One canonical "physical target spellings for a logical target" helper** (finding 5). Move the
   `{name, _lib, _kt, lib_}` set and the test-suffix strip next to wherever target names are
   *minted* (the macro/label layer), and have TargetVariantReachability, GradleDependencyToBazelDependency
   and AndroidInstrumentationBinaryDataExtractor consume it. *Effort: hours. Risk: low.* Doesn't
   remove the fallback (which is essential), just de-duplicates the knowledge it runs on.

7. **Unify `recordMainRoot`/`recordReachable` fold semantics** (MainReachabilityTracker.kt:255-276).
   Two union paths differing only in blank-name filtering, with a comment admitting the raw
   `addAll` exists to bit-match the out-param it replaced. Verify blank bucket names cannot reach
   `recordReachable` (they're produced by `ResolvedComponentsVisitor`'s project-edge discovery) and
   collapse to one method. *Effort: hours. Risk: low, but requires that verification first.*

## Altitude

- **Right altitude:** `DependencyBucketPlacementEngine` (a pure, per-project algorithm with an
  explicit input type), `BucketPlacementGraph` (thin cached view over `BucketHierarchyGraph` —
  exactly the adapter it claims to be), `DependencyBucketAccumulator` (honest dumb container),
  `RootContributionComputer` (good data-driven collapse of three parallel `when` blocks).
- **Too low:** the coverage rules. Nine-plus field-combination predicates are special cases piled
  on a shared primitive (`Coverage.subtract`), and `subtract` itself mixes two concerns —
  membership (drop/keep) and annotation (`overrideTarget` rewrite) — which is why the test planner
  has to run it and then fight it (restore pass).
- **Too low:** name-space translation for reachability — string suffix surgery at three call sites
  instead of a typed fact (`projectPath` × `variantName`) with one canonical spelling function.
- **Slightly too high:** `orderedCombinations` (BucketPlacementVariantInputs.kt:231-245) enumerates
  all 2^n flavor subsets by bitmask to synthesize owner buckets Gradle may never create. With
  real-world flavor-dimension counts (n ≤ 3-4) this is harmless, and the over-approximation is
  cheaper than modeling dimensions — acceptable, but worth a guard comment if n can ever be large.

## Big-O / wasted passes (beyond items 2-3 above)

- `scopedSiblingClosureDependenciesByShortId` (TestBucketPlanner.kt:564-587): builds the global
  notation-count map in O(total closure size), then for *each* dependency filters the *entire*
  count map into a fresh sorted set → O(D × N) per bucket (D = deps in bucket, N = distinct
  notations). On monorepo-scale test buckets (thousands of transitives) this is millions of string
  comparisons per bucket, and it runs twice per bucket (item 2). It could invert the loop: for each
  notation with count > threshold, add to the per-dep sets that qualify — or at minimum compute
  once and share.
- `MainBucketPlanner.plan` walks all plans ~5 times (declared-metadata collection, default merge,
  hierarchy merge, covered-deps snapshot, leaf merge + subtraction). Each pass is linear and clear;
  I would *not* fuse them — readability beats one O(P·D) constant factor here. No action.
- Everything is in-memory per Gradle invocation; nothing here is asymptotically dangerous except
  the sibling-closure map. The expensive thing this branch was built to avoid (per-module Gradle
  resolution) dwarfs all of it. Perf criticisms here are hygiene, not architecture.

## Keep as-is (plainly)

- The 3-stage placement ordering (deepest-explicit → widest-inferred → leaf-residual) and its
  documented invariants — this is the essential algorithm and the KDoc at
  DependencyBucketPlacementEngine.kt:89-115 is exactly the documentation such code needs.
- Test-bucket-as-residual with closure-superset coverage (`canCoverInheritedTestRoot`'s superset
  check). The starvation scenario it prevents is real.
- `MainReachabilityTracker` as a class, its seed-before-resolve ordering, and the
  intersection-of-exclusions semantics in `filterExcludedByEveryReachableRoot` — correct Gradle
  edge semantics, single-pass DFS, cached edge lookups. Sound.
- The referenced-but-unreached fallback's *existence*. It is not a hack around a bug; it is the
  necessary bridge between declared-edge reachability (available before generation) and emitted
  label references (available only after). Only its duplicated name-spelling knowledge should move
  (simplification 6).
- The package-level subsystem doc in BucketOwnershipPlanner.kt:17-75 with the worked example —
  genuinely good; it is the map this subsystem needs.
