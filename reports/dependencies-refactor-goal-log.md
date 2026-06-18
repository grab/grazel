# ARCHIVE: Dependencies Refactor Compatibility Goal Log

This file is historical context for the completed compatibility-hardening goal.
It is intentionally long. New goal sessions should read
`reports/dependencies-refactor-merge-readiness-goal.md` first and should not
read this full archive unless debugging historical intent.

Compatibility goal closed on 2026-06-18. Next active plan moved to
`reports/dependencies-refactor-merge-readiness-goal.md`.

## Current Status / Next Action

Current status as of 2026-06-17 23:50:59 +08:
- Branch: `arun/dependencies-refactor`.
- Goal source: `/Users/arun.sampathkumar/.codex/attachments/2d30ffba-8dfc-49e6-91be-a2fc7d803c68/pasted-text-1.txt`.
- `aggregatedDependencyResolution` is convention-default `true`; the default task graph no longer schedules the old per-project/per-variant `*ResolveDependencies` fan-out for `computeWorkspaceDependencies` or `migrateToBazel`.
- The current hardening slice is identity-aware bucket reduction plus owner-scoped declared exclude metadata:
  - same `group:artifact` at different versions is not reduced by `shortId` alone;
  - child/default reduction compares effective dependency identity;
  - direct external deps below project nodes carry `directProjectPath`;
  - exclude rules are read from matching declaration-bucket configurations for the current bucket and applied by owner project path, so normal downstream project excludes are preserved without root/global sibling bleed.
- The cheap declared-metadata path now has an explicit `DeclaredDependencyMetadataCollector` collaborator. It still runs during bucketing for the current compatibility slice, but the Gradle declaration walk is no longer embedded inside `AggregatedDependencyResolver`. The collector intentionally ignores `*Classpath` and `*DependenciesMetadata` configurations when reading declared exclude metadata because AGP concrete classpaths can expose inherited declaration excludes as dependencies.
- Root generated outputs currently use broad milestone buckets again: `maven`, `debug_maven`, `free_maven`, `paid_maven`, `demo_maven`, `full_maven`, leaf debug buckets, `test_maven`, `android_test_maven`, `lint_maven`, and `ksp_maven`. This is acceptable for Milestone 1 buildability but not final bucket minimization.
- Focused bucket labels still pass: debug-only paging routes through `@debug_maven`, common app deps route through `@maven`, root app free/paid flavor targets route constraintlayout through `@free_maven`/`@paid_maven`, built-in androidTest monitor routes through `@android_test_maven`, built-in androidTest does not emit duplicate `@android_test_maven//:androidx_core_core`, and constraintlayout exclude metadata is preserved in `WORKSPACE`.
- The selected downstream project fallback fixture now uses real flavor-only externals (`paidImplementation` timber and `freeImplementation` okio). The focused functional test proves the `flavor2` consumer selects the paid fallback target, keeps timber, does not leak okio, and preserves the paid constraintlayout exclude without free-exclude bleed.
- Functional fixture setup now deletes generated dependency JSON and fixture Maven lockfiles before each run. The production stale-output risk found through that fixture is also covered by a conservative `ComputeWorkspaceDependenciesTask` input-file collection over Gradle build scripts and version catalogs.
- Standalone `com.android.test` roots now keep main-overlap deps covered by broad `@maven`; `android_test_maven` owns only androidTest leftovers. This intentionally matches master-style compatibility over precise standalone test bucketing. The sample-label verifier now locks representative `sample-android-tests` generated instrumentation deps (`androidx.test:runner`, `androidx.test:rules`, and Compose UI test JUnit) to `@maven` and rejects `@android_test_maven` for those labels.
- Lint child buckets now keep Gradle-selected default versions for default-owned transitive deps via override targets. This preserves cases such as `com.google.auto.service:auto-service-annotations:1.1.1` in `lint_maven` while labels point at `@maven`.
- Override-carrier child Maven roots are now narrowed to override targets reachable from each child repo's direct roots. The narrowing uses `WorkspaceDependencies.variantTransitiveClasspath` when available and falls back to the legacy global `transitiveClasspath`.
- Pinner status checks now probe a real direct repo root before reachable override carriers, and generated child `override_targets` are filtered from the same root artifact set that the child repo resolves.
- Test and androidTest Maven dependency mapping now queries `default` before test-specific buckets. This matches the existing test-extends-main model and prevents lower test-owned duplicates such as `androidx.core:core:1.10.1` from entering instrumentation targets alongside the app/default `1.13.1`.
- Generated test target label coverage now explicitly locks the complementary test-extends-main behavior: inherited main-only deps such as `androidx.appcompat:appcompat` stay labelled from `@maven` in both generated `android_unit_test` and `android_instrumentation_binary` targets, while direct test/androidTest overrides still use `@test_maven` / `@android_test_maven`.
- Non-app library `testCompileOnly` is now covered by a real generated unit-test fixture. Declared Test/AndroidTest compileOnly metadata is collected from the variant model and routed into broad `test_maven` / `android_test_maven` buckets instead of falling back to `@maven`.
- Duplicate declared compileOnly declarations with the same `group:artifact` in one bucket are now reduced by highest version while merging metadata/excludes, so declaration order no longer decides the bucket version.
- Declared compileOnly dependencies with null or blank groups are skipped before constructing `ResolvedDependency` metadata; null-group behavior has a unit regression. Declared exclude metadata also skips blank dependency groups before forming `group:artifact` keys, with a unit regression that prevents malformed `:artifact` metadata rows.
- The latest subagent audits found no blockers in the declared compileOnly slice or the normal-library `androidTestCompileOnly` deferral. They confirmed coverage for `testCompileOnly -> @test_maven`, duplicate compileOnly highest-version selection, null-group skip, and that normal Android libraries currently do not emit generated instrumentation targets that could consume `androidTestCompileOnly` labels.
- The app flavor fixture now explicitly covers same-artifact/different-version ownership for a flavor parent: `com.google.code.findbugs:jsr305:3.0.1` stays in `default`, `3.0.2` lands in `flavor2`, and the generated flavor target uses `@flavor2_maven` rather than `@maven`.
- The app instrumentation fixture now explicitly covers same-artifact/different-version ownership for a direct `androidTestCompileOnly` declaration: app main `com.google.j2objc:j2objc-annotations:1.1` stays in `default`/`@maven`, direct androidTest `1.3` lands in `androidTest`/`@android_test_maven`, the generated `android_instrumentation_binary` target does not fall back to the broad default label for the direct androidTest override, and the `android_test_maven` artifact carries only the androidTest declaration exclude rather than the inherited main exclude.
- The Test-vs-AndroidTest same-artifact/same-version exclude gap is now explicitly covered. `android-library-flavor` declares `testCompileOnly("org.hamcrest:hamcrest-library:1.3")` with a test-only exclude, while `app` declares `androidTestCompileOnly("org.hamcrest:hamcrest-library:1.3")` with an androidTest-only exclude. Generated targets use `@test_maven` / `@android_test_maven` respectively, and generated `WORKSPACE` artifact blocks keep the excludes bucket-scoped. This needed no production patch after the prior androidTest exclude isolation fix.
- Same-project Test-vs-AndroidTest workspace exclude isolation is now covered with app `testCompileOnly("commons-io:commons-io:2.11.0")` and app `androidTestCompileOnly("commons-io:commons-io:2.11.0")`, each with different excludes. The generated `test_maven` and `android_test_maven` artifact blocks stay scoped, and the generated app instrumentation target uses `@android_test_maven`. App unit-test target-label coverage remains unavailable in this fixture because no app `android_unit_test` target is emitted.
- The explicit opt-out path is still available as an escape hatch: `aggregatedDependencyResolution.set(false)` schedules the legacy per-project/per-variant `*ResolveDependencies` tasks for `computeWorkspaceDependencies` in dry-run, while the convention-default path remains guarded by `verify-default-task-graph.sh`.
- Fresh verification after the Test-vs-AndroidTest same-version exclude fixtures and root regeneration: focused `BuildVariantTest.migrateToBazelWithFlavorsWereUsed`, forced full `BuildVariantTest` functional suite with `--rerun-tasks`, root `migrateToBazel --rerun-tasks`, `verify-default-task-graph.sh`, `verify-sample-bucket-labels.sh`, generated `WORKSPACE`/dependency spot checks, `git diff --check`, and `bazelisk build //... --disk_cache= --strategy=KotlinKapt=sandboxed` all pass.
- Fresh verification after inherited main-dependency test target assertions: focused `BuildVariantTest.migrateToBazelWithFlavorsWereUsed` with `--rerun-tasks` and embedded `bazelBuildAll` passed for both the unit-test and instrumentation assertions, and `git diff --check` passed. No production code changed in this slice.
- Fresh verification after androidTest exclude-context hardening: RED focused functional fixture failed on the androidTest Maven artifact carrying `main-only-exclude`; focused `AggregatedDependencyResolverTest`, focused `BuildVariantTest.migrateToBazelWithFlavorsWereUsed`, forced full `BuildVariantTest` functional suite, root `migrateToBazel`, `verify-default-task-graph.sh`, `verify-sample-bucket-labels.sh`, direct generated JSON/WORKSPACE spot checks, and `git diff --check` all pass.
- Fresh verification after the review fixes: focused calculator/pinner/compute tests, full plugin unit tests, focused flavor functional fixture with `bazelBuildAll`, root `migrateToBazel`, focused verifier scripts, `git diff --check`, lint selected-version spot check, exact failing instrumentation target build, and serialized root `bazelisk build //... --disk_cache= --jobs=1` all pass.
- Fresh verification after the declared-metadata collector extraction, selected-fallback fixture, and compute-task invalidation fix: focused collector/resolver/compute tests, focused flavor functional fixture with `bazelBuildAll`, compute invalidation functional regression, dry-run task graph functional check, root `migrateToBazel`, focused verifier scripts, and `git diff --check` pass.
- Fresh verification after non-app test compileOnly hardening: forced `BuildVariantTest` functional suite with `--rerun-tasks`, focused `AggregatedDependencyResolverTest`/`ComputeWorkspaceDependenciesTest`, `verify-default-task-graph.sh`, `verify-sample-bucket-labels.sh`, and `git diff --check` all pass.
- Fresh verification after the flavor version-override fixture: forced `BuildVariantTest` functional suite with `--rerun-tasks`, `verify-default-task-graph.sh`, `verify-sample-bucket-labels.sh`, and `git diff --check` all pass.
- Fresh verification after explicit legacy opt-out coverage: focused `BuildVariantTest.computeWorkspaceDependenciesSchedulesLegacyResolveTasksWhenAggregatedResolutionDisabled`, `verify-default-task-graph.sh`, `verify-sample-bucket-labels.sh`, and `git diff --check` all pass.
- Fresh verification after standalone `com.android.test` sample-label guard: `verify-sample-bucket-labels.sh`, `verify-default-task-graph.sh`, and `git diff --check` all pass.
- Fresh functional-class verification after adding the explicit opt-out regression and standalone sample-label guard: forced `BuildVariantTest` functional suite with `--rerun-tasks` passed, including the generated fixture `bazelBuildAll`; `git diff --check` passed afterward, and Gradle/Bazel workers were stopped.
- Fresh root verification after the latest guards: root `migrateToBazel`, `verify-default-task-graph.sh`, `verify-sample-bucket-labels.sh`, `git diff --check`, and root `bazelisk build //... --disk_cache= --strategy=KotlinKapt=sandboxed` all pass.
- The hardening work has been packaged into a local durability commit on top of `c84b96b`; tool artifacts `codedb.snapshot` and the stale `docs/superpowers` plan remain untracked and intentionally excluded.
- Root `bazelisk build //... --disk_cache=` was refreshed before the latest invalidation fix and still failed under default parallelism in Android resource processing with a KAPT generated jar reported as failed-to-open. A focused graph audit found no generated dependency path from the failing target to the sibling demo-free KAPT jar. After the later storage cleanup and task-input-only fix, `bazelisk build //... --disk_cache= --strategy=KotlinKapt=sandboxed` passed from a clean Bazel state.
- If storage or cache state blocks progress, it is acceptable to run the normal clean commands such as `bazelisk clean` and `./gradlew clean`, then continue verification.
- `bazelisk test //...` broad lint status has not been refreshed after this slice. Earlier broad test failures were generated lint/sample issues, not dependency graph failures.

Next action:
- Decide the next guided goal slice. Good candidates are higher-value flavor/test bucket fixtures, selected downstream project variant metadata hardening, blank-group coverage if desired, or the proper Gradle-managed declared-metadata task pipeline. The current inline collector remains the accepted compatibility fix for now.
- The previously flagged Test-vs-AndroidTest same-artifact/same-version exclude gap is covered at both cross-project target/workspace level and same-project workspace level. Keep broad test buckets unless a future RED case proves a real bleed or target-label mismatch.
- Keep broad `test_maven` / `android_test_maven` bucketing for Test/AndroidTest compileOnly until a failing case justifies per-leaf precision.
- Do not mark broad `bazelisk test //...` green unless generated lint baselines/resources are fixed or those lint targets are intentionally excluded.

## Checkpoints

### 2026-06-17 23:13:17 +08 — Inherited Main Deps Stay on Default Maven in Test Targets

Hypothesis:
- The old variant layering treats Test and AndroidTest as extending main/default. The new aggregated path must preserve that target-label behavior: deps inherited from main/default should stay on `@maven`, while true direct test/androidTest overrides should still use `@test_maven` / `@android_test_maven`.
- This is the complementary edge to the direct test/androidTest override fixtures already covered.

What changed:
- Added generated-target assertions in `BuildVariantTest.migrateToBazelWithFlavorsWereUsed`:
  - the library `android_unit_test` target contains `@maven//:androidx_appcompat_appcompat` and not `@test_maven//:androidx_appcompat_appcompat`;
  - the app `android_instrumentation_binary` target contains `@maven//:androidx_appcompat_appcompat` and not `@android_test_maven//:androidx_appcompat_appcompat`.
- Existing direct override assertions still prove `com.google.j2objc:j2objc-annotations` routes through `@test_maven` / `@android_test_maven` when directly declared in test contexts.

Result:
- Both assertions passed immediately, so no production code changed.
- This confirms the current `collectMavenDeps` default-first lookup for Test/AndroidTest and exact-version direct declaration path preserve the intended test-extends-main behavior at generated target level.

Commands and results:
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain --rerun-tasks`: passed after the unit-test assertion.
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain --rerun-tasks`: passed after adding the instrumentation assertion; fixture `bazelBuildAll` also passed.
- `git diff --check`: passed.

Remaining risk:
- This does not add per-leaf Test/AndroidTest bucketing; broad `test_maven` / `android_test_maven` remains the accepted milestone behavior.
- No daemon or Bazel process cleanup was needed; the long buildifier phase was normal for this fixture run.

### 2026-06-17 23:06:23 +08 — Root Verification Refresh After Test Bucket Fixtures

Hypothesis:
- The latest Test/AndroidTest fixture hardening changed fixture declarations and generated root outputs. Before moving to another behavior slice, the branch needed the objective's root regeneration and broad build evidence refreshed.

Commands and results:
- `./gradlew migrateToBazel --console=plain --rerun-tasks`: passed after executing all 43 tasks; generated root/project Bazel files and reported Maven pins up to date.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `git diff --check`: passed.
- `bazelisk build //... --disk_cache= --strategy=KotlinKapt=sandboxed`: passed, analyzing 239 targets and completing successfully.

