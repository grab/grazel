# Dependency Refactor Merge-Readiness Goal

Status: merge-readiness criteria met as of 2026-06-18 04:30 +08. The legacy
per-project/per-variant resolution path has been removed from production
wiring.

Authority: this file is the active plan for the next goal. The previous goal log
`reports/dependencies-refactor-goal-log.md` is archival only and should not
override this plan. Do not read the full previous goal log unless debugging
historical intent.

Baseline commit: `8cddc61 Harden aggregated dependency metadata buckets`.

Baseline rule: generated Bazel outputs from the baseline commit are the starting
point. Reasonable bucket moves are allowed when they are explained, tested, and
build verified; after acceptance they become the new baseline.

## Current Baseline Summary

- Aggregated dependency resolution is now unconditional. The
  `aggregatedDependencyResolution` DSL flag and `ResolveVariantDependenciesTask`
  path have been removed.
- Default `computeWorkspaceDependencies` / `migrateToBazel` no longer schedules
  the old per-project/per-variant `*ResolveDependencies` fanout.
- Current output is build-verified with root `migrateToBazel`, focused verifier
  scripts, focused Gradle tests, and `bazelisk build //... --disk_cache=
  --strategy=KotlinKapt=sandboxed`.
- Broad milestone buckets are accepted for now: duplicate deps across leaf
  buckets are less important than correctness and merge readiness.
- Excludes, compileOnly metadata, declared coordinates, project edges, KSP
  processor dependencies, and aggregated binary-root resolution snapshots now
  flow through serialized task inputs. The KSP task intentionally keeps Gradle
  `Configuration` handles internal and resolves them during its task action for
  this milestone.
- Same artifact at different versions must remain identity-aware. Example:
  `implementation "x:y:1.0"` plus `debugImplementation "x:y:2.0"` must keep
  `1.0` in `@maven` and `2.0` in `@debug_maven`.
- Shared `ksp_maven` remains acceptable.
- Library-only repos are out of scope; this refactor may require an app/root
  binary or `com.android.test` edge.
- Broad `bazelisk test //...` is not a success criterion because earlier
  generated lint/sample failures are preexisting/out of scope.

## Active Decisions

1. Keep declared dependency metadata behind a Gradle task/provider side channel.
   It collects declared deps/excludes/compileOnly metadata only and must not
   call `resolvedConfiguration`, `incoming.resolutionResult`, artifact views, or
   any per-variant classpath resolution API.
2. Declared metadata and KSP processor metadata should remain stable,
   debuggable JSON inputs so Gradle can cache them and generated-output diffs
   can be explained from serialized inputs.
3. Binary-root classpath resolution remains the source of truth for resolved
   versions, transitive closure, repositories, Jetifier flags, and buildability.
4. Bucket finalization comes after the metadata task exists. Reasonable
   granular bucket moves are allowed if verified.
5. Test/androidTest deeper modeling remains low priority. Keep the current
   `Variant.extendsFrom` behavior unless a focused failing fixture proves a gap.
6. Continue reducing live `Project` / `Configuration` reach around
   `ComputeWorkspaceDependenciesTask`. The task action now consumes JSON and
   serialized aggregated root snapshots, but registration still builds variants
   and wires Gradle root-component providers. Future strict cacheability work can
   split root snapshot production into its own task if provider-backed resolution
   during input fingerprinting becomes too expensive.
7. The old path and `aggregatedDependencyResolution` experiment flag are
   removed. `limitDependencyResolutionParallelism` remains only as a deprecated
   no-op DSL compatibility property.

## Milestones

1. Done: introduce cacheable declared metadata collection task and stable JSON
   output.
2. Done: wire declared metadata and KSP metadata JSON into aggregated workspace
   computation.
3. Done for this milestone: finalize buckets using the serialized inputs;
   accept verified bucket moves as the new baseline.
4. Done for this milestone: make `ComputeWorkspaceDependenciesTask` action
   consume serialized root snapshots instead of live Gradle
   `ResolvedComponentResult` handles. A future stricter optimization can move
   snapshot production to a separate task if needed.
5. Done: remove legacy resolution path and delete the old opt-out behavior.

## Verification

- Add focused fixtures/verifiers before changing behavior when practical.
- Run relevant Gradle unit/functional tests for changed behavior.
- Run `./gradlew migrateToBazel --console=plain`.
- Run `bash reports/scripts/verify-default-task-graph.sh`.
- Run `bash reports/scripts/verify-sample-bucket-labels.sh`.
- Run `bazelisk build //... --disk_cache= --strategy=KotlinKapt=sandboxed`
  when feasible. The empty `--disk_cache=` is intentional: it overrides any
  default/user Bazel disk-cache setup so verification proves the generated graph
  builds without relying on cached artifacts.
- Use `git diff` against the generated baseline to explain bucket/output moves.
- Do not claim broad `bazelisk test //...` is green unless the known generated
  lint/sample failures are addressed or explicitly excluded.

## Operating Notes

- Keep the worklog in this file concise after meaningful checkpoints:
  hypothesis/decision, files changed, commands/results, remaining risks, and
  next action.
- Use subagents to manage context when useful. Prefer explorer subagents for
  independent code audits or verification planning, and worker subagents only
  for disjoint write scopes. Do not make the main thread reread the archived
  compatibility log for context.
- If storage or worker pressure appears, stop Gradle daemons/Bazel processes and
  use normal clean commands such as `bazelisk clean` or `./gradlew clean`.

