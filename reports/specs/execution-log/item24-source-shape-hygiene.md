# Item 24: Branch-Diff Source Shape Hygiene

## Status

- Active after local Grazel commit `468dd5f` (`refactor: move workspace root inputs into variant layer`).
- Grazel branch: `arun/dependencies-refactor`; changes stay local and must not be pushed.
- PAX baseline workspace: `/Users/arun.sampathkumar/work/pax-android` on
  `arun/grazel-refactor` at `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`; do not commit PAX.
- Item 24 implementation and verification are complete and locally committed
  (`refactor: tidy dependency refactor source shape`). Do not push.

## Inventory

Deterministic inventory command:

```text
git diff --name-only --diff-filter=ACMR master...HEAD -- '*.kt'
```

Initial inventory count:

- 132 changed Kotlin files total.
- 85 production Kotlin files.
- 45 unit-test Kotlin files.
- 2 functional-test Kotlin files.

Cluster counts:

- `grazel-gradle-plugin/src/main/kotlin/com/grab`: 85 files.
- `grazel-gradle-plugin/src/test/kotlin/com/grab`: 45 files.
- `grazel-gradle-plugin/src/functionalTest/kotlin/com/grab`: 2 files.

## Decisions

- Item 24 remains preserving: no dependency-resolution, bucket-placement, reachability,
  rendering, or pinner behavior changes are allowed.
- PAX generated baseline must stay unchanged; any generated diff stops the cleanup.
- Policy-heavy collection/map extension receivers should be converted to explicit functions
  when the receiver hides a role name that matters for review.
- Existing problem-essential bucket set-math may stay algorithmically intact, but its source
  shape can still improve through explicit parameter names and clearer local model names.
- Reflection/source-string tests are to be removed when a typed API can assert the same
  contract. If reflection is the only practical way to assert Gradle annotation surface, keep
  it only with a documented reason.

## Subagent Audit

- Dependency/variant audit agent completed.
- Migrate/render audit agent completed.
- Task-layer audit agent completed.
- Test-scope audit agent completed after closing stale completed agents.

Parent reconciliation rules:

- Fix in Item 24 only when output-preserving and scoped to source/test shape.
- Defer behavior/model redesign findings to the roadmap when they require output-changing or
  architecture-slice work.
- Spot-check subagent claims before applying patches.

## Initial Mechanical Findings

- Policy-heavy private collection receivers found in:
  `BucketOwnershipPlanner.kt`, `DeclaredDependencyMetadataCollector.kt`,
  `MavenInstallRootArtifacts.kt`, `DependencyBucketPlacementEngine.kt`,
  `TargetReferenceFactsExtractor.kt`, `BucketHierarchyGraph.kt`, and related helpers.
- Internal collection/model extension receivers found in:
  `BucketSetMath.kt`, `DependencyBucketPlacementEngine.kt`,
  `TargetReferenceFactsCollector.kt`, `DeclaredDependencyMetadataCollector.kt`,
  `ResolveDependenciesResult.kt`, and migrate helpers.
- Reflection-like tests found in task annotation tests and `DependenciesDataSourceTest`.
- Historical/TODO/comment hygiene candidates found in dependency service comments, variant
  comments, test names, and Bazel 8 workaround comments. Only branch-artifact comments should
  be removed in this item; real product TODOs stay unless they obscure the current model.

## Reconciliation

Fixed in this slice:

- `BucketOwnershipPlanner.kt`
  - Converted `withDeclaredMetadataByBucket`, `withDeclaredMetadata`,
    `plannedTestBuckets`, `withoutTestDependenciesCoveredBy`,
    `withoutTestDependenciesCoveredByEveryLeaf`,
    `withoutMergedBaseTestDependenciesCoveredBy`,
    `scopedSiblingClosureDependenciesByShortId`, and
    `withoutDeclaredPlaceholdersCoveredByDefault` from policy-heavy generic receivers to
    explicit role-named parameters.
  - Kept the bucket set-math algorithm unchanged; this is source shape only.
- `DependencyBucketPlacementEngine.kt`
  - Renamed `DependencyBucketVariant` to `BucketPlacementVariantInput` to make its role as
    planner input explicit instead of sounding like a Gradle variant model.
- `AggregatedDependencyResolver.kt`
  - Replaced `String.toDeclaredProjectDependencyEdge()` with
    `parseDeclaredProjectDependencyEdge(encodedEdge)` so the encoded-edge role is named.
