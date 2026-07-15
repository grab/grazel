# BucketOwnershipPlanner Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Decompose the 962-line `BucketOwnershipPlanner` into a thin orchestrating spine plus two
focused planners (`MainBucketPlanner`, `TestBucketPlanner`) and a shared helpers file — so each
phase of bucket ownership planning is a unit you can read and test independently — without changing
behaviour.

**Architecture:** `plan()` today inlines main-bucket planning, test-bucket planning (×2), and result
assembly in one class. This split leaves `BucketOwnershipPlanner` as a ~120-line spine that
orchestrates `MainBucketPlanner.plan(...)` → `TestBucketPlanner.plan(...)` → `buildResults(...)`.
Helpers used by BOTH planners move to `BucketMetadataHelpers.kt` (no duplication). The main→test
data handoff (`mainCoveredDepsByProject`, `aggregateMainCoveredDeps`) is already a plain parameter,
not shared mutable state — that is why the seam is safe to cut. All files are in package
`com.grab.grazel.gradle.dependencies`, so same-package moves need no import changes.

**Tech Stack:** Kotlin, Gradle plugin. Byte-identity gated.

## Global Constraints

- **Behaviour-preserving only.** Every function body moves verbatim — no logic edits. Identical call
  order, identical results. This is a decomposition, not a rewrite.
- **Byte-identity gate.** After each task: `./gradlew :grazel-gradle-plugin:test --console=plain`
  then `./gradlew verifyGrazelGoldenBaseline --console=plain`. Success line: `Grazel golden baseline
  verified: migrateToBazel, task graph, bucket labels, and generated-file diff are clean.` Any
  generated-file drift = revert; the split is wrong.
- **One Gradle build at a time.**
- **Same package.** All new files declare `package com.grab.grazel.gradle.dependencies`. No import
  edits needed for same-package symbol moves.
- **KDoc travels with its declaration, verbatim.** Do not drop or reword the explanatory comments —
  they are load-bearing. No new `\u` sequences in KDoc (kapt copies KDoc into Java stubs).
- **Staging discipline.** Stage explicit `*.kt` paths (`git add -- '*.kt'`), never `git add -A`
  (`.superpowers/`, `scratchpad/` are untracked scratch and must not be staged). Use `git mv` where
  a file is renamed; ensure new + deleted paths both stage.
- **Field-threading rule.** When a moved function references the enclosing class field
  `declaredDependencyMetadata`, the new class that receives it takes `declaredDependencyMetadata`
  as a constructor `val`. `precomputedKspDependencies` is used ONLY by `buildResults` — it stays on
  the spine. If a helper turns out (by its actual callers) to be used by only ONE planner, put it in
  that planner rather than the shared file — do not create a shared symbol with a single caller.

---

### Task 1: Extract shared helpers into `BucketMetadataHelpers.kt`

**Files:**
- Create: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/BucketMetadataHelpers.kt`
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/BucketOwnershipPlanner.kt`

**Interfaces:**
- Produces (top-level `internal` funcs in `BucketMetadataHelpers.kt`) — the helpers used by BOTH the
  main and test planning paths:
  - `applyDeclaredMetadataByBucket(dependenciesByBucket, declaredMetadataByOutputBucket)`
  - `applyDeclaredMetadata(dependencies, declaredDependencies)` (dependency of the above)
  - `addDeclaredOutputMetadata(declaredMetadataByOutputBucket, bucketName, metadata)`
  - `mergeBucket` (the `MutableMap<K, Map<String, ResolvedDependency>>.mergeBucket` extension)
  - `unionDependencyMaps` (already `internal` top-level; move it here — also called by
    `AggregatedDependencyResolver`, same package, no import change)
  These are all pure (no `declaredDependencyMetadata` field reference) — confirm each is pure before
  moving; if one references the field, STOP and report it.

- [ ] **Step 1: Create the file and move the five helpers verbatim** (with their KDoc) from
  `BucketOwnershipPlanner.kt` into `BucketMetadataHelpers.kt`, as top-level `internal` declarations.
  Verify via their callers that each is genuinely used by both main (`planMainBuckets` /
  `withDeclaredMainMetadata`) and test (`plannedTestBuckets`) paths; if any is single-caller, leave
  it where its sole caller is and note it in the report.

- [ ] **Step 2:** Remove the moved declarations from `BucketOwnershipPlanner.kt`. Call sites are
  unchanged (same package, same names).

- [ ] **Step 3: Gate**
```bash
cd /Users/arun.sampathkumar/work/grazel
./gradlew :grazel-gradle-plugin:test --console=plain
./gradlew verifyGrazelGoldenBaseline --console=plain
```

- [ ] **Step 4: Commit**
```bash
git add -- '*.kt'
git commit -m "refactor: extract shared bucket-metadata helpers"
```

---

### Task 2: Extract `TestBucketPlanner`

**Files:**
- Create: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/TestBucketPlanner.kt`
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/BucketOwnershipPlanner.kt`

