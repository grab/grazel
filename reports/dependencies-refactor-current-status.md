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
- Latest override-scoping checkpoint, 2026-06-23:
  - Restored master-like `override_targets` scoping in `MavenInstallArtifactsCalculator`: generated
    override entries now come only from artifacts already present in that Maven repo plus matching
    extension overrides. The removed behavior synthesized default-owned transitive overrides into
    non-default repos and bloated PAX `WORKSPACE`.
  - Focused Grazel checks passed:
    `MavenInstallArtifactsCalculatorTest`,
    `ComputeWorkspaceDependenciesTest`, and `DefaultArtifactPinnerTest`.
  - PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace` passed in `22m 9s`;
    PAX `git diff --check` passed.
  - PAX `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
    passed after automatic `bazel.sh` retries. Two earlier attempts failed with remote-cache
    missing-blob errors for generated outputs, then the final retry completed successfully
    (`40477` total actions, elapsed `2358.554s`). These were cache-integrity misses, not
    missing-dependency compiler errors.
  - PAX `WORKSPACE` improved from the prior refactor's roughly `10K` lines to `5150`, but this is
    still above PAX `master` (`3520`) and above the desired `10-20%` bound. Current remaining growth
    is mostly wider repo artifact lists, especially `android_test_maven`, `test_maven`, and
    `lint_maven`, not the previous runaway override synthesis. Do not mark the workspace-size gate
    complete until this bucket-content growth is explained or reduced.
- Test-bucket precision checkpoint, 2026-06-23:
  - Read-only audit vs PAX master showed `WORKSPACE` growth concentrated in bucket contents, not
    repo count: `android_test_maven +335` artifacts, `test_maven +180`, `debug_maven +66`,
    `lint_maven +61`; no repos were added and `maven` shrank by `44` visible artifacts.
  - Root cause for the largest remaining growth was the deliberate broad test/androidTest shortcut
    in `AggregatedDependencyResolver`: it built graph-backed test placement plans and then flattened
    every test hierarchy/leaf dependency back into `test` or `androidTest`.
  - New behavior emits the planned test buckets directly. Explicit common test deps stay in
    `test`; shared typed deps can move to hierarchy buckets such as `debugUnitTest`; leaf-only deps
    can move to concrete leaf buckets such as `freeDebugUnitTest` or `freeDebugAndroidTest`.
  - Main inheritance is still preserved by subtracting `mainCoveredDepsByProject` before emitting
    test buckets, matching the long-term `test extends main` model without broadening the base test
    repos.
  - TDD evidence: `AggregatedDependencyResolverTest` was changed first and failed with the old
    broad behavior; production change then made it pass.
  - Focused checks passed after the change:
    `AggregatedDependencyResolverTest`, `DependencyBucketPlacementEngineTest`,
    `ComputeWorkspaceDependenciesTest`, `MavenInstallArtifactsCalculatorTest`, and
    `DefaultArtifactPinnerTest`.
  - Next gate: rerun PAX `migrateToBazel`, measure `WORKSPACE` against PAX master, then build
    `//app:app-gps-pax-debug.apk` and `//app:app-gps-pax-debug-android-test.apk` if diff shape is
    acceptable.
- Project-edge traversal checkpoint, 2026-06-23:
  - A PAX `migrateToBazel` attempt was interrupted during `:resolveWorkspaceDependencies` after a
    thread sample showed CPU time in `AggregatedDependencyResolver.collectMainProjectEdgeScope`,
    especially repeated declared project-edge parsing and project-path prefix scans.
  - Root cause was not Gradle resolution. The resolver computed dependency edges before checking
    whether a project had already been visited, so cycles could re-enter already-visited projects.
    It also treated child variants extending a selected parent as selected project-edge variants,
    causing unselected root variant excludes such as `debugImplementation` to leak into a
    `default` root scope.
  - Added a regression test:
    `project dependency cycle does not apply unselected root variant excludes`. It failed before
    the fix and passes now.
  - Fix:
    - Cache parsed declared project dependency edges and edge lists by project/selected variant set.
    - Mark a project visited before expanding its project edges.
    - For selected root traversal, include only variants whose own name is in the selected hierarchy
      set; do not include arbitrary child variants solely because they extend a selected parent.
  - Focused checks passed after this change:
    `AggregatedDependencyResolverTest`, `DependencyBucketPlacementEngineTest`,
    `ComputeWorkspaceDependenciesTest`, `MavenInstallArtifactsCalculatorTest`, and
    `DefaultArtifactPinnerTest`.