## Checkpoints

Older checkpoints are chronological notes. When they mention the legacy opt-out
path or now-deleted tests, the latest checkpoint supersedes them.

### 2026-06-19 - MAIN Bucket Planner Checkpoint

Decision/implementation:
- Added a pure `MainDependencyBucketPlanner` for MAIN default/build-type/flavor
  and leaf residual placement.
- Kept resolved graph extraction in `AggregatedDependencyResolver` /
  `ResolvedComponentsVisitor`; the new planner only owns bucket placement.
- Explicit Gradle hierarchy buckets win over inferred intersections, preserving
  nearest-owner/version behavior.
- Inferred non-default hierarchy buckets require at least two descendant leaves,
  avoiding promotion from a single filtered leaf unless Gradle provided an
  explicit hierarchy bucket.

Generated output notes:
- Root migration currently moves `androidx.fragment:fragment` into
  `free_maven_install.json` and generated `free_maven` in `WORKSPACE`.
- Treat this as acceptable bucket movement for the checkpoint if verification
  stays green.

Next goal preparation:
- Details are captured in
  `reports/dependencies-refactor-bucket-planner-goal.md`.
- Next architecture decision is whether the extracted planner remains enough or
  whether a fuller bucket DAG abstraction is needed for further precision.

### 2026-06-18 04:30:37 +08 - Serialized Root Snapshots and Final Verification

Decision/implementation:
- Replaced the previous hidden cache contract where
  `ComputeWorkspaceDependenciesTask` consumed live `ResolvedComponentResult`
  roots as `@Internal` plus separate scalar fingerprints.
- Added `AggregatedDependencyRootSnapshot`, a serializable representation of the
  resolved binary-root graph plus bucket metadata. `ComputeWorkspaceDependenciesTask`
  now has a single `@Input ListProperty<String>` of snapshot JSON and
  `AggregatedDependencyResolver` consumes those snapshots directly.
- Sorted migratable projects, variants, configurations, root snapshot metadata,
  and root exclude maps so logically identical roots produce stable input order.
- Added a focused unit guard for stable root exclude short-id ordering.

Subagent review:
- Explorer `019ed740-62f3-7e62-b6cd-fb42c9d45f3a` found no blocker in the
  serialized-snapshot refactor. It flagged two stability risks: snapshot list
  ordering and unsorted root exclude maps; both were addressed.
- Remaining caveat from the same audit: the snapshot JSON is still produced by
  provider-mapping Gradle `configuration.incoming.resolutionResult.rootComponent`.
  This means Gradle still resolves aggregated roots to fingerprint the compute
  task input, even if a remote build-cache hit could skip the task action. This
  is acceptable for merge readiness because it avoids restoring the old
  per-project/per-variant fanout, but a future stricter optimization can split
  root snapshot production into a dedicated task.

Generated output notes:
- Root `migrateToBazel` refreshed `sample-kotlin-library/BUILD.bazel` to include
  `build/generated/ksp/test/kotlin` as an additional source set for the
  generated `kotlin_test`. The root Bazel build verified the generated output.

Commands/results:
- GREEN:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --tests "com.grab.grazel.tasks.internal.ComputeWorkspaceDependenciesTaskTest" --console=plain`
  passed.
- GREEN:
  `./gradlew computeWorkspaceDependencies --console=plain --build-cache`
  followed by a second no-edit run showed `collectDeclaredDependencyMetadata`,
  `collectKspProcessorDependencies`, and `computeWorkspaceDependencies`
  `UP-TO-DATE` on the second run.
- GREEN forced functional suite:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesDoesNotScheduleLegacyResolveTasksByDefault" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesIsUpToDateWithoutInputChanges" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesInvalidatesWhenProjectDependencyDeclarationsChange" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesInvalidatesWhenProjectDependencyEdgesChange" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesInvalidatesWhenProjectDependencyExcludeRulesChange" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesInvalidatesWhenKspDependencyChanges" --tests "com.grab.grazel.migrate.BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain --rerun-tasks`
  passed.
- GREEN:
  `./gradlew migrateToBazel --console=plain` passed.
- GREEN:
  `bash reports/scripts/verify-default-task-graph.sh` passed.
- GREEN:
  `bash reports/scripts/verify-sample-bucket-labels.sh` passed.
- GREEN:
  `git diff --check` passed.
- GREEN:
  `bazelisk build //... --disk_cache= --strategy=KotlinKapt=sandboxed` passed.

Remaining risks / next action:
- Merge-readiness criteria are met for the dependency refactor path: the old
  fanout is gone, default behavior is aggregated, generated output is refreshed,
  and root Bazel build verification passed.
- Broad `./gradlew check` remains blocked by the previously documented,
  unchanged sample Android lint issue in `activity_main.xml`.
- Future optimization, not required for this merge-readiness slice: introduce a
  dedicated aggregated-root snapshot task if resolving root-component providers
  during compute task input fingerprinting is still too expensive in large repos.

### 2026-06-18 02:01:43 +08 - Declared Metadata Task First Slice

Hypothesis/decision:
- Move declared dependency metadata out of inline aggregated bucketing into a
  task-produced stable JSON input first, without changing bucket behavior.
- Keep the metadata collection cheap and declaration-only. The collector reads
  `Configuration.dependencies` from declaration buckets and must not resolve
  classpaths.
- Use subagents for independent audits/context management going forward.

