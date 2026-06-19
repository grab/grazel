# Dependency Refactor DAG Bucketing Goal

Status: in progress
Started: 2026-06-19

## Objective

Evolve MAIN dependency bucket placement from the current fixed-order planner to
a master-like graph/DAG planner while preserving fast aggregated root
resolution.

## Scope

- Preserve task altitude:
  `WorkspaceDependencyInputsRegistrar` wires Gradle root providers and metadata,
  `ComputeWorkspaceDependenciesTask` stays thin, `AggregatedDependencyResolver`
  extracts resolved dependency facts, and the planner owns MAIN placement.
- Do not restore old per-project/per-variant resolution.
- Keep declared metadata cheap/provider-backed JSON; do not resolve it.
- Preserve explicit hierarchy roots from Gradle declarations.
- Preserve identity-sensitive dedupe using `hasSameBucketOwnerAs` and
  `hasSameEffectiveIdentityAs` semantics.
- Keep KSP as the current cacheable global sidecar.
- Fix obvious MAIN coverage bugs for test/androidTest subtraction when they are
  directly adjacent to MAIN planner output.
- Keep library-only/JVM-only roots, broad compatibility cleanup, and old-code
  deletion out of scope.

## Graph Representation Decision

Use a local immutable adjacency model, not Guava graph APIs, for this slice.

Reasons:
- The current graph is small and domain-specific: default, build-type, flavor,
  and leaf MAIN bucket nodes.
- The planner needs bucket-specific operations such as descendant leaf closure,
  explicit hierarchy ownership, depth, and nearest valid owner selection.
  Local names make those rules clearer than generic graph traversal calls.
- No new dependency is needed. Guava is allowed and already present, but the
  extra API surface does not currently reduce complexity.
- If later work introduces richer composite source-set nodes or cross-project
  graph joins, this decision can be revisited.

Known assumption:
- Output repositories are still global bucket names, so the planner may keep
  global bucket output while using more explicit internal graph modeling. Any
  remaining name-collision assumption must be documented in implementation
  notes.

## Initial State

- Baseline commit: `abe0940 Extract main dependency bucket planner`.
- Worktree at goal start is clean except untracked `codedb.snapshot`.
- Current `MainDependencyBucketPlanner` already separates MAIN placement from
  resolved fact extraction, but still places buckets in fixed order:
  default, build type, flavor, then leaf.
- Latest accepted generated movement: `androidx.fragment:fragment` moved into
  `free_maven`.

## Planned Milestones

1. Tests first:
   - graph closest-owner placement, not fixed buildType-before-flavor priority
   - common dependency deduped to nearest valid ancestor
   - same shortId with different versions stays in separate nearest buckets
   - same shortId/version with different excludes does not collapse
   - filtered single-leaf deps do not promote to default
   - MAIN leaf buckets are counted as covered for test/androidTest subtraction
     where applicable
2. DAG model:
   - Build a typed MAIN bucket graph abstraction from
     `DeclaredDependencyMetadata.mainBucketVariants()` plus leaf and hierarchy
     closure keys.
   - Model at least default, build type, flavor, and leaf nodes.
   - Avoid project-topology loss where practical; document any remaining global
     bucket-name assumption.
3. Placement:
   - Replace default/buildType/flavor/leaf hardcoding with graph-derived
     placement.
   - For each dependency bucket-owner identity, choose the deepest/nearest
     valid owner covering all needed descendant leaves.
   - Explicit hierarchy closures win.
   - Inferred buckets need enough descendant evidence; do not promote from one
     surviving filtered leaf unless explicit.
   - Leaf residuals are dependencies no ancestor can own.
4. Integration/output:
   - Keep `AggregatedDependencyResolver` as fact extractor and delegate MAIN
     placement to the planner.
   - Run root migration, inspect generated diffs, and explain any bucket moves.
5. Verification/commit:
   - Run the required Gradle, script, Bazel, and diff checks.
   - Commit all aligned changes, excluding `codedb.snapshot` and unrelated
     artifacts.

## Checkpoints

### 2026-06-19 - Goal Start

Decision:
- Use local immutable adjacency for the DAG planner rather than Guava graph
  APIs.

Subagents:
- Spawned read-only explorers for variant/master hierarchy, test edge cases,
  and generated-output baseline. Summaries will be recorded after they return.

Next action:
- Add RED pure planner tests before production planner changes.

### 2026-06-19 - Planner Graph First Slice

Subagent audit summary:
- Variant/master hierarchy audit confirmed the current inputs already expose
  `Variant.name + extendsFrom`: Android MAIN leaves extend `default`, flavors,
  and build type; non-leaf build-type/flavor buckets extend `default`.
- The same audit recommended local adjacency because planner rules need
  domain-specific operations: explicit-owner precedence, descendant leaf
  closure, deepest valid owner selection, and identity-sensitive comparison.
