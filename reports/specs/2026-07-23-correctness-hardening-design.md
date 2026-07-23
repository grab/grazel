# Design — Correctness-hardening pass (adversarial-review items 10 + 6)

Source findings: `reports/review/03-critic-inversion.md` item 4 (zip-by-index) and
`reports/review/04-critic-buckets-reachability.md` #2 (triple-pass test subtraction); ratified
in `reports/review/06-synthesis.md` (backlog items 10 and 6). Branch:
`arun/dependencies-refactor`.

## Problem

**Item 10 — zip-by-index.** `ResolveWorkspaceDependenciesTask.kt:95` pairs
`rootComponents.zip(rootMetadata)`: an `@Input ListProperty<ResolvedComponentResult>` and a
JSON metadata list produced by a *different* task (`CollectWorkspaceDependencyRootMetadataTask`),
matched purely by position. Both are wired from the same `rootInputs` iteration across separate
`configure` blocks in `WorkspaceDependencyInputsRegistrar` (`:113-130`) — the ordering invariant
lives in three configure blocks and is protected only by list sizes matching. A reorder that
preserves count misattributes every downstream fact silently (wrong metadata onto wrong resolved
graph). `pinMavenArtifactsTask` (`:131-135`) consumes raw configurations without zipping —
unaffected.

**Item 6 — triple-pass test subtraction.** `TestBucketPlanner.withoutTestDependenciesCoveredBy`
(`:450-476`) runs three passes: (1) `Coverage.ofGrouped(coveredByShortId).subtract(...)` using
generic coverage; (2) restore `direct` deps that fail `canCoverTest`; (3) final `filterNot`
re-applying `canCoverTest` to the union. Critic-04 claims the keep/drop decision is fully
determined by pass 3. **The claim is unproven**: a non-`direct` dep dropped by pass 1 (generic
`canCover`) never reaches pass 3 — a single-`canCoverTest`-pass form is equivalent ONLY IF
`canCoverTest ⟹ canCover` for those deps (or the difference never manifests), AND the
`overrideTarget` annotation that pass 1's subtract attaches is reproduced identically.

## Decisions (agreed in brainstorm)

1. **Item 10 shape: keyed carrier at wiring.** Pairing becomes intrinsic; positional contract
   dies. Fallback (only if Gradle input-fingerprinting rejects the carrier): per-element
   identity `check` on the existing zip.
2. **Item 6 shape: prove-first.** The equivalence is a proof obligation settled by reading
   `Coverage`/`CoveredDependency` before any collapse; refutation is a valid outcome that
   closes the item as `[RESOLVED-KEEP]` with the counterexample documented.

## Item 10 design

- **Key**: `RootKey(projectPath, configurationName, kind)` derived from
  `AggregatedDependencyRootMetadata`. **Step 0 verifies uniqueness** across all planned roots
  (assertion in/next to `WorkspaceDependencyRootInputPlanner.plan`, unit-tested). If any
  collision: extend the key with `bucketName`, re-verify. No silent tolerance.
- **Carrier**: registrar wires ONE list —
  `rootComponent.map { KeyedRootComponent(rootInput.rootKey(), it) }` — replacing the
  components wiring; the metadata task's items carry the same key (the metadata already
  contains the key fields; the join key is *derived*, not duplicated, wherever possible).
- **Join**: resolve task builds `metadataByKey = rootMetadata.associateBy { it.rootKey() }`
  (checking no duplicate keys) and pairs each `KeyedRootComponent` by lookup, `check`ing every
  component's key resolves — missing or leftover keys fail with both sides printed. `zip` and
  the size check are deleted.
- **Fingerprinting risk gate (before PAX)**: samples compile + migrate + golden must pass with
  the carrier as the `@Input` element type. If Gradle rejects it (serialization/fingerprint),
  STOP — fall back to the decision-2 shape (zip + per-element `check` that the component's
  project identity matches `metadata.projectPath`), which hardens without restructuring.
- Order-preservation note: the carrier list preserves today's wiring order, so resolution
  ORDER (which feeds deterministic merge tie-breaks) is unchanged — byte-identity expected.

## Item 6 design

1. **Proof step (no production code)**: read `Coverage.canCover` vs
   `CoveredDependency.canCoverTest` and settle: (a) for non-direct deps, does
   `canCoverTest ⟹ canCover` (equivalently: can a dep be generically covered but NOT
   test-coverable — if yes, pass 1 drops something pass-3-alone would keep → collapse changes
   behavior); (b) where does the `overrideTarget` annotation attach during pass 1's subtract,
   and does the collapsed form attach it identically. Findings recorded as truth-table unit
   tests against the real predicates (constructed `CoveredDependency`/`ResolvedDependency`
   fixtures), not prose.
2. **Provable** → collapse `withoutTestDependenciesCoveredBy` to the single
   `canCoverTest`-based filter (annotation-preserving), tests + golden gate the equivalence.
3. **Refuted** → close item 6 as `[RESOLVED-KEEP]`: KDoc on the function replaces "three passes"
   mystery with the counterexample; the truth-table tests pin the semantic difference; synthesis
   backlog updated. No production behavior change.

## Verification

- Per task: `./gradlew :grazel-gradle-plugin:test --console=plain` →
  `./gradlew verifyGrazelGoldenBaseline --console=plain` (byte-clean; documented waiver only) →
  `bazelisk build --nobuild //...`.
- **One full PAX sweep after both items land** (`reports/specs/VERIFICATION-GATES.md` §PAX 1-6)
  — mandatory: item 10 changes task-input shape (fingerprints), and `bucket/` content equality
  is exactly what the size guard + golden arbitrate.
- New unit tests: RootKey uniqueness, join-mismatch loud-failure, item-6 truth tables.
- ANY golden/PAX output drift = stop, revert, report. Do not patch baselines.

## Execution + models

SDD: Sonnet implementers (item 10 = Gradle wiring semantics; item 6 = proof-carrying), Sonnet
task reviewers, controller-run /simplify pass, Opus adversarial final review. Fable advisory
only if item 6's proof lands ambiguous.

## Out of scope

Items 5/7/8/9 (future batch); metadata-merge consolidation (`[DEFERRED-BY-DESIGN]`); any
change to what buckets contain (item 6 collapses only on proof of identity); the pin task's
consumption shape; `TopologicalSorter`; `resolution/` beyond the join site.

## Risks

- **Carrier vs Gradle fingerprinting** — gated on samples before PAX; explicit fallback shape.
- **Item-6 proof subtlety** — the reason this item is 1-2 days and not hours; refutation is a
  first-class outcome, not a failure.
- **Key non-uniqueness** — checked before anything joins on it; extension path defined.