Files changed:
- Added `CollectDeclaredDependencyMetadataTask`, writing
  `build/grazel/declared-dependency-metadata.json`.
- `ComputeWorkspaceDependenciesTask` now depends on that task and consumes the
  JSON as an input file on the aggregated path.
- `AggregatedDependencyResolver` now consumes serialized
  `DeclaredDependencyMetadata` instead of invoking the collector inline.
- `DeclaredDependencyMetadataCollector` now serializes exclude metadata,
  compileOnly metadata, and declared external coordinates so ordinary
  implementation/api declaration edits invalidate compute.
- `BuildVariantTest` now asserts the default task graph schedules
  `:collectDeclaredDependencyMetadata` while still avoiding legacy
  `*ResolveDependencies` fanout.

Commands/results:
- RED:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesDoesNotScheduleLegacyResolveTasksByDefault" --console=plain`
  failed because no metadata task existed.
- GREEN:
  same focused test passed after wiring the task.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --console=plain`
  passed.
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesInvalidatesWhenProjectDependencyDeclarationsChange" --console=plain`
  first failed because the metadata JSON did not change for a plain
  `paidImplementation` edit, then passed after declared external coordinates
  were added to the JSON.
- A forced full `BuildVariantTest` run was interrupted by the user before a
  final result; treat it as no-result. Gradle and Grazel/test-fixture Bazel
  workers were stopped afterward. An unrelated `pax-android` Bazel server was
  left alone.

Remaining risks / next action:
- Wait for explorer audits on cacheability/coupling risks and incorporate
  concrete findings.
- Re-run full `BuildVariantTest` and root verifiers after audit-driven fixes.
- Check generated output diff carefully; `sample-android/BUILD.bazel` had a
  preexisting `//:parcelize` removal before this slice and should not be
  attributed to this work without confirmation.

### 2026-06-18 02:15:01 +08 - Metadata Task Hardening

Hypothesis/decision:
- The declared metadata task is the right first milestone boundary. Keep it even
  though `ComputeWorkspaceDependenciesTask` still has live Gradle model reach;
  that isolation work is a later milestone.
- Legacy opt-out must not pay for aggregated metadata collection.
- The metadata JSON must fingerprint declared external deps, project dependency
  edges, project-edge exclude rules, compileOnly deps, and exclude metadata so
  compute invalidates on cheap declaration edits without per-variant resolution.

Files changed:
- `ComputeWorkspaceDependenciesTask` now wires `CollectDeclaredDependencyMetadataTask`
  only when `aggregatedDependencyResolution` is true. The metadata input is
  optional so legacy opt-out keeps the old per-variant task graph.
- `DeclaredDependencyMetadataCollector` now serializes project dependency edges
  and project-edge exclude rules, and sorts exclude sets for stable JSON.
- `BuildVariantTest` now covers invalidation for external declaration edits,
  project dependency edge edits, project dependency exclude edits, default task
  graph behavior, and legacy opt-out task graph behavior.

Subagent review:
- Fixed concrete findings around project-edge exclude invalidation and
  compileOnly exclude ordering.
- Remaining larger risk: default aggregated mode still resolves some non-app
  project compile/lint classpaths and still rebuilds variants/reads live
  `Project`/`Configuration` state inside compute. This is the milestone-4 task
  boundary/altitude cleanup, not a reason to block the current metadata slice.

Commands/results:
- RED then GREEN:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesInvalidatesWhenProjectDependencyExcludeRulesChange" --console=plain`
  first failed with `:computeWorkspaceDependencies UP-TO-DATE`, then passed
  after project-edge excludes were included in metadata.
- GREEN:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependencies*" --console=plain`
  passed.
- GREEN after the project-edge-exclude fix:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest" --console=plain --rerun-tasks`
  passed, including the fixture `bazelBuildAll`.
- GREEN after the project-edge-exclude fix:
  `./gradlew migrateToBazel --console=plain`,
  `bash reports/scripts/verify-default-task-graph.sh`,
  `bash reports/scripts/verify-sample-bucket-labels.sh`, and
  `git diff --check` passed.

Remaining risks / next action:
- Decide whether to keep or remove the non-app compile/lint resolution shortcut
  before merge readiness. It is the main remaining deviation from the
  binary-root-only mental model.
- `ComputeWorkspaceDependenciesTask` still rebuilds variants and reads live
  `Project`/`Configuration` state; move this behind file/value/provider inputs
  during the task-boundary/altitude milestone.
- Keep `sample-android/BUILD.bazel` parcelize removal treated as preexisting
  user work unless explicitly folded into this branch.

### 2026-06-18 02:41:50 +08 - Non-App Implementation Shortcut Removed, Lint Roots Corrected

Hypothesis/decision:
- Non-app `implementation`/`api` deps should not be promoted into default/main
  buckets unless they are reachable from an app or standalone `com.android.test`
  binary root. The old non-app compileClasspath shortcut was too broad and could
  leak unreachable library deps into generated workspace buckets.
- Keep non-app declared `compileOnly` metadata injection because it is cheap,
  declaration-only metadata and is needed for generated target labels.
- Treat `lintChecks` specially: external lint checks should produce the
  `lint_maven` roots, while `lintChecks project(...)` remains a project label and
  should not pull that project's implementation deps into `lint_maven`.
- `lint_maven` should root selected flattened lint transitives so Gradle-selected
  versions such as `auto-service-annotations:1.1.1` are preserved.