Remaining risk:
- Plain default-parallel `bazelisk build //... --disk_cache=` remains unclaimed because the known KAPT generated-jar failed-to-open issue previously reproduced there. The KAPT-sandboxed build remains the reliable broad compile signal for this branch.
- Broad `bazelisk test //...` remains intentionally unclaimed; earlier failures are generated lint/sample issues outside this dependency graph slice.

### 2026-06-17 23:02:35 +08 — Same-Project Test-vs-AndroidTest Workspace Exclude Isolation

Hypothesis:
- The cross-project hamcrest fixture covers broad `test_maven` / `android_test_maven` bucket isolation, but the same project declaring the same artifact/version in both `testCompileOnly` and `androidTestCompileOnly` is a useful owner-path check.
- Since app unit-test targets are not emitted by this fixture shape, the same-project case should focus on generated workspace metadata and the existing instrumentation target.

Test fixture:
- Added app `testCompileOnly("commons-io:commons-io:2.11.0")` with exclude `com.example:same-project-test-only-exclude`.
- Added app `androidTestCompileOnly("commons-io:commons-io:2.11.0")` with exclude `com.example:same-project-android-test-only-exclude`.
- Added assertions that:
  - `test` and `androidTest` buckets both keep `commons-io:commons-io:2.11.0`;
  - the generated app instrumentation target uses `@android_test_maven//:commons_io_commons_io`;
  - the `test_maven` artifact carries only the same-project test exclude;
  - the `android_test_maven` artifact carries only the same-project androidTest exclude.

Debugging evidence:
- Initial RED failed on an invalid assumption that adding `src/test` would make this app fixture emit an `android_unit_test` target.
- Generated output showed `android_binary` and `android_instrumentation_binary` only, while the `test_maven` and `android_test_maven` `WORKSPACE` blocks were correctly scoped.
- Removed the unnecessary app unit-test source and narrowed the assertion to metadata plus instrumentation target behavior.
- The focused fixture then passed; no production code was changed.

Commands and results:
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain --rerun-tasks`: failed before narrowing the invalid app unit-target assertion; passed after narrowing.
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest" --console=plain --rerun-tasks`: passed after executing all 18 tasks.
- `git diff --check`: passed.

Remaining risk:
- Same-project app unit-test target label ownership is still not covered because this fixture does not emit an app `android_unit_test`. The non-app library fixture remains the generated unit-test target coverage for `test_maven`.
- No per-leaf Test/AndroidTest bucketing is attempted here.

### 2026-06-17 22:54:56 +08 — Test-vs-AndroidTest Same-Version Exclude Isolation

Hypothesis:
- The previous androidTest direct exclude fix should also protect broad `test_maven` and `android_test_maven` when both buckets declare the same `group:artifact:version` with different excludes.
- This is worth covering because same-version overlap does not exercise the version-aware label path; it specifically checks bucket-contextual declared metadata.

Test fixture:
- Added `android-library-flavor` `testCompileOnly("org.hamcrest:hamcrest-library:1.3")` with exclude `org.hamcrest:hamcrest-core`.
- Added app `androidTestCompileOnly("org.hamcrest:hamcrest-library:1.3")` with exclude `com.example:android-test-only-hamcrest-exclude`.
- Added assertions that:
  - `test` and `androidTest` buckets both keep `org.hamcrest:hamcrest-library:1.3`;
  - the generated library `android_unit_test` target uses `@test_maven//:org_hamcrest_hamcrest_library`;
  - the generated app `android_instrumentation_binary` target uses `@android_test_maven//:org_hamcrest_hamcrest_library`;
  - the `test_maven` artifact carries only the test exclude;
  - the `android_test_maven` artifact carries only the androidTest exclude.

Result:
- The focused fixture passed immediately, so no production code was changed in this slice.
- This confirms the prior `DeclaredDependencyMetadataCollector` filtering and owner-scoped fallback behavior already handle same-version Test-vs-AndroidTest exclude isolation.

Commands and results:
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain --rerun-tasks`: passed.
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest" --console=plain --rerun-tasks`: passed after executing all 18 tasks.
- `git diff --check`: passed.
- Generated `WORKSPACE` spot check: `android_test_maven` hamcrest carries `com.example:android-test-only-hamcrest-exclude`, and `test_maven` hamcrest carries `org.hamcrest:hamcrest-core`.

Remaining risk:
- This is cross-project bucket isolation (`android-library-flavor` test plus app androidTest), not same-project app `testCompileOnly` plus `androidTestCompileOnly`. That is acceptable for the broad bucket milestone because the resolver buckets are global by Test/AndroidTest context.
- No per-leaf Test/AndroidTest bucketing is attempted here.

### 2026-06-17 22:47:14 +08 — AndroidTest Direct Exclude Isolation

Hypothesis:
- Same `group:artifact` declared in app main and direct `androidTestCompileOnly` with different versions/excludes should keep version and exclude metadata bucket-specific: default `@maven` owns the main version/exclude, while `@android_test_maven` owns the androidTest version/exclude.

RED:
- Added fixture assertions in `BuildVariantTest.migrateToBazelWithFlavorsWereUsed` for `com.google.j2objc:j2objc-annotations`.
- Before fixture exclude declarations, focused functional failed because default Maven had no main exclude.
- After adding main/androidTest fixture excludes, focused functional failed because `android_test_maven` carried both `android-test-only-exclude` and inherited `main-only-exclude`.

Root cause:
- App androidTest leaf resolution was collecting declared excludes from AndroidBuild/Test/AndroidTest metadata and from concrete AGP classpath configurations. Concrete classpath configurations such as `flavor2DebugAndroidTestCompileClasspath` can expose inherited main excludes as dependencies, so reading them as declaration metadata bleeds main excludes into androidTest-owned artifacts.
- `excludeRulesFor` also unioned extended classpath fallback rules for root-owned dependencies even when scoped owner metadata existed.

Changes:
- Narrowed app unit/androidTest exclude metadata collection to the corresponding test variant type.
- Changed root-owned fallback behavior so scoped owner metadata wins; extended classpath fallback is used only when owner metadata is absent or unknown.
- Changed `DeclaredDependencyMetadataCollector` to read direct dependencies only from declaration-bucket configurations and ignore `*Classpath` / `*DependenciesMetadata` configurations.
- Added unit coverage for scoped root owner metadata and for ignoring classpath configs during declared exclude extraction.
- Extended the app instrumentation fixture to assert default/androidTest exclude isolation in generated `WORKSPACE`.

Commands and results:
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --console=plain`: passed.
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain --rerun-tasks`: passed after the fix.
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest" --console=plain --rerun-tasks`: passed.
- `./gradlew migrateToBazel --console=plain --rerun-tasks`: passed.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `git diff --check`: passed.
- `jq` spot check on fixture `build/grazel/dependencies.json`: `default` j2objc `1.1` has only `main-only-exclude`; `androidTest` j2objc `1.3` has only `android-test-only-exclude`.

Remaining risk:
- The read-only subagent found an adjacent uncovered case: same artifact/version in `testCompileOnly` and `androidTestCompileOnly` with different excludes. This should be the next RED candidate if we continue test/androidTest metadata hardening.

### 2026-06-17 01:58:52 +08 — Objective Intake and Current-State Audit

Hypothesis:
- The branch proves compile feasibility but not the original performance/semantic requirement.

Files inspected:
- `reports/dependencies-refactor-HANDOFF.md`
- `reports/dependencies-refactor-worklog.md`
- `reports/dependencies-refactor-design-notes.md`
- `reports/dependency-resolution-to-workspace.md`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/ComputeWorkspaceDependenciesTask.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/ComputeWorkspaceDependencies.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/DependencyResolutionService.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/MavenInstallStore.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/variant/VariantBuilder.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/variant/VariantDataSource.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/variant/AndroidVariants.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/variant/ConfigurationParsingVariant.kt`
- `build.gradle`
- Generated `BUILD.bazel`, `WORKSPACE`, and `*_maven_install.json` files.

Commands and results:
- `git status --short --branch`: branch `arun/dependencies-refactor`; only `codedb.snapshot` untracked.
- `git merge-base master HEAD`: `1d6c91ed4ab3363a32020ea2204d1f53092ad335`, so `master...HEAD` is a direct branch diff.
- `git diff --name-status master...HEAD`: code delta is concentrated in resolver/task/dependency classes; generated Bazel outputs have large diffs.
- `./gradlew computeWorkspaceDependencies --dry-run --console=plain`: old `*ResolveDependencies` tasks appear before `:computeWorkspaceDependencies`.
- `./gradlew migrateToBazel --dry-run --console=plain`: old `*ResolveDependencies` tasks appear before generation tasks.
- `./gradlew computeWorkspaceDependencies --console=plain`: build succeeded, but executed 107 old dependency-resolution tasks.
- `jq` on regenerated `build/grazel/dependencies.json`: aggregated output currently contains leaf buckets (`demoFreeDebug`, `fullPaidDebug`, unit/androidTest leaf variants), plus `default`, `test`, `androidTest`, and `lint`.

Bucket/task-graph findings:
- Task graph does not satisfy the default no-fan-out requirement.
- Current bucket output does not preserve master semantics under debug-only filtering.
- `MavenInstallStore` likely needs to account for `overrideTarget` or avoid indexing override-only duplicates as first-class variant ownership.

Risks/open questions:
- Matching master semantics may require resolving/constructing synthetic bucket closures (`default`, `debug`, flavor, leaf) from binary roots rather than relying only on filtered leaf intersections.
- Existing functional fixtures may be too small to cover root sample behavior; a focused root-level golden loop may be needed.
- `com.android.test` root support must remain intact while fixing common/debug bucketing.

### 2026-06-17 02:06:05 +08 — Default Task Graph Green

Hypothesis:
- The old fan-out was caused by configuration-time wiring: `ResolveVariantDependenciesTask.register(...)` always populated `compileDependenciesJsons`, so Gradle scheduled those producer tasks even when the aggregated task action branch was selected.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/extension/ExperimentsExtension.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/ComputeWorkspaceDependenciesTask.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/ResolveVariantDependenciesTask.kt`
- `build.gradle`
- `reports/scripts/verify-default-task-graph.sh`

Commands and results:
- `reports/scripts/verify-default-task-graph.sh`: failed before the production change with many `*ResolveDependencies SKIPPED` tasks; passes after the change.
- `./gradlew computeWorkspaceDependencies --dry-run --console=plain`: build successful; task graph contains `:computeWorkspaceDependencies SKIPPED` and no `*ResolveDependencies` tasks.
- `./gradlew migrateToBazel --dry-run --console=plain`: build successful; generation graph contains `:computeWorkspaceDependencies SKIPPED` and no `*ResolveDependencies` tasks.

Task-graph findings:
- `aggregatedDependencyResolution` is now convention-default `true`.
- The root sample no longer sets `aggregatedDependencyResolution.set(true)`.
- Legacy per-variant task creation is skipped during `afterEvaluate` when aggregated resolution is enabled. This preserves the explicit opt-out shape because setting the flag false before project evaluation still allows the legacy task registration block to run.

Next step:
- Move to bucket correctness: reproduce the `@maven`/`@debug_maven`/leaf bucket regressions in focused tests or checked-in verification, then fix `DefaultMavenInstallStore` lookup and bucket reconstruction.

Risks/open questions:
- The explicit opt-out path still needs verification with a fixture or init-script run.
- Functional tests still have fixture compatibility failures unrelated to this task-graph change; root-level verification is currently stronger for this requirement.

### 2026-06-17 02:23:00 +08 — Bucket Lookup and AndroidTest Audit

Hypothesis:
- The first large generated-output regressions were two separate issues:
  1. Bucket reconstruction from only leaf intersections was insufficient under debug-only filtering.
  2. Maven repo lookup ignored `overrideTarget`, so duplicate leaf/default artifacts could choose the wrong repo.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/DependencyResolutionService.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/MavenInstallStore.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/ResolvedComponentsVisitor.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/DefaultDependencyResolutionServiceTest.kt`
- `reports/scripts/verify-sample-bucket-labels.sh`

Commands and results:
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionServiceTest" --console=plain`: passed.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.ResolvedComponentsVisitorTest" --console=plain`: passed.
- `./gradlew :grazel-gradle-plugin:compileKotlin --console=plain`: passed.
- `./gradlew migrateToBazel --console=plain`: passed.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `./gradlew :sample-android:dependencies --configuration demoFreeDebugAndroidTestCompileClasspath --console=plain`: passed; report shows `androidx.test:monitor:1.6.1` as a first-level androidTest dependency.

Findings:
- Synthetic hierarchy bucket resolution fixed the main app sample for the high-signal labels:
  - `@debug_maven//:androidx_paging_paging_runtime` is present.
  - `@maven//:androidx_activity_activity`, `@maven//:androidx_compose_ui_ui`, and `@maven//:androidx_emoji2_emoji2` are present.
  - Those common deps are no longer emitted from `@demo_free_debug_maven`.
- `DefaultMavenInstallStore` now caches the resolved `MavenDependency`, including `overrideTarget` labels. The targeted duplicate-artifact unit test passes.
- `ResolvedComponentsVisitor` now treats first-level external children of the resolved root as direct when traversing project nodes. This matches Gradle's dependency report shape, but it did not by itself create a final `androidTest` bucket.
- After regeneration, `build/grazel/dependencies.json` still has no `androidTest` entry in `result`. `sample-android/BUILD.bazel` still differs from master for `androidx_test_monitor`.

Open decision:
- Should standalone `com.android.test` root closures be allowed to populate `default` transitively, or should only their direct declarations feed `@maven` while app built-in `androidTest` owns androidTest-only first-level/transitive artifacts such as `androidx.test:monitor`?

### 2026-06-17 02:42:49 +08 — AndroidTest Bucket Restored

Hypothesis:
- The missing shared `androidTest` bucket was caused by graph traversal treating dependency constraints as direct root ownership, plus a cache hit losing directness when a repeated node was first visited transitively.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/ResolvedComponentsVisitor.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/ResolvedComponentsVisitorTest.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/ComputeWorkspaceDependenciesTask.kt`
- Generated Bazel/Maven files.

Commands and results:
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.ResolvedComponentsVisitorTest" --console=plain`: failed before constraint handling; passed after the fix.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.ResolvedComponentsVisitorTest" --tests "com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionServiceTest" --console=plain`: passed.
- `./gradlew computeWorkspaceDependencies --console=plain`: passed.
- `./gradlew migrateToBazel --console=plain -q`: passed.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `git diff --check`: passed.

Findings:
- `sample-android/BUILD.bazel` now uses `@android_test_maven//:androidx_test_monitor` for all generated instrumentation targets.
- `build/grazel/dependencies.json` currently has buckets: `androidTest`, `debug`, `default`, `demo`, `free`, `full`, `lint`, `paid`.
- Raw aggregated output shows standalone `com.android.test` declared deps such as `espresso-core`, `ext:junit`, `rules`, and `runner` in `default`, while constraint-only `androidx.test:monitor` no longer becomes direct `default`.
- `android_test_maven_install.json` is back to the five-artifact shape from master: annotation, annotation-experimental, test annotation, monitor, tracing.

### 2026-06-17 02:48:07 +08 — Flavor Bucket Leakage Removed

Hypothesis:
- Flavor buckets were over-owning `androidx.paging:paging-runtime` because flavor reduction subtracted `default` but not already-owned build-type buckets.

