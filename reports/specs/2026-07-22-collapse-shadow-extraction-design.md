# Design — Collapse the shadow extraction pipeline (adversarial-review item 1, full S1)

Source finding: `reports/review/05-critic-refcollector.md` §S1, ratified in
`reports/review/06-synthesis.md` (backlog item 1). Branch: `arun/dependencies-refactor`.

## Problem

`TargetReferenceFactsExtractor` re-derives, per target type, the same selection logic
(reachable variants → compression grouping → representative picking) that the four
`TargetBuilder` implementations use to render — nearly line-for-line, across four pairs:

| Facts side (`TargetReferenceFactsExtractor`) | Render side |
|---|---|
| `androidLibraryData` / `androidUnitTestData` | `AndroidLibraryTargetBuilder.build` / `unitTestsTargets` |
| `androidBinaryFacts` | `AndroidBinaryTargetBuilder` |
| `androidInstrumentationFacts` | `AndroidInstrumentationBinaryTargetBuilder` |
| `standaloneAndroidTestFacts` | `AndroidTestTargetBuilder` |
| `collect()` when-ladder | `TargetBuilder.canHandle` dispatch |

The pairs have **already diverged** on one branch: with compression active and non-empty
`reachableSuffixes` but zero matching keys in `targetsBySuffix`,
`AndroidLibraryTargetBuilder` (`:96-103`) emits **nothing** (filtered map used as-is) while the
extractor (`TargetReferenceFactsExtractor.kt:186-193`) **falls back to per-variant extraction**
(`takeUnless(isEmpty) ?: map{extract}`). In that state, facts are collected — and published to
the render plan as "referenced" — for targets that never render: phantom references, the mirror
image of the dangling-label bug class this branch exists to prevent. Nothing structural stops
the two sides from drifting further.

## Decision summary (agreed in brainstorm)

1. **Scope: full S1** — all four pairs plus the `collect()` when-ladder. Not just the diverged
   library pair.
2. **Divergence: repro first** — prove the divergent state constructible (bug fix with a
   deliberate output delta) or unconstructible (pure refactor + enforced invariant) *before*
   restructuring.
3. **Seam: selection lives in the builders** — `TargetBuilder` gains a data-selection step the
   facts pass consumes; dispatch reuses `canHandle`.

## Design

### The seam

```kotlin
// migrate/BazelTarget.kt
interface TargetBuilder {
    fun build(project: Project): List<BazelTarget>
    fun canHandle(project: Project): Boolean
    fun selectData(project: Project): List<TargetData>   // NEW
}
```

`TargetData` is a minimal interface in `migrate/`:

```kotlin
interface TargetData {
    fun referenceFacts(): TargetReferenceFacts
}
```

The existing data classes (or thin composites, below) implement it; the current top-level
`referenceFacts()` adapter functions at the bottom of `TargetReferenceFactsExtractor.kt` become
these implementations, unchanged in content.

Each builder is refactored so that **selection logic exists exactly once**, in `selectData`,
and `build()` is a pure mapping of the selected data to `BazelTarget`s:

```kotlin
override fun build(project: Project): List<BazelTarget> =
    selectData(project).map { it.toTarget() }   // per-builder mapping
```

### Per-builder mapping (parity deltas are semantic; each moves INTO selectData)

- **AndroidLibraryTargetBuilder** — `selectData` returns library data (compressed-suffix
  selection) + unit-test data (suffix-grouped representatives). Both today's twins collapse
  into it. The divergent fallback branch is reconciled here (see below).
