# Item 41 - Branch-Wide Code Quality Hardening

## Current Status

- Started on 2026-07-02 after local checkpoint `104b4c7`
  (`refactor: tighten dependency task boundaries`).
- PAX regression workspace remains `/Users/arun.sampathkumar/work/pax-android`
  on branch `arun/grazel-refactor` at `d4105d1f64bd`.
- PAX must not be committed or pushed. Expected local PAX status is the
  maintainer proxy hook only: `M build.gradle`.

## Scope

Preserving / empty generated diff. This item covers branch-changed Kotlin
source and tests plus touched helper scripts/spec docs. `Dependencies.kt`
remains out of scope unless a local quality fix is purely preserving and does
not restart the legacy extractor cutover.

## Baseline Inventory

- Regenerated `reports/specs/source-shape-inventory.tsv` from scratch with
  `SOURCE_SHAPE_IGNORE_EXISTING=true reports/scripts/source-shape-inventory.sh`.
- Inventory shape was first fixed to match the Item41 columns and to avoid
  whitespace-delimited status shifting while preserving existing rows in future
  reruns.
- Baseline row count: 182 Kotlin files plus header.
- Area split: `main=116`, `test=64`, `functionalTest=2`.
- All 182 rows intentionally start as `pending`; every row must be reconciled
  before completion.

## Subagent Partitions

- Completed first read-heavy partition pass:
  - dependency/variant/task production code;
  - proxy/pinning/migrate production code;
  - tests and functional tests;
  - Bazel/Starlark/extension/util and script/spec hygiene.
- Parent reconciliation is required for every finding; subagent claims are not
  accepted without code spot checks.

## Decisions

- Retain `Collection<T>.quote` in `bazel/starlark/Statement.kt`. It is an
  intentional Starlark DSL convenience and keeps rule-generation call sites
  readable. Do not replace it with a free function as part of source-shape
  hygiene.
- A temporary `quoteStarlarkValues(...)` cleanup was reverted. Verification:
  `rg -n "quoteStarlarkValues" grazel-gradle-plugin/src/main/kotlin/com/grab/grazel`
  returns no matches, and the touched Starlark/rules quote files are no longer
  dirty.
- Replaced declared project-dependency edge string transport with
  `DeclaredProjectDependency`. Production now serializes target project,
  target configuration, configuration name, and excluded short IDs as typed
  metadata; `AggregatedDependencyResolver` no longer reparses
  `implementation->:project::[...]` strings. The legacy shorthand remains only
  inside `AggregatedDependencyResolverTest` fixture helpers.
- Replaced `CollectWorkspaceDependencyRootMetadataTask` exclude-rule
  string encoding with nested Gradle input beans. Exclude rules are no longer
  encoded/decoded as `group:artifact;...` strings across the task input seam.
- Renamed the misspelled source file `ArtificatPinner.kt` to
  `ArtifactPinner.kt` and updated current reports/spec references so future
  scans do not point at a deleted path.
- Hardened `source-shape-inventory.sh`:
  - detects collection receiver properties such as `Collection<T>.quote`;
  - detects annotated `Project` receiver extensions;
  - skips renamed/deleted Kotlin paths that still appear in branch diffs.
- Replaced anonymous dependency-notation destructuring in
  `ResolvedDependency.from(...)` with a named `ResolvedDependencyNotation`
  parser and added focused jetifier-source coverage.
- Reworked `BuildVariantTest` dry-run assertions behind a named
  `dryRunTaskPaths()` helper. TestKit does not expose these dry-run tasks via
  `BuildResult.task(path)`, so the output parsing is retained but isolated and
  supports both same-line and split-line `SKIPPED` formats.
- Investigated `SourcePathTest` ignored coverage. Re-enabling/deleting the
  ignored library-only fixture test exposed two existing issues outside this
  source-shape slice: library-only roots remain unsupported by the current root
  resolver, and the remaining shared-fixture asset test can fail before
  `@grab_bazel_common` is generated. Reverted `SourcePathTest` to baseline and
  left it out of Item41 edits.

## Verification

- Inventory coverage after reconciliation:
  - `183` changed Kotlin rows;
  - `28` fixed;
  - `132` no issue;
  - `7` retained problem-essential;
  - `16` deferred because the fix needs behavior/model/hash-sensitive work;
  - `0` pending/blank status fields.
- Focused dependency suite passed in `32s`:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest"
  --tests "com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadataCollectorTest"
  --tests "com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadataMergerTest"
  --tests "com.grab.grazel.gradle.dependencies.DependencyBucketPlacementEngineTest"
  --tests "com.grab.grazel.gradle.dependencies.BucketOwnershipPlannerTest"
  --console=plain --no-daemon`.
- Task-boundary focused suite passed in `20s`:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.tasks.internal.ResolveWorkspaceDependenciesTaskTest"
  --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest"
  --console=plain --no-daemon`.