- AndroidTest direct-dependency investigation, 2026-06-24:
  - PAX `migrateToBazel` failed in `:app:generateBazelScripts` because
    `com.squareup.leakcanary:leakcanary-android-instrumentation` is declared in
    `:app:androidTestImplementation` but no workspace bucket exposed a matching Maven label.
  - PAX declared metadata confirms the declaration is real and bucketed as `:app/androidTest`:
    variant `androidTest`, type `AndroidTest`, `bucketName=androidTest`,
    `configurationName=androidTestImplementation`, id `...:2.14`.
  - Current JSON confirms the drop happens before project BUILD generation:
    `workspace-dependency-results.json` and `dependencies.json` have an `androidTest` bucket but
    no LeakCanary instrumentation artifact.
  - Added/updated focused Grazel tests for:
    - base `androidTest` declarations absent from selected leaf closure;
    - base `androidTest` declarations with multiple selected androidTest leaves;
    - explicit test declaration surviving main subtraction when the same resolved artifact is in
      main.
  - PAX dependency-only evidence showed the resolver can own and emit the artifact in `androidTest`.
    Full `migrateToBazel` then exposed the real loss: final planned-test bucket assembly inserted
    the base bucket (`androidTest`) and then overwrote it with a leaf bucket of the same name.
  - Fix: duplicate planned test bucket names are merged with dependency-map union instead of
    replaced. This keeps direct base `androidTestImplementation` declarations when the base and leaf
    bucket names collide.
  - Temporary diagnostics were removed from production code.
  - Focused Grazel checks passed after the fix:
    `AggregatedDependencyResolverTest`, `DependencyBucketPlacementEngineTest`,
    `ComputeWorkspaceDependenciesTest`, `MavenInstallArtifactsCalculatorTest`, and
    `DefaultArtifactPinnerTest`.
  - PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace` passed in `19m 43s`.
    `dependencies.json` now places LeakCanary instrumentation in `androidTest`, and PAX
    `git diff --check` passed. `WORKSPACE` is `5149` lines, improved from the earlier `10K` blow-up
    but still above the old PAX master reference, so workspace shape remains a review gate.
  - Current verification in progress: PAX Bazel build for
    `//app:app-gps-pax-debug.apk` and `//app:app-gps-pax-debug-android-test.apk`.