Evidence before fix:
- `build/grazel/dependencies.json` had `androidx.paging:paging-runtime` as a direct dependency in `debug`, `demo`, `free`, `full`, and `paid`.
- The first run of `reports/scripts/verify-sample-bucket-labels.sh` after adding the check failed with: `Found debug-only paging dependency as direct dependency in demo bucket`.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`
- `reports/scripts/verify-sample-bucket-labels.sh`
- Generated Bazel/Maven files.

Commands and results:
- `./gradlew computeWorkspaceDependencies --console=plain`: passed.
- `./gradlew migrateToBazel --console=plain -q`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed after the reducer change.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `git diff --check`: passed.

Findings:
- `build/grazel/dependencies.json` now has buckets: `androidTest`, `debug`, `default`, `lint`.
- `debug` directly owns `androidx.paging:paging-runtime`; flavor buckets are absent because the sample currently has no external flavor-only dependencies.
- Current root maven install files are limited to repos referenced by WORKSPACE: `maven`, `debug_maven`, `android_test_maven`, `lint_maven`, and `ksp_maven`.
- Core/lifecycle labels are intentionally verified as `@maven`, not `@debug_maven`, because they are common implementation deps and the default repo owns newer selected versions.

### 2026-06-17 03:34:35 +08 — Verification Pass and Remaining Policy Decisions

Hypothesis:
- The refactor now satisfies the main performance/semantic requirement, and the remaining failures are either local Bazel worker behavior or sample lint expectations rather than dependency bucketing regressions.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/AndroidInstrumentationBinaryTargetBuilder.kt`
- Functional fixture build files and assertions under `grazel-gradle-plugin/src/functionalTest` and `grazel-gradle-plugin/src/test/projects`
- Generated root Bazel and Maven files.

Commands and results:
- `./gradlew :grazel-gradle-plugin:functionalTest --console=plain`: passed.
- `./gradlew :grazel-gradle-plugin:test --console=plain`: passed.
- `./gradlew computeWorkspaceDependencies --dry-run --console=plain`: passed; no legacy `*ResolveDependencies` task fan-out.
- `./gradlew migrateToBazel --dry-run --console=plain`: passed; no legacy `*ResolveDependencies` task fan-out.
- `./gradlew migrateToBazel --console=plain`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `git diff --check`: passed.
- `bazelisk build //... --strategy=AndroidAapt2=sandboxed`: passed.
- `bazelisk test //flavors/sample-library-demo:sample-library-demo-test //flavors/sample-library-full:sample-library-full-test //sample-android-library:sample-android-library-debug-test //sample-kotlin-library:sample-kotlin-library-test --strategy=AndroidAapt2=sandboxed`: passed.
- `bazelisk test //... --strategy=AndroidAapt2=sandboxed`: failed only in generated lint targets for sample lint/baseline issues.

Findings:
- Build-type-only AGP variants needed to be treated as concrete leaves using the backing `BaseVariant`, otherwise hybrid fixtures with only debug/release leaves collapsed incorrectly.
- Source-less generated instrumentation test targets needed to be skipped to avoid Bazel `kt_jvm_library` deps-without-srcs failures.
- Root `WORKSPACE` no longer references `test_maven`; the stale tracked `test_maven_install.json` was removed to keep generated outputs consistent with the loaded repos.
- Default Bazel AndroidAapt2 worker strategy appears stateful across same-package crashlytics resource actions. The sandboxed strategy builds successfully and the failing action graph did not contain a dependency path to the incorrectly opened sibling manifest.

Open decisions:
- Should `--strategy=AndroidAapt2=sandboxed` be encoded in `.bazelrc` or kept as a local verification workaround?
- Should generated lint tests be made expected-green for this sample, excluded from broad `bazel test //...`, or left as known sample debt?
- Should untracked functional-test generated outputs (`MODULE.bazel`, lockfiles, fixture maven jsons, generated layout file, etc.) be committed as fixture updates or cleaned before finalizing?

### 2026-06-17 04:20:35 +08 — Excludes Preserved and Final Verification Refresh

Hypothesis:
- The remaining `WORKSPACE` drift was a real bug: the aggregated resolver emitted direct dependencies from binary classpath graph traversal, but never attached Gradle `ExternalDependency.excludeRules`.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`
- `reports/scripts/verify-sample-bucket-labels.sh`
- `.bazel/.default.bazelrc`
- Regenerated Bazel/Maven files.

Commands and results:
- `reports/scripts/verify-sample-bucket-labels.sh`: failed before the resolver fix with `WORKSPACE must preserve Gradle exclude rules for androidx.constraintlayout:constraintlayout`; passed after regeneration.
- `./gradlew :grazel-gradle-plugin:compileKotlin --console=plain`: passed.
- `./gradlew migrateToBazel --console=plain`: passed and repinned the referenced repos.
- `./gradlew :grazel-gradle-plugin:test --console=plain`: passed.
- `./gradlew :grazel-gradle-plugin:functionalTest --console=plain`: passed.
- `./gradlew computeWorkspaceDependencies --dry-run --console=plain`: passed; only `:computeWorkspaceDependencies SKIPPED`, no legacy `*ResolveDependencies` tasks.
- `./gradlew migrateToBazel --dry-run --console=plain`: passed; no legacy `*ResolveDependencies` tasks.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `git diff --check`: passed.
- `bazelisk build //...`: first failed through the persistent AndroidAapt2 worker by opening the wrong sibling crashlytics manifest. `bazelisk build //... --strategy=AndroidAapt2=standalone` passed. After encoding `common --strategy=AndroidAapt2=standalone` in `.bazel/.default.bazelrc`, plain `bazelisk build //...` passed.
- `bazelisk test //...`: failed only the 8 generated lint targets under `//flavors/sample-android-flavor:*lint_test` and `//sample-android:*lint_test`; 9 tests passed.
- `bazelisk test //flavors/sample-library-demo:sample-library-demo-test //flavors/sample-library-full:sample-library-full-test //sample-android-library:sample-android-library-debug-test //sample-kotlin-library:sample-kotlin-library-test`: passed.

Generated diff review:
- `WORKSPACE` now differs from master only by removing `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.7.3`; this is intentional because BOM/platform artifacts are filtered before rules_jvm_external pinning.
- `WORKSPACE` again preserves the master `maven.artifact(...)` shape for `androidx.constraintlayout:constraintlayout`, including exclusions for `androidx.appcompat:appcompat` and `androidx.core:core`.
- `sample-android/BUILD.bazel` and `flavors/sample-android-flavor/BUILD.bazel` move common core/lifecycle deps from `@debug_maven` to `@maven`; paging stays in `@debug_maven`. This matches the new bucket ownership: common implementation deps in default, debugImplementation deps in debug.
- `test_maven_install.json` and per-leaf maven install JSONs are deleted because the current root `WORKSPACE` no longer references those repos.
- `android_test_maven_install.json`, `debug_maven_install.json`, `lint_maven_install.json`, `ksp_maven_install.json`, and `maven_install.json` are repinned outputs. Their large diffs are mostly rules_jvm_external lockfile schema v3 hashes/services plus the changed bucket artifact sets.

Findings:
- Exclude metadata is collected from declared external dependencies across migratable project configurations and attached to direct emitted dependencies by `shortId`.
- The broader metadata-merge attempt in `unionDependencyMaps` caused unnecessary androidTest transitive repo growth and was reverted; the exclude fix does not need that merge because the exclude map is shared.
- `AndroidAapt2=standalone` is a narrower and lower-disk workaround than sandboxing. It removes the persistent worker state leak while preserving the rest of the worker configuration.

Remaining risk/open question:
- Broad `bazelisk test //...` is not green because generated lint test targets expose existing sample lint/resource issues: duplicate generated resources, `SetTextI18n`, missing constraints, missing/extra translations, and related sample lint findings. Treat this as sample lint debt unless the goal expands to fixing generated lint baselines/resources.

### 2026-06-17 10:57:42 +08 — Pending Risk Register for Next Guided Goal Session

Context:
- The current branch is a working refactor, not a finalized merge package. The root sample oracle and focused verifiers cover the high-signal behavior, but several edge-heavy paths are not yet proven deeply enough to call low-risk.
- The next session should be user-guided: decide which of these are release blockers, which need fixture coverage, and which are acceptable documented tradeoffs.

Least-confident areas:
- Variant-specific KSP handling. The current aggregated path still emits a broad `ksp_maven` bucket. It has not proven separate processors or processor versions per flavor/build type.
- Exclude metadata bucketing. Decision for the next hardening pass: improve correctness inline during bucket formation by collecting declared exclude rules for the Gradle configurations that feed each synthetic bucket, then attach only the bucket-relevant excludes. This is cheap because it inspects declared dependency metadata, not resolved classpaths. The cleaner long-term path is a separate cache-friendly metadata collection pipeline that aggregates module/configuration exclude metadata for the resolver; explore that later, but do not let it reintroduce the old expensive variant-resolution fan-out.
  - Important requirement for the future metadata fanout: preserve configuration context, not just `group:artifact -> excludes`. Each metadata row needs at least project path, configuration name, derived synthetic bucket, dependency `group:artifact`, and exclude rules. Root aggregation can then answer "for bucket `freeDebug`, include excludes from `default + free + debug + freeDebug`" and avoid unrelated `paid`/`release` excludes.
  - The current inline hardening can use the same bucket hierarchy. For a `debug` emitted artifact, attach excludes from `default + debug`; for `freeDebug`, attach `default + free + debug + freeDebug`; for `androidTest`, attach the relevant main/default/test buckets only if that mirrors existing generation semantics.
- Declared metadata side channel. Decision: retain the old project -> `VariantBuilder` -> `Variant` -> configuration traversal as a cheap declared-metadata aggregation path, but do not use it to resolve classpaths. The binary-root/app classpath resolver remains the source of truth for resolved versions, transitive closure, repositories, jetifier state, and buildable graph. The cheap metadata path should supply edge/configuration facts that are lost by the inverted root-to-module traversal: exclude rules, `compileOnly`/variant-specific declared deps, and later possibly annotation processor/KSP declaration metadata.
  - Existing APIs support this direction: `Variant` exposes `name`, `extendsFrom`, `variantConfigurations`, `compileConfiguration`, `runtimeConfiguration`, `kspConfiguration`, and `migratableConfigurations`; `VariantBuilder` maps project -> synthetic variants; `ConfigurationParsingVariant` maps synthetic variants back to Gradle configurations; `Dependencies.collectMavenDeps` already uses `findGrazelVariantByKey(...).migratableConfigurations` to read declared external deps without resolving.
  - Shape to aim for: metadata rows like `project=:analytics`, `variant=debug`, `extendsFrom=[default]`, `configuration=debugCompileOnly`, `dependency=com.foo:debug-annotations`, `metadata={scope=compileOnly, excludes=[...]}`. Final bucket assembly can combine resolved graph ownership with metadata from the bucket hierarchy (`default + free + debug + freeDebug`) instead of inferring declaration facts from resolved binary classpaths.
  - Hard constraint: this path must inspect declared dependency metadata only (`configuration.dependencies` / equivalent), not `resolvedConfiguration`, `incoming.resolutionResult`, or any per-variant classpath resolution. It must not recreate the old expensive `ResolveDependencies` fan-out.
- Library-only and `compileOnly` placement. Non-app library compile classpaths are currently unioned into every leaf so unreachable deps land in `default`; this preserves availability but is broader than the old per-variant path. The declared metadata side channel above is the preferred hardening path for restoring bucket precision for cases such as `debugCompileOnly` without patching more inference into the root resolver.
- BOM/platform filtering. Decision: keep the current cheap suffix-based filtering (`-bom`/`.bom`) for now. It handles the observed rules_jvm_external pinning failure cheaply and is acceptable unless a real non-suffix platform/POM artifact leaks through.
- Explicit opt-out path. Decision: do not invest heavily in polishing `aggregatedDependencyResolution = false`. Keep the old path temporarily as a reference/escape hatch while hardening the new path, especially the declared-metadata side channel. Once the new path is satisfactory, do a deliberate cleanup goal that removes the old expensive per-project/per-variant resolution pipeline and the experiment flag.
- Generated lint tests. `bazelisk test //...` remains red only for generated lint/sample issues; decide whether to fix baselines/resources or intentionally exclude those targets.

Edge-heavy scenarios to test before treating the refactor as broadly safe:
- Repos without an app/`com.android.test` binary root. Decision: handle later. Grazel should eventually support this by requiring or deriving an edge node/binary-like root for dependency aggregation, but it is not part of the immediate hardening target.
- Multiple app/test roots with overlapping but non-identical leaf sets. Decision: union of all app/test root needs is acceptable for now because the generated workspace is root-wide and should contain everything needed by all binaries.
- Real flavor-only external dependencies, not just debug-only plus common deps. Decision: this is required behavior, not optional. The refactor must preserve reduced hierarchical buckets: deps common to all leaves go to `default`/`@maven`; build-type-only deps go to build-type repos such as `@debug_maven`; flavor-only deps go to flavor repos such as `@free_maven`; only leaf-unique residual deps should create/use leaf repos. This bucket reduction is intentional to avoid exploding Maven repos for every Android flavor/build-type permutation.
  - Important model note: Android variant ownership is a graph/DAG, not a simple tree. A leaf like `freeDebug` inherits from both `free` and `debug` plus `default`; test leaves also include `test`/`androidTest` and base variant nodes. Bucket and metadata reduction must account for multiple parents.
  - API confirmation: `Variant.extendsFrom` is a `Set<String>`, not a single parent. `VariantBuilder` expands Android projects into synthetic `default`/`test`/`androidTest`/`lint` buckets, concrete AGP leaves, build-type buckets, and flavor buckets. `AndroidVariant.extendsFrom` includes `default`, every product flavor, and the build type; test leaves add `test`, base variant, `androidTest` where applicable, and build-type-specific test buckets. `AndroidNonVariant` build-type/flavor buckets extend `default` and filter the opposite dimension. This is exactly the structure the reduced Maven bucket model depends on.
  - Milestone guidance: flavor-specific buckets are required, but they can be hardened incrementally. Do not require every flavor/build-type/test permutation edge case in one shot if the next goal can land high-value fixtures first.
- Same `group:artifact` appearing in default/build-type/flavor/test buckets with different versions or excludes. Decision: this must be modeled as DAG ownership, not `shortId` subtraction. For a concrete leaf, the closest declaration in the `Variant.extendsFrom` DAG should win; if siblings are equally close (`free` vs `debug`), the concrete resolved leaf classpath is the tie-breaker. A child bucket entry may be reduced away only when the inherited parent entry has the same effective dependency identity, at minimum same `group:artifact`, version, and relevant metadata/excludes. Example required behavior: `implementation "x:y:1.0"` and `debugImplementation "x:y:2.0"` should produce `x:y:1.0` in `@maven` and `x:y:2.0` in `@debug_maven`; debug targets should prefer the debug bucket.
  - Preferred model: resolved leaf classpaths are the source of truth for effective versions; declared metadata DAG identifies candidate owners. Move a dependency upward only when every descendant leaf that would inherit it agrees on the same effective dependency identity. If not, keep ownership in the lower bucket or leaf residual.
  - Pragmatic implementation preference: start with the existing set-bucketing model plus dependency-identity-aware reduction. Do not jump to full DAG distance/ownership modeling unless the simpler approach becomes hard to write, hard to reason about, or fails required fixtures. Full DAG modeling is more correct in theory but has higher code and testing cost.