- Pinner focused suite passed in `40s`:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.migrate.dependencies.DefaultArtifactPinnerTest"
  --console=plain --no-daemon`.
- Dependency-notation focused suite passed in `27s`:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.gradle.dependencies.model.ResolvedDependencyTest"
  --tests "com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependenciesTest"
  --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest"
  --console=plain --no-daemon`.
- BuildVariant dry-run functional test passed in `16s`:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests
  "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesDoesNotScheduleLegacyResolveTasksByDefault"
  --console=plain --no-daemon`.
- Full plugin unit test suite passed in `8s`:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon --quiet`.
- Full plugin unit test suite passed after Item41 follow-up edits:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon --quiet`.
- Subagent-guided preserving cleanup batch:
  - changed `AggregatedDependencyResolver` policy helpers from hidden receiver
    extensions to explicit bucket/metadata parameters;
  - replaced the remaining test-only project-dependency mini-language in
    `AggregatedDependencyResolverTest` with typed `DeclaredProjectDependency`
    fixture values;
  - changed `DeclaredDependencyMetadataCollector` declared-dependency parsing
    from a `String` receiver extension to an explicit `declaredDependencyId`
    parameter;
  - moved KSP planner helper models above the planner object;
  - renamed generic `VersionInfo` to `ComparableGradleVersion`;
  - removed unused stringly worker-action tracking from `FakeWorkQueue`;
  - replaced blank assertion-message wrappers in `DefaultArtifactPinnerTest`
    with direct `assertEquals` calls.
- Focused verification for that batch passed:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest"
  --tests "com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadataCollectorTest"
  --tests "com.grab.grazel.gradle.dependencies.model.ResolvedDependencyTest"
  --tests "com.grab.grazel.migrate.dependencies.DefaultArtifactPinnerTest"
  --console=plain --no-daemon --quiet`.
- Second preserving cleanup batch:
  - made `DefaultVariantMatcherTest` compare exact matched-variant sets and
    corrected duplicated expected staging variants;
  - changed `DefaultVariantBuilderTest` to store typed variant classes instead
    of class-name strings;
  - renamed KSP planner mutable accumulator state from `Input` to `State`;
  - converted remaining simple `Project` receiver helpers in Kotlin/Android
    extractors to explicit project-parameter helpers;
  - used Gradle `moduleVersion` coordinates for aggregated resolved dependency
    IDs instead of Gradle display strings;
  - added missing relative path sensitivity to task JSON inputs;
  - wrote `TargetReferenceFacts` test JSON through the typed model;
  - typed the root metadata task `kind` input while retaining optional
    `variantType` as a string because there is no nullable enum sentinel.
- Focused verification for the second batch passed:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest"
  --tests "com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadataCollectorTest"
  --tests "com.grab.grazel.gradle.dependencies.model.ResolvedDependencyTest"
  --tests "com.grab.grazel.gradle.variant.DefaultVariantMatcherTest"
  --tests "com.grab.grazel.gradle.variant.DefaultVariantBuilderTest"
  --tests "com.grab.grazel.gradle.variant.WorkspaceKspProcessorClasspathPlannerTest"
  --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest"
  --tests "com.grab.grazel.migrate.dependencies.DefaultArtifactPinnerTest"
  --console=plain --no-daemon --quiet`.
- Expanded focused verification after extractor/task/variant follow-up passed:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest"
  --tests "com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadataCollectorTest"
  --tests "com.grab.grazel.gradle.dependencies.model.ResolvedDependencyTest"
  --tests "com.grab.grazel.gradle.variant.DefaultVariantMatcherTest"
  --tests "com.grab.grazel.gradle.variant.DefaultVariantBuilderTest"
  --tests "com.grab.grazel.gradle.variant.WorkspaceKspProcessorClasspathPlannerTest"
  --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest"
  --tests "com.grab.grazel.tasks.internal.ResolveWorkspaceDependenciesTaskTest"
  --tests "com.grab.grazel.tasks.internal.CollectKspProcessorDependenciesTaskTest"
  --tests "com.grab.grazel.migrate.dependencies.DefaultArtifactPinnerTest"
  --tests "com.grab.grazel.migrate.android.DefaultAndroidTestDataExtractorTest"
  --tests "com.grab.grazel.migrate.android.DefaultAndroidUnitTestDataExtractorTest"
  --tests "com.grab.grazel.migrate.android.DefaultAndroidLibraryDataExtractorTest"
  --console=plain --no-daemon --quiet`.
- Failed attempt deliberately not retained: running the same command with
  `SourcePathTest` selected failed. Root cause and revert are recorded above.
- Simplify-pass reconciliation:
  - accepted the low-risk reuse finding in `BucketOwnershipPlanner` by routing
    `unionDependencyMaps` through the existing map `merge` utility;
  - rejected replacing `Collection<T>.quote`; maintainer direction is to retain
    the Starlark DSL extension;
  - deferred KSP direct-ID computation micro-optimization because
    `WorkspaceKspProcessorClasspathPlanner` already dedupes task inputs by
    processor classpath and has focused coverage for shared classpaths;
  - deferred provider materialization and proxy/lockfile package-level
    reshapes because they are model/performance slices, not preserving
    source-shape hygiene.
