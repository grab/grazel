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

Introduce a structured planning layer — computed once after dependency resolution and
**before** variant compression — that owns the rendering *decisions*: materialized Maven
repos, per-project/per-variant `@maven` compile-filter tags, override targets, and pin
inputs, **carrying per-variant provenance**. Renderers will later (Item 3) only *format*
from it. This item is **additive and behaviour-preserving**: the plan is computed and
proven equal to today's ad-hoc derivations; **no consumer is rewired**, so output stays
byte-identical to the Item 1 golden baseline.

## Why (the altitude this fixes)

Today three "decisions" are made at the wrong altitude / by scraping rendered output:
1. WORKSPACE materialized-repo set is derived from generated BUILD-file tag manifests via
   regex (`GeneratedBuildMavenRepos.kt:26`, `fromTargets`/`fromFiles`).
2. Artifact pinning regex-parses the generated WORKSPACE to find active repos
   (`ArtificatPinner.kt:316-320`).
3. `AndroidExtractor` walks direct project dependencies and re-selects *their* variants to
   pad `@maven` tags (`AndroidExtractor.kt:146-162`, `186-221`).

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

**Ordering is a correctness requirement, not a preference:** the `tagPlan` consumer is
`extract()`, which is invoked *by* `AnalyzeVariantCompressionTask` (per variant). For
Item 3's `extract` to read the plan, the plan must exist before compression runs:

```
ComputeWorkspaceDependencies → ComputeWorkspacePlan → AnalyzeVariantCompression → GenerateBazelScripts
```

The plan is **compression-agnostic** (it does not read compression results). This is safe
and verified: `VariantEquivalenceChecker` compares the (normalized) dependency set, so
compression only collapses variants with equivalent deps ⇒ the referenced-repo set is
invariant under compression. Tags are *excluded* from the equivalence check, but
compression picks the representative variant's tags, which our per-variant `tagPlan` +
pre-compression `extract` reproduces exactly.

## Components / file layout

- `gradle/dependencies/model/WorkspacePlan.kt` (new) — serializable plan + sub-types.
- `gradle/dependencies/WorkspacePlanBuilder.kt` (new, **pure** — no Gradle types, fully
  unit-testable; the `DependencyBucketPlacementEngine` is the role model for this style).
- `tasks/internal/ComputeWorkspacePlanTask.kt` (new) — reads inputs, calls the builder,
  serializes `workspace-plan.json`.
- `gradle/dependencies/WorkspacePlanService.kt` (new) — dedicated BuildService serving the
  plan. Kept separate from `DependencyResolutionService` to preserve the altitude
  boundary (resolved-values service vs planning service).
- `tasks/internal/TasksManager.kt` — register the task between compute and compression.
  **No consumer edits in this item.**

## Data model (provenance baked in)

```
WorkspacePlan
  repoPlan: Map<repoName, MaterializedRepo>
      rootArtifacts    : per-repo direct roots (selected versions)
      pinInputs        : full artifact list + version constraints
                         (Coursier maven_install.artifacts — Global Constraint 2)
      overrideTargets  : per-artifact redirect to another repo's label
      variantArtifacts : per-variant resolved identity   ← SOURCE OF TRUTH (provenance)
  tagPlan: Map<TagKey, Set<String /*@maven//:label*/>>    ← Global Constraint 3
      TagKey = (projectPath, variantName)                ← keyed PRE-compression,
               aligned to the VariantGraphKey/matchedVariant identity extract() uses
  materializedRepoNames: Set<String>                      ← = repoPlan.keys
```

- **Provenance:** `variantArtifacts` retains the full per-variant resolved identity
  (version / excludeRules / jetifier per owning variant — data that already exists in
  `WorkspaceDependencies.variantTransitiveClasspath`). Today's **global-collapse
  selection** (`MavenInstallRootArtifacts.kt:148-176`, DEFAULT_VARIANT preference) becomes
  a clearly-labelled **derived projection** over `variantArtifacts`, consumed by the
  as-yet-unchanged renderers. Item 5 is then a pure consumer-switch to the variant-scoped
  view — **no plan model change**.
- **TagKey** must align with the `matchedVariant`/`VariantGraphKey` identity, noting
  `matchedVariant.variantName` is the app-side variant name (see
  `AndroidExtractor.kt:86`).

## The load-bearing correctness invariant (single source)

> **The plan owns repo-affecting decisions; renderers only format. `materializedRepoNames`
> is derived over the very deps/tags the renderers consume from the plan. Any downstream
> decision today that can add or drop a repo reference — `AndroidExtractor` tag closure,
> override-target redirects, databinding/KSP/parcelize filtering — must be MODELED in the
> plan, not left in the renderer. When the differential test (below) finds a mismatch, the
> fix is to LIFT that decision into the plan, NEVER to scrape rendered output.**

This is what makes the altitude fix real rather than two implementations of "which repos"
that happen to agree. (In this item the plan is proven equal to current derivations; the
actual consumption is Item 3.)

## Inputs (all already exist upstream)

- `WorkspaceDependencies` (resolved values: `variantDeps`, `transitiveClasspath`,
  `variantTransitiveClasspath`, `aggregatedRepos`, `reachableMainBucketsByProject`).
- Project dependency graph via `DependencyGraphsService` (for the cross-project tag
  closure — replaces `AndroidExtractor.bestVariantKeyForTagClosure` /
  `collectTransitiveMavenDepsForTags`).
- Declared metadata (repo ownership / variant mapping).

## Testing / validation

**Differential characterization tests** — prove the plan reproduces each current ad-hoc
derivation, so divergence is caught and localized *here*, not as an opaque golden diff at
Item 3 cutover:
- `plan.materializedRepoNames` == today's `GeneratedBuildMavenRepos`-derived set (sample
  project + a one-shot PAX check).
- `plan.tagPlan[(proj,variant)]` == today's `AndroidExtractor` tag closure for that
  variant.
- `plan.repoPlan[repo]` derived-collapse view == today's `pinnableMavenInstallRepos` /
  `mavenInstallRootArtifactsByVariant` output (`ArtificatPinner.kt:322-345`,
  `MavenInstallRootArtifacts.kt`).
- Pure unit tests on `WorkspacePlanBuilder` over fixtures.
- The Item 1 golden guardrail must still produce an **empty `git diff`** (no consumer
  rewired ⇒ no output change). This is the top-level proof of "additive."

## Acceptance criteria

- `ComputeWorkspacePlanTask` emits `workspace-plan.json`; wired
  Compute → Plan → Compress → Generate.
- All differential characterization tests green (plan == current derivations).
- Item 1 golden guardrail: empty `git diff` on committed sample outputs.
- PAX acceptance loop still green (migrate + both APKs; documented waivers).
- `WorkspacePlanBuilder` is pure (no Gradle/`Project` types) and unit-tested.

## Out of scope (explicit)

- **Consumer rewiring** — project gen, root gen, pinner, `AndroidExtractor` continue to
  use their current code paths (Item 3).
- **Feedback-loop deletion** — `GeneratedBuildMavenRepos` manifest, WORKSPACE regex
  pinning, `AndroidExtractor` cross-project walk all remain until Item 4.
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
