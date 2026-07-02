# Altitude Review — `arun/dependencies-refactor` (whole branch)

> **Date:** 2026-07-02. **Method:** scout partition of the 111 changed production files into 10
> altitude domains (A–J) against the roadmap's 6-layer model, then a fan-out of one altitude-review
> subagent per domain, consolidated here. Proxy/pinning (Domains H/I) reuse the separate deep review
> in `reports/` (boundary confirmed clean). Pre-existing-vs-branch-new classification verified against
> `master` via symbol/file presence.
> **Scope of this review:** altitude/layering + arch smell only — NOT correctness (PAX baseline + the
> byte-identical pin gate cover correctness).

---

## Verdict

The layering landed **well**. Every headline altitude goal of the pass is confirmed in place, and the
new code (Layer 3 planner, typed graph, plan services, proxy) respects the boundaries. The residual
findings split three ways, and the honest headline is: **most of what the subagents tagged HIGH is
pre-existing legacy, not something this branch introduced.** The branch-introduced altitude debt is
real but modest and mostly MED — string re-derivation where a typed fact already exists, plus
config-cache hygiene gaps.

**Merge-readiness (altitude lens):** no altitude blocker. The branch-new items below are worth a
short follow-up slice; the legacy items are a separate, larger question the roadmap never claimed to
solve.

---

## Confirmed LANDED (positives — state them, don't re-litigate)

- **Item 12/13** — `BucketOwnershipPlanner` is the single ownership-deciding site;
  `DependencyBucketPlacementEngine` is fully subordinate (instantiated only inside the planner, not in
  DI, not in the task layer). The "parallel ownership" risk is genuinely resolved.
- **Item 18** — SCC retired; `TopologicalSorter` is pure Kahn with a detection-only cycle path. No
  vestigial `cyclic` flag.
- **Item 33** — `DeclaredDependencyMetadataCollector` consumes typed variant roles
  (`declaredDependencyConfigurations`, `compileOnlyDeclaredDependencyConfigurations`,
  `declarationBucketName`); no `BaseVariant` import, no inline config-name classification. Relocation
  is clean.
- **Item 34** — `WorkspacePlanService` split landed; the tag index and plan services are write-once /
  read-only; the consumer-first back-edge is isolated to `WorkspaceRenderPlanService` as intended.
- **Item 35** — `ProgressReporter` is a pure-JVM `fun interface`; Gradle confined to
  `ProgressLogger.withProgress`; `NoOp` not silently defaulted in production.
- **Item 30** — task JSON boundaries clean: all model payloads cross as `RegularFileProperty`/`@InputFile`,
  `fromJson`/`writeJson` only in `@TaskAction`, no eager encode/decode at configuration time.
