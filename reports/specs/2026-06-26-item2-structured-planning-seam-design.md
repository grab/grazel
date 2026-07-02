# Item 2 — Structured Planning Seam (`WorkspacePlan`) (Design)

> **Status:** Approved 2026-06-26. Second spec in the dependency-refactor spec set.
> **Executor:** Codex. **Behaviour change:** none (additive; computed and tested
> beside the existing machinery, no consumer rewired).
> **Global Constraints & Verification Playbook:** inherited verbatim from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`. Read that first.

> **⚠️ Execution note — delegate to subagents; protect the main context.** Wide code
> reads (consumer call-sites, the resolved-value model) and PAX differential checks must
> go to focused subagents that return only distilled results. Keep the orchestrating
> context for sequencing, decisions, and verification.

---

## Goal

Introduce a structured planning layer in two stages:

1. A pre-compression `WorkspacePlan` computed after dependency resolution and before
   variant compression. It owns candidate repo definitions, per-target `@maven`
   compile-filter tags, override-target decisions, pin inputs, and per-variant
   provenance.
2. A post-compression `WorkspaceRenderPlan` computed after `AnalyzeVariantCompression`.
   It owns the exact materialized repo set for rendering/pinning: reachable generated
   deps/plugins/tags, plus override-target closure and always-materialized repos.

Renderers will later (Item 3) only *format* from these plans. This item is **additive and
behaviour-preserving**: the plans are computed and proven equal to today's ad-hoc
derivations; **no consumer is rewired**, so output stays byte-identical to the Item 1
golden baseline.

## Why (the altitude this fixes)

Today three "decisions" are made at the wrong altitude / by scraping rendered output:
1. WORKSPACE materialized-repo set is derived from generated BUILD-file tag manifests via
   regex (`GeneratedBuildMavenRepos.kt:26`, `fromTargets`/`fromFiles`).
2. Artifact pinning regex-parses the generated WORKSPACE to find active repos
   (`ArtifactPinner.kt:316-320`).
3. Tag-producing extractors compute compile-filter tags locally. The most problematic path
   is `AndroidExtractor`, which walks direct project dependencies and re-selects *their*
   variants to pad `@maven` tags (`AndroidExtractor.kt:146-162`, `186-221`).

The plan makes these decisions once, from **model data** (`WorkspaceDependencies` + the
project graph), so later renderers read decisions instead of reconstructing them. This
item builds the seam; Items 3–4 move consumers onto it and delete the scrapes.

---

## Architecture

A new `ComputeWorkspacePlanTask` runs **after `ComputeWorkspaceDependencies` and before
`AnalyzeVariantCompression`**, consumes `WorkspaceDependencies` + the project dependency
graph (`DependencyGraphsService`) + declared metadata, and emits
`build/grazel/workspace-plan.json`, served via a dedicated `WorkspacePlanService`
BuildService.

A second `FinalizeWorkspacePlanTask` runs **after `AnalyzeVariantCompression`**, consumes
`workspace-plan.json` + the compression summary, and emits
`build/grazel/workspace-render-plan.json`. This task computes materialization from model
data, not generated BUILD/WORKSPACE files.

**Ordering is a correctness requirement, not a preference:** the `tagPlan` consumer is
`extract()`, which is invoked *by* `AnalyzeVariantCompressionTask` (per variant). For
Item 3's `extract` to read the plan, the plan must exist before compression runs:

```
ComputeWorkspaceDependencies
  → ComputeWorkspacePlan
  → AnalyzeVariantCompression
  → FinalizeWorkspacePlan
  → GenerateBazelScripts / GenerateRootBazelScripts / PinMavenArtifacts
```

The base `WorkspacePlan` is **compression-agnostic** (it does not read compression
results). The render plan is compression-aware because materialized repos must match the
actual reachable generated targets after compression. This avoids the wrong shortcut
`materializedRepoNames = repoPlan.keys`, which would materialize candidate repos that no
reachable target references.

## Components / file layout

- `gradle/dependencies/model/WorkspacePlan.kt` (new) — serializable plan + sub-types.
- `gradle/dependencies/WorkspacePlanBuilder.kt` (new, **pure** — no Gradle types, fully
  unit-testable; the `DependencyBucketPlacementEngine` is the role model for this style).
- `gradle/dependencies/WorkspaceRenderPlanBuilder.kt` (new, **pure**) — derives the exact
  materialized repo set from `WorkspacePlan` + compression results, including deps,
  plugins, tags, override-target closure, and always-materialized repos.
- `tasks/internal/ComputeWorkspacePlanTask.kt` (new) — reads inputs, calls the builder,
  serializes `workspace-plan.json`.
- `tasks/internal/FinalizeWorkspacePlanTask.kt` (new) — reads `workspace-plan.json` +
  compression results, calls the render-plan builder, serializes
  `workspace-render-plan.json`.
- `gradle/dependencies/WorkspacePlanService.kt` (new) — dedicated BuildService serving the
  base and render plans. Kept separate from `DependencyResolutionService` to preserve the
  altitude boundary (resolved-values service vs planning service).
- `tasks/internal/TasksManager.kt` — register base-plan and render-plan tasks in the
  ordering above.
  **No consumer edits in this item.**

## Data model (provenance baked in)

```
WorkspacePlan
  repoPlan: Map<repoName, CandidateMavenRepo>
      rootArtifacts    : per-repo direct roots (selected versions)
      pinInputs        : full artifact list + version constraints
                         (Coursier maven_install.artifacts — Global Constraint 2)
      overrideTargets  : per-artifact redirect to another repo's label
      variantArtifacts : per-variant resolved identity   ← SOURCE OF TRUTH (provenance)
  tagPlan: Map<TargetTagKey, Set<String /*@maven//:label*/>> ← Global Constraint 3
      TargetTagKey = VariantGraphKey + targetKind
          targetKind examples: AndroidLibrary, AndroidUnitTest, AndroidInstrumentation,
          KotlinLibrary, KotlinUnitTest

