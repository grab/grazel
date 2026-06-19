# Dependency Refactor Task-Shape Goal

Status: implementation verified
Started: 2026-06-18

## Objective

Complete the next architecture slice of the dependency refactor: fix the
task-shape/altitude around workspace dependency inputs.

## Scope

- Introduce a master-like registrar/helper named around "workspace dependency
  inputs".
- The registrar should own configuration-time variant/topology traversal and
  workspace dependency input wiring, analogous to master
  `ResolveVariantDependenciesTask.register`.
- Move root-component/root-provider wiring out of
  `ComputeWorkspaceDependenciesTask`.
- Keep `ComputeWorkspaceDependenciesTask` thin: consume declared metadata JSON,
  KSP JSON, and Gradle root-component/provider inputs; compute workspace
  dependency model; write output.
- Prefer passing Gradle root component providers directly, master-style.
- Keep `CollectDeclaredDependencyMetadataTask` cheap: no resolved
  configurations, artifact views, or classpath resolution.
- Keep current KSP sidecar/global `ksp_maven`; ensure its task remains
  stable/cacheable/up-to-date.
- Preserve current generated output as much as practical.

## Non-Goals

- DAG/deeper bucket graph optimization.
- Precise test/androidTest hierarchy.
- Library-only/JVM-only root support.
- Broad compatibility cleanup or old-pipeline wording cleanup beyond what the
  task-shape change requires.
- Bucketed KSP.

## Initial State

- Branch: `arun/dependencies-refactor`, ahead of
  `public/arun/dependencies-refactor` by 3 at goal start.
- Untracked files at goal start:
  - `codedb.snapshot`
  - `reports/dependencies-refactor-architecture-interview.md`
- Architecture interview source:
  `reports/dependencies-refactor-architecture-interview.md`.
- Durable implementation baseline commit: `cc46ad7`.

## Decisions

- Name the new helper around "workspace dependency inputs", not "aggregated
  dependency roots".
- Use a master-like split: configuration-time registrar wires root providers;
  declared metadata JSON remains an execution-time/cache input, not the source
  of provider wiring.
- Keep global KSP sidecar for this slice because processor classpath/plugin
  label ownership is special.

## Progress Log

- Read goal prompt, `AGENTS.md`, current branch status, and architecture
  interview notes.
- Spawned read-only subagents for:
  - master/current task wiring comparison
  - current implementation/test change-point review
- Added `WorkspaceDependencyInputsRegistrar`, which now owns declared metadata
  task registration, KSP sidecar registration, and configuration-time
  variant/root-provider wiring.
- Moved variant/root wiring helpers out of `ComputeWorkspaceDependenciesTask`.
- Changed compute inputs from serialized `AggregatedDependencyRootSnapshot`
  strings to master-like live `ResolvedComponentResult` root providers plus
  stable root metadata JSON.
- Updated `AggregatedDependencyResolver` to consume live root components through
  `ResolvedComponentsVisitor` directly while preserving BOM filtering,
  repository/Jetifier metadata, project traversal, and exclude handling.
- Updated the compute task architecture guard test to reject the old snapshot
  input and require workspace dependency root components plus metadata.
- Strengthened functional coverage so no-edit `computeWorkspaceDependencies`
  runs assert `collectDeclaredDependencyMetadata`,
  `collectKspProcessorDependencies`, and `computeWorkspaceDependencies` are all
  `UP-TO-DATE`.
- Updated `reports/scripts/verify-default-task-graph.sh` to require the
  declared metadata and KSP sidecar tasks in the default dry-run graph.

## Verification Log

- `./gradlew :grazel-gradle-plugin:compileKotlin --console=plain` passed.
- `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest"
  --tests "com.grab.grazel.tasks.internal.ComputeWorkspaceDependenciesTaskTest"
  --console=plain` passed.
- `./gradlew computeWorkspaceDependencies --console=plain --build-cache` passed.
- Second `./gradlew computeWorkspaceDependencies --console=plain --build-cache`
  passed with `collectDeclaredDependencyMetadata`,
  `collectKspProcessorDependencies`, and `computeWorkspaceDependencies`
  `UP-TO-DATE`.
- Focused functional suite passed:
  `computeWorkspaceDependenciesDoesNotScheduleLegacyResolveTasksByDefault`,
  `computeWorkspaceDependenciesIsUpToDateWithoutInputChanges`,
  invalidation tests for declarations/project edges/excludes/KSP, and
  `migrateToBazelWithFlavorsWereUsed`.
- `./gradlew migrateToBazel --console=plain` passed.
- Re-ran the focused functional suite after restoring deterministic component
  sorting; it passed.
- `reports/scripts/verify-default-task-graph.sh` passed.
- `reports/scripts/verify-sample-bucket-labels.sh` passed.
- `bazelisk build //... --disk_cache= --strategy=KotlinKapt=sandboxed`
  passed.
- `git diff --check` passed.
- `./gradlew check --console=plain` failed at the known preexisting
  `:sample-android:lintDemoFreeDebug` `MissingConstraints` issue in
  `sample-android/src/main/res/layout/activity_main.xml:73`.
- Read-only subagent review found no blocking task-shape logic issues. The
  only blocker it flagged is bookkeeping: `WorkspaceDependencyInputsRegistrar.kt`
  is untracked and must be included in any commit.
- After checking `ResolveVariantDependenciesTask` history, restored the
  historical cacheable shape: `ResolvedComponentResult` root providers are
  task inputs, not `@Internal` side channels.

## Failures / Findings

- Direct root providers are now `@Input`, matching the earlier cacheable
  `ResolveVariantDependenciesTask` shape from history. Stable metadata remains
  an `@Input`; declared metadata and KSP JSON remain file inputs. Focused
  functional coverage verifies no-edit up-to-date behavior for the declared
  metadata task, KSP sidecar task, and compute task.
- First live-root resolver attempt changed `androidx.fragment:fragment` bucket
  ownership more than expected because duplicate same-version components were
  merged in DFS order. Restored the old deterministic component sort inside the
  resolver while keeping live root provider inputs.
- Remaining generated diff after regeneration removes duplicate
  `androidx.fragment:fragment` entries from `free_maven` and `paid_maven`; the
  dependency remains in common `maven`. Shell verifiers and Bazel build pass,
  so this is recorded as reasonable bucket movement for the new baseline.

## Remaining Risks

- Property-backed dependency declarations outside the current tracked Gradle
  script/catalog input set remain a possible sidecar invalidation gap.
- Full `./gradlew check` still fails on the known preexisting sample Android
  lint `MissingConstraints` issue outside this refactor.
