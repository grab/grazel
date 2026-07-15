# Coverage Reshape (Minimal) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Give the ambient "coverage" concept a home as a type — reify the main-family
subtraction as a `Coverage` value type and colocate all coverage *predicates* as members of
`CoveredDependency` — so the bucket-planner phases become readable, without changing behaviour.

**Architecture:** Today coverage is a bag of free functions split across `BucketSetMath.kt`
(main family) and `BucketOwnershipPlanner.kt` (test family, private). This reshape introduces a
`Coverage` value type owning the main subtraction primitive, moves the `canCover` predicate onto
`CoveredDependency`, and (Task 2) moves the test predicate family onto `CoveredDependency` too.
No `interface` — there is no polymorphic call site to justify one; these are pure functions
becoming methods on the noun they operate on. Genuinely-standalone set helpers
(`intersectByBucketOwner`, `withoutDependenciesOwnedByNonDefaultHierarchy`,
`coveredDependenciesForBucket`, `allCoveredDependencies`) stay as free functions.

**Tech Stack:** Kotlin, Gradle plugin. Byte-identity gated.

## Global Constraints

- **Behaviour-preserving only.** Every function body moves verbatim — no logic edits. Identical
  call order, identical results. This is an API reshape, not a rewrite.
- **Byte-identity gate.** After each task: `./gradlew :grazel-gradle-plugin:test --console=plain`
  then `./gradlew verifyGrazelGoldenBaseline --console=plain`. Success line: `Grazel golden
  baseline verified: migrateToBazel, task graph, bucket labels, and generated-file diff are
  clean.` Any generated-file drift = revert; the reshape is wrong.
- **One Gradle build at a time.** Never start a build while another is running.
- **Scope discipline (minimal).** Task 2 moves the test *predicates* only. It does NOT touch the
  test two-pass subtraction (`withoutTestDependenciesCoveredBy`,
  `withoutTestDependenciesCoveredByEveryLeaf`, `scopedSiblingClosureDependenciesByShortId`) — those
  stay in `BucketOwnershipPlanner.kt`. The resulting asymmetry (main subtraction on `Coverage`,
  test subtraction still free in the planner) is intended and deferred to a later "fuller" pass.
- **File rename.** `BucketSetMath.kt` → `Coverage.kt` (git mv; the file is now the coverage home).
- **KDoc.** Preserve every existing KDoc block verbatim on the moved declaration. Do not drop or
  reword the explanatory comments — they are load-bearing documentation.
- **No `\u` in KDoc** (kapt copies KDoc into Java stubs and lexes `\u` as a unicode escape). If any
  moved comment contains a literal backslash-u, keep it phrased as it already is; do not introduce
  new ones.

---

### Task 1: Reify the main coverage family as `Coverage` + `CoveredDependency.canCover`

**Files:**
- Rename + modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/BucketSetMath.kt` → `Coverage.kt`
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/DependencyBucketPlacementEngine.kt`
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/BucketOwnershipPlanner.kt`

**Interfaces:**
- Produces:
  - `CoveredDependency.canCover(dependency: ResolvedDependency): Boolean` — now a **member** of the
    `CoveredDependency` data class (moved from the current `internal fun CoveredDependency.canCover`
    extension). Its private helpers `rootsSupersetClosureOf` and `canCoverDeclaredPlaceholder` move
    with it (as private members or private file-level funcs — keep them private).
  - `class Coverage` value type in `Coverage.kt`:
    - `companion object { fun of(covered: Iterable<CoveredDependency>): Coverage }` —
      groups by `dependency.shortId` (the body of the current `groupCoveredDependenciesByShortId`).
    - `fun ofGrouped(byShortId: Map<String, List<CoveredDependency>>): Coverage` on the companion —
      wraps an already-grouped map.
    - `fun subtract(dependenciesByShortId: Map<String, ResolvedDependency>): Map<String, ResolvedDependency>`
      — the **verbatim body** of the current `withoutDependenciesCoveredByShortId` (the 3-outcome
      strength-ladder: exact-identity → superset-closure → same-owner-both-direct→override →
      drop). Move the KDoc onto this method.
  - Free functions that REMAIN in `Coverage.kt` unchanged: `intersectByBucketOwner`,
    `withoutDependenciesOwnedByNonDefaultHierarchy`, `coveredDependenciesForBucket`,
    `allCoveredDependencies`. `groupCoveredDependenciesByShortId` is absorbed into `Coverage.of`
    (delete the standalone free function only if it has no remaining callers after the swaps below;
    otherwise keep it).

- [ ] **Step 1: git mv the file**

```bash
cd /Users/arun.sampathkumar/work/grazel
git mv grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/BucketSetMath.kt \
       grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/Coverage.kt