- **AndroidBinaryTargetBuilder** — `selectData` returns a composite carrying
  `AndroidLibraryData` + `AndroidBinaryData` per selected variant; its `referenceFacts()`
  merges both dep lists (today's `androidBinaryReferenceFacts`). The
  `appVariantFilter` predicate (reachable-or-referenced) lives only here.
- **AndroidInstrumentationBinaryTargetBuilder** — `selectData` applies the `hasSources`
  filter (today duplicated in `androidInstrumentationFacts`).
- **AndroidTestTargetBuilder** — `selectData` applies the reachable-or-referenced variant
  filter (today duplicated in `standaloneAndroidTestFacts`).
- **KotlinLibraryTargetBuilder** — `selectData` applies the `isReachableJvmProject` gate
  (today duplicated in `kotlinFacts`).

Implementation must verify each pair's current behaviour matches before collapsing; any
*additional* divergence discovered gets the same treatment as the known one (repro → decide
canonical side → deliberate delta or invariant).

### The facts pass afterlife

`TargetReferenceFactsExtractor` shrinks to a thin dispatcher (kept, to minimise the task-file
diff — `CollectTargetMavenRepoReferencesTask` keeps injecting it):

```kotlin
fun collect(project: Project): TargetReferenceFacts =
    targetBuilders.filter { it.canHandle(project) }
        .flatMap { it.selectData(project) }
        .map(TargetData::referenceFacts)
        .merged()
```

This deletes the four shadow pipelines and the when-ladder in one move. DI: builders are
already `@IntoSet @Singleton` in the same component; the extractor swaps its seven extractor
dependencies for `Set<@JvmSuppressWildcards TargetBuilder>`.

### Divergence handling (Step 0 — before any structural change)

Unit test constructs the suspect state: a `VariantCompressionResult` whose `variantToSuffix`
maps reachable variants to suffix X while `targetsBySuffix` lacks key X. Drive both sides;
assert they disagree.

- **Constructible** → live bug. The shared `selectData` adopts the **builder's branch**
  (emit/collect nothing) so rendered bytes stay identical; the facts-side change (phantom
  references disappear) is a deliberate delta adjudicated by golden + PAX gates.
- **Unconstructible** (invariant `variantToSuffix.values ⊆ targetsBySuffix.keys` holds by
  construction) → pure refactor. Enforce the invariant at `VariantCompressionResult`
  construction (fail loudly there), drop *both* consumer-side fallbacks, keep the test as the
  invariant's executable documentation.

## Altitude audit

Each design element, checked for depth (per the review's altitude lens):

- **`selectData` on `TargetBuilder`** — right altitude. Target-type knowledge (which variants,
  which representatives, which filters) already lives per-builder; the facts pass was
  re-implementing it one level *away* from its owner. Moving selection into the owner is
  generalizing the mechanism, not adding a special case. Dispatch reuse (`canHandle`) removes
  the parallel when-ladder rather than patching it.
- **`TargetData.referenceFacts()` as an interface method** — right altitude, with one accepted
  coupling: `migrate/` data types now reference `TargetReferenceFacts`
  (`gradle/dependencies/model`). Polymorphic dispatch beats the alternative (a when-ladder over
  data types in the facts layer — which would recreate the exact shape we are deleting).
  `TargetReferenceFacts` is a plain value model; the dependency direction (migrate → model) is
  already present elsewhere.
- **Invariant enforcement at `VariantCompressionResult` construction, not in consumers** —
  if the divergent state proves unconstructible, the check belongs where the data is built
  (deepest point), not re-checked at each consumer. Consumer-side fallbacks are the bandaid
  altitude; construction-site invariants are the mechanism altitude.
- **Keeping the thin `TargetReferenceFactsExtractor` wrapper** — deliberately *shallow*: it is
  pure plumbing (task boundary adapter), and inlining it into the task would couple task code
  to builder-set iteration for no gain. Shallow is correct for plumbing.
- **NOT changing** `TargetVariantReachability` helpers, `resolution/`, `bucket/`, or the
  collector's fixpoint — those are other items' altitude; folding them in here would make this
  diff unreviewable.

## Testing & gates

1. **Step 0 repro test** (divergence) — as above; lands first, in its own commit.
2. **Selector-level unit tests per builder**: reachable-compressed selection,
   referenced-fallback selection, unreachable-dropped; largely ports of existing
   `TargetVariantReachabilityTest` cases plus new `selectData` coverage. Existing tests must
   pass unchanged unless the divergence resolves as a live bug (then only the affected
   assertions move, deliberately).
3. **End-to-end**: existing `sample-android-test-util*` fixture chain guards the
   referenced-path shapes; golden corpus (verified) covers compression + cyclic shapes.
4. **Gates** (in order): `./gradlew :grazel-gradle-plugin:test` →
   `./gradlew verifyGrazelGoldenBaseline` (byte-clean, unless the §Divergence bug-fix delta —
   then diff must contain ONLY that) → `bazelisk build --nobuild //...` →
   PAX sweep (§PAX 1–6 of `reports/specs/VERIFICATION-GATES.md`) only if golden output moved.

## Out of scope

- **S2 memoization** (selectors caching outputs so `GenerateBazelScriptsTask` stops
  re-extracting) — natural follow-up once this seam exists; kept out to avoid coupling a
  perf change into a correctness refactor.
- **Item 2** (dual reachability channels) and **item 3** (fixpoint → worklist) — separate
  efforts per the agreed sequence.
- Any change to generated output beyond the possible §Divergence delta.

## Risks

- **The one divergent branch** is the only place bytes can move; it is handled explicitly and
  first. Everything else is behaviour-preserving code motion, gated by golden byte-identity.
- **Hidden parity deltas** between other pairs (beyond the known one) — mitigated by
  pair-by-pair verification before collapse and the per-builder unit tests.
- **DI surface change** in the extractor (7 extractors → builder set) — mechanical; Dagger
  wiring is already multibound.
