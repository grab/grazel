# ResolutionSession Breakdown Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decompose `AggregatedDependencyResolver.ResolutionSession` (~570-line god-class) into a thin
orchestrating spine plus four stateless collaborators, behaviour-preserving.

**Architecture:** `resolve()` and the resolver constructor are unchanged. `ResolutionSession` keeps the
folded state and becomes the single mutation site; computation moves into `MainReachabilityTracker`,
`RootContributionComputer`, `DependencyBucketAccumulator`, `DeclaredMetadataMerger` (new package
`com.grab.grazel.gradle.dependencies.resolution`). Data flow is return-and-fold: collaborators return
contributions, the spine folds them in `workspaceDependencyRoots` order.

**Tech Stack:** Kotlin, Gradle plugin, JUnit4 + Truth (existing test stack in this module).

**Design doc:** `reports/specs/2026-07-17-resolution-session-breakdown-design.md`

## Global Constraints

- Behaviour-preserving; every task keeps `verifyGrazelGoldenBaseline` **byte-clean** and
  `:grazel-gradle-plugin:test` green. A task that moves generated output is reverted, not patched.
- Preserve the ordering invariant: main-hierarchy/main-leaf roots fold before test roots that read the
  same project's reachability; folding stays in `workspaceDependencyRoots` order.
- New collaborators are `internal`, in `com.grab.grazel.gradle.dependencies.resolution`. Not
  kotlinx-serialized (package placement is free).
- No `git add -A`; never stage `codedb.snapshot`; one Gradle build at a time.
- Sonnet implementers/reviewers; Opus forbidden.

## Refactor test cycle (applies to every task)

This is a relocation refactor, so the authoritative gate per task is byte-identity, not new asserts:

```
./gradlew :grazel-gradle-plugin:test --console=plain            # unit tests green
./gradlew verifyGrazelGoldenBaseline --console=plain            # "Grazel golden baseline verified" + CLEAN diff
```
Both must pass before commit. Tasks 1 and 5 additionally add a focused unit test (real asserts below).

## File Structure

- Create `.../gradle/dependencies/resolution/MainReachabilityTracker.kt` — reachability + project-edge
  exclusion state and queries.
- Create `.../gradle/dependencies/resolution/RootVisitOutcome.kt` — the value returned by the root walk.
- Create `.../gradle/dependencies/resolution/DependencyBucketAccumulator.kt` — the five bucket-closure
  maps + lint, behind `fold`/`snapshot`.
- Create `.../gradle/dependencies/resolution/DeclaredMetadataMerger.kt` — declared/compileOnly/test
  layering.
- Create `.../gradle/dependencies/resolution/RootContributionComputer.kt` — data-driven per-root-kind
  dispatch producing `RootContribution`.
- Modify `.../gradle/dependencies/AggregatedDependencyResolver.kt` — `ResolutionSession` shrinks to the
  spine; `MainProjectEdgeScope` (currently the private top-level data class at lines 40-44) moves to the
  resolution package (made `internal`).
- Create tests under `.../test/kotlin/com/grab/grazel/gradle/dependencies/resolution/`.

`ResolvedComponentsVisitor`, `BucketOwnershipPlanner`, `mergeDependencyMetadataByMaxVersion`,
`resolveRootToDependencyMap` (initially) stay in `AggregatedDependencyResolver.kt`.

---

### Task 1: MainReachabilityTracker