Files changed:
- `AggregatedDependencyResolver` no longer resolves non-app compileClasspath
  fallbacks. It still injects declared compileOnly metadata, and now resolves
  `lintChecks` with legacy external-only traversal.
- `MavenInstallArtifactsCalculator` now roots all selected `lint` bucket
  artifacts instead of direct-only roots.
- `BuildVariantTest` adds a regression proving unreachable non-app
  implementation deps do not enter `dependencies.json`.
- `MavenInstallArtifactsCalculatorTest` adds a regression proving `lint_maven`
  roots selected transitive artifacts.
- Root generated `WORKSPACE`/pin files were regenerated. `lint_maven` now roots
  only `auto-service-annotations:1.1.1` and `slack-lint-checks:0.2.3` for the
  current sample lint setup.

Commands/results:
- RED:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesDoesNotPromoteUnreachableNonAppImplementationDeps" --console=plain`
  failed before removing the non-app compileClasspath shortcut because
  unreachable `okio` leaked into `dependencies.json`.
- GREEN:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesDoesNotPromoteUnreachableNonAppImplementationDeps" --tests "com.grab.grazel.migrate.BuildVariantTest.nonAppLibraryDeclaredCompileOnlyDepsUseExpectedBuckets" --tests "com.grab.grazel.migrate.BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain`
  passed.
- RED/GREEN:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.migrate.dependencies.MavenInstallArtifactsCalculatorTest.lint maven install roots selected transitive artifacts" :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain`
  first failed on the new unit assertion, then passed after lint root handling
  was corrected.
- GREEN:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallArtifactsCalculatorTest" :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest" --console=plain --rerun-tasks`
  passed.
- GREEN:
  `./gradlew migrateToBazel --console=plain`,
  `bash reports/scripts/verify-default-task-graph.sh`,
  `bash reports/scripts/verify-sample-bucket-labels.sh`,
  `git diff --check`, and
  `bazelisk build //... --disk_cache= --strategy=KotlinKapt=sandboxed`
  passed.

Remaining risks / next action:
- `ComputeWorkspaceDependenciesTask` still constructs the aggregated resolver
  with live Gradle model objects. The next milestone should reduce that task
  altitude by pushing more inputs through stable provider-backed JSON/value
  boundaries.
- Test/androidTest bucket precision is still intentionally lower priority unless
  a failing case is found.
- Old path removal and broader cleanup should wait until compatibility and task
  boundary changes are satisfactory.

### 2026-06-18 02:57:50 +08 - Variant Topology Metadata Drives Aggregated Compute

Hypothesis/decision:
- Keep the current generated-output baseline and reduce task altitude without
  changing bucket behavior.
- Move variant/project enumeration out of `ComputeWorkspaceDependenciesTask`.
  The compute task should consume serialized metadata, not own
  `MigrationChecker` or `VariantBuilder`.
- This is an incremental milestone-4 step. The resolver still reads live
  Gradle `Project`/`Configuration` objects to resolve classpaths; passing
  `resolutionResult.rootComponent` or stable resolved files into the compute
  boundary remains future work.

Files changed:
- `DeclaredDependencyMetadataCollector` now serializes migratable project
  topology: variant names, types, extends-from names, relevant configuration
  names, Android leaf/build-type/flavor attributes, declared deps/excludes, and
  compileOnly metadata. It tolerates missing classpath configurations in unit
  fixtures without resolving anything.
- `AggregatedDependencyResolver` now derives migratable projects and variants
  from `DeclaredDependencyMetadata` instead of invoking `MigrationChecker` and
  `VariantBuilder` inside compute.
- `ComputeWorkspaceDependenciesTask` no longer exposes task properties for
  `MigrationChecker` or `VariantBuilder`; registration still passes those to
  the metadata task and legacy opt-out wiring.
- Added `ComputeWorkspaceDependenciesTaskTest` to lock the compute task boundary.

Subagent review:
- Explorer `019ed6e6-328d-74a1-8ae5-8b559aa8bf0c` confirmed the old
  `ResolveVariantDependenciesTask` pattern: wire resolved roots/properties
  during registration, then consume task properties in the action.
- Explorer `019ed6e6-424c-7111-8a97-f784f13aa5f9` identified the existing
  `BuildVariantTest` guards as the best functional coverage and recommended a
  future no-edit `UP_TO_DATE` test for cacheability.

Commands/results:
- RED:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.tasks.internal.ComputeWorkspaceDependenciesTaskTest" --console=plain`
  failed because the compute task still exposed `getMigrationCheckerProvider`.
- GREEN:
  same focused test passed after removing those task properties.
- GREEN:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesDoesNotScheduleLegacyResolveTasksByDefault" --tests "com.grab.grazel.migrate.BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain --rerun-tasks`
  passed.
- GREEN:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --tests "com.grab.grazel.tasks.internal.ComputeWorkspaceDependenciesTaskTest" --console=plain --rerun-tasks`
  passed after making topology config-name collection tolerant of missing
  classpath configs in unit fixtures.
- GREEN:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest" --console=plain --rerun-tasks`
  passed.
- GREEN:
  `./gradlew migrateToBazel --console=plain`,
  `bash reports/scripts/verify-default-task-graph.sh`,
  `bash reports/scripts/verify-sample-bucket-labels.sh`,
  `git diff --check`, and
  `bazelisk build //... --disk_cache= --strategy=KotlinKapt=sandboxed`
  passed.

