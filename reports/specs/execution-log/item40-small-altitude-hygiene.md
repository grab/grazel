# Item 40 - Small Altitude Hygiene

## Current Status

- Completed on 2026-07-02 and committed locally in the current HEAD checkpoint
  (`refactor: tighten dependency task boundaries`).
- PAX regression workspace remains `/Users/arun.sampathkumar/work/pax-android`
  on branch `arun/grazel-refactor` at `d4105d1f64bd`.
- PAX must not be committed or pushed. Expected local PAX status before the
  next PAX gate is the maintainer proxy hook only: `M build.gradle`.

## Scope

Preserving / empty generated diff. `Dependencies.kt` is explicitly excluded.

## Implemented

- Bucket ownership now consumes typed variant facts for test bucket handling:
  `DependencyBucketPlacementPlan` carries `variantTypesByBucketName`, and
  `BucketOwnershipPlanner` no longer classifies test buckets by rendered suffix
  when typed facts are available.
- KSP processor root input planning moved to the variant layer through
  `WorkspaceKspProcessorClasspathPlanner`. `CollectKspProcessorDependenciesTask`
  now wires planned inputs instead of enumerating projects or classifying KSP
  configuration names.
- Branch service consumers now declare `usesService(...)`:
  `ComputeWorkspaceDependenciesTask`, `ComputeWorkspacePlanTask`,
  `FinalizeWorkspacePlanTask`, `CollectWorkspaceTargetTagPlanTask`,
  `CollectTargetMavenRepoReferencesTask`, `AnalyzeVariantCompressionTask`,
  `GenerateBazelScriptsTask`, and `GenerateRootBazelScriptsTask`.
- Declared metadata merge ordering is structural:
  `DeclaredDependencyMetadataMerger.merge()` writes a `SortedMap`, and
  project-bucket dependency maps are sorted by dependency short ID.
- `AnalyzeVariantCompressionTask` now precomputes build type by variant name
  instead of scanning matched variants for each compressor callback.

## Subagent Findings Reconciled

- BuildService audit listed eight missing service consumers plus the existing
  `PinMavenArtifactsTask` reference implementation. All eight missing usages
  were wired.
- KSP audit identified task-owned project enumeration and configuration-name
  classification. The implemented planner uses `Variant.kspConfiguration`.
- Bucket audit identified suffix classification in test bucket projection.
  Typed bucket facts now drive that logic.
- Determinism/re-derivation audit identified:
  - outer project metadata ordering: fixed with `associateTo(sortedMapOf())`;
  - inner project-bucket dependency ordering: fixed with `.toSortedMap()`;
  - build-type callback list scan: fixed with a precomputed map.
- The same audit suggested extracting additional compression task graph/filter
  helpers. Deferred intentionally: those are low-value abstraction moves around
  task orchestration and are better handled by a future compression-service
  boundary if needed. The trivial repeated lookup was fixed.

## Verification So Far

- Passed in `38s`:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`.
- Passed in `9s`:
  `./gradlew migrateToBazel --console=plain --no-daemon`. Generated Bazel and
  pinfile diff check returned empty.
- Passed:
  `reports/scripts/verify-default-task-graph.sh`.
- Failed with the documented pre-existing waiver only:
  `reports/scripts/verify-sample-bucket-labels.sh` reported
  `WORKSPACE must not union one-sided appcompat exclude onto androidx.constraintlayout:constraintlayout`.
- Passed:
  `reports/scripts/verify-pax-size-guard.sh --mode preserving` with unchanged
  counts `bucketCount=11`, `pinfileCount=11`, `totalArtifactRoots=1945`.
- Passed:
  `git diff --check` and `git diff --check master...HEAD`.
- Passed in `21s`:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.gradle.dependencies.BucketOwnershipPlannerTest" --tests
  "com.grab.grazel.gradle.variant.WorkspaceKspProcessorClasspathPlannerTest"
  --tests "com.grab.grazel.tasks.internal.CollectKspProcessorDependenciesTaskTest"
  --tests "com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadataMergerTest"
  --console=plain --no-daemon`.
- Passed in `28s` after adding deterministic inner-map coverage:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.gradle.dependencies.BucketOwnershipPlannerTest" --tests
  "com.grab.grazel.gradle.variant.WorkspaceKspProcessorClasspathPlannerTest"
  --tests "com.grab.grazel.tasks.internal.CollectKspProcessorDependenciesTaskTest"
  --tests "com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadataMergerTest"
  --tests "com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadataCollectorTest"
  --console=plain --no-daemon`.
- The second focused run reported a Kotlin incremental-compilation EOF and
  then fell back to non-incremental compilation; the build still succeeded.
- Passed in `10m 53s`:
  `cd /Users/arun.sampathkumar/work/pax-android &&
  ./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
  --rerun-tasks`. Pinning was checked and skipped as up to date.
- PAX generated-output guard passed after migrate:
  `git status --short` reported only the expected maintainer proxy hook
  `M build.gradle`, `git diff --name-only` returned only `build.gradle`, and
  `git diff --check` passed.
- Passed in `248.453s`:
  `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
  //app:app-gps-pax-debug-android-test.apk`.
- Passed in `19.622s`:
  `./bazel.sh test --test_output=errors
  //app-utils:app-utils-gps-pax-debug-test
  //app-test:app-test-gps-pax-debug-test
  //application-initializer:application-initializer-gps-pax-debug-test`.

## Remaining Item 40 Gates

- None. Continue to Item41 branch-wide code-quality hardening.
