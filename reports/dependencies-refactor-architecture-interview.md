# Dependency Refactor Architecture Interview

Status: active architecture notes for the next goal. Use this file to capture
open architecture items, decisions from discussion, and execution guidance. The
merge-readiness work is durably committed at `cc46ad7`.

## Purpose

The previous goal proved the aggregated dependency refactor can generate and
build a valid Bazel graph. This document is for the next architecture pass
before considering the branch mergeable.

## Pending Architecture Items

1. Root component handoff and task split
   - Implemented in the task-shape goal: `WorkspaceDependencyInputsRegistrar`
     wires Gradle `ResolvedComponentResult` root providers plus stable metadata
     JSON into `ComputeWorkspaceDependenciesTask`.
   - `AggregatedDependencyRootSnapshot` was removed; root providers are direct
     task inputs, matching the earlier cacheable `ResolveVariantDependenciesTask`
     shape from history.
   - Do not reintroduce bespoke aggregated-root snapshots unless later
     verification proves a concrete need.

2. Declared metadata as cheap variant fanout
   - Keep declared metadata collection cheap: no resolved configurations,
     artifact views, or per-variant classpath resolution.
   - Validate that excludes, compileOnly, project edges, declared coordinates,
     and configuration names are captured at the same altitude as variants.
   - Decision: this should become the canonical cheap variant-driven metadata
     layer. Preserve the old architecture altitude where variants drive later
     tasks/workspace generation, but replace expensive per-variant resolution
     with declared-only collection.

3. KSP bucketing
   - Current milestone uses global `ksp_maven`.
   - Future architecture can model KSP as a bucketed scope with processor class
     metadata. Identical `debugKsp` / `releaseKsp` should collapse upward;
     differing KSP deps should produce variant KSP repos.

4. Test/androidTest modeling
   - Current behavior is intentionally simpler.
   - Need decide whether test/androidTest should use the same variant hierarchy
     / bucket graph as main, driven by `Variant.extendsFrom`.

5. Variant graph vs tree
   - Current bucketing uses conservative hierarchy/set-intersection logic.
   - Decision: an explicit bucket DAG/planner is the better long-term model for
     correctness and code complexity, but should be introduced narrowly after
     the task-shape commit. It should not replace `ResolvedComponentsVisitor`;
     the visitor extracts resolved graph facts, while the bucket DAG places
     those facts into repos.
   - First milestone should cover main buckets only: default, build type,
     flavor, and leaf. Defer test/androidTest precision, bucketed KSP, and
     library-only roots.

6. Bucket ownership identity
   - Same artifact with different versions must remain identity-aware.
   - Bucket movement must be based on effective identity, not only shortId.

7. Library-only/JVM-only repos
   - Current architecture expects an app or `com.android.test` edge node.
   - Need define whether Grazel requires explicit edge nodes or supports
     synthetic roots for library-only repos.

8. Generated repo/plugin label ownership
   - Normal Maven deps use bucket repo names.
   - KSP plugin rules currently assume `@ksp_maven`; future bucketed KSP needs
     labels to follow the owning KSP repo.

9. Task altitude and cache boundaries
   - Desired layering:
     1. enumerate variants/configuration topology
     2. collect cheap declared metadata
     3. collect or pass expensive binary resolution roots
     4. compute bucketed workspace model from task inputs
     5. generate, pin, and verify Bazel outputs

10. Old path deletion validation
    - Old per-project/per-variant fanout is deleted.
    - Need ensure names, comments, reports, tests, and services no longer imply
      the old pipeline still exists.

11. Aggregation semantics
    - Need concise final architecture statement:
      binary-root resolved closure is source of truth for versions,
      transitives, repositories, Jetifier, and buildability; declared metadata
      supplies ownership/excludes/compileOnly/project-edge hints; bucketing
      derives common and variant repos from those inputs.

12. Performance architecture
    - Current mode avoids old fanout but still needs clarity on where Gradle
      resolution is expected to happen and what Gradle can cache for us.

13. MR-level architecture doc
    - The worklog is too long for review.
    - Prepare a concise architecture note explaining the final pipeline,
      accepted deferrals, and verification strategy.

## Decisions Captured

- Keep the current global/shared `ksp_maven` for the committed milestone.
- Treat proper bucketed KSP as a future architecture item, not a blocker for
  the current durable commit.
- Because KSP has special handling through processor classpath/plugin label
  ownership, keep the current KSP sidecar pipeline for the next architecture
  cleanup goal. Requirement: the KSP collection task must remain stable and
  cacheable/up-to-date, and the cleanup must not block future bucketed KSP.