Remaining risks / next action:
- Next altitude step should pass resolved roots or serialized resolved graph
  files through provider/task inputs, following the spirit of
  `ResolveVariantDependenciesTask`, instead of resolving configurations from
  live `Project` in compute.
- Add the recommended no-edit `UP_TO_DATE` functional guard if the next slice
  changes cacheability/incrementality boundaries.
- Keep old path removal deferred until the provider/root-input boundary and
  generated baseline are both satisfactory.

### 2026-06-18 03:23:29 +08 - Aggregated Resolved Roots Are Task Inputs

Hypothesis/decision:
- Move main/test/androidTest/lint aggregated classpath roots to task-wired
  provider inputs, leaving KSP on the live project/configuration path for now.
- Keep `ResolvedComponentResult` roots as `@Internal` because Gradle cannot
  snapshot them directly, but add a stable scalar fingerprint input derived
  from each resolved graph. This keeps `ComputeWorkspaceDependenciesTask`
  cacheable without depending only on declared metadata.
- Preserve standalone `com.android.test` compatibility: main roots still feed
  `default`, and androidTest owns only leftovers after main coverage.

Files changed:
- Added `AggregatedDependencyRoot`/`AggregatedDependencyRootMetadata` to carry
  each resolved root plus project path, configuration name, kind, target
  bucket(s), variant hierarchy, direct ids, and root excludes.
- `ComputeWorkspaceDependenciesTask` now wires
  `aggregatedDependencyRoots`, `aggregatedDependencyRootMetadataJsons`, and
  `aggregatedDependencyRootFingerprints` together from the same
  `resolutionResult.rootComponent` providers.
- `AggregatedDependencyResolver` now consumes the root list for
  main/test/androidTest/lint buckets instead of looking up live
  configurations. KSP remains deferred.
- `DeclaredDependencyMetadataCollector` now records coarse project type so
  non-app compileOnly metadata can still be injected without live plugin checks.
- `ComputeWorkspaceDependenciesTaskTest` now locks the task boundary: no
  variant-enumeration services, resolved roots accepted as task inputs, and a
  scalar fingerprint exists for cache correctness.

Subagent review:
- Explorer `019ed6fd-dc39-7d53-9654-d80945c88dcb` flagged that a cacheable
  compute task with `@Internal` roots and no resolved-graph fingerprint could
  reuse stale `dependencies.json` for resolution-only changes. Fixed by adding
  `aggregatedDependencyRootFingerprints`.
- Same review noted root/metadata pairing is order-based. Current helper adds
  root, metadata, and fingerprint together and the action now size-checks all
  three lists; a future combined value object would be cleaner if Gradle can
  model it.

Commands/results:
- RED/GREEN:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.tasks.internal.ComputeWorkspaceDependenciesTaskTest" --console=plain`
  first failed before root getters existed, then passed after root + metadata +
  fingerprint task inputs were added.
- GREEN:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --console=plain`
  passed.
- GREEN:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesInvalidatesWhenProjectDependencyEdgesChange" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesInvalidatesWhenProjectDependencyExcludeRulesChange" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesInvalidatesWhenProjectDependencyDeclarationsChange" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesDoesNotScheduleLegacyResolveTasksByDefault" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesSchedulesLegacyResolveTasksWhenAggregatedResolutionDisabled" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesDoesNotPromoteUnreachableNonAppImplementationDeps" --console=plain`
  passed.
- GREEN:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.nonAppLibraryDeclaredCompileOnlyDepsUseExpectedBuckets" --console=plain`
  passed.
- GREEN:
  `./gradlew migrateToBazel --console=plain`,
  `./reports/scripts/verify-default-task-graph.sh`,
  `./reports/scripts/verify-sample-bucket-labels.sh`, and
  `git diff --check` passed.
- GREEN after clean:
  Initial `bazelisk build //... --disk_cache= --strategy=KotlinKapt=sandboxed`
  failed in local Bazel output state with `aapt2` unable to open a generated
  ABI jar. Generated BUILD/cquery did not show a wrong cross-variant edge; after
  `bazelisk clean`, the same build passed from a clean output tree.

Remaining risks / next action:
- KSP still uses live project/configuration access inside the resolver. This is
  acceptable for now because KSP processor class extraction needs artifact
  files, but it is the next remaining altitude inconsistency.
- Root/metadata/fingerprint pairing is guarded by size checks, not a single
  typed provider value. It is currently centralized in `addRoot`; revisit only
  if Gradle can model a cleaner composite input.
- Old path removal remains deferred until compatibility and task-boundary work
  are fully satisfactory.

### 2026-06-18 03:29:52 +08 - No-Edit Cacheability Guard

Hypothesis/decision:
- Add the missing functional guard that runs `computeWorkspaceDependencies`
  twice against an unchanged fixture and requires the second run to be
  `UP_TO_DATE`.
- Gradle reported the rerun was caused by
  `aggregatedDependencyRootFingerprints`, not by task outputs or declared
  metadata.
- The unstable fingerprint was the serialized transitive dependency set. Sort
  each `VisitResult.transitiveDeps` set before joining it into the scalar task
  input string.

Files changed:
- `BuildVariantTest` now has
  `computeWorkspaceDependenciesIsUpToDateWithoutInputChanges`.
- `ComputeWorkspaceDependenciesTask.fingerprintResolvedRoot` now sorts
  transitive dependency results before serializing them.

