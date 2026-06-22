# Dependency Refactor Current Status

Last updated: 2026-06-22.

Read `reports/dependencies-refactor-active-anchor.md` first after compaction/resume. This file is
the current evidence ledger, not a full transcript.

## Current Invariants

- Root app / `com.android.test` Gradle resolution is the expensive source of truth.
- Variant APIs and cheap declared metadata drive ownership, excludes, bucket shape, and typed
  test/androidTest classification.
- Gradle-resolved selected versions and artifact-edge closures must win over declared versions.
- Maven `tags` are classpath-filter metadata and must be normalized to `@maven//:artifact_name`.
- Actual `deps` keep their owning repos such as `@debug_maven`, `@lint_maven`, or
  `@android_test_maven`.
- Target tags stay local: own direct Maven roots plus their Gradle-resolved closure, plus direct
  project tags. Parent targets must not union child project Maven closures.
- Extractors consume service/workspace data and must not run project-graph Maven tag reachability.

## Accepted Architecture

1. `VariantBuilder` / variant APIs describe project variants, source-set hierarchy, and typed
   test/androidTest variants.
2. Root dependency tasks resolve app / `com.android.test` graphs once and run
   `ResolvedComponentsVisitor`.
3. The resolver/compute layer stores bucket labels, selected artifacts, excludes, and transitive
   closure data in workspace/service data.
4. Module generation remains local: it asks the dependency service for labels and tag closure.

## Rejected Shortcut

- Do not restore `MavenTagClosureCollector` or equivalent extractor-side project dependency walking.
- That shortcut fixed one missing annotation symptom, but inflated a PAX target from about `140`
  Maven tags to `443` Maven tags and bypassed root resolved graph data.

## Local Grazel Evidence

- Removed wrong project-dependency Maven tag expansion from production code and tests.
- `MavenInstallArtifactsCalculator` applies extension `overrideTargetLabels` to artifacts inherited
  from the default owner as well as artifacts rooted in the current bucket.
- Post-baseline DAG/altitude cleanup after commit
  `860c56905bd1929fd656a7eb53ca747411792112`:
  - added a red/green regression that `DependencyBucketPlacementEngine` must not infer hierarchy
    buckets absent from `extendsFrom` metadata;
  - removed `buildType`/`productFlavors` candidate-bucket inference from placement;
  - added a red/green regression that declared hierarchy metadata adopts the Gradle-resolved leaf
    version by `group:name`;
  - `AggregatedDependencyResolver` now uses `DependencyBucketPlacementPlan.leafAncestors` instead
    of rebuilding a second ancestor graph.
  - graph-performance guardrail: keep closure/ancestor derivation centralized, reuse precomputed
    maps, dedupe through stable sets/maps, and avoid eager collection pipelines that repeatedly walk
    leaves or materialize large intermediate lists.
  - simplify/adversarial review fixes:
    - explicit hierarchy buckets with no selected descendant leaves are not emitted;
    - leaf buckets cover themselves, preserving explicit leaf-bucket output;
    - globally merged leaf buckets are filtered with each project's ancestor names, so divergent
      project topology does not cross-filter same-named leaves;
    - final resolver output has coverage for stale declared `1.0` metadata adopting resolved `2.0`
      while carrying excludes;
    - selected leaf names are held as a set to avoid repeated selected-node materialization during
      graph traversal;
    - `mainBucketVariants()` does not attach a declared owner to a leaf unless that owner is present
      in `extendsFrom` metadata or all of the owner's parents are present there;
    - surviving globally merged leaf buckets adopt selected ancestor versions for the same
      `group:name`, so a same-named leaf bucket cannot shadow an ancestor's higher Gradle-selected
      version.
  - checks passed:
    `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.DependencyBucketPlacementEngineTest" --console=plain`
    and
    `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --console=plain`.
  - broader local checks also passed:
    `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.DependencyBucketPlacementEngineTest" --tests "com.grab.grazel.gradle.variant.BucketHierarchyGraphTest" --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --tests "com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependenciesTest" --tests "com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionServiceTest" --tests "com.grab.grazel.gradle.dependencies.ResolvedComponentsVisitorTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallArtifactsCalculatorTest" --tests "com.grab.grazel.gradle.DefaultDependenciesDataSourceTest" --tests "com.grab.grazel.migrate.android.DefaultAndroidLibraryDataExtractorTest" --tests "com.grab.grazel.migrate.android.AndroidTestTargetTest" --console=plain`,
    `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest" --console=plain`,
    `./gradlew migrateToBazel --console=plain`,
    `reports/scripts/verify-default-task-graph.sh`,
    `reports/scripts/verify-sample-bucket-labels.sh`, and `git diff --check`.
- PAX app unit-test note: repeated discovery found no generated `gps-pax-debug` app unit-test target
  under `//app:*`; the app-specific generated test targets are lint tests only.
- Regression coverage added:
  - `MavenInstallArtifactsCalculatorTest.extension override target wins for default owner inherited artifacts`.
- Added databinding/direct-dependency regression:
  - `DefaultDependenciesDataSourceTest.collectMavenDeps keeps explicitly declared annotation dependency for non databinding modules`.
  - `DefaultDependenciesDataSourceTest.collectMavenDeps omits databinding provided annotation dependency for databinding modules`.
  - Red failure first exposed AGP-injected `com.android.databinding:baseLibrary` being treated as a
    Bazel Maven dep. Later PAX app builds exposed that grab-bazel-common databinding macros already
    inject `@maven//:androidx_annotation_annotation`, so databinding-enabled targets must also
    filter `androidx.annotation:annotation` from direct deps. Non-databinding modules still keep
    explicit annotation deps.