- `WorkspaceKspConfigurations.kt` / `CollectKspProcessorDependenciesTask.kt`
  - Renamed the mutating `Project.workspaceKspProcessorClasspath()` extension and
    `WorkspaceKspProcessorClasspath` model to explicit
    `createWorkspaceKspProcessorClasspath(project)` and
    `WorkspaceKspProcessorClasspathResult`.
- `DependencyResolutionService.kt`, `ResolveDependenciesResult.kt`,
  `ExperimentsExtension.kt`
  - Reworded history-heavy comments into current behavior contracts.
- `DefaultDependenciesDataSourceTest.kt`
  - Typed `dependencyResolutionService` as `DefaultDependencyResolutionService` and removed
    repeated casts.
- `BuildVariantTest.kt`
  - Replaced raw `dependencies.json` shortId/version string checks with structural JSON
    helpers. One failed rerun exposed that KSP processor data lives under `aggregatedRepos`;
    fixed by letting the version helper include aggregated repos when explicitly requested.
- `SourcePathTest.kt`
  - Renamed the vague `migrateToBazelWithAssert` test and collapsed duplicate assets helpers.

Retained / deferred with rationale:

- `BucketSetMath.kt` internal extension functions remain. Item 22 proved this set-math
  problem-essential for current PAX/sample behavior; converting all internal extensions here
  would be broad churn without improving the algorithmic contract.
- `DeclaredDependencyMetadataCollector.kt` configuration/string helpers remain. They are
  variant/configuration-shape code and broader typed declared-edge work, not a safe Item 24
  hygiene patch.
- `MavenInstallRootArtifacts.kt`, target-reference reachability, target builder policy, and
  Maven renderer/pinner text contracts remain. Subagents flagged them, but they are medium-risk
  architecture moves that overlap Item 25/27/future renderer/model work.
- Task annotation tests still use JVM reflection for Gradle task API annotations. Replacing
  them with real task input/output inspection is desirable, but it is a separate test
  harness/caching-semantics cleanup; keeping them avoids weakening current cacheability guards.
- `AggregatedDependencyResolverTest.failingRootComponent()` still uses a dynamic proxy.
  A typed fake would need to implement Gradle's full `ResolvedComponentResult` surface or
  subclass internal Gradle classes; that is more brittle than the one localized failure-path
  proxy in this preserving item.
- `ExperimentsExtension.limitDependencyResolutionParallelism` remains as an explicit DSL
  compatibility contract.

## Verification

- Focused dependency planner tests passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.BucketOwnershipPlannerTest" --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --tests "com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependenciesTest" --console=plain --no-daemon`.
- Focused rename/parser tests passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.DependencyBucketPlacementEngineTest" --tests "com.grab.grazel.gradle.dependencies.BucketOwnershipPlannerTest" --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --console=plain --no-daemon`.
- Focused unit test batch passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.tasks.internal.CollectKspProcessorDependenciesTaskTest" --tests "com.grab.grazel.gradle.dependencies.DependencyBucketPlacementEngineTest" --tests "com.grab.grazel.gradle.dependencies.BucketOwnershipPlannerTest" --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --tests "com.grab.grazel.gradle.DefaultDependenciesDataSourceTest" --tests "com.grab.grazel.extension.ExperimentsExtensionTest" --console=plain --no-daemon`.
- Functional test rerun:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest" --tests "com.grab.grazel.migrate.SourcePathTest" --console=plain --no-daemon`
  first failed in `computeWorkspaceDependenciesInvalidatesWhenKspDependencyChanges` because the
  structural helper read only `result` while the old raw string check also saw `aggregatedRepos`.
  After fixing the helper, the exact command passed in 3m14s.
- Full plugin unit suite passed:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`.
- Grazel generation passed:
  `./gradlew migrateToBazel --console=plain --no-daemon`.
- `reports/scripts/verify-default-task-graph.sh` passed.
- `reports/scripts/verify-sample-bucket-labels.sh` still hits the known pre-existing
  one-sided appcompat/constraintlayout exclude waiver; no new Item 24 failure was introduced.
- `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed unchanged:
  11 buckets, 11 pinfiles, 1945 total artifact roots.
- `git diff --check` and `git diff --check master...HEAD` passed.
- PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
  passed in 12m24s. PAX `git status --short` stayed clean and PAX `git diff --check`
  passed; the local PAX baseline was not committed or changed.
- Resource notes: disk was tight after verification cache growth. Ran `bazelisk shutdown`
  and `bazelisk clean --expunge` in Grazel, then the same in PAX, before the final PAX
  migrate. This restored free space to roughly 30 GiB. Did not delete PAX `bazel-cache`.

## Remaining Work

- Continue to Item 27 after the local checkpoint.