- Resolver directness and workspace-bloat checkpoint, 2026-06-24:
  - Fixed a selected-only project-edge traversal leak in `AggregatedDependencyResolver`: when a
    selected root variant traversed transitive project dependencies, the recursive call dropped the
    selected-only flag and could apply unselected owner-variant excludes. Added the regression
    `transitive project dependency edge excludes do not apply unselected owner variants`.
  - Corrected default-owner override directness in `ComputeWorkspaceDependencies`: when a non-default
    bucket points at a default-owned transitive via `overrideTarget`, the override carrier must stay
    non-direct even if the default bucket has the artifact direct. Added the regression
    `default owned child transitive override does not become direct`.
  - Focused Grazel checks passed after both fixes:
    `AggregatedDependencyResolverTest`, `DependencyBucketPlacementEngineTest`,
    `ComputeWorkspaceDependenciesTest`, and `MavenInstallArtifactsCalculatorTest`.
  - PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace` passed in `11m 39s`;
    PAX `git diff --check` passed. The latest `WORKSPACE` is still `5150` lines, so the directness
    fix was metadata-correct but did not solve the remaining workspace bloat.
  - Current root-cause hypothesis for remaining bloat: project-traversing root resolution drops
    non-direct external components before bucket planning, then `MavenInstallRootArtifacts` expands
    root artifacts afterward. That means test/androidTest-vs-main subtraction can happen before the
    main transitive closure is visible, so main-covered transitives can reappear in test repos as
    overrides. Next step is a focused failing test across resolver -> workspace computation ->
    Maven-install root artifact calculation before changing production code.
- Workspace compacting checkpoint, 2026-06-24:
  - Read-only audits confirmed PAX current `WORKSPACE` bloat is mostly duplicate repo-local roots
    and same-version override redirects, not new unique Maven surface: about `5150` lines current vs
    `3520` on PAX master, only `+24` unique coordinates, but about `+729` artifact entries and
    `+660` override entries.
  - Grazel master reference: `ResolveVariantDependenciesTask` emitted direct roots with transitive
    closure and `ComputeWorkspaceDependencies` created automatic override targets only for non-direct
    deps already present in default. It also built transitive classpath from direct deps only.
  - Added a regression that failed before the fix:
    `variant maven install does not root same resolved default owned transitive`. The test runs
    resolver-style results through `ComputeWorkspaceDependencies` and then
    `MavenInstallArtifactsCalculator`, reproducing same-version default-owned transitives being
    rooted again in `android_test_maven`.
  - Fix shape:
    - `ComputeWorkspaceDependencies` now drops same-version default-owned non-direct child entries
      instead of turning them into child override carriers. It still keeps child carriers when the
      default owner has the higher Gradle-resolved version.
    - `MavenInstallRootArtifacts` now skips same-version default-owned inherited transitives for
      non-lint child repos, while keeping version-correction carriers, non-default owner redirects,
      and lint's selected tool closure.
  - Focused Grazel checks passed:
    `AggregatedDependencyResolverTest`, `DependencyBucketPlacementEngineTest`,
    `ComputeWorkspaceDependenciesTest`, and `MavenInstallArtifactsCalculatorTest`.
  - Next gate: rerun PAX `migrateToBazel`, compare `WORKSPACE` line/artifact/override counts against
    PAX master, then run the debug APK and android-test APK Bazel builds if the diff shape is sane.
  - During the next PAX run, `WORKSPACE` had already dropped to about `2154` lines while
    `android_test_maven` pinning was still in progress, indicating the duplicate `override_targets`
    text bloat was largely removed. However, pinning then spent a long time in
    `@unpinned_android_test_maven//:pin` and Coursier printed an OOM while rendering a conflicting
    dependency tree. Do not treat line-count reduction alone as sufficient. The Maven-install JSON /
    pin input for each bucket, especially `android_test_maven`, must also be compared against PAX
    master/current expectations so inverted fanout gains are not amortized away by larger pinning
    inputs or harder conflict graphs.
  - Follow-up root cause: making child Maven repos too compact removed selected transitive
    coordinates from Coursier's force-version inputs. `version_conflict_policy = "pinned"` only
    forces artifacts listed as roots, so Coursier could rediscover lower/ranged transitive versions
    and emit huge conflict trees. New intended split: Maven artifact roots express ownership and
    materialization; generated `additional_coursier_options` express Gradle-selected transitive
    values via force-only `--force-version` pairs.
  - Added a focused red/green test that `android_test_maven` does not root a same-version
    default-owned transitive but does add it as a generated Coursier force option. Focused
    calculator/resolver tests passed after the change.
  - PAX `generateRootBazelScripts` then failed before root generation on inactive
    `:enterprise-business-profile-ui-tests` variant matching (`release` build type mismatch). This
    is separate from Maven pinning and should be treated as the reachable-generation architecture
    issue: root-selected PAX generation should not be blocked by modules outside the selected
    app/android-test graph. For fast pin feedback, a temporary excluded-task generation run is
    acceptable, but the final merge gate must make normal `migrateToBazel` clean.
  - Added a pre-match app-variant filter to `VariantMatcher` and wired reachable bucket filtering
    into Android test generation. `generateRootBazelScripts` then passed on PAX in `10m 45s`,
    clearing the previous inactive UI-test release mismatch.
  - Fast pin feedback learning: `bazelisk run @unpinned_debug_maven//:pin` is useful only after the
    generated `WORKSPACE` stops materializing unreachable repos. The no-script direct run touched
    `gps_pax_release_maven`/`staging_maven` and failed with a Bazel repository mapping cycle, not
    the previous Coursier conflict/OOM. Root cause: Android library compression still emitted
    release/staging targets, and `WorkspaceBuilder` also unioned generated project manifests with
    unfiltered `VariantCompressionService.referencedMavenRepos()`. That resurrected unreachable
    Maven repos even when project-level generation became reachable-aware.
  - Current fix in progress: Android library generation now keeps compressed target suffixes only
    when at least one reachable matched variant maps to the suffix; unit-test generation uses the
    same app-variant reachability filter. `WorkspaceBuilder` now uses generated target manifests as
    the root Maven repo materialization source instead of reading the unfiltered compression cache.
    Focused target reachability, variant matcher, workspace dependency, and Maven install tests
    passed after this change. Next gate is to regenerate PAX root scripts and confirm
    release/staging Maven repos disappear before retrying direct pin.
  - Correction after regenerating PAX: release/staging repos did not disappear because root
    reachability itself includes them. `:app` currently reports reachable buckets
    `debug/default/gps/gpsPaxDebug/gpsPaxRelease/gpsPaxStaging/pax/release/staging`. Therefore the
    remaining release/staging Maven repos are not just stale compression leakage; they follow the
    current "all root app variants" resolution policy. The shared reachability predicate is still
    the right target-generation altitude, but shrinking to debug-only requires an upstream root
    variant-selection decision, not more target-builder filtering.
  - Fast pin loop update: direct Bazel pinning is useful, but only after PAX's post-generation
    workspace patch task has run. `generateRootBazelScripts` alone emits a pre-patch `WORKSPACE`
    that lacks PAX's `rules_java_builtin` archive and custom repository patches, causing even
    `bazelisk query //:all` to fail with a `rules_java_builtin` repository-mapping cycle.
  - Verified fast loop after `./gradlew addAdditionalRepositories --no-daemon --console=plain
    --stacktrace`: `bazelisk query //:all` passed, then
    `bazelisk run @unpinned_debug_maven//:pin --script_path=/tmp/pax-debug-maven-pin-after-pax-post.sh`
    passed, and executing the script reported `debug_maven_install.json` up to date.
    The same loop passed for `@unpinned_android_test_maven//:pin`.
  - Current architecture concern remains: `android_test_maven_install.json` now pins successfully,
    but its generated diff/size is still larger than the PAX-master baseline and can amortize away
    the inverted fanout gains. Treat Maven-install JSON bucket size and contents, not just
    `WORKSPACE` line count, as a merge gate. Compare each bucket against PAX master and optimize the
    root/bucket placement rather than accepting a broad android-test bucket by default.