Commands/results:
- RED before the production fix:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesIsUpToDateWithoutInputChanges" --console=plain`
  failed because the second `computeWorkspaceDependencies` run executed.
- Diagnostic:
  `./gradlew computeWorkspaceDependencies --console=plain --info`
  reported `Value of input property 'aggregatedDependencyRootFingerprints' has
  changed`.
- GREEN:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesIsUpToDateWithoutInputChanges" --console=plain`
  passed, with the second fixture run showing `collectDeclaredDependencyMetadata
  UP-TO-DATE` and `computeWorkspaceDependencies UP-TO-DATE`.
- GREEN:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.tasks.internal.ComputeWorkspaceDependenciesTaskTest" --console=plain`
  passed.
- GREEN:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesIsUpToDateWithoutInputChanges" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesInvalidatesWhenProjectDependencyDeclarationsChange" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesInvalidatesWhenProjectDependencyEdgesChange" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesInvalidatesWhenProjectDependencyExcludeRulesChange" --console=plain`
  passed.
- GREEN:
  `./gradlew migrateToBazel --console=plain`,
  `./reports/scripts/verify-default-task-graph.sh`,
  `./reports/scripts/verify-sample-bucket-labels.sh`, and
  `git diff --check` passed.

Remaining risks / next action:
- KSP is still the main task-boundary inconsistency because processor class
  extraction still uses live project/configuration access.
- Old path removal remains deferred until compatibility and task-boundary work
  are fully satisfactory.

### 2026-06-18 03:34:11 +08 - KSP Task-Boundary Handoff

Verification update:
- `bazelisk build //... --disk_cache= --strategy=KotlinKapt=sandboxed`
  first failed in the same local Bazel output-state mode as before:
  `aapt2` could not open a generated kapt ABI jar.
- After `bazelisk clean`, the same build passed from a clean output tree.

Subagent handoff:
- Explorer `019ed710-61e4-7483-88a2-5594ed358474` confirmed the remaining
  live-model access is isolated to KSP collection.
- KSP configuration names are captured in declared metadata, but processor
  class extraction still needs resolved artifact jars because
  `KspProcessorClassExtractor` reads
  `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`
  from jars.
- During `ComputeWorkspaceDependenciesTask.action`, the aggregated resolver
  still uses `rootProject.findProject(projectPath)`,
  `project.configurationsByName(...)`, `config.allDependencies`,
  `cfg.incoming.artifactView(...).artifacts`, and
  `cfg.incoming.resolutionResult.root` for KSP.

Decision / next slice:
- Add a separate KSP collection task that owns live `Project`/`Configuration`
  access and writes stable JSON.
- Wire that JSON into `ComputeWorkspaceDependenciesTask` and have
  `AggregatedDependencyResolver` consume precomputed KSP results instead of
  touching live Gradle model during compute.
- Keep the first slice global/default: collect all migratable KSP processors
  into the existing `ksp_maven` repo, matching the decision that flavor/build
  type KSP precision is not required for this milestone.
- Add a RED functional invalidation guard: edit a KSP declaration, rerun
  `computeWorkspaceDependencies`, assert the task executes, and assert
  `dependencies.json` changes for `ksp_maven`.

### 2026-06-18 03:58:40 +08 - KSP Collection Task Implemented

Decision/implementation:
- Added `CollectKspProcessorDependenciesTask` as a cacheable task that writes
  `build/grazel/ksp-dependencies.json`.
- `ComputeWorkspaceDependenciesTask` now depends on that task in aggregated
  mode and passes the JSON result into `AggregatedDependencyResolver`.
- `AggregatedDependencyResolver` no longer performs live KSP project/config
  lookup during workspace computation; it consumes precomputed KSP deps and
  emits them into the default result so the existing global `ksp_maven`
  aggregation path is preserved.
- KSP collection remains intentionally broad/global for this milestone. We are
  not doing variant-specific KSP bucketing yet.
- The first KSP task attempt used resolved-root fingerprints as task inputs,
  but that reintroduced Gradle's "configuration resolved during configuration
  time" warning for the synthetic KSP classpath. The final shape keeps
  `Configuration` handles internal, resolves them inside the KSP task action,
  and snapshots declared KSP deps plus resolved classpath files as task inputs.
- `KspProcessorClassExtractor` now keys direct processor jars by exact resolved
  file path, with filename fallback for older call sites. This avoids wrong
  `processor_class` output when two global KSP processors have the same jar
  basename.

Tests/commands:
- RED/GREEN:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesDoesNotScheduleLegacyResolveTasksByDefault" --console=plain`
  initially failed before the KSP collection task was registered, then passed.
- GREEN:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesInvalidatesWhenKspDependencyChanges" --console=plain --rerun-tasks`
  passed after moving KSP resolution into the task action, with no synthetic
  KSP classpath configuration-time resolution warning.
- GREEN:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesDoesNotScheduleLegacyResolveTasksByDefault" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesSchedulesLegacyResolveTasksWhenAggregatedResolutionDisabled" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesIsUpToDateWithoutInputChanges" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesInvalidatesWhenProjectDependencyDeclarationsChange" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesInvalidatesWhenProjectDependencyEdgesChange" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesInvalidatesWhenProjectDependencyExcludeRulesChange" --console=plain`
  passed.
- GREEN:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.tasks.internal.ComputeWorkspaceDependenciesTaskTest" --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --console=plain`
  passed.
- GREEN:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.KspProcessorClassExtractorTest" --console=plain`
  passed.
