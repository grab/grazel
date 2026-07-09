# Branch Code-Review Remediation — Design & Punch-List

**Branch:** `arun/dependencies-refactor`
**Source:** high-effort workflow code review (Sonnet workers, 9 angles, 53 candidates verified, 18 survived), 2026-07-09.
**Scope decision (maintainer):** all four tiers; `#2` resolved to a dead-code delete; `#4` left as an accepted tradeoff; `#11` collapsed.

## Standing constraints

- **Correctness is covered** by the PAX baseline + byte-identical pin gate. This branch's concern is altitude/layering, duplication, and quality — not behavior. **No fix here may change generated output** except `#1`, which only changes a crash path (produces empty output instead of throwing).
- **Verification gates**, run after each themed change:
  - `./gradlew :grazel-gradle-plugin:test`
  - `./gradlew verifyGrazelGoldenBaseline` — generated-file git diff MUST stay EMPTY.
- **Build serialization:** only ONE Gradle build at a time. Workers that build run sequentially; never run a build while a worker builds.
- **Final gate:** non-destructive PAX validation (migrate → `git diff` vs snapshot must be byte-identical → Bazel APK build) once all tiers land.
- `#4` (`isDeclarationBucket`/`isCompileOnlyDeclaration` role-from-suffix in `VariantDependencyConfigurationRoles.kt`) is **explicitly out of scope** — accepted as the way Gradle configuration roles are conventionally named; the M3 classifier is intentionally kept private.

---

## Tier 1 — correctness

### T1.1 — `#2` Delete the dead `ResolvedDependency.merge()` (false-alarm regression)
- **File:** `model/ResolveDependenciesResult.kt:146-155`
- **Finding:** the review flagged a union→intersection change here as a silent exclude-rule-drop regression.
- **Investigation result:** `ResolvedDependency.merge()` has **zero callers** in main/test/functionalTest. It is dead code; the intersection never runs (this is why PAX is byte-identical). The *live* merge path (`mergeDependencyMetadataByMaxVersion`, 13 sites) is deliberate: it **unions** exclude rules when exactly one side is declared metadata and **intersects** only between two resolved copies.
- **Fix:** delete the `ResolvedDependency.merge()` function (146-155). **Keep** `Set<ExcludeRule>.intersectWith` (157-161) — it is live (used at `AggregatedDependencyResolver.kt:758`).
- **Verify:** compiles; test + golden baseline green (dead code → zero output change).

### T1.2 — `#1` Guard the empty-results crash
- **Files:** `ComputeWorkspaceDependencies.kt:26-108` (entry), with downstream `getValue` at `BucketReduction.kt:38`, `ComputeWorkspaceDependencies.kt:39`, `DefaultOverrideCarrierPlanner.kt:29`.
- **Finding:** when `AggregatedDependencyResolver.resolve()` returns `emptyList()` (nothing migratable — a state it already warns on), `computeFromResults` builds an empty `classPaths` and `reduceNonDefaultBuckets` throws `NoSuchElementException`, aborting `migrateToBazel` with a confusing crash.
- **Fix (altitude — guard once at the entry, not at each `getValue`):** in `computeFromResults`, if `results.isEmpty()` return an empty `WorkspaceDependencies` (all-empty maps) before any reduction. This covers all three downstream `getValue` sites via the single upstream guard.
- **Verify:** add a unit test — `computeFromResults(emptyList())` returns an empty `WorkspaceDependencies` and does not throw. Test + golden baseline green.

---

## Tier 2 — altitude / layering (re-derivation of a typed fact from a string)