- `androidTest` and standalone `com.android.test` roots with dependencies that overlap main/runtime deps.
- Projects with no app binary root, or library-heavy repos where compile-only deps are important but not consumed by an app classpath.
- Variant-filtered projects where Gradle creates partial leaf sets and synthetic hierarchy buckets must still reconstruct the expected Maven repos.
- Non-`-bom` platform dependencies or POM-only artifacts that rules_jvm_external cannot pin.

Cleanup sequencing:
- Decision: compatibility first, cleanup second. Keep the old path as a reference/escape hatch until the new path has enough declared-metadata and bucket-precision coverage. Only after that should a separate cleanup goal delete legacy resolution and the experiment flag.

Phased milestone strategy for the next goal:
- Do not dump every hard problem into one pass. Land this refactor incrementally around buildable, reviewable milestones.
- Milestone 1 should prioritize rough compatibility and successful generated Bazel build over perfect bucket minimization. Broad buckets such as `@free_debug_maven` and `@paid_debug_maven` may temporarily duplicate deps instead of lifting them one layer up, as long as generated targets resolve correctly and performance does not regress to the old expensive path.
- Milestone 2 should restore the project/variant/configuration layering expected by the existing codebase. Current master is layered as: build variants first, use variants to drive task/configuration work, compute workspace data later. The new path should respect that altitude: be explicit about what happens during configuration, inside Gradle tasks, and in final compute steps. This matters for mergeability of the MR.
- Milestone 3 can optimize bucket reduction: flavor/build-type commoning, DAG-aware or identity-aware reduction, and minimizing duplicate Maven repos. Spike full DAG ownership only after the rough non-perf path is correct and buildable; then optimize.
- Hard rule for implementation planning: separate correctness/buildability from bucket-minimization. It is acceptable to produce more buckets first; it is not acceptable to reintroduce the old expensive per-project/per-variant dependency resolution fan-out as the default.

### 2026-06-17 15:08:43 +08 — Identity-Aware Bucket Reduction Slice

Hypothesis:
- A child bucket must not be reduced away only because a parent/default bucket has the same `group:artifact`. It should be reduced only when the parent has the same effective dependency identity.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/ComputeWorkspaceDependencies.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/model/ResolveDependenciesResult.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolverTest.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/ComputeWorkspaceDependenciesTest.kt`

What changed:
- Added shared `ResolvedDependency.hasSameEffectiveIdentityAs(...)` in the dependency model. Identity includes `shortId`, version, transitive dependency set, exclude rules, repository, jetifier requirement, and jetifier source.
- `ComputeWorkspaceDependencies` now reduces non-default buckets and default override-targets by effective identity rather than `shortId` alone.
- `AggregatedDependencyResolver` now uses identity-aware coverage for build-type, flavor, per-leaf, `test`, and `androidTest` bucket filtering while keeping the current set-bucketing shape.
- Kept the implementation intentionally short of full DAG ownership. This is a Milestone 1 hardening slice, not bucket-minimization work.

TDD/review evidence:
- Focused RED before downstream compute fix: `ComputeWorkspaceDependenciesTest` failed with `NoSuchElementException` when `debug` had the same `shortId` as `default` at a different version.
- Spec review caught that downstream compute was insufficient because `AggregatedDependencyResolver` could drop the child bucket before compute saw it.
- Focused RED before resolver fix: `AggregatedDependencyResolverTest` failed with unresolved helper references before production identity-aware coverage was added.
- Code-quality review required three fixes: make `test`/`androidTest` identity-aware, share identity helper, and include `repository` in identity. A second review passed with no required fixes.

Commands and results:
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependenciesTest" --console=plain`: passed after the downstream fix; forced rerun also passed in verifier subagent.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --console=plain`: passed after resolver fix.
- `./gradlew :grazel-gradle-plugin:test --console=plain`: verifier subagent saw a cached green run before resolver follow-up; worker later attempted full unit tests but hit environment disk exhaustion.
- `git diff --check`: passed.

Remaining risks / next action:
- Disk is currently near-full (`df -h` showed about 423 MiB available). Broader Gradle/Bazel verification needs space recovery before running.
- Next small hardening slice recommended by read-only explorer: make `ComputeWorkspaceDependencies.computeFromResults` tolerate an empty `default` bucket with a non-default bucket such as `free`; this supports rough flavor-bucket compatibility without full DAG optimization.

### 2026-06-17 16:10:40 +08 — Owner-Scoped Inline Exclude Hardening

Hypothesis:
- The global inline exclude map was too broad, but scoping only to the root configuration was too narrow. The safe Milestone 1 shape is owner-scoped inline metadata: emit the declaring project path from the resolved project-node traversal, collect declared excludes from the bucket-relevant variants for each project, and apply excludes by owner project path.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/ResolvedComponentsVisitor.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/fake/Fakes.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolverTest.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/ResolvedComponentsVisitorTest.kt`
- Regenerated root Bazel/Maven files.

What changed:
- Removed the root-global `group:artifact -> excludes` map that could apply child/sibling excludes to default/root deps.
- `ResolvedComponentsVisitor.VisitResult` now carries `directProjectPath` for direct external deps when traversing project nodes.
- `AggregatedDependencyResolver` now precomputes `variantsByProject`, collects exclude metadata from matching `Variant.migratableConfigurations` for the current bucket/leaf/test bucket, and passes `Map<projectPath, Map<shortId, excludes>>` into configuration resolution.
- `resolveConfigToDependencyMap` now chooses excludes by owner project path. Root/app-owned deps can use root config rules; downstream project-owned deps use downstream project rules; sibling/root rules do not bleed into downstream owners.
- Added tests for:
  - keeping default deps when non-default hierarchy has the same shortId but different effective identity;
  - dropping default deps only when the same owner exists only in non-default hierarchy;
  - child-only excludes not appearing on the parent configuration metadata;
  - owner project excludes applying without root/sibling bleed;
  - root/unknown-owner fallback behavior;
  - project-node direct deps reporting the owner project path.

Review findings:
- First read-only review found two high issues:
  - global exclude collection applied child/sibling excludes too broadly;
  - default bucket filtering still used shortId-only non-default hierarchy subtraction.
- Both were fixed before broad verification.
- Follow-up review found one remaining high edge: if Gradle selects a downstream project variant that does not match the app bucket names, for example app `freeDebug` consuming `:lib:paidDebug` through `matchingFallbacks`, the current bucket-name metadata lookup may miss `paid`/`paidDebug` excludes. Do not broaden back to all-variant excludes as a blind fix because that would reintroduce child/sibling bleed. Proper fix should carry selected downstream project variant metadata from the resolution graph or collect declared metadata as a cache-friendly side channel keyed by project + selected variant/configuration.

Commands and results:
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --tests "com.grab.grazel.gradle.dependencies.ResolvedComponentsVisitorTest" --console=plain`: passed.
- `./gradlew :grazel-gradle-plugin:test --console=plain`: passed.
- `./gradlew :grazel-gradle-plugin:functionalTest --console=plain`: passed.
- `./gradlew migrateToBazel --console=plain`: passed and repinned the current broad milestone bucket set.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `git diff --check`: passed.
- `bazelisk build //... --disk_cache=`: passed.

Generated-output observations:
- The focused root sample remains correct for the high-signal labels and constraintlayout excludes.
- The current bucket set is broad again (`demo_maven`, `full_maven`, `free_maven`, `paid_maven`, leaf debug repos, and `test_maven` are present). This is acceptable under the current milestone guidance: buildability and correctness first, bucket minimization later.
- The root `WORKSPACE` and `android_test_maven_install.json` have large diffs because repinning now includes the broad bucket artifact sets.

Remaining risks / next action:
- Hard remaining exclude edge: selected downstream project variants under `matchingFallbacks` or dimension mismatch. This should be the first issue to discuss if the next goal is exclude correctness.
- The planned declared-metadata side channel is still the right long-term design. It should preserve project path, selected/synthetic variant, configuration name, dependency shortId/version intent, scope, and exclude metadata without resolving classpaths.
- Plain Bazel with default disk cache was not re-established as clean after the symlinked-manifest cache/materialization failure. The generated graph builds with `--disk_cache=`.

### 2026-06-17 16:29:45 +08 — Selected Downstream Variant Exclude Hardening

Hypothesis:
- Owner-scoped inline excludes need the downstream project variant that Gradle actually selected, not just the app/root bucket names. The root app may resolve `freeDebugRuntimeClasspath` but consume a downstream project variant such as `paidDebugRuntimeElements` through matching fallbacks or dimension mismatch.
- We can carry this selected variant display name from the already-resolved Gradle graph without introducing another resolution fan-out. This keeps the new aggregated resolver default and preserves the no-old-expensive-per-project/per-variant-resolution rule.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/ResolvedComponentsVisitor.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/ResolvedComponentsVisitorTest.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolverTest.kt`

What changed:
- `ResolvedComponentsVisitor.VisitResult` now carries `directProjectVariantDisplayName` for direct external dependencies below a project node. The value comes from the project edge's `ResolvedDependencyResult.resolvedVariant.displayName`.
- `ProjectExcludeRules` now keeps both bucket-level rules and per-variant rules. When a selected downstream variant display name matches a known project variant prefix, exclude lookup uses that selected variant hierarchy instead of falling back to the app/root bucket's sibling metadata.
- Selected variant prefix matching chooses the longest variant-name prefix, so `paidDebugRuntimeElements` maps to `paidDebug` rather than `paid`.
- JVM/Kotlin owner projects are handled when Android-rooted buckets ask for Android variant types. `collectExcludeRulesByProjectPath` now includes each requested type's JVM equivalent, allowing Android app buckets to collect downstream JVM `default`/`test` declared excludes without resolving JVM classpaths.
- JVM main outgoing variants such as `apiElements` and `runtimeElements` map to the synthetic `default` variant when a project has one. This prevents Android test buckets from applying JVM test-only excludes to a main-selected JVM dependency after `default` and `test` metadata have both been collected.

TDD/review evidence:
- RED: `ResolvedComponentsVisitorTest.assert direct dependencies below project nodes include selected project variant` initially failed because `VisitResult` did not expose `directProjectVariantDisplayName`.
- RED: selected-project-variant resolver tests initially failed because `ProjectExcludeRules` and selected hierarchy matching did not exist.
- Read-only reviewer found the first follow-up issue: Android-rooted buckets collected owner excludes using the root `variantTypes`, so downstream JVM/Kotlin project excludes were dropped. Added RED test `collects jvm owner excludes when root bucket asks for android build metadata`, then fixed by widening owner variant types to include JVM equivalents.
- Read-only reviewer found the second follow-up issue: Android test buckets could aggregate JVM `default` and `test` metadata, then fall back to both for selected JVM main variants named `runtimeElements`/`apiElements`. Added RED test `maps jvm main selected variant display name to default excludes`, then fixed selected hierarchy matching for JVM main outgoing variants.

Commands and results:
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.ResolvedComponentsVisitorTest.assert direct dependencies below project nodes include selected project variant" --console=plain`: failed before implementation, passed after.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest.collects jvm owner excludes when root bucket asks for android build metadata" --console=plain`: failed before JVM-owner fix, passed after.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest.maps jvm main selected variant display name to default excludes" --console=plain`: failed before JVM-main fallback fix, passed after.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --tests "com.grab.grazel.gradle.dependencies.ResolvedComponentsVisitorTest" --console=plain`: passed.
- `./gradlew :grazel-gradle-plugin:test --console=plain`: passed.
- `./gradlew migrateToBazel --console=plain`: passed; pinning skipped because artifacts were up to date.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `git diff --check`: passed.
- `./gradlew :grazel-gradle-plugin:functionalTest --console=plain`: passed.
- `bazelisk build //... --disk_cache=`: passed.

Remaining risks / next action:
- Matching selected downstream variants still uses Gradle display-name prefix heuristics. Observed/expected names such as `paidDebugRuntimeElements`, `runtimeElements`, and `apiElements` are handled, but a future declared-metadata side channel keyed by project + selected variant/configuration would be more explicit.
- The current tests cover helper-level selected variant behavior and fake graph propagation. A full fixture for real Android `matchingFallbacks`/dimension mismatch selecting a sibling downstream project variant is still worth adding when we create broader fixtures.
- Broad bucket output remains acceptable for Milestone 1. Bucket minimization and full DAG ownership remain later work.

### 2026-06-17 18:37:44 +08 — Standalone Test and Lint Compatibility Hardening

Hypothesis:
- The old module-to-root aggregation hid two compatibility assumptions that the inverted root-to-module path must preserve for Milestone 1:
  - standalone `com.android.test` dependencies that overlap app/main deps should keep using the broad default `@maven` repo when possible;
  - child buckets that see a lower transitive version should not repin that lower version if default/root already selected the higher Gradle-effective version.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/ComputeWorkspaceDependencies.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/android/AndroidTestDataExtractor.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/dependencies/MavenInstallArtifactsCalculator.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/ComputeWorkspaceDependenciesTest.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/migrate/dependencies/MavenInstallArtifactsCalculatorTest.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/migrate/dependencies/DefaultArtifactPinnerTest.kt`
- `reports/scripts/verify-sample-bucket-labels.sh`
- Regenerated root Bazel/Maven files.

What changed:
- Standalone `com.android.test` root closures are now also unioned into the default hierarchy closure before final androidTest leftover calculation. This keeps standalone test deps that are already default-covered routed through `@maven`.
- `AndroidTestDataExtractor` no longer forces `ANDROID_TEST_VARIANT` when collecting Maven deps and filters inherited library Maven deps before combining target deps. This prevents the test target from duplicating broad `@maven` library deps while preserving androidTest leftovers such as `androidx.test:monitor`.
- `ComputeWorkspaceDependencies` now promotes lower child transitive deps to the default-selected dependency when default has the same effective identity or a higher selected version. The promoted child entry carries an override target back to the default `@maven` label.
- `MavenInstallArtifactsCalculator.mavenInstallRootArtifacts(...)` currently includes override-target carriers in child repos so Coursier can pin the same version the child repo references through `override_targets`.
- `verify-sample-bucket-labels.sh` now accepts both simple coordinate strings and detailed `maven.artifact(...)` output for the lint `auto-service-annotations:1.1.1` assertion, because generated exclusions can force detailed artifact rendering.

Decision notes:
- Broad `ksp_maven` remains acceptable for Milestone 1. We are not modeling variant-specific KSP buckets now because KSP rarely differs by debug/release/flavor in the target use case, and correctness/buildability are the current priority.
- The override-carrier child repo behavior is intentionally broad right now. It may make repos such as `android_test_maven`, `test_maven`, and `lint_maven` much larger, but that is acceptable under the current "broad buckets first" milestone. A later optimization should narrow override carriers to the transitive closure of each child repo's direct roots rather than including every override carrier.
- This reverses the earlier assumption that override-target artifacts should not be root artifacts in child Maven installs. The lint fixture showed that child Coursier resolution still needs the carrier to pin the selected version even when the final Bazel label points to `@maven`.

Commands and results:
- `./gradlew migrateToBazel --console=plain`: passed and repinned all root generated Maven repos.
- `reports/scripts/verify-sample-bucket-labels.sh`: failed before the verifier update because `lint_maven` rendered `auto-service-annotations:1.1.1` as detailed `maven.artifact(...)`; passed after the script accepted detailed artifacts.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `git diff --check`: passed.
- `./gradlew :grazel-gradle-plugin:test --console=plain`: passed.
- `bazelisk build //sample-android:sample-android-full-paid-debug --disk_cache= --verbose_failures`: passed.
- `bazelisk build //... --disk_cache=`: failed twice under default parallelism. The failures were in Android resource processing and reported generated source jars such as `lib_sample-android-demo-free-debug_kt-java-gensrc.jar` or `lib_sample-android-demo-paid-debug_kt-ksp-kt-gensrc.jar` as failed-to-open.
- `bazelisk build //... --disk_cache= --jobs=1`: passed, 239 targets.
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "*BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain`: passed, including fixture `bazelBuildAll`.