- GREEN:
  Root `./gradlew computeWorkspaceDependencies --console=plain` followed by a
  second no-edit run showed `collectDeclaredDependencyMetadata`,
  `collectKspProcessorDependencies`, and `computeWorkspaceDependencies`
  `UP-TO-DATE`.
- GREEN:
  `./gradlew migrateToBazel --console=plain`,
  `./reports/scripts/verify-default-task-graph.sh`,
  `./reports/scripts/verify-sample-bucket-labels.sh`, and
  `git diff --check` passed.
- GREEN:
  `bazelisk build //... --disk_cache= --strategy=KotlinKapt=sandboxed`
  passed without needing a Bazel clean after the KSP task-boundary changes.

Subagent review:
- Explorer `019ed724-40e1-7e81-9efe-e1acb079d271` reviewed KSP task
  cacheability/lifecycle risk.
- Action taken from review: fixed filename-only processor jar mapping by using
  exact resolved file paths and added a collision unit test.
- Accepted caveat: the KSP task still stores Gradle `Configuration` objects as
  internal handles and resolves them during `@TaskAction`. This is the right
  task-boundary shape for now and avoids configuration-time resolution, but it
  is not configuration-cache cleanup. A future stricter task-cache pass can add
  a serializable resolved graph snapshot if KSP output ever needs repository
  metadata to be exact for same-coordinate/same-artifact repository changes.

Remaining risks / next action:
- Old path removal is still deferred until compatibility and task-boundary work
  are fully satisfactory.
- Test/androidTest precision remains low priority; no full DAG modeling has
  been added.
- Library-only/JVM-only repos still depend on the broader binary-root decision
  from the plan.

### 2026-06-18 04:09:29 +08 - Legacy Path Removed After KSP Boundary

Decision/implementation:
- Made the aggregated dependency path unconditional. Removed the
  `aggregatedDependencyResolution` DSL flag, deleted
  `ResolveVariantDependenciesTask`, removed legacy `compileDependenciesJsons`
  inputs, and deleted the old JSON parse path from `ComputeWorkspaceDependencies`.
- `ComputeWorkspaceDependenciesTask` now always consumes
  `declared-dependency-metadata.json`, `ksp-dependencies.json`, and aggregated
  root-component providers.
- `CollectDeclaredDependencyMetadataTask` and
  `CollectKspProcessorDependenciesTask` are now always wired for
  `computeWorkspaceDependencies`.
- Left `experiments.limitDependencyResolutionParallelism` as a deprecated no-op
  property for DSL compatibility, but removed its root build usage and all task
  behavior tied to it.
- Removed the functional opt-out test and added a task-surface unit guard that
  verifies the compute task no longer exposes legacy path inputs.

Subagent review:
- Explorer `019ed72b-ae9c-7050-8b6c-086283cd5927` audited old-path references
  before cleanup and identified the exact production/test surfaces to remove.
- Explorer `019ed730-647f-7b03-8a02-5dada0c3a7d0` audited after cleanup and
  found no production blocker. Remaining references are intentional negative
  guards, shared `ResolveDependenciesResult` model usage, the deprecated no-op
  DSL compatibility property, and historical docs/checkpoints.

Commands/results:
- GREEN forced functional suite:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesDoesNotScheduleLegacyResolveTasksByDefault" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesIsUpToDateWithoutInputChanges" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesInvalidatesWhenProjectDependencyDeclarationsChange" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesInvalidatesWhenProjectDependencyEdgesChange" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesInvalidatesWhenProjectDependencyExcludeRulesChange" --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesInvalidatesWhenKspDependencyChanges" --console=plain --rerun-tasks`
  passed.
- GREEN forced unit suite:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.tasks.internal.ComputeWorkspaceDependenciesTaskTest" --tests "com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependenciesTest" --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --tests "com.grab.grazel.gradle.dependencies.KspProcessorClassExtractorTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallArtifactsCalculatorTest" --console=plain --rerun-tasks`
  passed.
- GREEN:
  `./gradlew migrateToBazel --console=plain` passed.
- GREEN:
  `./reports/scripts/verify-default-task-graph.sh` and
  `./reports/scripts/verify-sample-bucket-labels.sh` passed.
- GREEN:
  root `./gradlew computeWorkspaceDependencies --console=plain` followed by a
  second no-edit run showed `collectDeclaredDependencyMetadata`,
  `collectKspProcessorDependencies`, and `computeWorkspaceDependencies`
  `UP-TO-DATE` on the second run.
- GREEN:
  `git diff --check` passed.
- GREEN:
  `bazelisk build //... --disk_cache= --strategy=KotlinKapt=sandboxed` passed.
- BROAD CHECK GAP:
  `./gradlew check --console=plain` failed in
  `:sample-android:lintDemoFreeDebug` on an unchanged sample layout lint error
  (`sample-android/src/main/res/layout/activity_main.xml:73`,
  `MissingConstraints`). This is outside the dependency-refactor files and was
  not fixed in this slice.

Remaining risks / next action:
- Bucket behavior is build-verified, but any further bucket granularity changes
  should still be treated as deliberate baseline moves and explained with the
  generated-output diff.
- `ComputeWorkspaceDependenciesTask` registration still enumerates variants and
  wires Gradle root-component providers. The action boundary is cleaner, but a
  stricter cacheability/altitude pass can move more provider/model work to
  stable file or scalar inputs.