**Files:**
- Create: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/resolution/MainReachabilityTracker.kt`
- Create: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/resolution/MainProjectEdgeScope.kt` (moved from `AggregatedDependencyResolver.kt:40-44`, `private`→`internal`)
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`
- Test: `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/resolution/MainReachabilityTrackerTest.kt`

**Interfaces:**
- Consumes: `DeclaredDependencyMetadata`, `DeclaredVariantDependencyMetadata`, `DeclaredProjectDependency`,
  `ProjectDependencyBucket`, `AggregatedDependencyRootMetadata`, `selectedVariantHierarchyNames`,
  `DEFAULT_VARIANT`, `AndroidBuild`/`JvmBuild`.
- Produces:
  ```kotlin
  internal class MainReachabilityTracker(
      private val declaredDependencyMetadata: DeclaredDependencyMetadata,
      private val migratableProjectPaths: List<String>,
  ) {
      fun shouldResolveMainHierarchyRoot(metadata: AggregatedDependencyRootMetadata): Boolean
      fun computeScope(projectPath: String, variantNames: Set<String>, selectedOnly: Boolean): MainProjectEdgeScope
      fun recordMainRoot(metadata: AggregatedDependencyRootMetadata, scope: MainProjectEdgeScope)  // folds reachable sets + appends scope
      fun recordReachable(projectPaths: Set<String>, bucketNamesByProject: Map<String, Set<String>>) // folds a RootVisitOutcome delta (used from Task 2)
      fun selectedMainVariantHierarchyNames(projectPath: String, selectedVariantDisplayName: String?): Set<String>
      fun isReachableMainBucket(bucket: ProjectDependencyBucket): Boolean
      fun filterExcludedByEveryReachableRoot(
          dependenciesByProjectBucket: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>
      ): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>
      val reachableMainBucketNamesByProject: Map<String, Set<String>>   // read-only snapshot for OwnershipPlannerInput
  }
  ```

- [ ] **Step 1: Create `MainProjectEdgeScope.kt`** in the resolution package, moving the data class from
  `AggregatedDependencyResolver.kt:40-44` verbatim, changing `private` → `internal`. Delete the original
  declaration.

- [ ] **Step 2: Create `MainReachabilityTracker.kt`** and move these members out of `ResolutionSession`
  verbatim (bodies unchanged), rehoming their state onto the tracker:
  - state: `reachableMainProjectPaths`, `reachableMainBucketNamesByProject`, `mainProjectEdgeScopes`,
    `mainBuildTypeNamesByProject`, `declaredProjectDependencyEdgesCache`
  - methods: `variantsFor`, `declaredProjectDependencyEdges`, `knownMainBucketNames`,
    `selectedMainVariantHierarchyNames`, `addReachableMainBuckets`, `isReachableMainBucket`,
    `withoutDependenciesExcludedByEveryReachableRoot` (rename to `filterExcludedByEveryReachableRoot`),
    `collectMainProjectEdgeScope` (rename to `computeScope`), `shouldResolveMainHierarchyRoot`,
    `orDefaultVariantIn` (the helper added by the earlier /simplify pass).
  - Add `recordMainRoot(metadata, scope)` wrapping the existing "append scope + fold reachable sets"
    block from `collectRootClosures` lines ~457-462, and `recordReachable(...)` (a no-op stub this task;
    wired in Task 2).

- [ ] **Step 3: Update `ResolutionSession`** to construct `MainReachabilityTracker` and delegate: replace
  the moved calls (`collectMainProjectEdgeScope(...)` → `tracker.computeScope(...)` + `tracker.recordMainRoot(...)`,
  `isReachableMainBucket` → `tracker.isReachableMainBucket`, `withoutDependenciesExcludedByEveryReachableRoot`
  → `tracker.filterExcludedByEveryReachableRoot`, `::selectedMainVariantHierarchyNames` →
  `tracker::selectedMainVariantHierarchyNames`, `shouldResolveMainHierarchyRoot` → `tracker....`). The
  `OwnershipPlannerInput.reachableMainBucketNamesByProject` reads `tracker.reachableMainBucketNamesByProject`.
  Keep passing `reachableMainProjectPaths`/`reachableMainBucketNamesByProject` mutable references into
  `resolveRootToDependencyMap` for now by exposing them from the tracker (Task 2 removes this).

- [ ] **Step 4: Run the refactor test cycle.**
  ```
  ./gradlew :grazel-gradle-plugin:test --console=plain
  ./gradlew verifyGrazelGoldenBaseline --console=plain
  ```
  Expected: BUILD SUCCESSFUL; "Grazel golden baseline verified ... clean".

- [ ] **Step 5: Write the focused unit test** `MainReachabilityTrackerTest.kt`:
  ```kotlin
  class MainReachabilityTrackerTest {
      @Test fun `computeScope collects transitive reachable projects and stops on cycles`() {
          // metadata: :app -> :a -> :b, and :b -> :a (cycle). Expect reachableProjectPaths = {:app,:a,:b}
          // and each visited once (no infinite recursion).
      }
      @Test fun `filterExcludedByEveryReachableRoot keeps a dep excluded on only one reachable edge`() {
          // two recorded scopes reaching :lib; one excludes group:artifact, the other does not.
          // Expect the dep RETAINED (intersection semantics).
      }
      @Test fun `filterExcludedByEveryReachableRoot drops a dep excluded on every reachable edge`() {
          // both scopes exclude group:artifact -> dropped.
      }
      @Test fun `selectedMainVariantHierarchyNames falls back to default when known`() {
          // display name with no variant match, default present -> {default}; default absent -> {}.
      }
  }
  ```
  Build the tracker from a hand-constructed `DeclaredDependencyMetadata` (see
  `DeclaredDependencyMetadataCollectorTest.kt` for fixtures). Run:
  `./gradlew :grazel-gradle-plugin:test --tests "*MainReachabilityTrackerTest" --console=plain` → PASS.

- [ ] **Step 6: Commit.**
  ```bash
  git add grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/resolution/MainReachabilityTracker.kt \
          grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/resolution/MainProjectEdgeScope.kt \
          grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt \
          grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/resolution/MainReachabilityTrackerTest.kt
  git commit -m "refactor(resolver): extract MainReachabilityTracker from ResolutionSession"
  ```

---

### Task 2: RootVisitOutcome conversion (return-and-fold)

**Files:**
- Create: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/resolution/RootVisitOutcome.kt`
- Modify: `AggregatedDependencyResolver.kt` (`resolveRootToDependencyMap`, `ResolutionSession` call sites), `MainReachabilityTracker.kt` (`recordReachable`)