## Resource Notes

- PAX reachability/build gate update:
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace` passed after wiring test
    and android-test root traversals into `reachableMainBucketsByProject`.
  - The concrete missing-target symptom was
    `//food-rating-ui-tests:food-rating-ui-tests-gps-pax-debug` referenced from the app
    android-test target while `food-rating-ui-tests/BUILD.bazel` was empty. Root cause: modules
    reachable only through android-test roots were not marking their main buckets reachable.
  - Regression test added: android-test roots now mark reached project main buckets reachable.
    PAX generated `food-rating-ui-tests/BUILD.bazel` with the expected
    `food-rating-ui-tests-gps-pax-debug` target and reachability `default/debug`.
- PAX duplicate databinding update:
  - After the reachability fix, Bazel analysis failed because `app-gps-pax-debug` saw
    `androidx.databinding:databinding-adapters` from `@maven`, `@debug_maven`, and `@gps_maven`.
  - Corrected root cause/model: generated `--force-version` was a shortcut. Grazel master used
    resolved transitive closure in `maven_install.artifacts` as the Coursier value constraint, so
    child repos must include reachable Gradle-resolved transitives at selected versions. Coursier
    then sees the selected versions directly in artifacts instead of relying on POM conflict
    behavior or generated force options.
  - Correct fix shape: `artifacts` carries the reachable resolved closure for Coursier correctness;
    `override_targets` carries Bazel label ownership. A default-owned transitive can appear in a
    child repo's `artifacts` to force Coursier's version while `override_targets` redirects the
    Bazel label back to `@maven`.
  - Focused Maven install tests now assert closure-in-artifacts, default-owned transitive
    redirection, promoted-root closure recovery, and absence of generated `--force-version`.
