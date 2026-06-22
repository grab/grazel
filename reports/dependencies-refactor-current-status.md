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

- Recent PAX `migrateToBazel` passed:
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`
- Current unresolved gate:
  - command: `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
  - failure target: `//deliveries/deliveries-menu-items:deliveries-menu-items-gps-pax-debug_kt`
  - symptom: unresolved `androidx.annotation.VisibleForTesting` in
    `deliveries/deliveries-menu-items/src/main/java/com/grab/pax/deliveries/menu/base/PopulatedItemsHelper.kt`
- Current blocker is not yet proven. Earlier notes conflict about whether
  `:deliveries:deliveries-menu-items` truly owns `androidx.annotation:annotation` for this target.
  Verify ownership with focused diagnostics before changing Grazel or copying PAX master tags.
- Focused ownership evidence collected on 2026-06-22:
  - `deliveries-menu-items` source directly imports/uses `androidx.annotation.VisibleForTesting`.
  - its `build.gradle` does not declare `Libs.supportAndroidAnnotations`,
    `androidx.annotation:annotation`, or `annotation-experimental`.
  - fresh `build/grazel/declared-dependency-metadata.json` shows the module's only direct Maven
    declaration is `com.grab.logger:logsdk:1.0.0`; no annotation declaration is present.
  - Gradle `dependencyInsight --configuration debugCompileClasspath --dependency
    androidx.annotation:annotation` shows annotation on the compile classpath through AGP/databinding
    and project/transitive paths, with consistent-resolution constraints selecting `1.3.0`.
  - The module-local stale `deliveries-menu-items/build/grazel/default/dependencies.json` from
    June 10 contains broad `logsdk` transitive closure, but it is older than the current generated
    root workspace data and must not be used as current evidence.
- Current interpretation: the PAX source appears to rely on an implicit transitive/AGP compile
  classpath entry. Prefer adding an explicit PAX module dependency before broadening Grazel tags or
  auto-injecting databinding artifacts.
- 2026-06-22 decision: do not auto-add databinding or annotation libraries in Grazel when a module
  has databinding/viewbinding enabled. The production filter that drops AGP/databinding internals is
  intentional; if source imports `androidx.annotation.VisibleForTesting`, the owning module should
  declare `androidx.annotation:annotation`.
- Nuance for the next Grazel-side fix/test: excluding AGP/databinding internals is different from
  dropping a dependency the user explicitly declared. Keep `androidx.databinding:*` out by default,
  but verify whether a databinding module that explicitly declares `androidx.annotation:annotation`
  should still emit that direct dep/tag instead of being filtered by `project.hasDatabinding`.
- Applied PAX ownership fix:
  `deliveries/deliveries-menu-items/build.gradle` now declares
  `implementation Libs.supportAndroidAnnotations`.
- Fresh PAX declared metadata after that edit shows
  `:deliveries:deliveries-menu-items` default AndroidBuild direct deps include both
  `androidx.annotation:annotation:1.3.0` and `com.grab.logger:logsdk:1.0.0`.
- A first PAX `migrateToBazel` rerun after the edit was stopped because the Gradle JVM became
  unresponsive while disk dropped to `3.7GiB` free. Ran `bazelisk clean --expunge` in PAX, which
  recovered disk to about `37GiB`. `bazel-cache` was left in place because space was no longer low.
- Fresh PAX `migrateToBazel` after cleanup passed:
  - command: `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`
  - captured log: `/tmp/pax-migrate-after-annotation.log`
  - result: `BUILD SUCCESSFUL in 15m 8s`
- Regenerated `deliveries/deliveries-menu-items/BUILD.bazel` now gives
  `deliveries-menu-items-gps-pax-debug`:
  - direct dep `@maven//:androidx_annotation_annotation`;
  - normalized tag `@maven//:androidx_annotation_annotation`;
  - no broad project-child Maven tag union.
- Focused previous-failure target passed:
  - command:
    `./bazel.sh build //deliveries/deliveries-menu-items:deliveries-menu-items-gps-pax-debug_kt --verbose_failures`
  - captured log: `/tmp/pax-focused-deliveries-menu-items.log`
  - result: target built successfully in `97.815s`; `VisibleForTesting` compile error is resolved.
- Full PAX app/android-test gate passed:
  - command:
    `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures`
  - captured log: `/tmp/pax-app-debug-and-android-test.log`
  - result: `Build completed successfully`, `53635` total actions, elapsed `943.979s`.
  - disk after this build: about `14GiB` free, so cleanup/resource planning is required before more
    heavy PAX verification.