**Interfaces:**
- Produces:
  ```kotlin
  internal data class RootVisitOutcome(
      val dependencies: Map<String, ResolvedDependency>,
      val reachableProjectPaths: Set<String>,
      val reachableBucketNamesByProject: Map<String, Set<String>>,
  )
  ```
- Consumes: `MainReachabilityTracker.recordReachable(projectPaths, bucketNamesByProject)` (implemented here).

- [ ] **Step 1: Add `RootVisitOutcome.kt`.**

- [ ] **Step 2: Change `resolveRootToDependencyMap`** (`AggregatedDependencyResolver.kt:717-833`) to
  return `RootVisitOutcome`. Remove the `reachableProjectPaths: MutableSet<String>?` and
  `reachableBucketNamesByProject: MutableMap<String, MutableSet<String>>?` parameters. Inside the
  `forEach`, accumulate into local `mutableSetOf<String>()` / `mutableMapOf<String, MutableSet<String>>()`
  instead of the injected out-params (same `reachableBucketNamesForProject?.invoke(...)` logic), and
  return them in the outcome alongside `depMap`.

- [ ] **Step 3: Implement `MainReachabilityTracker.recordReachable`** to fold an outcome's
  `reachableProjectPaths` + `reachableBucketNamesByProject` into the tracker's accumulating state
  (same union logic previously done by the mutable out-params).

- [ ] **Step 4: Update `ResolutionSession`** call sites: each `resolveRootToDependencyMap(...)` call drops
  the two mutable-ref args, receives a `RootVisitOutcome`, then calls `tracker.recordReachable(outcome.…)`
  and uses `outcome.dependencies` as the closure. Preserve per-kind ordering exactly (main roots record
  before test roots run).

- [ ] **Step 5: Refactor test cycle** (both commands, byte-clean). Commit:
  ```bash
  git commit -m "refactor(resolver): resolveRootToDependencyMap returns RootVisitOutcome (return-and-fold)"
  ```

---

### Task 3: DependencyBucketAccumulator