```

- [ ] **Step 2: In `Coverage.kt`, make `canCover` a member of `CoveredDependency`**

Move the body of `internal fun CoveredDependency.canCover(dependency)` (current lines ~119-123) to
be a member function inside the `CoveredDependency` data class. Move `rootsSupersetClosureOf` and
`canCoverDeclaredPlaceholder` with it (keep private). Preserve the KDoc verbatim on the member.
`this.dependency` inside the extension becomes `dependency` (the class's own property) — the
class already has a `dependency: ResolvedDependency` property, so `this.dependency` self-references
resolve naturally; the parameter is also named `dependency`, so rename the PARAMETER to `candidate`
to avoid shadowing and update the body references accordingly (mechanical rename only, no logic
change).

- [ ] **Step 3: In `Coverage.kt`, introduce the `Coverage` type**

Add `class Coverage private constructor(private val byShortId: Map<String, List<CoveredDependency>>)`
with the `of` / `ofGrouped` companion factories and the `subtract` method carrying the verbatim
body + KDoc of `withoutDependenciesCoveredByShortId`. Delete the old free `withoutDependenciesCoveredBy`
and `withoutDependenciesCoveredByShortId` functions once call sites (Step 4) are converted.

- [ ] **Step 4: Convert the four external call sites**

- `DependencyBucketPlacementEngine.kt:212` and `:265`: `withoutDependenciesCoveredBy(deps, coveredList)`
  → `Coverage.of(coveredList).subtract(deps)`.
- `BucketOwnershipPlanner.kt:215`: same conversion.
- `BucketOwnershipPlanner.kt:798`: `withoutDependenciesCoveredByShortId(deps, coveredByShortId)`
  → `Coverage.ofGrouped(coveredByShortId).subtract(deps)`.
- `BucketOwnershipPlanner.kt:997` `covered.canCover(dependency)` — no change needed (member call
  reads identically to the extension call).

Match each call's existing named/positional argument style; do not reorder arguments.

- [ ] **Step 5: Gate**

```bash
cd /Users/arun.sampathkumar/work/grazel
./gradlew :grazel-gradle-plugin:test --console=plain
./gradlew verifyGrazelGoldenBaseline --console=plain
```
Expected: unit suite passes; golden prints the success line with a clean generated-file diff.

- [ ] **Step 6: Commit**

```bash
git add -- '*.kt'
git commit -m "refactor: reify main coverage as Coverage type + CoveredDependency.canCover"
```

---

### Task 2: Move the test predicate family onto `CoveredDependency`

**Files:**
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/Coverage.kt`
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/BucketOwnershipPlanner.kt`

**Interfaces:**
- Consumes: `CoveredDependency` (now carrying `canCover` from Task 1).
- Produces:
  - `CoveredDependency.canCoverTest(dependency: ResolvedDependency, declaredTestDependency: ResolvedDependency?, scopedSiblingClosureDependencies: Set<String> = emptySet()): Boolean`
    — a **member** of `CoveredDependency`, holding the verbatim dispatch body of the current
    `private fun CoveredDependency.canCoverTestDependency` (BucketOwnershipPlanner.kt:956). Its three
    private helpers — `canCoverDeclaredTestMetadata`, `canCoverInheritedTestRoot`,
    `canCoverDeclaredTestRoot` — move with it into `Coverage.kt` (as private members of
    `CoveredDependency` or private file-level funcs in `Coverage.kt`; keep private). Preserve all
    KDoc verbatim.

- [ ] **Step 1: Move the test predicate family into `Coverage.kt`**

Cut `canCoverTestDependency` + `canCoverDeclaredTestMetadata` + `canCoverInheritedTestRoot` +
`canCoverDeclaredTestRoot` from `BucketOwnershipPlanner.kt` into `Coverage.kt`. Rename the public
entry point `canCoverTestDependency` → `canCoverTest` and make it a member of `CoveredDependency`.
Apply the same `dependency` parameter → `candidate`-style de-shadowing ONLY if a shadow arises;
otherwise leave parameter names as-is. Move KDoc verbatim.

- [ ] **Step 2: Convert the three call sites in `BucketOwnershipPlanner.kt`**

`:805`, `:815`, `:843`: `covered.canCoverTestDependency(...)` → `covered.canCoverTest(...)`.
Keep all arguments identical.

- [ ] **Step 3: Gate (local)**

```bash
cd /Users/arun.sampathkumar/work/grazel
./gradlew :grazel-gradle-plugin:test --console=plain
./gradlew verifyGrazelGoldenBaseline --console=plain
```
Expected: unit suite passes; golden success line, clean diff.

- [ ] **Step 4: Commit**

```bash
git add -- '*.kt'
git commit -m "refactor: move test coverage predicates onto CoveredDependency"
```
