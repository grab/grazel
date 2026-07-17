# ResolutionSession Breakdown — Design

**Goal:** Decompose the `AggregatedDependencyResolver.ResolutionSession` god-class (~570 lines,
8+ mutable accumulation fields, four fused responsibilities) into focused, stateless collaborators
behind a thin orchestrating spine — behaviour-preserving and byte-identity gated.

**Architecture:** `AggregatedDependencyResolver.resolve()` and its constructor are unchanged.
`ResolutionSession` becomes a thin spine that owns the folded state and orchestrates four
collaborators, mirroring the `AndroidBinaryTargetBuilder` (spine + collaborators) pattern and the
existing `.bucket` package precedent. Data flow is **return-and-fold**: collaborators are stateless
computations that return contributions; the spine is the single mutation site.

**Tech stack:** Kotlin, Gradle plugin. New collaborators live in a new package
`com.grab.grazel.gradle.dependencies.resolution/`. None of these classes are kotlinx-serialized, so
the package placement is free (no lockfile/output impact).

## Global Constraints

- **Behaviour-preserving, byte-identity gated.** Every task must keep `verifyGrazelGoldenBaseline`
  byte-clean and `:grazel-gradle-plugin:test` green. Any task that moves generated output is
  reverted, not patched over.
- **Preserve the load-bearing ordering invariant.** Main-hierarchy / main-leaf roots must be
  processed (in `workspaceDependencyRoots` order) before any test root that reads the same project's
  main reachability. Folding must remain in `workspaceDependencyRoots` order so cross-root reads see
  already-folded state.
- **No `git add -A`; never stage `codedb.snapshot`; one Gradle build at a time.**
- **Model selection:** Sonnet implementers/reviewers. Opus forbidden.

---

## Target Shape

```
AggregatedDependencyResolver            unchanged public resolve() + constructor
  └── ResolutionSession (spine)         owns folded ResolutionState; loops roots; ONLY mutation site
        ├── MainReachabilityTracker     computeScope() → MainProjectEdgeScope (pure); record(scope)
        │                               folds; queries: isReachableBucket / variantHierarchyNamesFor /
        │                               filterExcludedByEveryReachableRoot / shouldResolveMainHierarchyRoot
        ├── RootContributionComputer    per root → RootContribution (closure + reachability deltas +
        │                               routing directive); data-driven table by RootKind, no `when` cascade
        ├── DependencyBucketAccumulator fold(routing, closure) into the 5 *Closures maps + lint;
        │                               snapshot() → OwnershipPlannerInput closures
        └── DeclaredMetadataMerger      produces declared / compileOnly / test contributions to fold,
                                        reading the tracker for reachability gating
```

**Stays put:** `resolve()` entry point; `BucketOwnershipPlanner` and `ResolvedComponentsVisitor`
(already well-scoped collaborators); `mergeDependencyMetadataByMaxVersion` (clean free function).

### Return-and-fold data flow

`resolveRootToDependencyMap` loses its mutable out-params (`reachableProjectPaths`,
`reachableBucketNamesByProject`) and returns:

```kotlin
data class RootVisitOutcome(
    val dependencies: Map<String, ResolvedDependency>,
    val reachableProjectPaths: Set<String>,
    val reachableBucketNamesByProject: Map<String, Set<String>>,
)
```

`RootContributionComputer` wraps that (plus the optional `MainProjectEdgeScope` and a routing
directive) into a `RootContribution`. The spine folds the reachability delta into the tracker and the
closure into the accumulator — in root order — so a test root reads already-folded main reachability.

---

## Tasks

Each task compiles on its own, keeps the golden byte-clean, and is independently revertible.

### Task 1 — MainReachabilityTracker
Move the reachability / project-edge-exclusion cluster: `collectMainProjectEdgeScope`,
`declaredProjectDependencyEdges` (+ its cache), `variantsFor`, `mainBuildTypeNamesByProject`,
`knownMainBucketNames`, `selectedMainVariantHierarchyNames`, `addReachableMainBuckets`,
`isReachableMainBucket`, `withoutDependenciesExcludedByEveryReachableRoot`,
`shouldResolveMainHierarchyRoot`. The tracker owns reachability state; `record(scope)` is its single
mutation point. Session delegates; closure accumulation stays in the session for now. Most
self-contained seam → first. **New focused unit test** (see Testing).

### Task 2 — RootVisitOutcome conversion
Flip `resolveRootToDependencyMap` to return `RootVisitOutcome` instead of mutating out-params; the
session folds the outcome into the tracker. Core return-and-fold change and the highest-risk
data-flow edit — isolated so a golden move is unambiguously attributable.

### Task 3 — DependencyBucketAccumulator
Move the five `*Closures` maps + `lintDeps` + `addDependenciesToProjectBucket` / `addToHierarchyBucket`
/ `addToTestHierarchyBucket` / `snapshotDependencyBuckets` / `hasResolvedClosures` behind
`fold(routing, closure)` / `snapshot()`. (Retains the already-applied `mergeBucket` reuse.)

### Task 4 — DeclaredMetadataMerger
Move `addDeclaredMetadataClosures` + `shouldAddDeclaredHierarchyDependency`; reads the tracker for
reachability gating, returns declared / compileOnly / test contributions the spine folds, and still
returns `declaredTestDependenciesByBucket` for `BucketOwnershipPlanner`.

### Task 5 — RootContributionComputer (data-driven dispatch)
Collapse `collectRootClosures`' three parallel `when(metadata.kind)` blocks into a table keyed by
`AggregatedDependencyRootKind` describing {exclude-rule source, seeds-reachability?, routing}. Each
root → `RootContribution`; the spine loops + folds. `ResolutionSession` is now the thin spine.
**New focused unit test** (see Testing).

### Final — cleanup + review
`/simplify` pass over the new package, whole-branch review, then the full PAX sweep.

---

## Testing

**Hard gate (every task):**
```
./gradlew :grazel-gradle-plugin:test --console=plain
./gradlew verifyGrazelGoldenBaseline --console=plain     # success line + CLEAN diff
```

**New focused unit tests (additive, not a substitute for byte-identity):**
- `MainReachabilityTracker` — DFS reachability, per-edge exclude-rule intersection (a dep excluded on
  one reachable edge but not another is kept), cycle / diamond single-visit guard, default-variant
  fallback.
- `RootContributionComputer` — the kind→routing table: each `AggregatedDependencyRootKind` maps to the
  correct exclude-rule source, reachability-seeding flag, and destination bucket map.

`DependencyBucketAccumulator` and `DeclaredMetadataMerger` stay covered by golden + PAX (pure
map-folding already exercised end-to-end).

**Full PAX sweep (once, at the very end):** migrate (`--rerun-tasks`, background) →
`git status --porcelain` clean (ignore `linters/`) + `diff --check` → `verify-pax-size-guard.sh
--mode preserving` (11/11/1945, no deltas) → APK build → focused tests. Non-destructive.

**Storage guardrails (before each expensive run):** `df -h /` + `du -sh` the bazel / gradle caches;
do NOT disable the Bazel disk cache; deliberate `bazelisk shutdown && bazelisk clean --expunge` only
when a private output root >90 GB or disk is low.

## Execution

Subagent-driven-development: fresh Sonnet implementer per task + task review (spec compliance + code
quality), ledger at `.superpowers/sdd/progress.md`, broad whole-branch review at the end. Local
golden gate after each task; one final PAX + local sweep confirms no regression before push.