**Files:**
- Create: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/resolution/DependencyBucketAccumulator.kt`
- Modify: `AggregatedDependencyResolver.kt`

**Interfaces:**
- Produces:
  ```kotlin
  internal enum class BucketTarget { HIERARCHY, TEST_HIERARCHY, LEAF, LEAF_UNIT_TEST, LEAF_ANDROID_TEST }
  internal class DependencyBucketAccumulator {
      fun fold(target: BucketTarget, projectPath: String, bucketName: String,
               closure: Map<String, ResolvedDependency>, keepEmpty: Boolean = true)
      fun foldLint(closure: Map<String, ResolvedDependency>)
      fun hasResolvedClosures(): Boolean
      // snapshots for OwnershipPlannerInput:
      fun leaf(): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>
      fun leafUnitTest(): ...; fun leafAndroidTest(): ...; fun hierarchy(): ...; fun testHierarchy(): ...
      val lintDeps: Map<String, ResolvedDependency>
  }
  ```
- Consumes: `mergeBucket`, `unionDependencyMaps`, `ProjectDependencyBucket`, `ResolvedDependency`.

- [ ] **Step 1: Create `DependencyBucketAccumulator.kt`** moving the five `*Closures` maps + `lintDeps` +
  `addDependenciesToProjectBucket` (as `fold`), `addToHierarchyBucket`/`addToTestHierarchyBucket` (folded
  into `fold(HIERARCHY|TEST_HIERARCHY, keepEmpty=false)`), `snapshotDependencyBuckets` (applied inside
  the `leaf()/hierarchy()/...` snapshot accessors), `hasResolvedClosures`. The `BucketTarget` enum
  replaces the "which map" decision that `collectRootClosures` currently makes inline.

- [ ] **Step 2: Update `ResolutionSession`** to hold a `DependencyBucketAccumulator` and replace every
  `addTo*`/`addDependenciesToProjectBucket(<map>, …)`/`unionDependencyMaps(lintDeps, …)` with the matching
  `accumulator.fold(...)` / `accumulator.foldLint(...)`; `OwnershipPlannerInput` reads the snapshot
  accessors; `hasResolvedClosures()` delegates.

- [ ] **Step 3: Refactor test cycle** (byte-clean). Commit:
  ```bash
  git commit -m "refactor(resolver): extract DependencyBucketAccumulator"
  ```

---

### Task 4: DeclaredMetadataMerger

**Files:**
- Create: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/resolution/DeclaredMetadataMerger.kt`
- Modify: `AggregatedDependencyResolver.kt`

**Interfaces:**
- Produces:
  ```kotlin
  internal class DeclaredMetadataMerger(
      private val declaredDependencyMetadata: DeclaredDependencyMetadata,
      private val projectMetadataByPath: Map<String, ProjectDeclaredDependencyMetadata>,
  ) {
      // folds compileOnly + declared-main (reachability + exclude gated via tracker) into the accumulator,
      // returns declaredTestDependenciesByBucket for BucketOwnershipPlanner.
      fun merge(
          accumulator: DependencyBucketAccumulator,
          tracker: MainReachabilityTracker,
      ): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>
  }
  ```
- Consumes: `DependencyBucketAccumulator`, `MainReachabilityTracker`,
  `collectCompileOnlyDependenciesByProjectBucket`, `collectDeclaredMainDependenciesByProjectBucket`,
  `collectDeclaredTestDependenciesByProjectBucket`.

- [ ] **Step 1: Create `DeclaredMetadataMerger.kt`** moving `addDeclaredMetadataClosures` (as `merge`) and
  `shouldAddDeclaredHierarchyDependency` verbatim; the three folding steps call `accumulator.fold(...)`
  and the reachability/exclude gating calls `tracker.isReachableMainBucket` /
  `tracker.filterExcludedByEveryReachableRoot`. Preserve the documented 3-step order exactly (compileOnly
  → declared-main gated → declared-test).

- [ ] **Step 2: Update `ResolutionSession.resolve()`** to call
  `val declaredTest = DeclaredMetadataMerger(...).merge(accumulator, tracker)` in place of
  `addDeclaredMetadataClosures()`, passing `declaredTest` to `OwnershipPlannerInput`.

- [ ] **Step 3: Refactor test cycle** (byte-clean). Commit:
  ```bash
  git commit -m "refactor(resolver): extract DeclaredMetadataMerger"
  ```

---

### Task 5: RootContributionComputer (data-driven dispatch)