- Focused verification after the `BucketOwnershipPlanner` merge-helper cleanup
  passed in `21s`:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.gradle.dependencies.BucketOwnershipPlannerTest"
  --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest"
  --tests "com.grab.grazel.gradle.variant.WorkspaceKspProcessorClasspathPlannerTest"
  --console=plain --no-daemon --quiet`.
- Adversarial task-boundary review found that the Item41 cleanup had removed
  the defensive KSP configuration read boundary in
  `WorkspaceKspProcessorClasspathPlanner`. Restored the guard as
  `kspConfigurationsOrEmpty(...)` and added focused coverage for variants whose
  KSP configuration getter throws.
- Focused KSP verification passed in `20s`:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.gradle.variant.WorkspaceKspProcessorClasspathPlannerTest"
  --console=plain --no-daemon --quiet`.
- Adversarial source-shape review findings reconciled:
  - fixed: inventory now detects private domain receiver helpers as
    `domain_receiver_extension`;
  - fixed: `AggregatedDependencyResolver` root metadata hierarchy/bucket
    helpers now take explicit metadata parameters;
  - fixed: `BucketOwnershipPlanner` plan-policy helpers now take explicit
    `DependencyBucketPlacementPlan` parameters;
  - fixed: `AggregatedDependencyResolverTest` no longer uses
    `Proxy.newProxyInstance` for `ResolvedComponentResult`; it uses the
    existing Mockito test dependency;
  - deferred: proxy test-only helper seams and colon-packed intermediate
    dependency strings require model/API-shape work and are outside this
    preserving source-shape slice.
- Focused verification after source-shape review fixes passed in `15s`:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest"
  --tests "com.grab.grazel.gradle.dependencies.BucketOwnershipPlannerTest"
  --tests "com.grab.grazel.gradle.variant.WorkspaceKspProcessorClasspathPlannerTest"
  --console=plain --no-daemon --quiet`.

## Final Verification Checkpoint

- Full plugin unit test passed after simplify/adversarial fixes:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon --quiet`.
- Source-shape inventory was regenerated after final fixes. The inventory has
  no blank/pending status cells; the detector now marks private domain receiver
  helpers as `domain_receiver_extension`, with the retained Starlark
  `Collection<T>.quote` exception explicitly classified.
- Grazel diff hygiene passed:
  - `git diff --check`;
  - `git diff --check master...HEAD`.
- Grazel `migrateToBazel` passed in `10s`:
  `./gradlew migrateToBazel --console=plain --no-daemon`.
  The run reported:
  - `Collected declared dependency metadata for 10 projects across 10 shards in
    19ms`;
  - `Resolved 45 deps across 54 roots in 89ms`;
  - `Collected target tags for 32 targets in 82ms`;
  - `Analyzed variant compression for 2 projects in 58ms`;
  - `Collected target references across 10 modules in 148ms`.
- `reports/scripts/verify-default-task-graph.sh` passed.
- `reports/scripts/verify-sample-bucket-labels.sh` failed on the
  `androidx.constraintlayout:constraintlayout` one-sided appcompat exclude
  guard. This is treated as a pre-existing guard/baseline mismatch for Item41:
  the current `migrateToBazel` produced no local generated diff for the checked
  files, and `HEAD:WORKSPACE` plus `master:WORKSPACE` already contain the same
  appcompat/core exclusion block for constraintlayout.
- PAX `migrateToBazel` passed in `11m 43s`:
  `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
  --rerun-tasks`.
  The run reported:
  - `Collected declared dependency metadata for 2327 projects across 2327
    shards in 543ms`;
  - `Resolved 496 deps across 2451 roots in 24180ms`;
  - `Collected target tags for 17090 targets in 15922ms`;
  - `Analyzed variant compression for 2096 projects in 61954ms`;
  - `Collected target references across 2327 modules in 34045ms`.
- PAX generated baseline stayed stable after migrate: `git diff --name-only`
  reports only `build.gradle`, which is the maintainer-owned proxy hook.
  `git diff --check` passed.
- PAX APK build gate passed in `216s`:
  `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
  //app:app-gps-pax-debug-android-test.apk`.
- PAX focused Bazel test gate passed in `22s`:
  `./bazel.sh test --test_output=errors
  //app-utils:app-utils-gps-pax-debug-test
  //app-test:app-test-gps-pax-debug-test
  //application-initializer:application-initializer-gps-pax-debug-test`.
- PAX size guard passed in preserving mode with unchanged counts:
  `bucketCount=11`, `pinfileCount=11`, `totalArtifactRoots=1945`.

## Remaining Risks

- The branch-wide surface is large, but the Item41 source-shape findings have
  been reconciled against code and rerun through focused tests, full plugin
  unit tests, Grazel generation, PAX migrate/build/test, and the PAX size guard.
- The `verify-sample-bucket-labels.sh` constraintlayout failure remains a
  documented pre-existing guard/baseline mismatch, not an Item41 output change.