- Test audit identified an adjacent correctness bug:
  `MainDependencyBucketPlan.coveredDependencies()` omitted leaf MAIN buckets,
  so test/androidTest subtraction could leak deps already owned by MAIN leaf
  buckets.
- Generated-output audit established `abe0940` as the DAG baseline. Suspicious
  future diffs include debug-only paging moving to `maven`, free/paid
  `constraintlayout` moving to default or the wrong flavor bucket, androidTest
  bucket inversions, lint version downgrades, lost excludes, and unexplained pin
  checksum/version churn.

RED tests:
- Added `explicit deeper hierarchy bucket wins over inferred ancestor bucket`.
  The current fixed-order planner put the dependency into inferred `debug`
  instead of the explicit deeper `free` bucket.
- Added `leaf main buckets are included in covered dependencies`. The current
  plan coverage omitted leaf buckets.
- The focused planner test command failed at those two tests before production
  changes:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.MainDependencyBucketPlannerTest" --console=plain`.

Implementation:
- Replaced fixed buildType-then-flavor placement with local adjacency-driven
  hierarchy selection.
- Explicit non-default hierarchy buckets are selected first and keep their
  precedence.
- Inferred hierarchy buckets are selected from graph nodes using descendant
  leaf evidence and graph depth.
- Leaf residuals subtract selected transitive ancestors, not only immediate
  parents.
- `coveredDependencies()` now includes leaf buckets.
- Added an identity guard for same `shortId` and version with different excludes
  staying in separate nearest buckets.

Verification so far:
- GREEN:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.MainDependencyBucketPlannerTest" --console=plain`
  passed after implementation.

Risks / next action:
- Output repos are still global bucket names. The planner uses local adjacency
  internally, but project/name/type collisions remain a documented assumption
  for this slice.
- The current graph models default, build type, flavor, and leaf nodes. Richer
  composite source-set buckets remain a follow-up unless a generated diff or
  fixture proves they are required for this goal.
- Run the required unit, functional, generation, verifier, and Bazel checks.

### 2026-06-19 - Audit Fixes and Final Verification

Generated-output audit:
- The first regenerated output moved `androidx.constraintlayout:constraintlayout`
  labels from `free_maven`/`paid_maven` to `demo_maven`/`full_maven`, and shrank
  `free_maven_install.json`/`paid_maven_install.json` heavily. This exposed that
  peer flavor buckets with partial overlap were suppressing each other too
  aggressively.
- After removing partial-overlap suppression, `verify-sample-bucket-labels.sh`
  then caught the opposite bug: debug-only `androidx.paging:paging-runtime`
  appeared as a direct dependency in a flavor repo. This exposed that a selected
  peer bucket that covers all candidate leaves should suppress the candidate.
- The final regenerated output has no generated-file diff. The committed slice
  is source/test/log only.

Additional planner audit findings:
- A selected hierarchy bucket whose name is a leaf variant, such as
  `freeDebug`, could be selected and then dropped because the plan only emitted
  build-type and flavor hierarchy buckets. This matters for cheap declared
  metadata such as compileOnly buckets.
- Explicit hierarchy buckets were processed shallowest-first, allowing an
  explicit ancestor to remove the dependency from an explicit descendant.

Additional RED tests:
- `explicit deeper hierarchy bucket wins over explicit ancestor bucket`
- `leaf named hierarchy bucket is emitted as leaf bucket`
- `overlapping peer flavor buckets keep independent ownership`
- `selected peer bucket covering all candidate leaves suppresses candidate bucket`

Implementation:
- Explicit hierarchy buckets are processed deepest-first so explicit descendant
  ownership wins over explicit ancestors.
- Selected peer buckets no longer suppress candidates merely because they share
  some leaves. Suppression is limited to graph ancestor/descendant relationships
  or strict descendant-leaf coverage, where the selected bucket covers every
  leaf the candidate would cover.
- Selected leaf-named hierarchy buckets are emitted through `leafBuckets` and
  included in `coveredDependencies()`.

Verification:
- Passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.MainDependencyBucketPlannerTest" --console=plain`
- Passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --tests "*Bucket*" --console=plain`
- Passed:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain`
- Passed:
  `./gradlew migrateToBazel --console=plain`
- Passed:
  `bash reports/scripts/verify-sample-bucket-labels.sh`
- Passed:
  `bash reports/scripts/verify-default-task-graph.sh`
- Passed:
  `bazelisk build //... --disk_cache= --strategy=KotlinKapt=sandboxed`
- Passed:
  `git diff --check`

Remaining follow-up:
- Richer composite source-set bucket output remains future work. This slice
  still models and emits default, build-type, flavor, and leaf buckets only.
- Global bucket-name assumptions remain documented; project-qualified bucket
  identity is not changed in this slice.
