# Item 21 — Simplify Pass: Dead Code, Duplication & Indirection (Design)

> **Status:** Approved 2026-06-28 (grounded against branch diff via four parallel review agents).
> **Executor:** Codex. **Behaviour change:** none — dead-code removal, de-duplication, inlining.
> Golden EMPTY-diff.
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`. **Depends on:** Item 10 (size guard). Mostly
> independent of Items 17/18/19, but `compressionResults` input removal must wait until Item
> 19 decides whether target-reference facts need compression data.

> **⚠️ Execution note — delegate to subagents; protect the main context.**

---

## Goal

A whole-branch simplify pass found a pile of genuinely reducible code that the layering work
left behind: dead functions/fields with zero production callers, verbatim duplication beyond
Item 17's scope, and one-caller indirection wrappers. Remove it. Every change is
**output-preserving (golden empty-diff)** and individually small; the golden + size guard +
existing tests are the safety net. ~250–300 LOC removed, no algorithm change.

Scope was decided with the maintainer: this item is groups **A (dead code) + B (duplication) +
C (indirection) + the one perf-inline (D)**. Explicitly **out**: the redundant `TasksManager`
`dependsOn` calls (kept deliberately as readable intent) and the `@InputFiles`
caching-precision change (belongs to the cacheability track). The `normalized()` redundancy is
handled in **Item 18** (same file).

## Care points (apply to every removal)

- **Provably-unreachable only.** Before deleting, grep for callers across `src/main` *and*
  `src/test`/`src/functionalTest`. Zero production callers ⇒ safe; test-only callers ⇒ delete
  the dead surface and adjust the test to use the live API (do not keep the dead method alive
  just to satisfy a test).
- **Serialized intermediate plan JSON may change shape** when fields are removed (e.g.
  `CandidateMavenRepo`). That is fine: these are intermediate task outputs, never committed,
  and producer + consumer always re-run together. Generated `BUILD.bazel`/`WORKSPACE`/pin
  output must stay byte-identical — that is the golden gate.
- **Batch by file/concern, commit in small slices** so any unexpected golden diff is
  attributable.

## Work

### A. Dead code — zero production callers (delete)

1. **`WorkspacePlan.tagsFor()` extension** (`gradle/dependencies/model/WorkspacePlan.kt:56–68`).
   Linear-scan twin of the indexed `WorkspacePlanService.tagsFor(...)`; all production callers
   (`AndroidExtractor`, `KotlinProjectDataExtractor`, `AndroidInstrumentationBinaryDataExtractor`)
   use the service. Delete the extension.
2. **`CandidateMavenRepo.rootArtifacts` field** (`model/WorkspacePlan.kt:31`;
   `WorkspacePlanBuilder.kt:44,67`). Always equal to `pinInputs`, never read in production.
   Delete the field and its two assignment sites (and the `val rootArtifacts = sortedArtifacts`
   local in the aggregated branch). *(Maintainer decision: delete the speculative
   "future divergence" hook; re-add when a real consumer exists.)*
3. **`CandidateMavenRepo.variantArtifacts` field** (`model/WorkspacePlan.kt:34`;
   `WorkspacePlanBuilder.kt:55–58`). Duplicates `WorkspaceDependencies.variantDeps`; only a test
   reads it. Delete the field, the 3 builder lines, and the structural test assertion.
4. **`List<ResolvedDependency>.mavenInstallRootArtifacts(defaultArtifacts,
   workspaceArtifactsByVariant)` bridge overload** (`migrate/dependencies/MavenInstallRootArtifacts.kt:34–50`).
   Zero callers; vestige of an old test path. Delete.
5. **`WorkspacePlanService.getPlan()` / `getRenderPlan()`** (`gradle/dependencies/WorkspacePlanService.kt:40–42`)
   — dead interface methods exposing nullable internal state. Delete from interface +
   `DefaultWorkspacePlanService`.
6. **`MavenInstallStore` 3-arg + 4-arg `set()` overloads** (`gradle/dependencies/MavenInstallStore.kt:41,43`).
   Only the 6-arg version is called; the others are a dead delegation chain. Delete both from
   the interface and implementation.
7. **`BucketHierarchyGraph.predecessorsOf()/successorsOf()/contains()`**
   (`gradle/variant/BucketHierarchyGraph.kt:84–92`). Test-only introspection; production uses
   the engine's string-keyed wrapper (`hasAncestor`, `ancestorsOf`, `leafDescendantsOf`, …).
   Delete the three methods; rewrite the `BucketHierarchyGraphTest` cases to assert via the
   wrapper API (or via the engine), not the deleted node-level methods.
8. **Unread `compressionResults` `@InputFile`** (`tasks/internal/CollectTargetMavenRepoReferencesTask.kt:73–75`).
   Wired but never read in `action()`. Ordering is already guaranteed by the explicit
   `dependsOn(analyzeVariantCompressionTask)` in `TasksManager.kt` (which the maintainer is
   keeping). **Run this subtask only after Item 19 or after explicitly proving Item 19's
   `TargetReferenceFactsExtractor` does not need compression data as a declared input.** If
   Item 19 needs compression data, keep the input and update this item as intentionally waived.
   Otherwise remove the unread input property + its wiring; keep the `dependsOn`.

### B. Duplication — consolidate

9. **`hasSameDefaultOwnerIdentityAs` + `isDeclaredDependency` duplicated verbatim** between
   `gradle/dependencies/DefaultBucketDependencyReducer.kt:62–69` and
   `gradle/dependencies/DefaultOverrideCarrierPlanner.kt:105–112`. Promote each to a single
   package-level `internal` (alongside the Item 17 `BucketSetMath.kt` home, or a small shared
   file). **Do NOT touch** `hasSameDefaultDirectOwnerIdentityAs` (drops the `dependencies`
   field — a genuine distinct predicate, not a duplicate).

### C. Indirection — inline

10. **`computeFromResults` → `computeInternal` two-level split** (`ComputeWorkspaceDependencies.kt:26–27`).
    One caller; no interface/overload justifies the split. Inline (rename `computeInternal` to
    the public name, drop the wrapper).
11. **`ResolvedDependency.defaultOwnerOverrideTarget()` one-liner wrapper**
    (`migrate/dependencies/MavenInstallRootArtifacts.kt:197–199`). One call site with full
    context. Inline to `dependency.overrideTarget ?: mavenOverrideTarget(dependency.shortId, DEFAULT_VARIANT)`.
12. **Redundant `populatePlan(plan)`** (`tasks/internal/FinalizeWorkspacePlanTask.kt:76`). After
    `initPlan(...)` returns the already-present plan, re-writing it is a no-op in both fresh and
    cache-restored paths. Delete line 76. *(Verify: in every path reaching this task the plan is
    already populated by `ComputeWorkspacePlanTask`/`CollectTargetMavenRepoReferencesTask`.)*
13. **Dead `!constraint` sub-expression** (`gradle/dependencies/ResolvedComponentsVisitor.kt:225`).
    The early `return@forEach` when `constraint == true` (`:221`) makes `!constraint` always
    true at `:225`. Simplify `if (traverseProjectNodes && !constraint)` → `if (traverseProjectNodes)`.
14. **Test-only delegating wrappers** `collectExcludeRulesByProjectPath` /
    `collectCompileOnlyDependenciesByBucket` (`gradle/dependencies/DeclaredDependencyMetadataCollector.kt:107–125`).
    Production calls the `DeclaredDependencyMetadata` methods directly; these collector-level
    wrappers exist only for one test each. Delete them and have the test call
    `collect(...).collectExcludeRulesByProjectPath(...)` directly.

### D. Perf-inline (slightly larger, still empty-diff)

15. **Inline `VariantMavenInstallInput` + filter before override computation**
    (`migrate/dependencies/MavenInstallArtifactsCalculator.kt:76–88,184–189`). The staging data
    class pre-computes `calculateOverrideTargets(...)` for **every** variant, then the
    `materializedMavenRepos` filter drops most of them two lines later — wasted work on dropped
    repos. Move the early-exit/filter ahead of `rootArtifacts.getValue(...)` and
    `calculateOverrideTargets(...)`, compute both inline in the surviving-repo lambda, and delete
    the 4-field data class. `calculateOverrideTargets` is pure/stateless, so output is identical.

## Explicitly out of scope (decided)

- **`TasksManager` redundant `dependsOn` calls** — kept as deliberate readable intent.
- **`@InputFiles dependencyDeclarationFiles`** removal — caching-precision change, owned by the
  cacheability track, not this behaviour-frozen pass.
- **`normalized()` redundancy** — handled in Item 18 (same file).
- **Six duplicated coverage helpers** — Item 17.
- The "leave alone in Item 21" set the audit confirmed: set-membership ownership math
  (`intersectByBucketOwner`, `withoutDependenciesOwnedByNonDefaultHierarchy`,
  `withoutTestDependenciesCoveredBy*`), `hasSameResolved{Artifact,Owner}IdentityAs` (distinct on
  `dependencies`), `WorkspaceRenderPlanBuilder.alwaysMaterializedVariants` (test seam),
  `reachableMainBucketsByProject` in both result + aggregate models (different scopes),
  `scopedSiblingClosureDependenciesByShortId`. Do not touch these in this dead-code/
  de-duplication pass. Item 22 separately challenges whether the set-membership ownership math
  is model-essential or problem-essential using PAX measurements.

## Safety mechanism

- **Sample golden EMPTY-diff** + **PAX generated diff stable** against the frozen Item 10
  baseline. Any output diff is stop-and-investigate.
- **No parity flag** — these are compile-time removals; compiler + tests + golden are the net.
- **Size guard (Item 10):** no change expected.
- Grep-confirm zero production callers for every A-group delete before removing.

## Acceptance criteria

- All A/B/C/D items above removed/inlined; the "out of scope" and "leave alone in Item 21"
  sets untouched.
- Tests referencing deleted test-only surface (`BucketHierarchyGraph` methods, the collector
  wrappers, the `variantArtifacts` assertion) updated to use live APIs or removed; full
  `:grazel-gradle-plugin:test` + `functionalTest` green.
- Sample golden empty-diff; PAX generated diff stable; PAX builds green; size guard no-increase.

## Out of scope / Non-goal

- Any output/behaviour change; caching-semantics changes (group E); target-reference model
  (Item 19); SCC (Item 18); set-math dedup (Item 17).
