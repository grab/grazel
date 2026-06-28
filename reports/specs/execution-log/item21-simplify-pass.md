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

## 2026-06-28 Group A First Slice

Implemented:

- Removed duplicate `CandidateMavenRepo.rootArtifacts` and `variantArtifacts`; callers now use
  explicit `pinInputs`.
- Removed unused `WorkspacePlan.tagsFor` extension; production uses `WorkspacePlanService.tagsFor`.
- Removed test-only `WorkspacePlanService.getPlan` and `getRenderPlan`.
- Removed unused `List<ResolvedDependency>.mavenInstallRootArtifacts(...)` bridge overload.
- Removed `MavenInstallStore` 3-arg and 4-arg `set` overloads; full setter remains.
- Removed `BucketHierarchyGraph.predecessorsOf`, `successorsOf`, `contains`, and the now-dead
  successor map construction. Tests assert ancestry/leaf-descendant behavior instead.
- Removed unused `CollectTargetMavenRepoReferencesTask.compressionResults` input and its
  `TasksManager` property wiring; kept `dependsOn(analyzeVariantCompressionTask)`.

Debug note:

- First focused test compile failed because a local `targetTagPlan` variable shadowed the task
  property in `WorkspacePlanTasksTest`; fixed with `this.targetTagPlan.set(...)`.
- Second run failed one graph assertion after replacing `successorsOf` with `leafDescendantsOf`;
  corrected the expectation to the test/androidTest leaves under `freeDebug`.

Verification:

- Resource checks before Gradle runs showed about 24-25 GiB free on Data and no cleanup-triggering
  pressure. Some idle Bazel servers from pinner tests remain; not cleaned during focused tests.
- Passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.WorkspacePlanBuilderTest" --tests "com.grab.grazel.gradle.dependencies.WorkspaceRenderPlanBuilderTest" --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest" --tests "com.grab.grazel.gradle.variant.BucketHierarchyGraphTest" --tests "com.grab.grazel.migrate.dependencies.DefaultArtifactPinnerTest" --console=plain --no-daemon`.

## 2026-06-28 Groups B/C/D Slice

Implemented:

- Added shared `DependencyIdentity.kt` helpers for `isDeclaredMetadata` and
  `hasSameDefaultOwnerIdentityAs`.
- Moved `isDeclaredMetadata` out of `AggregatedDependencyResolver.kt` and deleted duplicate
  declared/default-owner helper copies from `DefaultBucketDependencyReducer` and
  `DefaultOverrideCarrierPlanner`.
- Inlined `ComputeWorkspaceDependencies.computeInternal` into `computeFromResults`.
- Inlined `ResolvedDependency.defaultOwnerOverrideTarget` in `MavenInstallRootArtifacts`.
- Removed redundant `FinalizeWorkspacePlanTask.populatePlan(plan)`.
- Removed unreachable `!constraint` check in `ResolvedComponentsVisitor` while preserving the
  earlier `if (constraint) return@forEach`.
- Removed wrapper methods from `DeclaredDependencyMetadataCollector`; updated tests to call
  `collect(...).collect...`.
- Inlined `VariantMavenInstallInput` in `MavenInstallArtifactsCalculator` and filter
  `materializedMavenRepos` before `rootArtifactsByVariant.getValue(...)` and override-target
  calculation.

Verification:

- Stopped stale temp Bazel servers from JUnit scratch workspaces before the next focused test run;
  left real Grazel/PAX Bazel servers running.
- Passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --tests "com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependenciesTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallArtifactsCalculatorTest" --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest" --tests "com.grab.grazel.gradle.dependencies.WorkspacePlanBuilderTest" --tests "com.grab.grazel.gradle.dependencies.WorkspaceRenderPlanBuilderTest" --tests "com.grab.grazel.gradle.variant.BucketHierarchyGraphTest" --console=plain --no-daemon`.

## 2026-06-28 Grazel Migration Check

- Passed `./gradlew migrateToBazel --console=plain --no-daemon`.
- Generated output stayed clean: `git status --short` and `git diff --name-only` showed only
  source/test/docs touched by Item 21, no generated BUILD/WORKSPACE/json drift.
- Existing fallback compression messages for `:sample-android` still appear; this is the same
  logged Item 19 hygiene note, not a generated-output change.

## 2026-06-28 Local Broad Gates

- Passed `git diff --check`.
- Passed full plugin unit tests:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`.
- Passed `reports/scripts/verify-default-task-graph.sh`.
- `reports/scripts/verify-sample-bucket-labels.sh` failed only on the documented pre-existing
  waiver: `WORKSPACE must not union one-sided appcompat exclude onto
  androidx.constraintlayout:constraintlayout`.
  Evidence: `git diff -- WORKSPACE build/grazel/dependencies.json sample-android/BUILD.bazel
  sample-android-tests/BUILD.bazel` was empty after this Item 21 migrate run, and the same waiver
  is recorded repeatedly in `EXECUTION-LOG.md` and `REVIEW-GUIDE.md`.
- Passed `reports/scripts/verify-pax-size-guard.sh --mode preserving`; PAX baseline/current:
  bucket count `11`, pinfile count `11`, total artifact roots `1945`, all unchanged.

## 2026-06-28 PAX Gates

- Passed PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
  in `11m 30s`.
- PAX generated output stayed clean against the committed local baseline
  `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`.
- Passed PAX `git diff --check`.
- Passed `reports/scripts/verify-pax-size-guard.sh --mode preserving`; bucket count `11`,
  pinfile count `11`, total artifact roots `1945`, no per-repo deltas.
- Passed PAX
  `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
  in `234.914s`.
- Passed PAX focused Bazel tests:
  `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test`.
- PAX worktree remained clean after migrate/build/test.
- Resource checks were run before expensive commands; Data volume stayed around `21 GiB` free.
  No `bazel-cache`, Gradle cache, private Bazel root, or process cleanup was needed.

## 2026-06-28 Final Review And Functional Gate

- Read-only verification audit found no generated-output/PAX drift, but correctly flagged the
  missing Item 21 functional-test gate.
- Passed `./gradlew :grazel-gradle-plugin:functionalTest --console=plain --no-daemon` in
  `5m 4s`.
- Functional tests left no generated fixture drift; `git status --short` still showed only Item
  21 source/test/docs changes and the new shared helper file.
- Read-only code-quality/altitude audit found no blocking findings. Checked hotspots:
  shared dependency identity helpers, Maven install filtering/override ordering,
  `compressionResults` input removal with preserved task dependency ordering, and removed
  internal/test-only surfaces.