- **Item 25** — `FormatBazelBuildFileTask` is genuinely gone; formatting dissolved into
  `formatWithBuildifier()`. (The scout's file list was stale; the code is clean.)
- **`BucketSetMath`** — clean, problem-essential set operations on resolved values only.
- **Proxy (H/I)** — altitude boundary held: no `Project`/`Configuration`/`ComponentIdentifier`/
  `ResolvedArtifactResult`/`ArtifactView` leaks into the Ktor server, reconstructor, or pinner.

---

## Triage

### Bucket 1 — Branch-introduced altitude debt (this refactor's follow-up)

| # | Finding | File:Line | Sev | Note |
|---|---------|-----------|-----|------|
| 1 | `BucketOwnershipPlanner` infers `variantType` from `bucketName.endsWith(testSuffix)` instead of threading the typed field it already holds (`visibleMainBucketNamesForTestBucket`, `outputBucketNameForTestBucket`, `testHierarchyBucketClosuresFor`) | `BucketOwnershipPlanner.kt:400-403,586-590,609-626` | **MED** | Cleanest actionable item: thread `variantType` into `DependencyBucketPlacementPlan`. Branch-new file. |
| 2 | `bucketSpecificity()` re-ranks bucket placement at render time | `Dependencies.kt:248-258` | **MED** | Branch-new. Placement at Layer 5. |
| 3 | `isExternalDependencyDeclaration` — a divergent declaration-bucket classifier (narrower exclusion list than the variant layer's canonical `isDeclarationBucket`) | `Dependencies.kt:605-614` | **MED** | Branch-new **and** the exact classifier Item 33 fenced out. See "accepted deferrals" — but note the branch *added* it rather than reusing the variant layer. |
| 4 | KSP config-role knowledge scattered as string heuristics outside the variant layer: `WorkspaceKspConfigurations.isKspDeclarationBucket` and `CollectKspProcessorDependenciesTask`'s `name.startsWith("ksp") && "classpath" !in ...` | `WorkspaceKspConfigurations.kt:54-55`, `CollectKspProcessorDependenciesTask.kt:175-221` | **MED** | Branch-new. Wants a `KspProcessorRootInputPlanner` / typed KSP role in the variant layer, paralleling the Item 26 workspace-dep planner. |
| 5 | `DependenciesGraphsBuilder` routes edges on AGP `BaseVariant` indirectly via `ConfigurationDataSource.isThisConfigurationBelongsToThisVariants` rather than typed `Variant<*>` membership | `DependenciesGraphsBuilder.kt:69` | **MED** | L1 graph build touching AGP type. |
| 6 | `AggregatedDependencyResolver` repeats a test-vs-main bucket routing branch 4× and gates declared-hierarchy deps by project-type+bucket-name; `ComputeWorkspaceDependencies` performs KSP→`KSP_MAVEN` placement, read back by `DependencyResolutionService.getValidKspProcessorShortIds` | `AggregatedDependencyResolver.kt:345-349,518-545`; `ComputeWorkspaceDependencies.kt:91-103` | **MED** | Some is essential closure-scoping; the KSP placement+read-back is a mild L2→output coupling. Verify against intentional hybrid rules before reshaping. |
| 7 | 6 tasks consume `BuildService` instances via `@Internal Property<T>` **without `usesService(...)`** (only `PinMavenArtifactsTask` does it right) | `tasks/internal/*` (DependencyResolution/WorkspacePlan/RenderPlan/TagPlan services) | **MED** | Branch-new services; config-cache/lifecycle tracking gap. Clean, worth fixing. |
| 8 | Tasks re-derive service-owned decisions in `@TaskAction`: migratable-project re-filter, topological ordering, compression pipeline orchestration | `GenerateRootBazelScriptsTask.kt:114-116`, `CollectTargetMavenRepoReferencesTask.kt:93-103`, `AnalyzeVariantCompressionTask.kt:165-218` | **LOW-MED** | Cheap re-derivations; "call one service method" cleanups, not urgent. |
| 9 | `TasksManager` conditional `dependsOn` reads `Provider<Boolean>` extension values at configuration time (config-cache hostile); prefer task enable/disable | `TasksManager.kt:195-203` | **LOW** | |

### Bucket 2 — Known accepted deferrals (re-confirmed present; NOT new work unless scheduled)

- **Item 34 render-plan back-edge** — `WorkspaceRenderPlanService.populateRenderPlan` mutates mid-collection. This is the consumer-first back-edge the maintainer **explicitly accepted and only isolated**. Re-confirmed isolated to one documented class. No action unless you want to finalize-once it.
- **Item 33 classifier unification** — merging the divergent declaration-bucket classifiers was deferred because unifying them changes bucket placement (breaks empty-diff). Finding #3 above is this classifier; it's branch-new but consciously left divergent. Scheduling the unification is a gated, output-classified item, not a quiet fix.

### Bucket 3 — Pre-existing legacy (verified in `master`; NOT this refactor's altitude debt)

These were flagged HIGH/MED by subagents but predate the branch — out of scope for this pass, listed
so they're not mistaken for regressions:

- **`Dependencies.kt collectMavenDeps`** — render-time live-`Configuration` resolution + role
  classification driving the per-project extractors (`AndroidExtractor`, `KotlinProjectDataExtractor`,
  etc.). **Pre-existing.** This is the single largest altitude gap vs the end-vision (renderers should
  consume typed plans), but it's the *per-project BUILD-generation* path, which the refactor's declared
  scope (workspace-dependency + pin path) never claimed to cut over. **See "the one strategic question"
  below.**
- `Repository.isSupported()` migration policy + `DefaultMavenArtifactRepository` (Gradle-internal type)
  in the `RepositoryDataSource` public contract — pre-existing.
- `gradle/Configuration.kt` `isUnitTest()/isAndroidTest()` divergent test classifiers — pre-existing.
- `util/Progress.kt NoOpProgressLogger` (Gradle-internal type in `util`) — pre-existing.
- `ConfigurationParsingVariant` creating/mutating resolvable configs (`kspClasspathConfiguration`,
  `classpathConfiguration`) — pre-existing.
- `WorkspaceBuilder.addAndroidSdkRepositories` live subproject SDK read; `ManifestValuesBuilder` live
  transitive subgraph walk — pre-existing.
- `MigrationChecker.canMigrate` transitive fall-back heuristic — pre-existing.

---

## The one strategic question worth surfacing

Three independent agents (A, B, G) converged on **`Dependencies.kt` (`DefaultDependenciesDataSource`)**
as the altitude epicenter. It still performs **live resolution** (`collectMavenDeps`, pre-existing),
**role classification** (`isExternalDependencyDeclaration`, branch-new), and **placement**
(`bucketSpecificity`, branch-new) at render time — the exact concerns the new L2–L4 typed pipeline was
built to own.

This strongly suggests **two parallel dependency paths coexist**: the new typed pipeline (feeding the
workspace/pin path) and the legacy per-project `collectMavenDeps` path (still feeding per-project BUILD
generation). That's not a regression — but it means the altitude end-vision ("renderers consume typed
plans, don't re-derive") is achieved for the workspace/pin path and **not** for per-project BUILD
generation.

**Decision for the maintainer:** was cutting the per-project extractor path over to the typed pipeline
in scope for this goal, or is it the next altitude frontier? If out of scope, say so explicitly in the
roadmap so `Dependencies.kt` isn't mistaken for unfinished work; if in scope, it's a sizeable follow-up
item (and the branch-new `bucketSpecificity`/`isExternalDependencyDeclaration` additions to that file
should fold into it rather than deepening the legacy path).

---

## Recommended follow-up slices (suggested, not scheduled)

1. **Thread `variantType` through the placement plan** — kills findings #1 and #2 (stop inferring
   variant type from rendered bucket-name strings). Small, empty-diff, high clarity win.
2. **Consolidate KSP config-role facts into the variant layer** — finding #4; a `KspProcessorRootInputPlanner`
   parallel to the Item 26 workspace-dep planner; removes the two scattered `ksp`-name heuristics.
3. **`usesService()` correctness pass** — finding #7; wire the 6 service-consuming tasks; config-cache
   hygiene, likely empty-diff.
4. **(Larger, needs a scope decision)** the `Dependencies.kt` per-project cutover above, folding in the
   branch-new `bucketSpecificity`/`isExternalDependencyDeclaration` and the deferred Item 33 classifier
   unification.

---

## Confidence notes

- Domains A, B, D, F, J had deep reads (30–40 tool calls); C, E, G were shallower single-pass
  (1 tool call each) — their severities are directionally right but the specific line refs should be
  re-confirmed before action. The convergence of C/E/G with the deeper A/B/D on the same themes
  (string re-derivation; `Dependencies.kt`) raises overall confidence in the *themes*.
- Pre-existing-vs-branch-new is verified by symbol/file presence in `master`, not full `git blame`;
  spot-check line-level provenance before acting on any individual legacy item.