- `CollectDeclaredDependencyMetadataTask` should be the canonical cheap
  variant metadata layer. It should enumerate project -> variant ->
  configuration topology through `VariantBuilder`/`Variant.kt`, collect only
  declared metadata, and avoid all classpath resolution APIs.
- Maintain layering altitude from the old design: variants drive metadata,
  dependency root wiring, workspace computation, and generation. Do not let
  `ComputeWorkspaceDependenciesTask` become an ad hoc variant/topology builder
  if that data can come from the variant-driven metadata layer.
- Root wiring should be master-like: a dedicated variant-driven
  registrar/helper, analogous to master `ResolveVariantDependenciesTask.register`,
  owns variant/topology traversal and root-provider wiring. Do not bury this
  inside `CollectDeclaredDependencyMetadataTask.register`, and do not keep it as
  ad hoc enumeration inside `ComputeWorkspaceDependenciesTask`.
- Test/androidTest precise hierarchy is deferred as a longer-term goal. The next
  architecture cleanup should preserve room for old-style `Variant.extendsFrom`
  modeling, but does not need to fully implement precise test bucket hierarchy.
- Library-only/JVM-only repository support is future work. This slice assumes an
  app or `com.android.test` binary edge node is available as the resolution root.
- Bucket graph/DAG modeling is likely the immediate follow-up after the
  master-like task graph cleanup lands, but it is not necessarily part of the
  current cleanup slice unless the task graph changes make it unavoidable.
- Compatibility cleanup and deeper bucketing should come after the fundamental
  task-shape architecture cleanup. For now, keep source compatibility where it
  is already retained and avoid mixing cleanup/bucketing churn into the task
  graph correction.
- Name the new registrar/helper around "workspace dependency inputs", not
  "aggregated dependency roots", because its role is to assemble the inputs for
  workspace dependency computation while preserving master-like variant/task
  layering.
- `docs/superpowers/` is ignored locally.
- Verification should include `bazelisk build //... --disk_cache=
  --strategy=KotlinKapt=sandboxed`; the empty `--disk_cache=` is intentional to
  avoid relying on user/default disk cache.
- `ResolvedComponentResult` root providers should be `@Input` on the cacheable
  compute task. History shows the earlier cacheable `ResolveVariantDependenciesTask`
  used root components as task inputs before the later resolved-graph-GC change.
- `ResolvedComponentsVisitor` remains the right low-level Gradle graph walker.
  The follow-up DAG work should consume visitor output and declared metadata; it
  should not reimplement Gradle graph traversal.

## Current Interview Thread

### Next Goal Scope Boundary

Decision:
- The next goal is strictly the fundamental task-shape architecture cleanup:
  introduce a master-like registrar/helper for variant-driven root wiring, move
  root wiring out of `ComputeWorkspaceDependenciesTask`, keep declared metadata
  and KSP tasks stable/cacheable, and preserve current generated output as much
  as practical.
- Use "workspace dependency inputs" as the naming frame for the new
  registrar/helper.
- Explicit non-goals for that slice:
  DAG/deeper bucketing, precise test/androidTest hierarchy, library-only/JVM-only
  support, and broad compatibility/old-pipeline cleanup.
- Those follow-ups can happen after the task graph shape is corrected and
  verified.

### Root Component Handoff

Question:
- On master, `ResolvedComponentResult` root components were passed into tasks
  and Gradle handled cacheability. Does that change whether we should split root
  snapshot production into a separate task?

Notes:
- Need compare the old `ResolveVariantDependenciesTask` root-provider pattern
  against the current `ComputeWorkspaceDependenciesTask` snapshot-input pattern.
- Need decide if the desired architecture is:
  1. pass root components directly to compute, plus JSON from declared metadata
     collectors; or
  2. create a dedicated task that serializes root snapshots; or
  3. keep current serialized snapshot provider mapping until a measured
     performance issue appears.

Decision:
- Prefer the master-style root-component provider handoff first. Do not create
  a separate aggregated-root snapshot task solely for cacheability unless
  verification proves Gradle cannot reuse/cache the root-component resolution
  shape well enough.
- Keep cheap collector outputs as JSON/serializable metadata because those are
  Grazel-specific ownership hints, not Gradle resolution output.
- Treat the current bespoke `AggregatedDependencyRootSnapshot` shape as a
  merge-readiness implementation detail to revisit, not necessarily the final
  architecture.
- The architecture target is likely:
  `ComputeWorkspaceDependenciesTask` receives root-component providers plus
  declared metadata/KSP JSON, then computes buckets from those inputs without
  restoring the old per-project/per-variant fanout.

### Declared Metadata Layer