- KSP remains global via shared `ksp_maven`, by decision. Variant-specific KSP
  bucketing is not required for this milestone.
- Test/androidTest precision and library-only/JVM-only repo support remain
  lower-priority follow-ups unless a focused failing fixture appears.

### 2026-06-18 04:20:00 +08 - Bucket Movement and Version Guards Audited

Decision/assessment:
- No new guard is needed for the user-raised version override example. The
  current branch already has both reducer-level and functional coverage for
  "default has one version, debug has another".
- `ComputeWorkspaceDependenciesTest.keeps child bucket dependency when same
  artifact has different version than default` verifies
  `com.example:library:1.0` stays in the default bucket while
  `com.example:library:2.0` stays in `debug`.
- `BuildVariantTest.migrateToBazelWithFlavorsWereUsed` verifies the real Gradle
  fixture where `implementation 'org.apache.commons:commons-lang3:3.9'` and
  `debugImplementation 'org.apache.commons:commons-lang3:3.12.0'` produce
  default bucket version `3.9`, debug bucket version `3.12.0`, and generated
  debug target labels use `@debug_maven` instead of `@maven`.
- `verify-sample-bucket-labels.sh` remains mostly generated-label coverage. It
  also has one version guard for `lint_maven` preserving
  `auto-service-annotations:1.1.1`, but it intentionally does not duplicate the
  default/debug `commons-lang3` functional test.

Generated bucket movement vs baseline commit `8cddc61`:
- `maven_install.json`: 224 -> 220 artifacts.
- `debug_maven_install.json`: 31 -> 31 artifacts.
- `android_test_maven_install.json`: 16 -> 16 artifacts.
- `test_maven_install.json`: 6 -> 6 artifacts.
- `ksp_maven_install.json`: 20 -> 20 artifacts.
- `lint_maven_install.json`: 2 -> 2 artifacts.
- Common flavor buckets `demo_maven_install.json`,
  `free_maven_install.json`, `full_maven_install.json`, and
  `paid_maven_install.json`: 63 -> 8 artifacts each.
- Leaf flavor/debug buckets `demo_free_debug_maven_install.json`,
  `demo_paid_debug_maven_install.json`,
  `full_free_debug_maven_install.json`, and
  `full_paid_debug_maven_install.json`: 2 -> 6 artifacts each.

Commands/results:
- GREEN:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain --rerun-tasks`
  passed, including fixture `bazelBuildAll`.
- Explorer `019ed736-9a20-7ea0-90c3-845d4df58256` independently confirmed the
  same version-override coverage and recommended not adding a duplicate guard.

Remaining risks / next action:
- The bucket movement is now explained at artifact-count level and version
  override behavior is guarded. Further bucket changes should still be treated
  as deliberate baseline moves with generated-output diffs.
- The main remaining merge-readiness question is task altitude/cacheability:
  `ComputeWorkspaceDependenciesTask` still wires Gradle root-component providers
  during registration, with fingerprints as cache inputs and roots as internal
  execution handles.

### 2026-06-18 04:15:36 +08 - Metadata Input Scope Narrowed

Decision/implementation:
- `CollectDeclaredDependencyMetadataTask` was using a broad Gradle file tree
  over the root project for `*.gradle`, `*.gradle.kts`, and version catalog
  files. With Bazel convenience symlinks present, Gradle reported removed files
  under `bazel-grazel/.../external/...` as changes to
  `dependencyDeclarationFiles`.
- Excluded `bazel-*/**` from the declared metadata task input file tree so
  ignored Bazel output symlink trees do not churn metadata task cache keys.
- Added `CollectDeclaredDependencyMetadataTaskTest` to lock the declaration
  input filter: real root/module Gradle files and version catalogs are included,
  while `.gradle`, `build`, and `bazel-*` generated trees are excluded.

Commands/results:
- Diagnostic:
  `./gradlew computeWorkspaceDependencies --console=plain --build-cache --info`
  first showed `collectDeclaredDependencyMetadata` out-of-date due removed files
  below `bazel-grazel/.../external/test_maven/...`.
- GREEN after the exclusion:
  `./gradlew computeWorkspaceDependencies --console=plain --build-cache`
  showed `collectDeclaredDependencyMetadata`,
  `collectKspProcessorDependencies`, and `computeWorkspaceDependencies`
  `UP-TO-DATE`.
- GREEN:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.tasks.internal.CollectDeclaredDependencyMetadataTaskTest" --tests "com.grab.grazel.tasks.internal.ComputeWorkspaceDependenciesTaskTest" --console=plain`
  passed.

Subagent review:
- Explorer `019ed738-1e27-79c0-a590-356bd2cdb642` found the current
  `ComputeWorkspaceDependenciesTask` cache shape is Gradle-valid but still
  relies on a hidden contract: `aggregatedDependencyRoots` are `@Internal`, so
  `aggregatedDependencyRootFingerprints` must fully represent every
  output-affecting property consumed by `AggregatedDependencyResolver`.

Remaining risks / next action:
- The root-component fingerprint proxy is acceptable for the current milestone
  but not ideal final altitude. The stronger next step is a serializable
  aggregate root snapshot that is both the task input and resolver input,
  replacing `ListProperty<ResolvedComponentResult>` without restoring the old
  per-project/per-variant task fanout.
- A smaller interim guard would be focused tests around fingerprint completeness
  for repository, project/variant ownership, Jetifier state, transitive deps,
  and metadata/exclude changes.