- Fresh PAX `migrateToBazel` after the databinding filter test split passed:
  - command: `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`
  - result: `BUILD SUCCESSFUL in 17m 12s`; `resolveWorkspaceDependencies` and
    `computeWorkspaceDependencies` both ran.
- First APK rebuild after that migration failed during Bazel package loading:
  - command:
    `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures`
  - symptom: `@maven//:androidx_annotation_annotation` duplicated in generated internal
    databinding app rules such as `lib_app-gps-pax-debug_base` `exports`,
    `lib_app-gps-pax-debug_kt` `deps`, and `_app-gps-pax-debug_lint_sources` `deps`.
  - root cause: PAX app generated `android_binary(... enable_data_binding = True ...)` was also
    passed direct dep `@maven//:androidx_annotation_annotation`, while grab-bazel-common
    `DATABINDING_DEPS` injects the same label for databinding. PAX did not declare the dep twice.
  - fix: treat `androidx.annotation:annotation` as databinding-provided only for
    databinding-enabled targets; keep it for non-databinding direct declarations.
  - local proof after fix:
    `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.DefaultDependenciesDataSourceTest.collectMavenDeps omits databinding provided annotation dependency for databinding modules" --tests "com.grab.grazel.gradle.DefaultDependenciesDataSourceTest.collectMavenDeps keeps explicitly declared annotation dependency for non databinding modules" --console=plain`
    passed.
  - broader local proof:
    `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.DefaultDependenciesDataSourceTest" --console=plain`
    passed.
- Fresh PAX migration after the duplicate-annotation fix passed:
  - command: `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`
  - result: `BUILD SUCCESSFUL in 16m 57s`
  - `resolveWorkspaceDependencies`, `computeWorkspaceDependencies`, `generateDatabindingMetaData`,
    root script generation, formatting, and artifact pinning all ran.
- Focused generated-app inspection after that migration:
  - `android_binary(name = "app-gps-pax-debug")` still has `enable_data_binding = True`.
  - a narrow awk scan found no `androidx_annotation_annotation` entry in that target's direct
    `deps` or `tags`; the previous duplicate direct-dep failure is gone.
- Fresh PAX debug APK + android-test APK gate passed after the duplicate-annotation fix:
  - command:
    `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures`
  - result: `Build completed successfully`, elapsed `863.996s`, `29546` total actions.
  - `//app:lib_app-gps-pax-debug_kt` KSP/KAPT/compile and
    `//app:app-gps-pax-debug-android-test_lib_kt` KAPT/compile both completed.
  - rules_jvm_external still printed duplicate-version debug messages for artifacts such as
    `androidx.annotation:annotation`, databinding artifacts, Dagger, and Kotlin artifacts. They did
    not fail this build, but keep them in mind for a later Coursier warning/bucket cleanup audit.
- PAX `git diff --check` passed after the generated output.
- Bounded PAX generated target/tag audit after the passing build:
  - `app-gps-pax-debug`: current and `HEAD` both have `deps=1446`, `tags=0`, and no
    `androidx_annotation_annotation` direct entry; generated target keeps `enable_data_binding =
    True`.
  - `app-gps-pax-debug-android-test`: current `tags=1950`, split as `@direct=1334`,
    `@maven=615`, `@self=1`, with no duplicate tags. `HEAD` had no tags block for this target.
  - android-test `deps` count stayed at `1504`; variant Maven repo deps reduced from `HEAD`
    (`@debug_maven 14 -> 1`, `@android_test_maven 34 -> 12`, `@test_maven 2 -> 0`).
  - audit verdict: no obvious generated BUILD violation of the local tag contract; hard proof of
    no parent Maven union would require resolver metadata comparison, but the generated shape is
    target-local (`@direct`, normalized `@maven`, `@self`) and the PAX build passed.
- Duplicate-version warning audit:
  - PAX and local Grazel builds print rules_jvm_external duplicate-version debug messages for
    annotation/databinding/Dagger/Kotlin families.
  - audit found no duplicate watched artifact rows inside the generated Maven lock JSONs.
  - evidence points to existing `WORKSPACE` composition (`DAGGER_ARTIFACTS +
    GRAB_BAZEL_COMMON_ARTIFACTS + [...]`) and unchanged `grab_bazel_common`/Dagger references, not
    a clear current-refactor regression.
  - treat as non-blocking for this goal unless the merge bar expands to warning cleanup.
- Resource cleanup after the successful PAX gate:
  - disk dropped to about `14GiB` free during APK packaging;
  - ran `bazelisk clean --expunge` in PAX after the build passed;
  - disk recovered to about `30GiB` free;
  - no PAX Bazel/Gradle processes remained afterward.