### T2.1 — `#3` Thread the typed `VariantAttr` name through project-reachability
- **Files:** `AggregatedDependencyResolver.kt:662` (`reachableBucketNamesForProject` callback signature `(String, String?)`), `DeclaredDependencyMetadataCollector.kt:427` (`selectedMainVariantHierarchyNames`).
- **Finding:** the typed `VariantAttr` name is already captured per project edge, but the callback discards it, forcing `displayName.startsWith(variantName, ignoreCase=true)`. A variant name that prefixes another (`paid` vs `paidFull`) resolves to the wrong hierarchy → dep lands in the wrong bucket.
- **Fix:** extend the callback to accept the typed `selectedVariantAttrName` (already threaded for exclude rules via the #3 work) and prefer it; fall back to the display-name prefix scan only when the attribute is null. Reuse the existing typed-path selection in `ProjectExcludeRules.rulesFor`.
- **Verify:** unit test with a `paid`/`paidFull` pair proving typed-name selection picks `paidFull`; golden baseline green (PAX carries the attribute, so no output change expected).

### T2.2 — `#9` De-duplicate the `apiElements`/`runtimeElements` sentinel across layers
- **Files:** `DeclaredDependencyMetadataCollector.kt:189` (L1), `AggregatedDependencyResolver.kt:247` (L3).
- **Finding:** both layers independently hardcode `"apiElements"`/`"runtimeElements"` as `DEFAULT_VARIANT` aliases. A fix to one misses the other.
- **Fix:** hoist a single shared constant set (e.g. `DEFAULT_VARIANT_ELEMENT_CONFIGURATIONS`) or a small helper next to `DEFAULT_VARIANT`, and consume it at both sites.
- **Verify:** test + golden baseline green.

### T2.3 — `#5` `removeTypedTestSuffix` → typed `VariantType`
- **File:** `migrate/target/TargetVariantReachability.kt:51-52`
- **Finding:** strips `AndroidTest`/`UnitTest` from the variant name string; a flavor named `unitTest` misclassifies a main variant as test and drops the target.
- **Fix:** use the typed `VariantType` already on the `MatchedVariant`/`BaseVariant` under test instead of string-suffix stripping.
- **Verify:** unit test with a `unitTest`-named flavor proving the main variant stays reachable; golden baseline green.

### T2.4 — `#6` MAIN_HIERARCHY test/main routing by typed field
- **File:** `AggregatedDependencyResolver.kt:486-490`
- **Finding:** `when (bucketName) { TEST_VARIANT, ANDROID_TEST_VARIANT -> ... }` routes by bucket-name string equality; the metadata already carries `kind`/`variantType`.
- **Fix:** branch on the typed `variantType`/`kind` on `AggregatedDependencyRootMetadata` instead of the name string.
- **Verify:** test + golden baseline green.

### T2.5 — `#7`/`#13` Typed base-bucket test lookup
- **File:** `BucketOwnershipPlanner.kt:635,650`
- **Finding:** `testSuffixForBaseBucket` and `testVariantTypeForBaseBucket` switch on the base-bucket NAME string; `DependencyBucketPlacementPlan.variantTypesByBucketName` already holds the typed `VariantType`. `testSuffixForBaseBucket` additionally duplicates `VariantType.testSuffix`.
- **Fix:** read `VariantType` from `variantTypesByBucketName`; collapse `testSuffixForBaseBucket` into `testVariantTypeForBaseBucket(plan).testSuffix` at the single call site and delete the redundant function.
- **Verify:** test + golden baseline green.

---

## Tier 3 — duplication

### T3.1 — `#8` `MutableMap.mergeInto` extension for the merge-or-insert idiom
- **Files:** `BucketOwnershipPlanner.kt:281,327,460,517,583`, `AggregatedDependencyResolver.kt:177`.
- **Fix:** add one internal extension `MutableMap<String, Map<String, ResolvedDependency>>.mergeInto(key, deps, merger)` and replace all six sites. Note file-ownership: two files — serialize the edits.
- **Verify:** test + golden baseline green.

### T3.2 — `#10` Reuse `util/Map.kt` `merge` instead of `mergeDependencyMaps`
- **File:** `BucketOwnershipPlanner.kt:312`
- **Fix:** replace `mergeDependencyMaps` with `dependencyMaps.merge(::unionDependencyMaps)` using the existing `com.grab.grazel.util.merge`. Confirm accumulator semantics match (guard order-sensitivity).
- **Verify:** test + golden baseline green.

### T3.3 — `#12` Collapse the two `extract…ExcludeRulesByShortId` twins
- **File:** `DeclaredDependencyMetadataCollector.kt:466-477, 500-511`
- **Fix:** one function parameterised by the dependency-set selector (`allDependencies` vs `dependencies`); the two public entry points delegate to it.
- **Verify:** test + golden baseline green.

### T3.4 — `#11` Collapse the two reducer wrapper classes
- **Files:** `DefaultBucketDependencyReducer.kt`, `DefaultOverrideCarrierPlanner.kt`, callers in `ComputeWorkspaceDependencies.kt:50,79`.
- **Finding:** both are stateless single-method classes that are named lambdas over `reduceNonDefaultBuckets`.
- **Fix:** inline both into direct `reduceNonDefaultBuckets(...)` call sites (with the concrete transform lambda) in `computeFromResults`; delete the wrapper classes and their Dagger/test wiring if any.
- **Verify:** test + golden baseline green; confirm no DI binding references remain.

---

## Tier 4 — quality / perf

### T4.1 — `#14` `collectCompileOnly…` uses the `variantTypes` param
- **File:** `DeclaredDependencyMetadataCollector.kt:219`
- **Fix:** replace the hard-coded 4-element `VariantType` OR-chain with the `variantTypes` set parameter the adjacent `collectDeclaredDependenciesByProjectBucket` already uses.
- **Verify:** test + golden baseline green.

### T4.2 — `#17` `alwaysMaterializedVariants` from `DefaultVariants.entries`
- **File:** `WorkspaceRenderPlanBuilder.kt:30`
- **Fix:** default to `DefaultVariants.entries.mapTo(sortedSetOf()) { it.toString() }` instead of hardcoding the four constants.
- **Verify:** test + golden baseline green.

### T4.3 — `#16` Shared cached-service lock base
- **Files:** `WorkspacePlanService.kt`, `WorkspaceRenderPlanService.kt`
- **Fix:** extract the shared `synchronized(lock)` populate/init/close boilerplate into a small abstract base (`CachedBuildService<T>`), or a shared helper, so concurrency fixes apply to both.
- **Verify:** test + golden baseline green. **Lower priority** — pre-existing pattern; skip if it risks touching build-service registration semantics.

### T4.4 — perf `#15` + hierarchy recompute (HELD sub-items — evaluate, don't force)
- `#15`: `reachableMainBucketsSnapshot` (`BucketOwnershipPlanner.kt:673`) deep-copied into every `ResolveDependenciesResult` then re-merged O(N·P·B) in `ComputeWorkspaceDependencies.reachableMainBucketsByProject`. Candidate: carry once on `WorkspaceDependencies` rather than per-result.
- perf: `selectedMainVariantHierarchyNames` (`AggregatedDependencyResolver.kt:237`) rebuilds `variantHierarchyNamesByName` on every resolved-component visit. Candidate: precompute a `Map<projectPath, Map<String,Set<String>>>` once in `ResolutionSession`.
- **These are the previously-held perf items.** They change data shape / caching and carry the highest regression risk of the batch. Do them **last, each as its own isolated change** with golden-baseline + PAX validation, or defer if the golden gate shows any movement. Do NOT bundle with mechanical edits.

---

## Execution model

File-ownership dictates concurrency — two heavily-touched files (`BucketOwnershipPlanner.kt`, `AggregatedDependencyResolver.kt`) forbid naive parallel edits. Workstreams:

- **WS-A (Tier 1):** `ResolveDependenciesResult.kt`, `ComputeWorkspaceDependencies.kt`, `BucketReduction.kt`, `DefaultOverrideCarrierPlanner.kt` — T1.1, T1.2.
- **WS-B (`BucketOwnershipPlanner.kt` owner):** T2.5, T3.2, plus this file's half of T3.1.
- **WS-C (`AggregatedDependencyResolver.kt` owner):** T2.4, this file's half of T3.1, T2.2 (L3 site), perf hierarchy item.
- **WS-D (`DeclaredDependencyMetadataCollector.kt` owner):** T2.1 (collector site), T2.2 (L1 site), T3.3, T4.1.
- **WS-E (leaf files):** T2.3, T4.2, T4.3.

Shared-file items (T3.1 across B+C; T2.1/T2.2 across C+D) are **serialized**, not parallel. Each workstream ends with test + golden-baseline green before the next build. `#11` (T3.4) and the perf items (T4.4) run **after** the mechanical tiers settle, each validated alone.