Remaining risks / next action:
- Child Maven repos are broader than desired because override-target carriers are now root artifacts. Future narrowing should use direct-root transitive closure hints or another explicit ownership signal.
- Parallel root `bazelisk build //... --disk_cache=` is not clean in this workspace, but serialized root Bazel build is clean. If cache/storage state starts blocking further work, clean Bazel/Gradle state and continue.
- Next guided goal can either add targeted flavor/test fixtures to lock the broad-bucket semantics, or optimize the override-carrier breadth once compatibility is stable.

### 2026-06-17 18:45:06 +08 — Override-Carrier Maven Root Narrowing

Hypothesis:
- The lint selected-version fix requires child Maven repos to root override-carrier artifacts that are actually needed by child direct roots, but it does not require every child repo to root every unrelated override carrier in that bucket.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/dependencies/MavenInstallArtifactsCalculator.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/dependencies/ArtificatPinner.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/migrate/dependencies/MavenInstallArtifactsCalculatorTest.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/migrate/dependencies/DefaultArtifactPinnerTest.kt`
- Regenerated root Bazel/Maven files.

What changed:
- `mavenInstallRootArtifacts(...)` now keeps all non-override default artifacts in `@maven`.
- For non-default repos, it keeps direct artifacts plus override-target carriers whose `shortId` appears in `WorkspaceDependencies.transitiveClasspath` for at least one direct root in that same repo.
- `MavenInstallArtifactsCalculator` and `DefaultArtifactPinner` both use the same root-artifact selection, so pin status checks and generated `maven_install` repos agree.
- Empty non-default repos are skipped for pinning. Aggregated repos such as `ksp_maven` still use their explicit artifact lists.

TDD evidence:
- RED: `MavenInstallArtifactsCalculatorTest.override target artifacts are only resolved in child maven install when reachable from direct roots` failed while child repos included every override carrier.
- GREEN: focused calculator and pinner tests passed after the helper accepted `transitiveClasspath` and filtered override carriers by direct-root reachability.

Commands and results:
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.migrate.dependencies.MavenInstallArtifactsCalculatorTest.override target artifacts are only resolved in child maven install when reachable from direct roots" --console=plain`: failed before the production change as expected.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.migrate.dependencies.MavenInstallArtifactsCalculatorTest" --tests "com.grab.grazel.migrate.dependencies.DefaultArtifactPinnerTest" --console=plain`: passed.
- `./gradlew migrateToBazel --console=plain`: passed and repinned all root generated Maven repos with smaller child repo root sets.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `git diff --check`: passed.
- Lint spot check: `lint_maven` still contains `com.google.auto.service:auto-service-annotations:1.1.1` and an override target pointing at `@maven//:com_google_auto_service_auto_service_annotations`.
- `bazelisk build //... --disk_cache= --jobs=1`: passed, 239 targets.

Remaining risks / next action:
- `WorkspaceDependencies.transitiveClasspath` is a `shortId`-keyed reachability hint. It is enough to avoid rooting unrelated override carriers, but it is not a complete variant/version/repository ownership proof.
- `calculateOverrideTargets(artifacts)` still emits override-target mappings from the full bucket artifact list, not only from narrowed root artifacts. This was left broad to avoid breaking transitive override semantics while compatibility is still being hardened.
- Full plugin unit tests and the focused flavor functional fixture were not rerun after this latest narrowing at the time of this checkpoint. Rerun them before treating the current WIP as freshly green.

### 2026-06-17 19:01:28 +08 — Review Fixes and AndroidTest Default Preference

Hypothesis:
- Child Maven repos should root only override carriers needed by that repo's own direct roots, not carriers leaked through another variant with the same direct dependency shortId.
- Test/androidTest targets should prefer default/main Maven labels for artifacts already present in the app/default repo, then fall back to test-specific repos for test-only leftovers. This avoids mixing lower test-owned versions with the app binary's higher default-owned versions.

Review findings addressed:
- `DefaultArtifactPinner.shouldRunPinning(...)` used the first root artifact for status probes. Because root artifacts can now contain reachable override carriers, the probe could target an overridden/default-owned artifact instead of a real child repo root. Added `pinStatusProbeArtifact()` to prefer `direct && overrideTarget == null`, then non-override artifacts, then final fallback.
- `MavenInstallArtifactsCalculator` narrowed resolved artifacts but still calculated `override_targets` from the full bucket artifact list. It now calculates override targets from `artifactsToResolve`.
- `WorkspaceDependencies.transitiveClasspath` was global by direct dependency shortId. Added `variantTransitiveClasspath` for Maven root selection while preserving the global map for existing transitive dependency consumers.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/ComputeWorkspaceDependencies.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/Dependencies.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/model/ResolveDependenciesResult.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/dependencies/MavenInstallArtifactsCalculator.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/dependencies/ArtificatPinner.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/ComputeWorkspaceDependenciesTest.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/migrate/dependencies/MavenInstallArtifactsCalculatorTest.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/migrate/dependencies/DefaultArtifactPinnerTest.kt`
- `reports/scripts/verify-sample-bucket-labels.sh`
- Regenerated root Bazel/Maven files.

What changed:
- `WorkspaceDependencies` now carries `variantTransitiveClasspath: Map<variantName, Map<directShortId, transitiveShortIds>>` with a default value for JSON/backward compatibility.
- `ComputeWorkspaceDependencies` derives the variant-scoped map from the reduced pre-flatten classpath and derives the legacy global map as the union.
- `MavenInstallArtifactsCalculator` and `DefaultArtifactPinner.pinnableMavenInstallRepos()` use the variant-scoped map for variant repos, falling back to the legacy global map when absent.
- Non-default `override_targets` now match the narrowed child root artifacts instead of including unreachable carrier mappings.
- `DefaultDependenciesDataSource.collectMavenDeps(...)` now places `default` first for `VariantType.Test` and `VariantType.AndroidTest`, preserving the existing main/default ownership model for test targets.
- `verify-sample-bucket-labels.sh` now rejects `@android_test_maven//:androidx_core_core` while still requiring androidTest-only `@android_test_maven//:androidx_test_monitor`.

TDD/debugging evidence:
- RED: focused calculator/pinner tests initially failed to compile on missing `variantTransitiveClasspath` and `pinStatusProbeArtifact`.
- GREEN: focused calculator/pinner/compute tests passed after adding the producer and consumer wiring.
- RED: `reports/scripts/verify-sample-bucket-labels.sh` failed on the generated `@android_test_maven//:androidx_core_core` label.
- Reproduced root failure: `bazelisk build //sample-android:sample-android-full-paid-debug-android-test --disk_cache= --jobs=1 --verbose_failures` failed in DexMerger with `Attempt at compiling intermediate artifact without its context` while generated deps mixed test-owned `androidx.core:core:1.10.1` with default/app-owned `androidx.core:core:1.13.1`.
- GREEN: after default-first test/androidTest mapping and regeneration, the same exact instrumentation target built successfully.

Commands and results:
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependenciesTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallArtifactsCalculatorTest" --tests "com.grab.grazel.migrate.dependencies.DefaultArtifactPinnerTest" --console=plain`: passed.
- `./gradlew migrateToBazel --console=plain`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: failed before the default-first mapper fix; passed after regeneration.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `git diff --check`: passed.
- Lint spot check: `lint_maven` still contains `com.google.auto.service:auto-service-annotations:1.1.1` and an override target pointing at `@maven//:com_google_auto_service_auto_service_annotations`.
- `bazelisk build //sample-android:sample-android-full-paid-debug-android-test --disk_cache= --jobs=1 --verbose_failures`: failed before the default-first mapper fix; passed after regeneration.
- `bazelisk build //... --disk_cache= --jobs=1`: passed, 239 targets.
- `./gradlew :grazel-gradle-plugin:test --console=plain`: passed.
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "*BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain`: passed, including fixture `bazelBuildAll`.

Remaining risks / next action:
- `android_test_maven` may still root default-covered artifacts for pinning because root artifacts are selected from the repo closure, but generated target labels now prefer `@maven` for default-covered test deps. Further root-artifact minimization can happen later.
- The legacy global transitive dependency store remains shortId-keyed by design. Variant-scoped reachability is currently used only for Maven install root selection.
- Broad `bazelisk test //...` remains known-red/unstated because generated lint/sample targets are separate debt.

### 2026-06-17 19:04:01 +08 — Target-Scoped Flavor Bucket Verifier

Hypothesis:
- The root sample verifier should prove flavor-specific bucket behavior on the generated app targets, not only prove that flavor labels exist somewhere in `sample-android/BUILD.bazel`.

Files changed:
- `reports/scripts/verify-sample-bucket-labels.sh`

What changed:
- Added target-scoped label helpers that extract one generated rule block by target name.
- Added checks that:
  - `sample-android-demo-free-debug` and `sample-android-full-free-debug` use `@free_maven//:androidx_constraintlayout_constraintlayout`;
  - `sample-android-demo-paid-debug` and `sample-android-full-paid-debug` use `@paid_maven//:androidx_constraintlayout_constraintlayout`;
  - those app binary targets do not satisfy the flavor check via sibling flavor repos or broad `@maven`.

Commands and results:
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `git diff --check`: passed.

Remaining risks / next action:
- This is a verifier hardening step only; no production or generated-output change was required.
- The app androidTest targets intentionally still use broader `@maven` for constraintlayout because test/androidTest mapping now prefers default/main ownership.
- Next useful hardening remains adding more semantic fixtures or starting the declared-metadata side-channel milestone.

### 2026-06-17 19:11:43 +08 — Declared Metadata Collector Extraction

Hypothesis:
- Exclude correctness should remain behavior-equivalent while moving the cheap declaration walk out of the resolver body. This gives the planned declared-metadata side channel a real boundary without introducing the later cache-safe Gradle task pipeline yet.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/DeclaredDependencyMetadataCollector.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolverTest.kt`
- `reports/dependencies-refactor-goal-log.md`

What changed:
- Added `DeclaredDependencyMetadataCollector.collectExcludeRulesByProjectPath(...)`.
- Moved `ProjectExcludeRules`, selected-variant display-name matching, declared exclude extraction, and `excludeRulesFor(...)` into the collector-side file.
- `AggregatedDependencyResolver` now receives a `DeclaredDependencyMetadataCollector` and calls it for main, unit test, androidTest, standalone test, and fallback variant resolution buckets.
- The JVM-owner exclude coverage now calls the collector directly instead of reaching into `AggregatedDependencyResolver` by reflection.

Commands and results:
- RED before implementation: `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest.collects jvm owner excludes when root bucket asks for android build metadata" --console=plain` failed at `compileTestKotlin` on missing `DeclaredDependencyMetadataCollector`.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest.collects jvm owner excludes when root bucket asks for android build metadata" --console=plain`: passed after adding the collector.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --console=plain`: passed.
- `./gradlew migrateToBazel --console=plain`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `git diff --check`: passed.

Remaining risks / next action:
- This is a layering/refactor checkpoint only; it does not yet make declared metadata a separate cache-safe Gradle task artifact.
- Broad full plugin unit tests and root Bazel build were not rerun after this extraction. The prior checkpoint remains the latest serialized `bazelisk build //... --disk_cache= --jobs=1` pass.
- Next guided step can either add fixtures around selected downstream variant metadata or decide whether to invest in the full Gradle-managed declared-metadata aggregation pipeline.

### 2026-06-17 19:26:59 +08 — Selected Fallback Flavor-Only Fixture and Bazel KAPT Diagnostic

Hypothesis:
- The selected downstream variant case needs real flavor-only external dependencies, not only flavor-specific exclude metadata, so a fallback consumer can prove the generated target uses the selected variant's Maven labels and does not leak the unselected sibling flavor.
- The refreshed default-parallel Bazel failure should be separated from dependency graph correctness before treating it as a generated-output regression.

Files changed:
- `grazel-gradle-plugin/src/functionalTest/kotlin/com/grab/grazel/migrate/BuildVariantTest.kt`
- `grazel-gradle-plugin/src/test/projects/android-project/android-library-mismatch/build.gradle`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/ComputeWorkspaceDependenciesTest.kt`
- `reports/dependencies-refactor-goal-log.md`

What changed:
- Added focused functional assertions for the `android-library-mismatch` selected-fallback fixture:
  - `app` consuming `flavor2` should depend on `//android-library-mismatch:android-library-mismatch-flavor2-debug`.
  - The matched paid fallback target should contain `@maven//:com_jakewharton_timber_timber`.
  - The matched paid fallback target should not contain the unselected free-only `@maven//:com_squareup_okio_okio`.
  - The selected paid constraintlayout exclude should be present in generated dependency metadata without the unselected free exclude bleeding in.
- Added real flavor-only external declarations to the fixture: `paidImplementation "com.jakewharton.timber:timber:4.7.1"` and `freeImplementation "com.squareup.okio:okio:2.8.0"`.
- Tightened `BuildVariantTest` setup to delete generated fixture dependency JSON and Maven lockfiles before each run. This keeps fixture edits from reusing stale generated dependency state, but does not resolve the production up-to-date question.
- Added a calculator regression test for a non-default dependency when the default bucket is empty.

TDD/debugging evidence:
- RED: the focused flavor functional test failed before adding real flavor-only externals because the matched paid target had no paid-only Maven dependency to assert.
- GREEN: after adding timber/okio and cleaning stale fixture generated outputs, `migrateToBazelWithFlavorsWereUsed` passed with fixture `bazelBuildAll`.
- Root default-parallel `bazelisk build //... --disk_cache=` failed in Android resource processing for `//sample-android:sample-android-full-free-debug-android-test`, reporting `sample-android/lib_sample-android-demo-free-debug_kt-kapt-generated-class.jar` as failed-to-open.
- Graph audit found no generated path or action input from the failing full-free androidTest target to the sibling demo-free KAPT jar. Treat this as a Bazel worker/execution-state issue unless a reproducible generated edge appears.

Commands and results:
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependenciesTest.keeps non default dependency when default bucket is empty" --console=plain`: passed.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependenciesTest" --console=plain`: passed.
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "*BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain`: failed before fixture data/cleanup; passed after the fixture update.
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "*BuildVariantTest.computeWorkspaceDependenciesDoesNotScheduleLegacyResolveTasksByDefault" --console=plain`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `bazelisk build //... --disk_cache=`: failed under default parallelism with the KAPT generated jar failed-to-open symptom.
- `bazelisk build //... --disk_cache= --jobs=1`: passed, 239 targets.
- `bazelisk build //... --disk_cache= --strategy=KotlinKapt=sandboxed`: passed, 239 targets.
- `git diff --check`: passed.

Remaining risks / next action:
- Investigate `ComputeWorkspaceDependenciesTask` up-to-date/cache inputs for dependency declaration changes. The stale fixture output uncovered during TDD may represent a real production invalidation gap.
- Do not claim plain default-parallel root Bazel build green. Current reliable verification is serialized or KAPT-sandboxed.
- Broad `bazelisk test //...` remains intentionally unclaimed because generated lint/sample targets are still separate debt.

