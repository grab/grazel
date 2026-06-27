# Item 12 — Extract `BucketOwnershipPlanner` (Layer 3, RELOCATE) (Design)

> **Status:** Approved 2026-06-27 (grounded). **Executor:** Codex.
> **Behaviour change:** none — same algorithm, relocated. Golden empty-diff. (Step A of the
> full-layering rewrite; Item 13 is Step B, the output-changing improvement.)
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`. **Depends on:** Item 10 (size guard exists).

> **⚠️ Execution note — delegate to subagents (Opus); protect the main context.**

---

## Goal

Make bucket ownership a first-class **Layer-3 `BucketOwnershipPlanner`** by moving the
existing ownership/placement logic out of `AggregatedDependencyResolver.ResolutionSession`
**as-is** (no algorithm change). The resolver keeps Layer-2 value resolution. This proves
the layer boundary with **zero behaviour change** before Item 13 changes the algorithm.

## The value/ownership boundary (verified)

**Boundary line:** inside `resolve()`, everything before the `planMainBuckets()` call is
VALUE; everything from there on is OWNERSHIP.

**STAYS in Layer 2 (resolver):** `collectRootClosures()` (`AggregatedDependencyResolver.kt`
398–547), `resolveRootToDependencyMap()` (1103–1217, the only Gradle-API contact),
`collectMainProjectEdgeScope()` (339–396), `addDeclaredMetadataClosures()` (549–581),
reachability accessors, `mergeDependencyMetadataByMaxVersion`/`unionDependencyMaps`
(shared utilities).

**MOVES to Layer 3 (`BucketOwnershipPlanner`):** `planMainBuckets()` (591–693),
`planTestBuckets()` (836–859), `testBucketPlans()`/`plannedTestBuckets()` (912–996),
the declared-metadata folding (`withDeclaredMainMetadata`/`declaredOutputMetadata`/
`withDeclaredMetadata*` 695–777), cross-project merge (`mergeNamedBuckets`/
`mergeDependencyMaps` 779–799), `withGlobalAncestorResolvedMetadata` (801–834),
test/main subtraction + visibility (`withoutTestDependenciesCoveredBy` 1310–1329,
`visibleMainBucketNamesForTestBucket` 1002–1018), the set-math pure functions
(`withoutDependenciesCoveredBy` 1271–1308, `withoutDependenciesOwnedByNonDefaultHierarchy`
1403–1417, `intersectByBucketOwner` 1419–1436, `canCover*`), concrete-test-leaf mapping
(861–910), and `buildResults()` (1020–1052).

**Already extracted:** `DependencyBucketPlacementEngine` already holds the within-project
placement + `BucketPlacementGraph` + variant-list builders. Item 12 moves the *cross-project
aggregation / declared-folding / test-subtraction / result-assembly* layer to join it.

## Pure planner interface

```kotlin
internal class BucketOwnershipPlanner(
    private val declaredDependencyMetadata: DeclaredDependencyMetadata,
    private val precomputedKspDependencies: Set<ResolvedDependency>,
) {
    fun plan(input: OwnershipPlannerInput): List<ResolveDependenciesResult>
}

internal data class OwnershipPlannerInput(
    val leafClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
    val leafUnitTestClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
    val leafAndroidTestClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
    val hierarchyBucketClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
    val testHierarchyBucketClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
    val reachableMainBucketNamesByProject: Map<String, Set<String>>,   // immutable snapshot
    val lintDeps: Map<String, ResolvedDependency>,
    val declaredTestDependenciesByBucket: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
)
```

The planner is **pure** — by the time ownership runs, all Gradle resolution is done; it
touches only `Map<String, ResolvedDependency>`, `DependencyBucketVariant`,
`DeclaredDependencyMetadata` (a serialized data tree), and `BucketHierarchyGraph`. No
`Project`/`Configuration`/live-Gradle access. Output is the current `buildResults()` return.

## The one care point (verified, must honor)

`addDeclaredMetadataClosures()` sits exactly on the boundary: it MUTATES
`hierarchyBucketClosures` / `testHierarchyBucketClosures` and RETURNS
`declaredTestDependenciesByBucket`. It must stay entirely in Layer 2 and **complete** (seal
the closure maps) before `OwnershipPlannerInput` is constructed. Do not move or split it
into the planner. At handoff, snapshot the five mutable maps + `reachableMainBucketNamesByProject`
into immutable copies, package as `OwnershipPlannerInput`, and call `plan(input)`.

**No back-edges (verified):** no ownership method writes any field the value stage reads —
the session fields are write-once (value phase) / read-once (ownership phase). The move is a
clean handoff, not an untangling.

## Safety mechanism

- **Sample golden EMPTY-diff** — the move must not change generated output.
- **Flag-gated parity** (`-Pgrazel.internal.ownershipParity=true`): keep the old in-resolver
  path temporarily; run both and assert the new planner's `List<ResolveDependenciesResult>`
  is identical to the old path's. Codex enables it for PAX (exact check where no content
  golden exists); remove the old path once parity is green on PAX + sample.
- **Size guard (Item 10)** must show no increase (it won't — empty-diff).

## Win

The planner is a pure function → **directly unit-testable as a Layer-3 component** (inputs →
`ResolveDependenciesResult`s), instead of only through the full resolver. Add focused planner
unit tests for the placement/folding/subtraction cases.

## Acceptance criteria

- `BucketOwnershipPlanner` exists as a pure class with the interface above; the ownership
  methods are moved as-is; resolver retains value resolution.
- Sample golden empty-diff; PAX parity green; size guard no-increase; PAX builds green.
- New Layer-3 planner unit tests; existing resolver tests stay green.
- Old in-resolver ownership path + parity flag removed after parity confirmed.

## Out of scope / Non-goal

- Changing the ownership algorithm (Item 13 — declaration-driven rewrite).
- Slimming `ComputeWorkspaceDependencies` (Item 14).
- Variant compression.