- Recent local checks passed:
  - focused red/green regression above;
  - full `DefaultDependenciesDataSourceTest`;
  - `BucketHierarchyGraphTest`, `DependencyBucketPlacementEngineTest`, and
    `ComputeWorkspaceDependenciesTest`;
  - `AggregatedDependencyResolverTest`, `DefaultDependencyResolutionServiceTest`,
    `ResolvedComponentsVisitorTest`, and `MavenInstallArtifactsCalculatorTest`;
  - `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.DefaultDependenciesDataSourceTest" --tests "com.grab.grazel.migrate.android.DefaultAndroidLibraryDataExtractorTest" --tests "com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependenciesTest" --console=plain`
  - `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.DefaultDependenciesDataSourceTest" --tests "com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionServiceTest" --tests "com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependenciesTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallArtifactsCalculatorTest" --console=plain`
  - `./gradlew migrateToBazel --console=plain`
  - `reports/scripts/verify-default-task-graph.sh`
  - `reports/scripts/verify-sample-bucket-labels.sh`
  - `git diff --check`

## PAX Evidence

- Latest PAX verification after post-baseline graph/review fixes:
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`
    passed with `BUILD SUCCESSFUL in 17m 50s` (`4737` actionable tasks; `4596` executed,
    `118` from cache, `23` up-to-date).
  - `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures`
    passed with `Build completed successfully`, elapsed `169.669s`, `6009` total actions, and
    `7153` disk cache hits.
  - PAX `git diff --check` passed.
  - `bazelisk query 'kind(".*test rule", //app:*)'` found only app lint tests:
    `//app:app-gps-pax-debug.lint_test` and sibling flavor lint targets. No generated
    `gps-pax-debug` app unit-test target exists under `//app:*`.
- Historical PAX ownership fix:
  - `deliveries-menu-items` used `androidx.annotation.VisibleForTesting` without declaring
    `androidx.annotation:annotation`.
  - PAX-side fix added `implementation Libs.supportAndroidAnnotations`.
  - Focused target
    `//deliveries/deliveries-menu-items:deliveries-menu-items-gps-pax-debug_kt` then passed.
  - This was a PAX direct-dependency fix, not a Grazel tag-broadening or databinding auto-injection
    fix.
- Databinding decision:
  - Do not auto-add databinding or annotation artifacts in Grazel for databinding/viewbinding
    modules.
  - Filter databinding-provided artifacts from direct Bazel deps for databinding-enabled targets:
    `androidx.databinding:*`, `com.android.databinding:*`, and `androidx.annotation:annotation`.
  - Non-databinding modules can still emit explicit annotation deps.
- Generated output audit:
  - `app-gps-pax-debug` retains `enable_data_binding = True`, has no direct
    `androidx_annotation_annotation` entry, and PAX build passes.
  - `app-gps-pax-debug-android-test` tags are normalized/local (`@direct`, `@maven`, `@self`) with
    no observed duplicate tags.
  - PAX generated deps moved away from broad variant repos in several places; reductions are
    acceptable under the current bucket-dedupe goal.
  - Latest bounded target count comparison vs `HEAD:app/BUILD.bazel`:
    - `app-gps-pax-debug`: `deps=1446` unchanged, `tags=0` unchanged, no direct annotation dep;
      `@debug_maven` direct deps reduced `35 -> 6`.
    - `app-gps-pax-debug-android-test`: `deps=1504` unchanged, `tags=1950` added as normalized
      local filter tags with `duplicate_tags=0`; `@debug_maven` direct deps reduced `14 -> 1` and
      `@android_test_maven` direct deps reduced `34 -> 12`.
    - PAX overall generated diff remains intentionally large (`2226` changed paths), dominated by
      generated BUILD/JSON churn and bucket reductions; the accepted shape is the target-local
      bounded audit plus passing APK/android-test builds, not raw line count.
- Known non-blocking PAX items:
  - rules_jvm_external still prints duplicate-version debug messages for annotation/databinding,
    Dagger, and Kotlin artifacts. Audit found no duplicate watched artifact rows in generated Maven
    lock JSONs; evidence points to existing `WORKSPACE` composition, not this refactor.
  - `//app:app-gps-pax-debug.lint_test` fails on `SerializedNameDefaultValue` in external Maven AARs
    and was audited as a preexisting baseline lint exposure, not a dependency-refactor missing-class
    failure.

## Later Performance Follow-Ups

- Consider Gradle Worker API only after correctness/merge gates are finished. The safer candidate is
  CPU-only post-resolution work: project/variant bucket placement and metadata transformation over
  immutable JSON/serializable inputs, followed by deterministic sorted merge. Do not parallelize
  Gradle resolution itself unless the inputs are isolated and cache/configuration-cache safe.
- Keep graph work bounded: precompute ancestor/leaf relationships once per placement graph, dedupe
  by stable keys, and avoid repeated eager `groupBy`/`flatten`/leaf scans in resolver or module
  generation code.

## Remaining Gates

- Run final Grazel `git diff --check`, commit, and push.

## Resource Notes

- Current free space after the latest PAX `bazelisk clean --expunge` is about `30GiB`.
- Idle PAX Bazel/lint workers and the Gradle daemon were stopped with `bazelisk shutdown` and
  `./gradlew --stop` after the lint run left large resident workers.
- Check disk/CPU before expensive PAX commands.
- Prefer `bazelisk clean --expunge` before deleting PAX `bazel-cache`.
- Use `rm -rf bazel-cache` only when genuinely needed.