### 2026-06-17 19:38:28 +08 — Compute Workspace Dependency Invalidation

Hypothesis:
- The stale fixture dependency output was a real production invalidation gap: in aggregated mode `ComputeWorkspaceDependenciesTask` was cacheable but had no declared input representing the Gradle dependency declarations it reads through the project model.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/ComputeWorkspaceDependenciesTask.kt`
- `grazel-gradle-plugin/src/functionalTest/kotlin/com/grab/grazel/migrate/BuildVariantTest.kt`
- `reports/dependencies-refactor-goal-log.md`

What changed:
- Added `dependencyDeclarationFiles` as `@InputFiles @PathSensitive(PathSensitivity.RELATIVE)` on `ComputeWorkspaceDependenciesTask`.
- Wired the input collection from `rootProject.fileTree(rootProject.projectDir)` to include `*.gradle`, `*.gradle.kts`, nested Gradle scripts, and `gradle/**/*.toml`, while excluding `.gradle` and `build` outputs.
- Added a functional invalidation regression that:
  - copies the android fixture into a temp directory without generated `build`, `.gradle`, or `bazel-*` state;
  - rewrites the fixture's external `constants.gradle` apply path for the temp copy;
  - runs `computeWorkspaceDependencies`;
  - edits `android-library-mismatch/build.gradle` to add okio to the selected paid fallback variant;
  - reruns `computeWorkspaceDependencies` without deleting outputs;
  - asserts the task executes again and `build/grazel/dependencies.json` includes the newly selected okio dependency.

TDD/debugging evidence:
- RED before the task-input fix: the second nested `computeWorkspaceDependencies` run was `UP-TO-DATE`, and the test failed at the assertion requiring a fresh execution.
- GREEN after adding the Gradle declaration file input: the second nested run executed and the output JSON included `com.squareup.okio:okio`.
- The first temp-copy helper tried to copy local Bazel/generated state and hit `No space left on device`; the helper now filters generated directories before copying.
- Per storage guidance, `bazelisk clean` and `./gradlew clean --console=plain` were run, then verification continued.

Commands and results:
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "*BuildVariantTest.computeWorkspaceDependenciesInvalidatesWhenProjectDependencyDeclarationsChange" --console=plain`: failed before the production fix with the second nested run `UP-TO-DATE`; passed after the task-input fix and filtered temp-copy helper.
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "*BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain`: passed, including fixture `bazelBuildAll`.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependenciesTest" --console=plain`: passed.
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "*BuildVariantTest.computeWorkspaceDependenciesDoesNotScheduleLegacyResolveTasksByDefault" --console=plain`: passed.
- `./gradlew migrateToBazel --console=plain`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `bazelisk build //... --disk_cache= --strategy=KotlinKapt=sandboxed`: passed, 239 targets, after `bazelisk clean`.
- `git diff --check`: passed.

Remaining risks / next action:
- This is a conservative milestone fix, not the final cache-safe declared-metadata pipeline. It tracks normal in-root Gradle scripts and version catalogs, but not arbitrary applied scripts outside the root tree or buildSrc/convention-plugin implementation code.
- Plain default-parallel `bazelisk build //... --disk_cache=` remains unclaimed because the earlier KAPT generated-jar failed-to-open issue reproduced there; use the KAPT-sandboxed strategy for the current reliable broad root build signal.

### 2026-06-17 20:36:21 +08 — Selected Flavor CompileOnly Declared Metadata

Hypothesis:
- The app/binary root classpath resolver can miss non-app library `compileOnly` declarations because those dependencies are not runtime-transitive from the app root.
- The immediate inline fix should use the existing cheap declared-metadata side channel during bucketing, not add a new task pipeline yet.
- For supported AGP declaration buckets such as `compileOnly`, `debugCompileOnly`, and `flavor2CompileOnly`, `Variant.name` is the correct output bucket key (`default`, `debug`, `flavor2`).

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/DeclaredDependencyMetadataCollector.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`
- `grazel-gradle-plugin/src/functionalTest/kotlin/com/grab/grazel/migrate/BuildVariantTest.kt`
- `grazel-gradle-plugin/src/test/projects/android-project/android-library-flavor/build.gradle`
- generated root/fixture Bazel outputs and Maven lockfiles after `migrateToBazel`
- `reports/dependencies-refactor-goal-log.md`

What changed:
- Added `DeclaredDependencyMetadataCollector.collectCompileOnlyDependenciesByBucket(...)`.
- The collector walks only declaration configurations from the already-built variant model, filters `ExternalDependency`, and creates direct `ResolvedDependency` entries keyed by `Variant.name`.
- `AggregatedDependencyResolver` now adds non-app project declared compileOnly metadata to hierarchy buckets before the existing broad non-app compileClasspath fallback.
- Added a functional fixture dependency: `flavor2CompileOnly "com.squareup.okhttp3:logging-interceptor:4.9.3"`.
- Added functional assertions that the generated selected flavor target uses `@flavor2_maven//:com_squareup_okhttp3_logging_interceptor` and does not fall back to `@maven`.

TDD/debugging evidence:
- RED before production wiring: `./gradlew :grazel-gradle-plugin:functionalTest --tests "*BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain` failed in fixture `:bazelBuildAll`; generated `android-library-flavor/BUILD.bazel` had `@maven//:com_squareup_okhttp3_logging_interceptor`, but `@maven` did not contain that target.
- Direct inspection during RED showed `com.squareup.okhttp3:logging-interceptor` was absent from fixture `build/grazel/dependencies.json` and Maven lockfiles.
- GREEN after production wiring: the forced focused functional test executed all 18 Gradle tasks, including fixture `bazelBuildAll`, and passed.
- Direct inspection after GREEN showed the dependency only in the `flavor2` bucket with repository marker `Declared`, the generated BUILD label as `@flavor2_maven`, and `flavor2_maven_install.json` containing the artifact.
- Subagent read-only check confirmed `Variant.name` aligns with flavor/build-type/default bucket naming. It also flagged that a synthetic combination declaration such as `flavor2DebugCompileOnly` would need leaf routing if such a configuration existed; the current AGP fixture does not create that declaration method, so this is noted rather than covered by this slice.

Commands and results:
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "*BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain --rerun-tasks`: passed, fixture `bazelBuildAll` passed.
- `jq '.result | to_entries[] | select(any(.value[]?; .shortId == "com.squareup.okhttp3:logging-interceptor")) | {bucket: .key, deps: [.value[] | select(.shortId == "com.squareup.okhttp3:logging-interceptor") | {id, shortId, direct, repository, dependencies}]}' grazel-gradle-plugin/src/test/projects/android-project/build/grazel/dependencies.json`: showed bucket `flavor2`, direct `true`, repository `Declared`, no dependency closure.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --console=plain`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `./gradlew migrateToBazel --console=plain`: passed and repinned generated root Maven repos.
- `git diff --check`: passed.
- `bazelisk build //... --disk_cache= --strategy=KotlinKapt=sandboxed`: passed, 239 targets, 185 actions.

Remaining risks / next action:
- This is intentionally the inline declared-metadata fix. The proper Gradle-managed, cache-safe declared-metadata aggregation task pipeline remains a later milestone.
- Declared compileOnly entries are direct-only and declared-version-only. They do not carry a resolved transitive closure, repository attribution, or jetifier metadata from Gradle resolution; rules_jvm_external pinning supplies the repository lock data afterward.
- Dependencies without an explicit version are skipped for now. If real builds use constraints/platforms for compileOnly declarations, the later declared-metadata pipeline needs to account for that.
- The broad non-app compileClasspath fallback remains in place for compatibility.
- Plain default-parallel `bazelisk build //... --disk_cache=` remains unclaimed; KAPT-sandboxed broad build is still the reliable broad Bazel signal for this branch.

### 2026-06-17 20:40:43 +08 — Build-Type CompileOnly Bucket Coverage

Hypothesis:
- `debugCompileOnly` on a non-app Android library is the build-type counterpart to the prior `flavor2CompileOnly` case.
- The risk was that the broad non-app `debugCompileClasspath` fallback could push the dependency into every leaf and make it look like `default`/`@maven`.
- The existing declared metadata path should make `debug` own the direct declaration, and generated targets should prefer `@debug_maven`.

Files changed:
- `grazel-gradle-plugin/src/test/projects/android-project/android-library-flavor/build.gradle`
- `grazel-gradle-plugin/src/functionalTest/kotlin/com/grab/grazel/migrate/BuildVariantTest.kt`
- `reports/dependencies-refactor-goal-log.md`

What changed:
- Added fixture declaration `debugCompileOnly "com.squareup.okhttp3:okhttp-urlconnection:4.9.3"`.
- Extended the compileOnly functional assertion to require `@debug_maven//:com_squareup_okhttp3_okhttp_urlconnection` and reject broad `@maven` for the same artifact.
- No production change was needed; the current inline declared metadata collector and target dependency lookup already route this case correctly.

Evidence:
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "*BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain --rerun-tasks`: passed, including fixture `bazelBuildAll`.
- Direct fixture JSON inspection showed `com.squareup.okhttp3:okhttp-urlconnection` in bucket `debug`, direct `true`, repository marker `Declared`.
- Direct generated file inspection showed `android-library-flavor/BUILD.bazel` uses `@debug_maven//:com_squareup_okhttp3_okhttp_urlconnection`, and `debug_maven_install.json` contains the artifact.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `git diff --check`: passed.

Remaining risks / next action:
- This covers a supported build-type declaration bucket, not unsupported synthetic combination declaration methods such as `flavor2DebugCompileOnly`.
- The broader non-app compileClasspath fallback still exists; this fixture proves it does not steal the selected build-type direct label in the current target generation path.
- Remaining declared-metadata risks are now mostly around dependencies without explicit versions, processor/KSP declaration metadata, and the future cache-safe metadata pipeline.

### 2026-06-17 20:49:03 +08 — Same Artifact Different Version Functional Guard

Hypothesis:
- Unit coverage already protects the reducer from treating `shortId` alone as identity, but the real generated output path still needed an end-to-end guard.
- The risky path is label lookup, because `DefaultMavenInstallStore` is keyed by variant + group + name, while target generation supplies a variant hierarchy. For a `flavor2Debug` app target, the hierarchy must find `debug` before `default` when the same artifact has a build-type override.

Subagent findings used:
- Existing same-shortId/different-version coverage is unit-only:
  - `ComputeWorkspaceDependenciesTest.keeps child bucket dependency when same artifact has different version than default`
  - `AggregatedDependencyResolverTest.keeps child bucket dependency when covered dependency has same short id but different version`
  - related test-bucket/default-preservation reducer tests.
- No functional fixture previously exercised real Gradle output for `implementation "x:y:1.0"` plus `debugImplementation "x:y:2.0"`.
- Variant declared-metadata review also found a separate future risk: non-app `testCompileOnly` / `androidTestCompileOnly` is modeled by variants but currently dropped by the compileOnly collector. That needs a proper test-target fixture before changing production logic.

Files changed:
- `grazel-gradle-plugin/src/functionalTest/kotlin/com/grab/grazel/migrate/BuildVariantTest.kt`
- `grazel-gradle-plugin/src/test/projects/android-project/app/build.gradle`
- generated android fixture `BUILD.bazel`, `WORKSPACE`, and Maven lockfiles after the focused functional test.
- `reports/dependencies-refactor-goal-log.md`

What changed:
- Added app fixture declarations:
  - `implementation 'org.apache.commons:commons-lang3:3.9'`
  - `debugImplementation 'org.apache.commons:commons-lang3:3.12.0'`
- Added functional assertions that:
  - `dependencies.json` keeps `org.apache.commons:commons-lang3:3.9` in `default`.
  - `dependencies.json` keeps `org.apache.commons:commons-lang3:3.12.0` in `debug`.
  - generated `app/BUILD.bazel` uses `@debug_maven//:org_apache_commons_commons_lang3`.
  - generated `app/BUILD.bazel` does not use `@maven//:org_apache_commons_commons_lang3` for the debug target.
- No production change was needed; the current hierarchy ordering and bucket identity logic already satisfy this case.

TDD/debugging evidence:
- RED before adding fixture declarations: focused functional test failed at the new assertion because the commons dependency was absent from `dependencies.json`.
- GREEN after adding fixture declarations: focused functional test passed, including nested fixture `bazelBuildAll`.
- Direct `dependencies.json` inspection showed:
  - `default`: `org.apache.commons:commons-lang3:3.9`, repository `BintrayJCenter`, direct `true`.
  - `debug`: `org.apache.commons:commons-lang3:3.12.0`, repository `BintrayJCenter`, direct `true`.
  - no `flavor2Debug` residual for the artifact.
- Direct generated file inspection showed `app/BUILD.bazel` contains `@debug_maven//:org_apache_commons_commons_lang3`, and both `maven_install.json` and `debug_maven_install.json` contain the artifact for their respective repos.

Commands and results:
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "*BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain --rerun-tasks`: failed before the fixture declaration was added; passed after the fixture declaration was added.
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest" --console=plain`: passed all three `BuildVariantTest` tests, including the dependency-declaration invalidation test and default task-graph dry run.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --tests "com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependenciesTest" --console=plain`: passed.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `git diff --check`: passed.
- `jq '.result | {default: [.default[]? | select(.shortId == "org.apache.commons:commons-lang3") | {id, version, repository, direct}], debug: [.debug[]? | select(.shortId == "org.apache.commons:commons-lang3") | {id, version, repository, direct}], flavor2debug: [.flavor2Debug[]? | select(.shortId == "org.apache.commons:commons-lang3") | {id, version, repository, direct}]}' grazel-gradle-plugin/src/test/projects/android-project/build/grazel/dependencies.json`: showed the expected default/debug split and no leaf residual.
- `rg -n 'commons_lang3|commons-lang3|org.apache.commons' grazel-gradle-plugin/src/test/projects/android-project/app/BUILD.bazel grazel-gradle-plugin/src/test/projects/android-project/WORKSPACE grazel-gradle-plugin/src/test/projects/android-project/maven_install.json grazel-gradle-plugin/src/test/projects/android-project/debug_maven_install.json`: showed the generated debug label and pinned artifacts.

Remaining risks / next action:
- Same artifact/different version is now guarded through the real Gradle-to-Bazel fixture for the app debug override case.
- The version-aware logic still stores one version per bucket per shortId; if multiple app roots with the same bucket name resolve different versions, `unionDependencyMaps` still chooses the max version for that bucket, matching Gradle-style single-classpath resolution but not preserving multiple versions inside one bucket.
- Next declared-metadata edge to consider: non-app `testCompileOnly` / `androidTestCompileOnly`, but covering it well likely requires adding real test sources/targets to the fixture rather than only checking dependency JSON.

### 2026-06-17 21:01:47 +08 — Non-App Test CompileOnly Bucket Coverage

Hypothesis:
- The root-app aggregated classpath path does not see a library module's own `testCompileOnly` declarations.
- The old per-project/per-variant path handled this because the unit-test variant drove its own dependency extraction.
- The cheap replacement should collect declared metadata from the already-built variant model and put Test/AndroidTest declarations in broad `test` / `androidTest` buckets for now, not in the main/default hierarchy.

