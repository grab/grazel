# Item 21 - Simplify Pass: Dead Code, Duplication, Indirection

## 2026-06-28 Subagent Audit Summary

Status: planning/audit only. No Item 21 implementation edits have been made yet in this log.

Group A caller audit:

- `WorkspacePlan.tagsFor` extension has no callers. Production extractors call
  `WorkspacePlanService.tagsFor`.
- `CandidateMavenRepo.rootArtifacts` has no production reads. Production uses `pinInputs`;
  builder assignments and tests are the remaining coupling.
- `CandidateMavenRepo.variantArtifacts` has no production reads; only builder writes and one
  structural test assertion remain.
- The `mavenInstallRootArtifacts(defaultArtifacts, workspaceArtifactsByVariant)` bridge overload
  has no callers; only the detailed private overload is used.
- `WorkspacePlanService.getPlan` and `getRenderPlan` have no production callers.
- `MavenInstallStore` 3-arg and 4-arg `set` overloads have no external callers; production calls
  the 6-arg `set` from `DependencyResolutionService`.
- `BucketHierarchyGraph.predecessorsOf`, `successorsOf`, and `contains` have no external
  production callers. `predecessorsOf` has an internal self-call from `computeAncestorsOf`, so
  deletion needs replacing that call with `predecessorsByNode[current].orEmpty()` or a private
  helper.
- `CollectTargetMavenRepoReferencesTask.compressionResults` is wired in `TasksManager` but never
  read in `action()`. After Item 19, it can be removed as an input property, but keep the
  `dependsOn(analyzeVariantCompressionTask)` ordering because compression data now flows through
  `DefaultVariantCompressionService`.

Group A expected test updates:

- `WorkspacePlanBuilderTest`: replace `rootArtifacts` assertions with `pinInputs`, remove
  `variantArtifacts` assertion, and remove constructor `rootArtifacts = ...`.
- `WorkspaceRenderPlanBuilderTest` and `DefaultArtifactPinnerTest`: remove `rootArtifacts = ...`
  constructor args or replace with explicit `pinInputs = ...`.
- `WorkspacePlanTasksTest`: remove/update `getPlan()` and `getRenderPlan()` assertions; JSON and
  live service APIs should cover behavior.
- `BucketHierarchyGraphTest`: rewrite direct edge/contains assertions to public live graph APIs or
  remove low-level introspection checks.

Groups B/C/D implementation guidance:

- For `isDeclaredDependency`, do not add a second shared helper blindly. There is already a
  package-level equivalent, `isDeclaredMetadata()`, in `AggregatedDependencyResolver.kt`. Move it
  to a neutral shared file or reuse it from both `DefaultBucketDependencyReducer` and
  `DefaultOverrideCarrierPlanner`.
- Add only one shared `hasSameDefaultOwnerIdentityAs`. Do not substitute
  `hasSameResolvedArtifactIdentityAs`; that includes `repository`/`jetifierSource` and would
  change behavior.
- `ComputeWorkspaceDependencies`: keep public method name `computeFromResults`; inline the
  `computeInternal` body into it and delete the wrapper.
- `MavenInstallRootArtifacts`: replace the override expression with
  `dependency.overrideTarget ?: mavenOverrideTarget(dependency.shortId, variantName)`, then remove
  `defaultOwnerOverrideTarget()` and the unused `OverrideTarget` import.
- `FinalizeWorkspacePlanTask`: deleting `populatePlan(plan)` is safe. `initPlan(...)` already
  populates from JSON on cache-restored paths, and otherwise returns the existing same object.
- `ResolvedComponentsVisitor`: simplify only `if (traverseProjectNodes && !constraint)` to
  `if (traverseProjectNodes)`. Keep the earlier `if (constraint) return@forEach`.
- `DeclaredDependencyMetadataCollector`: delete the two collector wrappers. Tests should call
  `collect(...).collectExcludeRulesByProjectPath(...)` and
  `collect(...).collectCompileOnlyDependenciesByBucket(...)`.
- `MavenInstallArtifactsCalculator`: filter by `materializedMavenRepos` before
  `rootArtifactsByVariant.getValue(...)` and `calculateOverrideTargets(...)`, then delete
  `VariantMavenInstallInput`. Keep override-target calculation based on `rootArtifacts`, not
  `allArtifacts`.

Groups B/C/D expected test updates and risks:

- Update wrapper-only tests in `AggregatedDependencyResolverTest` to use
  `DeclaredDependencyMetadata`.
- Existing Maven materialization tests cover output stability; a new perf test is optional if we
  want to lock the "dropped repos do not compute overrides" intent.
- Biggest risk is accidentally leaving two declared-dependency predicates after deduplication.
- Maven calculator refactor can change output if override targets are computed after configured
  version overrides are applied; keep current ordering.

Verification to run after Item 21 edits:

- Focused touched tests first.
- `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`
- `./gradlew migrateToBazel --console=plain --no-daemon`
- `reports/scripts/verify-default-task-graph.sh`
- `reports/scripts/verify-sample-bucket-labels.sh`
- `reports/scripts/verify-pax-size-guard.sh --mode preserving`
- PAX migrate/build/test loop from `CURRENT-GOAL-ANCHOR.md` before claiming the item green.