Decision:
- The declared metadata task is not just an invalidation helper. It should be
  the cheap, canonical variant-driven metadata layer.
- It should preserve the old layering model:
  project -> variants -> configurations -> later tasks -> workspace generation.
- It should collect only cheap declared data: excludes, compileOnly,
  project edges, declared external coordinates, configuration names, variant
  hierarchy names, build type/flavor/test topology, and KSP declaration names.
- It must not call resolution APIs such as `resolvedConfiguration`,
  `incoming.resolutionResult`, or artifact views.

Execution guidance:
- Move topology decisions out of `ComputeWorkspaceDependenciesTask` where
  practical and into the metadata produced by `CollectDeclaredDependencyMetadataTask`.
- `ComputeWorkspaceDependenciesTask` should wire/consume inputs and compute
  bucket ownership; it should not independently rediscover topology that the
  variant layer already knows.

Master comparison:
- On `public/master`, `ComputeWorkspaceDependenciesTask` is a thin reducer. It
  owns `compileDependenciesJsons`, writes `dependencies.json`, and delegates
  variant/task wiring to `ResolveVariantDependenciesTask.register`.
- `ResolveVariantDependenciesTask.register` owns the variant-driven layer:
  after evaluate, it gets `VariantBuilder`, iterates projects/variants, first
  creates per-variant tasks, then makes a second pass to wire
  `Variant.extendsFrom` task dependencies.
- That is the layering to preserve, but with a cheaper data model:
  `CollectDeclaredDependencyMetadataTask` should own variant/topology metadata
  collection, while aggregated root wiring should be driven by the same
  variant/topology layer instead of ad hoc enumeration inside compute.

Follow-up architecture target:
- Refactor current `ComputeWorkspaceDependenciesTask.configureAggregatedDependencyRoots`
  into a variant-driven registration/helper layer analogous to master
  `ResolveVariantDependenciesTask.register`.
- Keep `ComputeWorkspaceDependenciesTask` thin: consume declared metadata JSON,
  KSP JSON, and root-component providers/metadata; run the resolver; write
  workspace dependencies.

### Root Wiring Ownership

Decision:
- Use a dedicated master-style registrar/helper for root wiring.
- The helper should own the configuration-time variant/topology traversal,
  create the root-component provider inputs, and attach those inputs to
  `ComputeWorkspaceDependenciesTask`.
- Keep `CollectDeclaredDependencyMetadataTask` focused on cheap declared
  metadata production, and keep `ComputeWorkspaceDependenciesTask` focused on
  reducing task inputs into the workspace dependency model.

### Metadata / Root Spec Contract

Decision:
- Use the master-like split.
- Root provider wiring is derived directly from configuration-time
  variant/topology traversal by the new registrar/helper.
- Do not make root-provider wiring depend on reading the declared metadata JSON:
  that file is produced at task execution time, after configuration-time wiring
  has already happened.
- The declared metadata JSON may include the same root/variant/configuration
  identities for task inputs, debugging, and cache invalidation, but it should
  not be the authoritative source for wiring Gradle `rootComponent` providers.
- The preferred implementation shape is shared variant/topology construction:
  both root wiring and declared metadata collection should be driven from the
  same model/pattern, matching the old master altitude, without making either
  task rediscover topology independently.

## Next Questions

Use these to resume the interview after compaction. Ask the user one topic at a
time and record decisions here before execution.

1. Root wiring ownership
   - Should root wiring live in a new master-style registrar/helper, or inside
     `CollectDeclaredDependencyMetadataTask.register`?
   - Decision: use a dedicated variant-driven helper/registrar analogous to
     master `ResolveVariantDependenciesTask.register`, so the metadata task
     stays cheap and compute stays thin.

2. Root component input shape
   - Should the next goal remove `AggregatedDependencyRootSnapshot` and return
     to passing Gradle `rootComponent` providers plus root metadata JSON?
   - Current decision: yes, prefer master-style root-component provider handoff
     first; separate snapshot task only if verification proves it is required.

3. Metadata/root spec relationship
   - Should declared metadata produce explicit root specs, or only enough
     topology for a separate root-wiring helper to derive specs?
   - Decision: master-like split. Root specs/providers are derived by the
     configuration-time registrar; declared metadata JSON can contain matching
     identities as execution-time input/debug metadata, but must not drive
     provider wiring.

4. KSP scope
   - Should KSP remain explicitly deferred as global `ksp_maven` for the
     architecture cleanup goal?
   - Decision: yes. Keep the current global `ksp_maven` sidecar for this goal
     because KSP has special processor classpath/plugin-label handling. Ensure
     the KSP collection task remains stable/cacheable/up-to-date, and do not
     make choices that block future bucketed KSP.