## Focused Diagnostic Loop

Before another full PAX migration/APK build, gather a small diagnostic output for the failing
module/variant:

- Gradle configurations that feed `deliveries-menu-items-gps-pax-debug`.
- Declared Maven deps seen by Grazel for those configurations.
- Whether `androidx.annotation:annotation` is present, and in which bucket/repo.
- `collectTransitiveMavenDeps` input roots and closure result for this target.
- Generated Bazel `deps`/`tags` for the failing target.

Good mechanisms:

- add temporary `logger.quiet` at the exact Gradle task/data boundary;
- add a small focused diagnostic task/file under `build/grazel/...`;
- use targeted `jq`/scripts instead of reading large JSON in main context;
- ask a subagent to inspect a clean slice, with required exact file/line citations.

## Decision Rule For The Blocker

- If the PAX source uses a Maven artifact without declaring it, fix PAX `build.gradle` and rerun
  migration instead of broadening Grazel tags.
- If Grazel dropped a valid declared dependency or closure, add a focused Grazel test first and fix
  the lowest correct layer.
- If the class should arrive through a project dependency, verify the child target owns correct
  direct Maven deps/tags rather than copying its Maven closure into the parent.

## Remaining Gates

- The detailed goal prompt references
  `reports/dependencies-refactor-dag-test-bucket-next-goal.md` and
  `reports/dependencies-refactor-dag-test-bucket-foundation.md`, but those files are not present in
  the current worktree. Continue from `dependencies-refactor-active-anchor.md`,
  `dependencies-refactor-current-truth.md`, and this status file unless the missing files reappear.
- PAX app unit/lint discovery:
  - `bazelisk query 'kind(".*test rule", //app:*)'` found lint tests only for app variants;
  - no generated `gps-pax-debug` app unit-test target was found under `//app:*`;
  - current app-specific gate is `//app:app-gps-pax-debug.lint_test`.
- PAX lint result:
  - command:
    `./bazel.sh test //app:app-gps-pax-debug.lint_test --test_output=errors --verbose_failures`
  - captured log: `/tmp/pax-app-gps-pax-debug-lint-test.log`
  - result: failed, but not with missing classes; errors are `SerializedNameDefaultValue` issues in
    external Maven AARs such as `com_grab_geo_kampung_map_kampungmap_sdk` and
    `com_grab_karta_poi_kartapoi_sdk_nudge_pax`.
  - focused read-only audit verdict: preexisting baseline exposure for the binary lint target.
  - `//app:app-gps-pax-debug.lint_test` is generated from
    `android_binary(name = "app-gps-pax-debug")`, whose deps already contained
    `@debug_maven//:com_grab_karta_poi_kartapoi_sdk` and
    `@maven//:com_grab_geo_kampung_map_kampungmap_sdk` in both current output and
    `HEAD:app/BUILD.bazel`.
  - Bazel query found `@maven//:com_grab_karta_poi_kartapoi_sdk_nudge_pax` reachable through
    `//app:app-gps-pax-debug -> //app:_app-gps-pax-debug_lint_sources ->
    @debug_maven//:com_grab_karta_poi_kartapoi_sdk ->
    @maven//:com_grab_karta_poi_kartapoi_sdk_nudge_pax`.
  - `HEAD:debug_maven_install.json` already contained `kartapoi-sdk` and its transitive
    `kartapoi-sdk-nudge-pax`; `HEAD:maven_install.json` already contained `kampungmap-sdk`.
  - Caveat: current generated `android_instrumentation_binary(name =
    "app-gps-pax-debug-android-test")` has a new tags block including those artifacts, but the
    failing lint label is the binary lint test and its dependency path already existed at `HEAD`.
- Optional deeper resolver-metadata proof for android-test tags, if requested:
  `jq '.projects[":app"].variants[] | select(.name=="gpsPaxDebugAndroidTest") |
  {declaredDependencies, declaredProjectDependencies}' build/grazel/declared-dependency-metadata.json`
- Final local Grazel checks, simplify pass, and adversarial review.

## Resource Notes

- Current free space after the latest PAX `bazelisk clean --expunge` is about `30GiB`.
- Idle PAX Bazel/lint workers and the Gradle daemon were stopped with `bazelisk shutdown` and
  `./gradlew --stop` after the lint run left large resident workers.
- Check disk/CPU before expensive PAX commands.
- Prefer `bazelisk clean --expunge` before deleting PAX `bazel-cache`.
- Use `rm -rf bazel-cache` only when genuinely needed.
