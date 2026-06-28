# Item 17 — Consolidate Bucket Set-Math; Remove Duplication & Dead Code (Design)

> **Status:** Draft for final review 2026-06-28 (grounded against codebase).
> **Executor:** Codex.
> **Behaviour change:** none — mechanical extraction + dead-code removal. Golden EMPTY-diff.
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`. **Depends on:** Items 10 (size guard) and 12
> (planner extracted). **Execute before Item 18** so the SCC work lands on de-duplicated code.

> **⚠️ Execution note — delegate to subagents; protect the main context.**

---

## Goal

Remove the only genuinely reducible mess left in the ownership domain: the bucket set-math
primitives are **duplicated verbatim across three files**, the extraction that created
`BucketOwnershipPlanner` (Item 12) **copied** the helpers instead of moving them, and one copy
is now fully dead. Consolidate the shared primitives into a single `BucketSetMath.kt`, delete
the duplicates and the dead function. Pure mechanical move — **golden empty-diff**, ~120 LOC
removed, no algorithm change.

This is **not** a re-architecture. The set-based ownership math (`intersectByBucketOwner` →
default, `withoutDependenciesCoveredBy` → subtract inherited) is **irreducible essential
complexity** — flavor ownership *is* set membership — and stays exactly as written. We are only
removing duplication and dead code, not "purifying" the algorithm.

## Grounded current state (verified)

The shared set-math primitives are scattered and duplicated:

| Primitive | Resolver copy | Planner duplicate | Engine consumes |
|---|---|---|---|
| `data class CoveredDependency` | `AggregatedDependencyResolver.kt:770` | (uses resolver's) | yes (`:46`) |
| `Map<…>.withoutDependenciesCoveredBy(vararg)` + private `(coveredByShortId)` | `:775`, `:783` | `BucketOwnershipPlanner.kt:778` | yes (`:170`,`:218`) |
| `groupByShortId` | `:780` | `:647` | — |
| `CoveredDependency.canCover` | `:814` | `:809` | — |
| `ResolvedDependency.canCoverDeclaredPlaceholder` | `:820` | `:815` | — |
| `CoveredDependency.rootsSupersetClosureOf` | `:853` | `:883` | — |
| `CoveredDependency.toOverrideTarget` | `:894` | `:889` | — |
| `intersectByBucketOwner` | `:875` | (uses resolver's) | yes (`:111`,`:258`) |
| `withoutDependenciesOwnedByNonDefaultHierarchy` | `:859` | (uses resolver's) | yes (`:135`) |
| `Map<…>.asCoveredBy(bucketName)` | (uses engine's) | (uses engine's) | **defined** `DependencyBucketPlacementEngine.kt:670` |
| `withoutDeclaredPlaceholdersCoveredByDefault` | `:832` **— DEAD** | `:827` (live, called `:138`,`:168`) | — |

Two distinct problems:

1. **Dead code.** `AggregatedDependencyResolver.kt:832 withoutDeclaredPlaceholdersCoveredByDefault`
   has **zero callers** in the resolver after Item 12's extraction. The live copy is the
   planner's (`BucketOwnershipPlanner.kt:827`, called at `:138`/`:168`). Delete the resolver's.
2. **Verbatim duplication.** The general-purpose primitives (`withoutDependenciesCoveredBy`,
   `groupByShortId`, `canCover`, `canCoverDeclaredPlaceholder`, `rootsSupersetClosureOf`,
   `toOverrideTarget`) exist as byte-identical copies in both the resolver and the planner. The
   `internal` primitives the engine consumes (`CoveredDependency`, `intersectByBucketOwner`,
   `withoutDependenciesOwnedByNonDefaultHierarchy`, the `internal withoutDependenciesCoveredBy`)
   are squatting at the bottom of `AggregatedDependencyResolver.kt` — the *wrong* file, since
   the resolver no longer owns ownership (Item 12 moved that to the planner). `asCoveredBy`
   lives in a third file (the engine).

## The one care point (must honor)

**The planner's test-ownership variants are NOT duplicates — leave them with the planner.**
`canCoverTestDependency` (`:759`), `canCoverDeclaredTestMetadata` (`:848`),
`canCoverInheritedTestRoot` (`:856`), `canCoverDeclaredTestRoot` (`:871`) exist only in
`BucketOwnershipPlanner.kt` and encode test/androidTest delta-ownership rules (Item 13). They
are not general set-math and have no resolver twin. Do **not** move them to `BucketSetMath.kt`;
they stay private to the planner. Only the **general** primitives in the table above move.

**Verify identity before merging.** Before deleting any duplicate, diff the resolver copy
against the planner copy and confirm they are byte-identical (the audit reported they are). If
any pair has diverged, STOP — that divergence is a latent behaviour difference, not a safe
de-dup, and must be reconciled deliberately, not silently collapsed.

## Work

1. **Create `BucketSetMath.kt`** in
   `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/` holding the
   single canonical copy of the general primitives: `CoveredDependency`,
   `withoutDependenciesCoveredBy` (both overloads), `groupByShortId`, `canCover`,
   `canCoverDeclaredPlaceholder`, `rootsSupersetClosureOf`, `toOverrideTarget`,
   `intersectByBucketOwner`, `withoutDependenciesOwnedByNonDefaultHierarchy`, and
   `asCoveredBy` (moved from the engine). Preserve visibility (`internal` stays `internal`) so
   the resolver, planner, engine, and tests all resolve to the one definition.
2. **Delete the resolver's orphaned cluster** (`AggregatedDependencyResolver.kt:770–896`),
   including the dead `withoutDeclaredPlaceholdersCoveredByDefault` (`:832`) which is removed
   outright (not moved — it is dead and the planner has the live copy).
3. **Delete the planner's duplicate copies** of only the general primitives (`:647`, `:778`,
   `:809`, `:815`, `:883`, `:889`), keeping the planner's test-ownership variants
   untouched. The planner's `withoutDeclaredPlaceholdersCoveredByDefault` (`:827`) is the live
   planner-specific helper. **Keep it private in `BucketOwnershipPlanner.kt`** and make it call
   the shared helpers from `BucketSetMath.kt`. Do not move it to `BucketSetMath.kt` in this
   item; doing so would blur the generic set-math layer with planner ownership policy.
4. **Move `asCoveredBy` out of the engine** (`DependencyBucketPlacementEngine.kt:670`) into
   `BucketSetMath.kt`; update the engine's import.
5. **Update imports** in `AggregatedDependencyResolver.kt`, `BucketOwnershipPlanner.kt`,
   `DependencyBucketPlacementEngine.kt`, and any test referencing these `internal` symbols.

## Safety mechanism

- **Sample golden EMPTY-diff** — moving code and deleting a dead function changes no output.
- **No parity flag needed** — this is a compile-time relocation; identical bytecode-level
  behaviour. The compiler + existing tests + golden are the safety net.
- **Size guard (Item 10):** no change expected.
- **Provably-unreachable deletes only:** confirm zero non-test callers of the resolver's
  `withoutDeclaredPlaceholdersCoveredByDefault` before deleting (grep), and confirm
  byte-identity of every de-duplicated pair before collapsing it.

## Win

One canonical home for bucket set-math → "which file owns the ownership primitives" is
obvious, the dead function is gone, and the resolver file stops squatting on ownership helpers
it no longer uses (reinforcing the Item 12 value/ownership boundary). ~120 duplicated LOC + one
dead function removed with zero behaviour change.

## Acceptance criteria

- `BucketSetMath.kt` holds the single copy of the general primitives; resolver, planner,
  engine, and tests import from it.
- The resolver's bottom ownership cluster (`:770–896`) and the resolver's dead
  `withoutDeclaredPlaceholdersCoveredByDefault` are removed; planner's duplicate general
  primitives are removed; planner's live private
  `withoutDeclaredPlaceholdersCoveredByDefault` remains in `BucketOwnershipPlanner.kt`;
  planner's **test-ownership variants are untouched**.
- `asCoveredBy` lives in `BucketSetMath.kt`, not the engine.
- Every de-duplicated pair was confirmed byte-identical before collapse (no silent divergence).
- Sample golden empty-diff; existing resolver/planner/engine unit tests stay green; PAX builds
  green; size guard no-increase.

## Out of scope / Non-goal

- Changing any ownership algorithm or set-math semantics (the math is irreducible — leave it).
- "Purifying" the engine's graph+set-math hybrid into a pure DAG model — that would re-implement
  the same set semantics with more code (documented non-goal in the roadmap).
- SCC ordering (Item 18); target-reference facts (Item 19); task cacheability (candidate
  future Item 20).