WorkspaceRenderPlan
  materializedRepoNames: Set<String>
      = repos referenced by reachable generated deps/plugins/tags
        + override-target closure
        + always-materialized repos
      != repoPlan.keys
```

- **Provenance:** `variantArtifacts` retains the full per-variant resolved identity
  (version / excludeRules / jetifier per owning variant — data that already exists in
  `WorkspaceDependencies.variantTransitiveClasspath`). Today's **global-collapse
  selection** (`MavenInstallRootArtifacts.kt:148-176`, DEFAULT_VARIANT preference) becomes
  a clearly-labelled **derived projection** over `variantArtifacts`, consumed by the
  as-yet-unchanged renderers. Item 5 is then a pure consumer-switch to the variant-scoped
  view — **no plan model change**.
- **TargetTagKey** must align with the actual extractor key. Use `VariantGraphKey` so
  Android main, unit test, androidTest, JVM main, and JVM test cannot collide when a
  project and variant name are the same. Include `targetKind` so two target renderers
  never share tags accidentally.

## The load-bearing correctness invariant (single source)

> **The plan owns repo-affecting decisions; renderers only format. `materializedRepoNames`
> is derived over the very deps/plugins/tags the renderers consume from the plan and the
> compression result. Any downstream decision today that can add or drop a repo reference —
> Android/Kotlin tag closures, override-target redirects, databinding/KSP/parcelize
> filtering — must be MODELED in the plan, not left in the renderer. When the differential
> test (below) finds a mismatch, the fix is to LIFT that decision into the plan, NEVER to
> scrape rendered output.**

This is what makes the altitude fix real rather than two implementations of "which repos"
that happen to agree. (In this item the plan is proven equal to current derivations; the
actual consumption is Item 3.)

## Inputs (all already exist upstream)

- `WorkspaceDependencies` (resolved values: `variantDeps`, `transitiveClasspath`,
  `variantTransitiveClasspath`, `aggregatedRepos`, `reachableMainBucketsByProject`).
- Project dependency graph via `DependencyGraphsService` (for the cross-project tag
  closure — replaces `AndroidExtractor.bestVariantKeyForTagClosure` /
  `collectTransitiveMavenDepsForTags` and every other extractor-side transitive tag query).
- Declared metadata (repo ownership / variant mapping).
- Variant compression summary (render-plan stage only) so materialized repos match the
  actually generated reachable targets.

## Testing / validation

**Differential characterization tests** — prove the plan reproduces each current ad-hoc
derivation, so divergence is caught and localized *here*, not as an opaque golden diff at
Item 3 cutover:
- `renderPlan.materializedRepoNames` == today's `GeneratedBuildMavenRepos`-derived set
  (sample project + a one-shot PAX check). This assertion is on `WorkspaceRenderPlan`,
  not `WorkspacePlan.repoPlan.keys`.
- `plan.tagPlan[TargetTagKey]` == today's tag closure for every tag-producing extractor:
  Android library, Android unit test, Android instrumentation binary, Kotlin library, and
  Kotlin unit test.
- `plan.repoPlan[repo]` derived-collapse view == today's `pinnableMavenInstallRepos` /
  `mavenInstallRootArtifactsByVariant` output (`ArtifactPinner.kt:322-345`,
  `MavenInstallRootArtifacts.kt`).
- Pure unit tests on `WorkspacePlanBuilder` over fixtures.
- The Item 1 golden guardrail must still produce an **empty `git diff`** (no consumer
  rewired ⇒ no output change). This is the top-level proof of "additive."

## Acceptance criteria

- `ComputeWorkspacePlanTask` emits `workspace-plan.json`; `FinalizeWorkspacePlanTask`
  emits `workspace-render-plan.json`; wired
  Compute → Plan → Compress → FinalizePlan → Generate.
- All differential characterization tests green (plan == current derivations).
- Item 1 golden guardrail: empty `git diff` on committed sample outputs.
- PAX acceptance loop still green (migrate + both APKs; documented waivers).
- `WorkspacePlanBuilder` and `WorkspaceRenderPlanBuilder` are pure (no Gradle/`Project`
  types) and unit-tested.

## Out of scope (explicit)

- **Consumer rewiring** — project gen, root gen, pinner, and tag-producing extractors
  continue to use their current code paths (Item 3).
- **Feedback-loop deletion** — `GeneratedBuildMavenRepos` manifest, WORKSPACE regex
  pinning, and extractor-side tag derivation all remain until Item 4.
- **Provenance selection flip** — global collapse stays the behaviour; variant-scoped
  selection is Item 5.

## Non-goal (documented, with rationale)

**Variant compression is NOT refactored and NOT moved into the planning layer.**
Compression consumes `extract()` output (`AndroidLibraryData`) to make a collapse
decision — a planning decision sitting downstream of a rendering computation, a
pre-existing altitude quirk. It is a *linear* path, not a feedback cycle; none of the
three mergeability blockers live there; PAX passes with it as-is. Refactoring it is
output-changing (it decides target collapse), would fight Items 3–4 which depend on the
current `AndroidLibraryData` shape, and gains nothing for this effort. **It is provably
safe to leave untouched:** tags are excluded from `VariantEquivalenceChecker`, so moving
tag computation into the plan cannot change any compression decision. Revisit only if it
ever blocks a build.