- Verification after these fixes:
  - Focused Grazel suite passed:
    `MavenRulesTest`, `TargetVariantReachabilityTest`, focused `DefaultVariantMatcherTest`,
    `MavenInstallArtifactsCalculatorTest`, `ComputeWorkspaceDependenciesTest`,
    `AggregatedDependencyResolverTest`, and `DependencyBucketPlacementEngineTest`.
  - `reports/scripts/verify-default-task-graph.sh` and
    `reports/scripts/verify-sample-bucket-labels.sh` passed after the Maven override fix.
  - PAX `migrateToBazel` passed in `13m 33s` after restoring closure-in-artifacts semantics.
  - Generated PAX `WORKSPACE` has no generated `--force-version` entries. Databinding appears in
    child repo `artifacts` and has `override_targets` back to `@maven`.
  - PAX Bazel gate passed:
    `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
    completed successfully after the closure-in-artifacts correction in `200.447s` with `53840`
    total actions.
  - `git diff --check` passed in both PAX and Grazel after the build.
- Remaining architecture/perf note: PAX `WORKSPACE` is now about `5025` lines. This is acceptable
  for the current milestone per discussion; optimization should be discussed before implementing.
- Resource cleanup: the PAX Bazel build dropped disk to about `11GiB` free; ran
  `bazelisk clean --expunge`, recovering to about `27GiB`. `bazel-cache` was about `17GiB` and was
  left intact because disk pressure was no longer critical.
- Verification update: focused Grazel dependency/bucketing tests, `git diff --check`,
  `reports/scripts/verify-default-task-graph.sh`, and
  `reports/scripts/verify-sample-bucket-labels.sh` passed after the latest changes.
- Superseded note: the earlier promoted-root `--force-version` fix was intentionally replaced.
  Promoted roots are now handled by adding their reachable Gradle-resolved closure to
  `maven_install.artifacts` with ownership redirects, which matches the master Grazel contract.
- Important fast-loop lesson: stale `--script_path` pin wrappers can appear to pass because they
  point at an older external repo/runfiles tree. For pin verification after WORKSPACE changes, use
  the freshly generated script under `build/grazel/maven/` or rerun the full Gradle pin task.
- Current free space after the latest PAX `bazelisk clean --expunge` is about `30GiB`.
- Idle PAX Bazel/lint workers and the Gradle daemon were stopped with `bazelisk shutdown` and
  `./gradlew --stop` after the lint run left large resident workers.
- Check disk/CPU before expensive PAX commands.
- Prefer `bazelisk clean --expunge` before deleting PAX `bazel-cache`.
- Use `rm -rf bazel-cache` only when genuinely needed.

## Later Architecture Option

- If the current intersection/residual bucket engine keeps fighting correctness or workspace size,
  revisit a cleaner split: declarations drive placement, Gradle resolution drives values, and
  `ComputeWorkspaceDependencies` plus `override_targets` remain the load-bearing value layer.
- In that model, direct declarations pick owners cheaply (`debugImplementation` -> `debug`,
  `testImplementation` -> `test`, etc.). Gradle-resolved selected artifacts, transitive closure,
  excludes, and override redirects still come from root resolution and the workspace compute layer.
- Desired invariant: shared same-version artifacts live in `@maven` once; child repos carry genuine
  deltas, especially version-divergent coordinates. Do not pivot to this while the current verified
  path is converging, but keep it as the fallback if bucket intersection continues to create
  duplicate repo roots or broad override maps.