Subagent findings used:
- `AndroidLibraryTargetBuilder` appends unit-test targets for Android libraries, but `AndroidUnitTestTarget` only emits a rule when `srcs` is non-empty.
- A normal Android library fixture cannot currently produce an embedded instrumentation target for `src/androidTest`; instrumentation binary generation is app/com.android.test oriented.
- App root test classpaths should not be expected to include non-app library test configurations.
- `DeclaredDependencyMetadataCollector.collectCompileOnlyDependenciesByBucket` was filtering to `AndroidBuild` and `JvmBuild`, so Test/AndroidTest variants were dropped before this slice.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/DeclaredDependencyMetadataCollector.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`
- `grazel-gradle-plugin/src/functionalTest/kotlin/com/grab/grazel/migrate/BuildVariantTest.kt`
- `grazel-gradle-plugin/src/test/projects/android-project/android-library-flavor/build.gradle`
- `grazel-gradle-plugin/src/test/projects/android-project/android-library-flavor/src/test/java/com/grab/grazel/android/flavor/FlavorUnitTest.java`
- generated android fixture `BUILD.bazel`, `WORKSPACE`, and Maven lockfiles after focused functional tests.

What changed:
- Added fixture declaration `testCompileOnly "org.apache.commons:commons-text:1.10.0"`.
- Added a tiny Java unit-test source that references `org.apache.commons.text.WordUtils`, so the Android library emits an `android_unit_test` rule that needs the dependency at compile time.
- Added a focused functional test, `nonAppLibraryTestCompileOnlyDepsUseTestBucket`, that asserts:
  - the library unit-test target is generated;
  - `dependencies.json` stores `org.apache.commons:commons-text:1.10.0` in `test`;
  - the artifact is not stored in `default`;
  - generated `android-library-flavor/BUILD.bazel` uses `@test_maven//:org_apache_commons_commons_text`;
  - the generated BUILD does not use `@maven//:org_apache_commons_commons_text`.
- Updated `DeclaredDependencyMetadataCollector` to include Test and AndroidTest variants when collecting compileOnly declarations.
- Test and AndroidTest compileOnly declarations now collapse to broad `test` and `androidTest` bucket names; main Android/JVM declarations remain keyed by their variant names.
- Updated `AggregatedDependencyResolver` so compileOnly metadata for `test` / `androidTest` goes into `testHierarchyBucketClosures`, not the main hierarchy buckets.

TDD/debugging evidence:
- RED before production change: `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.nonAppLibraryTestCompileOnlyDepsUseTestBucket" --console=plain --rerun-tasks` failed after `migrateToBazel` succeeded. The failure was the new assertion at `BuildVariantTest.kt:316`, where `versionsByBucket["test"]` was empty.
- RED inspection showed `android-library-flavor/BUILD.bazel` had the generated `android_unit_test`, but emitted `@maven//:org_apache_commons_commons_text` instead of `@test_maven`.
- GREEN after production change: the same focused functional test passed.
- Direct generated-file inspection after GREEN showed `@test_maven//:org_apache_commons_commons_text` in the unit-test deps and no `@maven` label for that artifact.

Commands and results:
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.nonAppLibraryTestCompileOnlyDepsUseTestBucket" --console=plain --rerun-tasks`: failed before production change at the new test-bucket assertion; passed after production change.
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest" --console=plain`: passed all four `BuildVariantTest` tests, including the existing fixture `bazelBuildAll` path.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --tests "com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependenciesTest" --console=plain`: passed.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `git diff --check`: passed.

Remaining risks / next action:
- This intentionally treats all non-app Test compileOnly declarations as broad `test_maven`, and all AndroidTest compileOnly declarations as broad `android_test_maven`. Precise per-test-leaf bucketing can be a later milestone if a failing case justifies the complexity.
- Normal Android library `androidTestCompileOnly` still lacks a minimal generated-target fixture in this project because embedded instrumentation targets are not generated for that fixture shape today.
- The collector still records declared compileOnly dependencies as direct, declared-version metadata only. Transitive closure, repository attribution, and jetifier details still come from rules_jvm_external pinning afterward.

### 2026-06-17 21:20:51 +08 — Declared CompileOnly Hardening

Hypothesis:
- The broad Test/AndroidTest compileOnly collector fix was directionally right, but it still needed hardening around generated-label scope, duplicate declarations, and malformed external dependency metadata.
- Because these are cheap declared metadata, the current inline bucket-time collector remains acceptable for this milestone; the separate Gradle-managed metadata fanout is still a later cleanup/architecture slice.

Subagent audit:
- A read-only explorer reviewed the current compileOnly slice and found no blockers.
- It confirmed the fixture covers `testCompileOnly -> @test_maven`, duplicate declared compileOnly highest-version selection, and null-group skip.
- It noted that blank-string groups are handled by production code but not separately tested.
- It reaffirmed that normal Android library `androidTestCompileOnly` still lacks a meaningful generated-target fixture because this fixture shape does not currently emit embedded instrumentation targets.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/DeclaredDependencyMetadataCollector.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`
- `grazel-gradle-plugin/src/functionalTest/kotlin/com/grab/grazel/migrate/BuildVariantTest.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolverTest.kt`
- `grazel-gradle-plugin/src/test/projects/android-project/android-library-flavor/build.gradle`
- `grazel-gradle-plugin/src/test/projects/android-project/android-library-flavor/src/test/java/com/grab/grazel/android/flavor/FlavorUnitTest.java`

What changed:
- The non-app compileOnly functional test was tightened to inspect the generated `android_unit_test` block, so an unrelated file-wide label can no longer satisfy the assertion.
- Added a duplicate declared compileOnly fixture:
  - `debugCompileOnly "org.apache.commons:commons-collections4:4.4"`
  - `debugCompileOnly "org.apache.commons:commons-collections4:4.1"`
- Added assertions that `commons-collections4` lands only in `debug`, chooses version `4.4`, and the generated target uses `@debug_maven` rather than `@maven`.
- `DeclaredDependencyMetadataCollector` now groups compileOnly declarations by `shortId` inside each bucket and reduces duplicates with `mergeDependencyMetadataByMaxVersion`, preserving merged metadata/excludes while choosing the highest version.
- Added a unit regression for an external dependency declaration with no group.
- The collector now skips null and blank groups before creating `ResolvedDependency` metadata.

TDD/debugging evidence:
- RED for duplicate declarations: the focused functional test failed with `commons-collections4` selecting `4.1`, proving declaration order was leaking into bucket ownership.
- GREEN after reducer change: the same focused functional test passed and generated JSON showed `commons-collections4:4.4` only in `debug`.
- RED for null group: the focused unit test failed with an NPE in the declared metadata collector.
- GREEN after guard: the same unit test passed.
- A final forced functional run of all `BuildVariantTest` tests passed with `--rerun-tasks`, including the fixture `bazelBuildAll` path.

Commands and results:
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.nonAppLibraryDeclaredCompileOnlyDepsUseExpectedBuckets" --console=plain --rerun-tasks`: failed before the duplicate reducer, passed after the reducer and scoped BUILD assertion.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest.skips declared compileOnly dependencies without group" --console=plain --rerun-tasks`: failed before the null-group guard, passed after the guard.
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest" --console=plain --rerun-tasks`: passed after executing all 18 tasks.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --tests "com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependenciesTest" --console=plain`: passed.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `git diff --check`: passed.

Remaining risks / next action:
- Blank-string group skip is guarded in production code but not separately asserted. The null-group regression covers the original failure mode.
- Same artifact in `default`/main and direct `testCompileOnly` with a different version can still be affected by the current default-first Maven lookup for test buckets. This is accepted for the broad Test/AndroidTest milestone and should become a version-aware Maven lookup/failing fixture only if it shows up.
- Normal Android library `androidTestCompileOnly` generated-target coverage is still deferred; a standalone `com.android.test` fixture would not prove that path for normal libraries.
- Declared compileOnly metadata remains declared/direct-only. The proper path is still a Gradle-managed declared metadata task pipeline that cheaply aggregates metadata from project variants while leaving expensive classpath resolution at binary roots.

### 2026-06-17 21:31:49 +08 — Flavor Parent Version Override Fixture

Hypothesis:
- The debug-parent same-artifact/different-version fixture proves one parent edge, but the required DAG behavior also needs a flavor-parent fixture.
- A filtered fixture with only `flavor2Debug` enabled is useful because flavor dependencies can otherwise collapse into default if explicit hierarchy buckets are not honored.

Files changed:
- `grazel-gradle-plugin/src/functionalTest/kotlin/com/grab/grazel/migrate/BuildVariantTest.kt`
- `grazel-gradle-plugin/src/test/projects/android-project/app/build.gradle`

What changed:
- Added an app fixture declaration:
  - `implementation 'com.google.code.findbugs:jsr305:3.0.1'`
  - `flavor2Implementation 'com.google.code.findbugs:jsr305:3.0.2'`
- Added `sameArtifactDifferentFlavorVersionsShouldUseNearestBucket(...)`, which asserts:
  - `default` keeps `com.google.code.findbugs:jsr305:3.0.1`;
  - `flavor2` keeps `com.google.code.findbugs:jsr305:3.0.2`;
  - generated app target uses `@flavor2_maven//:com_google_code_findbugs_jsr305`;
  - generated app target does not use `@maven//:com_google_code_findbugs_jsr305`.

TDD/debugging evidence:
- RED before fixture declarations: the focused functional test failed at the new default-bucket assertion because `jsr305` was not declared yet.
- An initial fixture attempt used `org.apache.commons:commons-math3`; generation reached Bazel, but `bazelBuildAll` failed dexing the app because the extra jar pushed the single-dex method count over 65K. That was a bad fixture artifact, not a resolver failure.
- Swapped the fixture to tiny annotation artifact `com.google.code.findbugs:jsr305`, preserving the same default/flavor version-override shape without materially affecting method count.
- GREEN after the fixture swap: focused flavor functional test passed with `bazelBuildAll`; no production resolver change was needed.
- The full `BuildVariantTest` class then passed with all tasks rerun.

Commands and results:
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain --rerun-tasks`: failed before fixture declarations at `BuildVariantTest.kt:314`; failed with `commons-math3` due app single-dex method count; passed after switching to `jsr305`.
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest" --console=plain --rerun-tasks`: passed after executing all 18 tasks.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `git diff --check`: passed.

Remaining risks / next action:
- Flavor-parent version ownership is now covered in the real Gradle-to-Bazel fixture.
- This did not add production code because the current identity-aware set bucketing already handled the case.
- Test/androidTest same-artifact/version overlap remains the next edge-heavy bucket area if the next guided slice should continue fixture hardening.

### 2026-06-17 22:08:18 +08 - Version-Aware Target Label Lookup

Hypothesis:
- The maven store already knew which bucket owned each resolved artifact, but target generation looked up labels only by `group:name`.
- For declarations like `implementation "x:y:1.0"` plus `debugImplementation "x:y:2.0"`, target deps need to choose the closest direct declaration's version so debug targets use `@debug_maven` while default targets keep `@maven`.
- Test and androidTest buckets need the same version-aware behavior for true direct test declarations, but inherited main dependencies must still fall back to the main/default bucket when they are only present through AGP metadata.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/MavenInstallStore.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/DependencyResolutionService.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/Dependencies.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/DefaultDependencyResolutionServiceTest.kt`
- `grazel-gradle-plugin/src/functionalTest/kotlin/com/grab/grazel/migrate/BuildVariantTest.kt`
- `grazel-gradle-plugin/src/test/projects/android-project/app/build.gradle`
- `grazel-gradle-plugin/src/test/projects/android-project/android-library-flavor/build.gradle`
- `grazel-gradle-plugin/src/test/projects/android-project/android-library-flavor/src/test/java/com/grab/grazel/android/flavor/FlavorUnitTest.java`

What changed:
- `MavenInstallStore` now stores both exact `(variant, group, name, version)` entries and broad `(variant, group, name)` fallback entries.
- `DependencyResolutionService.getMavenDependency(...)` accepts an optional `version`.
- `DefaultDependenciesDataSource.collectMavenDeps(...)` now groups external deps by `shortId`, builds a variant hierarchy, and selects one target label per artifact by repo priority.
- Exact version lookup is used only when a matching direct declaration is found in the current variant hierarchy.
- Direct declaration lookup now walks the hierarchy but only through variants of the same `VariantType`.
- Direct declaration lookup is restricted to real declaration bucket configurations: names ending in `implementation`, `api`, `compileOnly`, or `runtimeOnly`, excluding `DependenciesMetadata` and `classpath` configs.
- That restriction is important: AGP metadata configs can carry inherited main declarations into androidTest. Treating those metadata entries as direct declarations caused generated instrumentation targets to use `@android_test_maven//:androidx_core_core`.

TDD/debugging evidence:
- RED unit test before API/storage change: `DefaultDependencyResolutionServiceTest.test getMavenDependency prefers exact version before broad fallback` could not compile because lookup had no `version` parameter.
- GREEN after store/service change: exact `2.0` resolved to `test_maven`, exact `1.0` resolved to `maven`, broad lookup still resolved to `maven`.
- RED functional case after first implementation: the focused flavor migration showed duplicate labels for the same artifact because both default and variant declarations were emitted. Grouping by `shortId` and choosing the closest repo fixed it.
- RED sample verifier after that: `reports/scripts/verify-sample-bucket-labels.sh` found `@android_test_maven//:androidx_core_core` in `sample-android/BUILD.bazel`.
- Root cause of the sample failure: androidTest metadata exposed inherited main `androidx.core:core:1.10.1`; exact matching treated it as direct androidTest ownership even though generated targets should prefer the default/main bucket.
- GREEN after declaration-bucket filtering: sample verifier passed while `android_test_maven` can still contain the artifact for pinning.

Commands and results:
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain --rerun-tasks`: passed after the final declaration-bucket filter.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionServiceTest" --console=plain --rerun-tasks`: passed.
- `./gradlew migrateToBazel --console=plain --rerun-tasks`: passed.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `git diff --check`: passed.
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest" --console=plain --rerun-tasks`: passed after executing all 18 tasks.

Remaining risks / next action:
- Exact version target lookup now has coverage for main/debug, flavor, and unit-test direct overrides.
- androidTest direct override coverage is still via the sample verifier shape, not a small purpose-built fixture.
- The declaration-bucket filter is intentionally name-pattern based, matching the existing variant parsing style. If a future Gradle or AGP plugin adds a new declaration bucket name, it may need to be added here.
- This still keeps broad `test_maven`, `android_test_maven`, and `ksp_maven` as the milestone behavior. More precise DAG modeling remains a later optimization, not required for the current compatibility phase.

### 2026-06-17 22:21:55 +08 - AndroidTest Direct Version Override Fixture

Hypothesis:
- Version-aware target lookup was covered for main/debug, flavor, and unit-test direct overrides, but androidTest direct overrides only had indirect sample verifier coverage.
- A small app instrumentation fixture should prove that a direct `androidTestCompileOnly` declaration with a different version owns the generated instrumentation dependency label while the app binary still uses the default/main Maven label.

Files changed:
- `grazel-gradle-plugin/src/functionalTest/kotlin/com/grab/grazel/migrate/BuildVariantTest.kt`
- `grazel-gradle-plugin/src/test/projects/android-project/app/build.gradle`
- `grazel-gradle-plugin/src/test/projects/android-project/app/src/androidTest/java/com/example/androidproject/MainActivityInstrumentedTest.java`

What changed:
- Added app fixture declarations:
  - `implementation 'com.google.j2objc:j2objc-annotations:1.1'`
  - `androidTestCompileOnly 'com.google.j2objc:j2objc-annotations:1.3'`