**Interfaces:**
- Consumes: `BucketMetadataHelpers` (Task 1), `Coverage`/`CoveredDependency`, `OwnershipPlannerInput`,
  `DependencyBucketPlacementEngine`, `DependencyBucketPlacementPlan`.
- Produces: `internal class TestBucketPlanner(private val declaredDependencyMetadata: DeclaredDependencyMetadata)`
  with a public entry:
  `fun plan(input, variantType, baseBucketName, leafClosures, mainCoveredDepsByProject, aggregateMainCoveredDeps, declaredTestDependenciesByBucket, inheritedTestCoveredDeps): Map<String, Map<String, ResolvedDependency>>`
  — i.e. exactly the current `planTestBuckets` signature, lifted to a class method. The spine calls
  it twice (unit test, android test) with the same arguments it passes today.

- [ ] **Step 1: Move the test-half members** from `BucketOwnershipPlanner.kt` into `TestBucketPlanner`
  (verbatim bodies + KDoc): `planTestBuckets` (rename to `plan`), `testHierarchyBucketClosuresFor`,
  `typedTestBucketNames`, `concreteTestLeafName`, `concreteTestLeafClosures`, `testBucketPlans`,
  `plannedTestBuckets` (with its nested locals), `testBucketNamesForTestBucket`,
  `visibleMainBucketNamesForTestBucket`, `concreteTestLeafNamesFor`, `outputBucketNameForTestBucket`,
  `isTypedTestBucket`, `testVariantTypeForBaseBucket`. Move `variantsFor` here IF its only callers are
  test-side (verify; if main also uses it, place it in `BucketMetadataHelpers.kt` instead, taking
  `declaredDependencyMetadata` as a parameter).

- [ ] **Step 2: Move the test-only file-scoped functions** into `TestBucketPlanner.kt` (verbatim +
  KDoc): `withoutTestDependenciesCoveredBy`, `withoutTestDependenciesCoveredByEveryLeaf`,
  `withoutMergedBaseTestDependenciesCoveredBy`, `scopedSiblingClosureDependenciesByShortId`,
  `ResolvedDependency.toDependencyNotation`. Keep them `private` file-scoped in the new file (or
  private members) — match whichever the code currently uses.

- [ ] **Step 3: Wire the spine.** In `BucketOwnershipPlanner.plan()`, replace the two
  `planTestBuckets(...)` calls with `TestBucketPlanner(declaredDependencyMetadata).plan(...)` (construct
  once, reuse for both calls). Remove the moved members from `BucketOwnershipPlanner.kt`.

- [ ] **Step 4: Gate** (same commands as Task 1 Step 3).

- [ ] **Step 5: Commit**
```bash
git add -- '*.kt'
git commit -m "refactor: extract TestBucketPlanner from BucketOwnershipPlanner"
```

---

### Task 3: Extract `MainBucketPlanner`, reduce spine

**Files:**
- Create: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/MainBucketPlanner.kt`
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/BucketOwnershipPlanner.kt`

**Interfaces:**
- Consumes: `BucketMetadataHelpers`, `Coverage`, `OwnershipPlannerInput`,
  `DependencyBucketPlacementEngine`.
- Produces: `internal class MainBucketPlanner(private val declaredDependencyMetadata: DeclaredDependencyMetadata)`
  with `fun plan(input: OwnershipPlannerInput): MainBucketPlanResult`. Move the `MainBucketPlanResult`
  data class into `MainBucketPlanner.kt` (the spine imports it — same package, no import line needed).

- [ ] **Step 1: Move the main-half members** into `MainBucketPlanner` (verbatim + KDoc):
  `planMainBuckets` (rename to `plan`), `withDeclaredMainMetadata`, `withGlobalAncestorResolvedMetadata`,
  `mergeNamedBuckets`, `mergeDependencyMaps`, `declaredOutputMetadata`, and the main-only file-scoped
  `withoutDeclaredPlaceholdersCoveredByDefault`. Move the `MainBucketPlanResult` data class (and its
  `coveredDependencies()` method) into this file.

- [ ] **Step 2: Wire the spine.** In `BucketOwnershipPlanner.plan()`, replace `planMainBuckets(input)`
  with `MainBucketPlanner(declaredDependencyMetadata).plan(input)`. Remove the moved members.

- [ ] **Step 3: Confirm the spine is now minimal** — `BucketOwnershipPlanner` should hold only:
  constructor (`declaredDependencyMetadata`, `precomputedKspDependencies`), `plan()`, `buildResults`
  (+ its nested `buildResult`), and the `OwnershipPlannerInput` data class. If anything else remains,
  it is either genuinely shared (belongs in `BucketMetadataHelpers.kt`) or was missed — report it.

- [ ] **Step 4: Gate** (same commands).

- [ ] **Step 5: Commit**
```bash
git add -- '*.kt'
git commit -m "refactor: extract MainBucketPlanner; reduce BucketOwnershipPlanner to spine"
```