**Files:**
- Create: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/resolution/RootContributionComputer.kt`
- Modify: `AggregatedDependencyResolver.kt` (`collectRootClosures` collapses into the spine loop)
- Test: `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/resolution/RootContributionComputerTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  internal data class RootContribution(
      val scope: MainProjectEdgeScope?,          // main roots only; spine folds via tracker.recordMainRoot
      val outcome: RootVisitOutcome,
      val routing: List<BucketRouting>,          // (BucketTarget, bucketName) pairs; spine folds into accumulator
      val lintClosure: Map<String, ResolvedDependency>?,  // LINT only
      val seedsBinaryRoot: Boolean,
  )
  internal data class BucketRouting(val target: BucketTarget, val bucketName: String)
  internal class RootContributionComputer(
      private val tracker: MainReachabilityTracker,
      private val declaredDependencyMetadata: DeclaredDependencyMetadata,
      private val resolveRoot: (AggregatedDependencyRoot, /*exclude+reachability context*/ …) -> RootVisitOutcome,
  ) {
      fun compute(root: AggregatedDependencyRoot): RootContribution
  }
  ```

- [ ] **Step 1: Create `RootContributionComputer.kt`** turning the three parallel `when(metadata.kind)`
  blocks in `collectRootClosures` (`:441`, `:449`, `:466`, `:474`, `:537`) into a single per-kind
  computation. Encode the per-kind deltas as a table/`when` that returns, for each kind: the exclude-rule
  source (main = `excludeRulesFor(AndroidBuild)`; test-hierarchy = by bucket; unit/android-test =
  `collectExcludeRulesByProjectPath` with `variantNamesForLeafTest`; lint = none), whether it seeds
  reachability (main only → produce `scope`), and the routing list (the current `targetBucketNames`
  → `BucketTarget` mapping including the `TEST_VARIANT`/`ANDROID_TEST_VARIANT`/`DEFAULT_VARIANT` splits).
  `variantNamesForLeafTest` moves here (or stays a shared free function it calls).

- [ ] **Step 2: Reduce `ResolutionSession` to the spine.** `resolve()` becomes: build tracker /
  accumulator / computer; `workspaceDependencyRoots.filter(tracker::shouldResolveMainHierarchyRoot)`;
  for each root `val c = computer.compute(root)`; fold: `c.scope?.let { tracker.recordMainRoot(root.metadata, it) }`,
  `tracker.recordReachable(c.outcome.…)`, `c.routing.forEach { accumulator.fold(it.target, …, c.outcome.dependencies) }`,
  `c.lintClosure?.let(accumulator::foldLint)`, `if (c.seedsBinaryRoot) sawBinaryRoot = true`; then the
  existing binary-root check, `DeclaredMetadataMerger.merge`, and `BucketOwnershipPlanner.plan`. Delete
  the emptied `collectRootClosures`.

- [ ] **Step 3: Refactor test cycle** (byte-clean).

- [ ] **Step 4: Write `RootContributionComputerTest.kt`:**
  ```kotlin
  class RootContributionComputerTest {
      @Test fun `main hierarchy root seeds reachability and routes to hierarchy buckets`() { … }
      @Test fun `main leaf root routes test and androidTest bucket names to leaf test maps`() { … }
      @Test fun `lint root produces lint closure and no reachability scope`() { … }
      @Test fun `unit test root uses Test exclude rules and routes to leaf unit test`() { … }
  }
  ```
  Use a stub `resolveRoot` returning a fixed `RootVisitOutcome` so the test asserts routing/scope/exclude
  selection, not real graph walking. Run
  `./gradlew :grazel-gradle-plugin:test --tests "*RootContributionComputerTest" --console=plain` → PASS.

- [ ] **Step 5: Commit.**
  ```bash
  git commit -m "refactor(resolver): data-driven RootContributionComputer; ResolutionSession is now a spine"
  ```

---

### Final: cleanup + whole-branch review + PAX sweep

- [ ] `/simplify` over `dependencies/resolution/` (dedup/altitude), byte-clean gated.
- [ ] Whole-branch code review (superpowers:requesting-code-review) on the full task range.
- [ ] **Full PAX sweep (once):** pre-flight `df -h /` + cache `du -sh`; migrate `--rerun-tasks`
  (background) → `git status --porcelain` clean (ignore `linters/`) + `diff --check` →
  `verify-pax-size-guard.sh --mode preserving` (11/11/1945, no deltas) → APK build → focused tests.
- [ ] Update `.superpowers/sdd/progress.md` ledger; push on green + user sign-off.

---

## Self-Review

**Spec coverage:** all five design tasks + testing + PAX gate mapped to Tasks 1-5 + Final. Both spec unit
tests present (Task 1, Task 5). Return-and-fold (Task 2), data-driven table (Task 5), thin spine (Task 5)
all covered.

**Placeholder scan:** interface signatures use `…` only for mechanical repetition of already-named
snapshot accessors / context params — the moved method bodies are verbatim from the cited line ranges, so
no logic is left unspecified.

**Type consistency:** `RootVisitOutcome` (Task 2) is consumed by `RootContributionComputer` (Task 5);
`BucketTarget`/`DependencyBucketAccumulator.fold` (Task 3) is consumed by `DeclaredMetadataMerger` (Task 4)
and the spine (Task 5); `MainReachabilityTracker` (Task 1) `recordReachable` stub is implemented in Task 2
and consumed in Task 5. `MainProjectEdgeScope` moves once (Task 1) and is referenced by Tasks 2/5.