- Added a tiny androidTest Java source using `@Weak` so the app fixture actually emits an `android_instrumentation_binary`.
- Added assertions that:
  - `default` keeps `com.google.j2objc:j2objc-annotations:1.1`;
  - `androidTest` keeps `com.google.j2objc:j2objc-annotations:1.3`;
  - the app `android_binary` uses `@maven//:com_google_j2objc_j2objc_annotations`;
  - the generated `android_instrumentation_binary` uses `@android_test_maven//:com_google_j2objc_j2objc_annotations`;
  - the instrumentation target does not use the broad default Maven label for the direct androidTest override.
- Scoped the existing main/debug and flavor version assertions to the generated `android_binary` block. The earlier file-wide checks became brittle once the same BUILD file also contained an instrumentation target that legitimately inherited broad default labels.

TDD/debugging evidence:
- Initial RED: the new androidTest assertion failed because the app fixture had no `src/androidTest` source, so no `android_instrumentation_binary` was generated.
- Added the minimal androidTest source and direct declarations. The fixture then generated and built through `bazelBuildAll`, but an older main/debug assertion failed because it scanned all of `app/BUILD.bazel` and saw a broad `@maven` label in the instrumentation target.
- Scoped main/debug and flavor assertions to `android_binary`; the focused functional test passed.
- A subagent review found the androidTest block assertions targeted the intended generated rule and flagged one low brittleness: the global dependency JSON default-bucket assertion could be satisfied by another module. Added an app-binary label assertion so the app's own main declaration is covered too.

Commands and results:
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain --rerun-tasks`: failed before adding androidTest source; failed before assertion scoping; passed after scoping; passed again after the app-binary guard.
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest" --console=plain --rerun-tasks`: passed before the final app-binary guard; the guard only touches the already-rerun focused method.
- `./gradlew migrateToBazel --console=plain --rerun-tasks`: passed.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `git diff --check`: passed.

Remaining risks / next action:
- Direct androidTest version ownership is now covered by a purpose-built fixture.
- This does not attempt precise per-leaf androidTest bucketing; broad `android_test_maven` remains the accepted milestone behavior.
- Normal Android library `androidTestCompileOnly` generated-target coverage and standalone `com.android.test` edge shapes remain deferred unless a failing case shows the current broad/default-first behavior is wrong.

### 2026-06-17 23:27:10 +08 - Filtered Build-Type Excludes Stay Out of Default Buckets

Hypothesis:
- The selected fallback fixture proved paid/free selection, but did not prove selected build-type parent metadata against filtered-out build types.
- `DefaultVariantBuilder.build(project)` used only migratable variants to compute default-variant `ignoreKeywords`.
- When `release` variants were filtered out, `releaseImplementation` was no longer in that migratable set, so default bucket parsing could accidentally include filtered release metadata/excludes.
- `DefaultVariantBuilder.onVariants(project)` already used all declared flavor/build-type names for default-variant ignores, so the eager `build(project)` path should match that behavior.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/variant/VariantBuilder.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/variant/DefaultVariantBuilderTest.kt`
- `grazel-gradle-plugin/src/functionalTest/kotlin/com/grab/grazel/migrate/BuildVariantTest.kt`
- `grazel-gradle-plugin/src/test/projects/android-project/android-library-mismatch/build.gradle`

What changed:
- Added a selected-debug-only fixture declaration in `android-library-mismatch`:
  - `debugImplementation("javax.annotation:javax.annotation-api:1.3.2")` excluding `com.example:selected-debug-only-exclude`
  - `releaseImplementation("javax.annotation:javax.annotation-api:1.3.2")` excluding `com.example:unselected-release-only-exclude`
- Added functional assertions that generated metadata contains the selected debug exclude and does not contain the filtered release exclude.
- Added a unit regression proving an `AndroidDefaultVariant` no longer parses release configurations when the variant filter removes release variants.
- Updated `DefaultVariantBuilder.build(project)` so default variants compute `ignoreKeywords` from all declared flavors/build types via `variantDataSource.getFlavors(project)` and `variantDataSource.getBuildTypes(project)`, matching `onVariants(project)`.
- Kept actual parsed bucket variants driven by migratable variants. This avoids broadening generated buckets while fixing default metadata bleed.

TDD/debugging evidence:
- RED fixture wiring: focused functional test initially failed because the new artifact was not declared/generated.
- RED metadata bleed: after adding the fixture declarations, the focused functional test failed because `dependencies.json` contained both `selected-debug-only-exclude` and `unselected-release-only-exclude`.
- RED unit regression: after fixing the test DSL shape, the focused unit test failed at the new assertion because default AndroidBuild still saw a release configuration.
- GREEN unit regression: after the `VariantBuilder.build(project)` patch, the focused unit test passed.
- GREEN functional regression: the focused `BuildVariantTest.migrateToBazelWithFlavorsWereUsed` test passed, including its inner generated-project `bazelBuildAll`.
- Generated metadata spot check found `selected-debug-only-exclude` and no `unselected-release-only-exclude`; generated `android-library-mismatch/BUILD.bazel` uses `@maven//:javax_annotation_javax_annotation_api`.
- Gradle daemons and Bazel servers were shut down before/after heavy runs to keep resources stable.

Commands and results:
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.variant.DefaultVariantBuilderTest.default android variants ignore configurations for filtered build types" --console=plain --rerun-tasks`: failed before the production patch at `DefaultVariantBuilderTest.kt:181`; passed after the patch.
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain --rerun-tasks`: failed before the patch with filtered release exclude bleed; passed after the patch, with generated-project Bazel build success.
- `rg -o "selected-debug-only-exclude|unselected-release-only-exclude|javax.annotation:javax.annotation-api" grazel-gradle-plugin/src/test/projects/android-project/build/grazel/dependencies.json`: found the selected exclude and artifact, and did not find the unselected release exclude.
- `rg -n "javax_annotation_javax_annotation_api" grazel-gradle-plugin/src/test/projects/android-project/android-library-mismatch/BUILD.bazel`: found the expected `@maven` label.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `git diff --check`: passed.

Remaining risks / next action:
- This fixes default-bucket metadata bleed from filtered build types/flavors, not every possible downstream metadata ambiguity.
- Broad selected downstream labels remain the accepted milestone behavior; no per-leaf bucket optimization or DAG model was introduced.
- A future cheap declared-metadata aggregation task can still be the cleaner long-term path for excludes and declared-only metadata. This checkpoint keeps the current inline collection/filtering correct enough for the compatibility milestone.

### 2026-06-17 23:31:56 +08 - Blank-Group Declared Exclude Metadata Is Ignored

Hypothesis:
- The compileOnly declared metadata collector already skipped null/blank dependency groups before creating `ResolvedDependency` rows, but declared exclude extraction still built short IDs directly from `dependency.group`.
- A malformed external dependency with a blank group and an exclude rule could therefore produce a bogus `:artifact` metadata key. It would usually fail to match resolved artifacts, but it is still incorrect declared metadata and makes the side channel less defensible.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/DeclaredDependencyMetadataCollector.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolverTest.kt`

What changed:
- Added a unit regression that creates an external dependency with blank `group`, name `library`, version `1.0`, and an exclude rule.
- `extractDeclaredExcludeRulesByShortId()` now skips external dependencies whose group is null or blank before forming `group:artifact` keys.
- The older `extractExcludeRulesByShortId()` fallback path now applies the same null/blank group filter, keeping malformed keys out of both declared and extended-classpath metadata maps.

TDD/debugging evidence:
- RED: the focused unit test failed at the new assertion because declared exclude extraction returned malformed metadata for the blank-group dependency.
- GREEN: after adding the group filter to the extraction helpers, the focused unit test passed.
- The full `AggregatedDependencyResolverTest` class passed after the patch.

Commands and results:
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest.declared exclude metadata skips dependencies without group" --console=plain --rerun-tasks`: failed before the production patch; passed after the patch.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --console=plain`: passed.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `git diff --check`: passed.
- `./gradlew --stop`: stopped the Gradle daemon after verification.

Remaining risks / next action:
- This closes the concrete blank-group declared-exclude metadata gap. It does not add a separate compileOnly blank-group test because the compileOnly guard was already in production and the null-group regression covers construction failure.
- Normal Android library `androidTestCompileOnly` generated-target coverage remains deferred because the current fixture shape does not emit embedded instrumentation targets for libraries.
- Continue with either selected downstream metadata hardening or another high-value flavor/test bucket fixture; keep broad milestone buckets unless a failing case justifies more precision.

### 2026-06-17 23:36:35 +08 - Explicit Opt-Out Still Schedules Legacy Resolve Fanout

Hypothesis:
- The new aggregated resolver should remain the default, but the old per-project/per-variant resolver path should stay available as an explicit escape hatch while compatibility hardening continues.
- A dry-run task-graph regression is enough for this milestone because the opt-out path is retained for durability/reference, not polished as the preferred behavior.

Files changed:
- `grazel-gradle-plugin/src/functionalTest/kotlin/com/grab/grazel/migrate/BuildVariantTest.kt`

What changed:
- Added a copied-fixture functional regression that injects `aggregatedDependencyResolution.set(false)` into the root `experiments` block.
- The regression runs `computeWorkspaceDependencies --dry-run --console=plain` and asserts that legacy `*ResolveDependencies` tasks are present, plus the root `:computeWorkspaceDependencies` task remains in the graph.
- Kept the existing default task-graph verifier as the complementary guard that convention-default behavior does not schedule the legacy fanout.

Debugging evidence:
- The first focused run failed at the fixture-mutation guard because `.trimIndent()` removed the four-space indentation present in the copied `build.gradle`, so the replacement did not apply.
- The test setup was corrected to replace the known `minSdkVersionWorkaround.set(true)` line directly.
- The rerun passed and printed per-project/per-variant `*ResolveDependencies SKIPPED` entries, proving the opt-out path still registers the old task graph.

Commands and results:
- `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesSchedulesLegacyResolveTasksWhenAggregatedResolutionDisabled" --console=plain --rerun-tasks`: failed before the test setup fix at `BuildVariantTest.kt:129`; passed after the fix.
- `bash reports/scripts/verify-default-task-graph.sh`: passed.
- `bash reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `git diff --check`: passed.

Remaining risks / next action:
- The opt-out is verified only at task-graph/dry-run level in this slice, not with full legacy resolution output. That is intentional for now because the branch is hardening the new default, while the old path remains a reference/escape hatch.
- Continue choosing small compatibility fixtures or metadata hardening slices before any old-code cleanup.

### 2026-06-17 23:40:39 +08 - Standalone Test Sample Labels Guarded, Library AndroidTest Still Deferred

Hypothesis:
- Two deferred AndroidTest edges should be separated:
  - normal `com.android.library` `androidTestCompileOnly` generated-target coverage is only useful if the product emits a library instrumentation target that consumes the label;
  - standalone `com.android.test` modules already have generated instrumentation targets in the root sample and can be guarded cheaply at label level.

Evidence:
- A read-only subagent confirmed normal Android libraries currently do not generate `android_instrumentation_binary` targets:
  - `AndroidInstrumentationBinaryTargetBuilder.canHandle(project)` requires `project.isAndroidApplication`;
  - `AndroidTestTargetBuilder.canHandle(project)` is only for `project.isAndroidTest`;
  - `AndroidLibraryTargetBuilder` emits Android library targets and unit-test targets, not embedded instrumentation targets.
- Therefore a normal-library `androidTestCompileOnly` fixture could only prove JSON bucket presence today, not real generated-target label routing. Keep that edge deferred unless product semantics change.

Files changed:
- `reports/scripts/verify-sample-bucket-labels.sh`

What changed:
- Generalized the verifier's target-block helper so it can inspect targets in `sample-android/BUILD.bazel` and `sample-android-tests/BUILD.bazel`.
- Added assertions over the four generated standalone test targets:
  - require `@maven//:androidx_test_runner`;
  - require `@maven//:androidx_test_rules`;
  - require `@maven//:androidx_compose_ui_ui_test_junit4`;
  - reject the corresponding `@android_test_maven` labels.
- This locks the accepted milestone behavior that standalone `com.android.test` direct deps already covered by the broad default repo stay on `@maven`.

Commands and results:
- `bash reports/scripts/verify-sample-bucket-labels.sh`: passed after fixing the helper to read the target from `$2`.
- `bash reports/scripts/verify-default-task-graph.sh`: passed.
- `git diff --check`: passed.

Remaining risks / next action:
- This is verifier coverage over already-generated root sample outputs, not a new functional fixture. It is intentionally cheap because the behavior is a compatibility guard, not a new bucketing algorithm.
- Normal-library `androidTestCompileOnly` generated-target coverage remains deferred until Grazel emits a meaningful instrumentation target for normal libraries or a failing case proves JSON-only coverage would catch a real regression.

### 2026-06-17 23:47:45 +08 - Root Generation and Build Verification Refresh

Hypothesis:
- After the explicit opt-out regression and standalone `com.android.test` sample-label guard, the branch needed root-level evidence refreshed against the objective's normal gates.
- No production code changed in this slice; the purpose was to prove the current generated root outputs and broad compile signal remain healthy.

Commands and results:
- `./gradlew migrateToBazel --console=plain`: passed; generated root and project Bazel files, checked Maven pin status, and skipped repinning because artifacts were up to date.
- `bash reports/scripts/verify-default-task-graph.sh`: passed; default `computeWorkspaceDependencies --dry-run` still has no legacy `*ResolveDependencies` task fanout.
- `bash reports/scripts/verify-sample-bucket-labels.sh`: passed, including the newer standalone `sample-android-tests` label guards.
- `git diff --check`: passed.
- `bazelisk build //... --disk_cache= --strategy=KotlinKapt=sandboxed`: passed for 239 targets.

Remaining risks / next action:
- Broad `bazelisk test //...` remains intentionally unclaimed because the known generated lint/sample failures are out of scope for this dependency-resolution goal.
- Plain default-parallel `bazelisk build //... --disk_cache=` remains less trusted than the KAPT-sandboxed command because earlier failures were KAPT/generated-jar worker/cache related rather than dependency graph failures.
- The remaining strategic work is not another small known bug; it is either the future Gradle-managed declared-metadata task pipeline or a user-directed cleanup/packaging pass once compatibility is considered sufficient.

### 2026-06-17 23:50:59 +08 - Local Durability Commit

Decision:
- Package the current hardening state into a local commit for durability, excluding tool/local planning artifacts.

Included:
- Resolver and downstream dependency model hardening.
- Declared metadata collector extraction and tests.
- Functional fixtures and generated root/sample Bazel outputs.
- Focused verifier scripts and goal log updates.

Excluded:
- `codedb.snapshot` because it is a local code-index artifact.
- `docs/superpowers/plans/2026-06-17-dependency-resolution-refactor.md` because it is a stale unchecked implementation-plan artifact superseded by the goal log.

Verification before packaging:
- `./gradlew migrateToBazel --console=plain`: passed.
- `bash reports/scripts/verify-default-task-graph.sh`: passed.
- `bash reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `git diff --check`: passed.
- `bazelisk build //... --disk_cache= --strategy=KotlinKapt=sandboxed`: passed for 239 targets.
- `git diff --cached --check`: passed before commit.

Remaining risks / next action:
- Branch still needs a user decision on whether compatibility coverage is sufficient for cleanup/merge packaging, or whether to start the larger Gradle-managed declared-metadata task pipeline.