5. Test/androidTest hierarchy
   - Should test/androidTest hierarchy alignment be included in the next goal or
     deferred?
   - Context: old variant API modeled tests via `Variant.extendsFrom`; current
     behavior is simpler and has passed focused samples, but deeper precision is
     not fully reasoned.
   - Decision: defer as a longer-term goal. The next architecture cleanup should
     preserve the ability to implement test/androidTest hierarchy with
     `Variant.extendsFrom`, but does not need to fully solve precise test bucket
     ownership unless a concrete build/generation failure appears.

6. Library-only/JVM-only repos
   - Is support for repos without app or `com.android.test` edge nodes out of
     scope until the binary-edge architecture is clean?
   - Decision: out of scope for this slice. Current architecture may require an
     app or `com.android.test` binary edge node as the resolution root; support
     for library-only/JVM-only repos is future work.

7. Bucket graph/DAG
   - Should DAG modeling remain deferred until the restored master-style
     layering proves insufficient?
   - Decision: DAG/bucket graph modeling is the right immediate next goal after
     the master-like task graph changes land. Keep it narrow: introduce a pure
     bucket planner for main variants first, preserve broad bucketing where
     needed, and defer precise test/androidTest hierarchy and bucketed KSP.
   - Reasoning: current map/intersection logic is already an implicit DAG with
     special cases. A small explicit planner should make cases like
     `implementation x:y:1.0` plus `debugImplementation x:y:2.0` easier to
     model as closest-owner placement.

8. Compatibility cleanup
   - Is the current DSL compatibility story final?
   - Current state: `aggregatedDependencyResolution` removed;
     `limitDependencyResolutionParallelism` remains as deprecated no-op.
   - Decision: deeper compatibility cleanup and old-pipeline wording cleanup
     should happen after the fundamental task-shape architecture is fixed.
     Keep current compatibility posture for this slice:
     `aggregatedDependencyResolution` removed because new behavior is default,
     and `limitDependencyResolutionParallelism` remains deprecated no-op.

9. MR architecture note
   - After decisions settle, prepare a concise MR-facing architecture doc that
     replaces the long worklogs for reviewers.

## Resume Instructions

- Do not reread the full archival `dependencies-refactor-goal-log.md` unless
  debugging historical intent.
- Start from this file, the latest commit `cc46ad7`, and current `git status`.
- Continue the interview before implementation. Capture each decision in this
  file first.
- The next implementation goal should likely be architecture cleanup around
  task altitude/root wiring, not another broad behavior refactor.

## Next Goal Prompt Draft

Use this for the next `/goal` after committing the task-shape slice:

```
We are in /Users/arun.sampathkumar/work/grazel on branch arun/dependencies-refactor.
Read AGENTS.md, reports/dependencies-refactor-architecture-interview.md, and
reports/dependencies-refactor-task-shape-goal.md first. The task-shape slice is
committed: ComputeWorkspaceDependenciesTask is thin, WorkspaceDependencyInputsRegistrar
wires cacheable @Input ResolvedComponentResult root providers plus metadata JSON,
and ResolvedComponentsVisitor still extracts resolved graph facts.

Goal: introduce the next architecture slice for dependency bucketing: a narrow,
pure bucket graph/DAG planner for main variant buckets. Do not replace
ResolvedComponentsVisitor. Visitor output remains the resolved graph fact source;
declared metadata supplies variant topology, excludes, compileOnly, project-edge,
and ownership hints; the new planner decides repo placement.

Primary correctness target: model closest-owner bucket placement for default,
build type, flavor, and leaf buckets. Example:
implementation "x:y:1.0" plus debugImplementation "x:y:2.0" should place
x:y:1.0 in maven and x:y:2.0 in debug_maven, with leaf buckets only getting
residual deps. Same artifact with different versions must remain identity-aware.

Scope:
- Add focused unit tests for the bucket planner before broad rewrites.
- Keep generated-output diff explainable and verify with existing generated
  BUILD/WORKSPACE baseline.
- Preserve current task shape, KSP global ksp_maven sidecar, and binary-root
  resolution requirement.
- Defer precise test/androidTest hierarchy, bucketed KSP, library-only/JVM-only
  support, and old-path cleanup unless a failing case forces a small change.

Verification should include focused unit/functional tests, migrateToBazel,
reports/scripts/verify-default-task-graph.sh,
reports/scripts/verify-sample-bucket-labels.sh, git diff --check, and
bazelisk build //... --disk_cache= --strategy=KotlinKapt=sandboxed. Note that
full ./gradlew check currently fails on the known preexisting sample Android
lint MissingConstraints issue.
```
