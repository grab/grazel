# Item 10 - Adversarial Follow-Up Fixes

## 2026-06-26 19:45 SGT - Review Findings Fixed Locally

- Trigger:
  - Read-only code-diff audit found three remaining review blockers after the
    PAX green baseline:
    1. `com.android.test` instrumented app references were string-shaped in
       real extraction, while the collector test used a structured project
       dependency.
    2. Target-reference collection was one-pass, so targets activated by an
       earlier referenced-project pass could be generated later without their
       own Maven repos being collected.
    3. Corruption recovery unpinned `maven_install_json` but left pinned macro
       load/call lines active.
- Root-cause decisions:
  - Keep instrumented app references structured at the extractor boundary where
    possible.
  - Collect target references to a fixed point using the same target model
    builder, seeding the temporary render plan with references discovered in
    prior passes.
  - Parse string project labels only inside target model collection as a
    fallback for model fields that are already represented as raw Bazel labels;
    this is not generated BUILD/WORKSPACE scraping.
  - Unpin recovery must comment pinned macro load/call lines as well as
    `maven_install_json`.
- TDD evidence:
  - Red tests first:
    - real string-shaped Android test `instruments` was not collected;
    - `DefaultAndroidTestDataExtractor` did not guarantee a structured
      instrumented target reference;
    - fixed-point collection missed a Maven repo from a referenced-only target;
    - corruption recovery did not fully comment pinned macro lines.
  - Green command:
    `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon
    --tests "com.grab.grazel.tasks.internal.TargetMavenRepoReferencesCollectorTest"
    --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest"
    --tests "com.grab.grazel.migrate.dependencies.DefaultArtifactPinnerTest"
    --tests "com.grab.grazel.migrate.android.DefaultAndroidTestDataExtractorTest"`.
  - Result: passed in 35s.
- Hygiene:
  - Grazel `git diff --check` passed.

## Next

- Run the broader focused local test set.
- Run `verifyGrazelGoldenBaseline`, task graph, sample bucket labels, and then
  PAX regenerate/build/audit gates from this updated state.

## 2026-06-26 19:49 SGT - Local Focused Gates Passed

- Broader focused test command:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon
  --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest"
  --tests "com.grab.grazel.gradle.dependencies.DependencyBucketPlacementEngineTest"
  --tests "com.grab.grazel.gradle.dependencies.WorkspacePlanBuilderTest"
  --tests "com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependenciesTest"
  --tests "com.grab.grazel.migrate.dependencies.MavenInstallArtifactsCalculatorTest"
  --tests "com.grab.grazel.migrate.dependencies.DefaultArtifactPinnerTest"
  --tests "com.grab.grazel.tasks.internal.TargetMavenRepoReferencesCollectorTest"
  --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest"
  --tests "com.grab.grazel.migrate.target.TargetVariantReachabilityTest"
  --tests "com.grab.grazel.migrate.android.DefaultAndroidTestDataExtractorTest"`.
- Result: passed in 32s.
- Lightweight gates:
  - `reports/scripts/verify-default-task-graph.sh`: passed.
  - `reports/scripts/verify-sample-bucket-labels.sh`: passed.
  - Grazel `git diff --check`: passed.
- Golden guardrail:
  - `./gradlew verifyGrazelGoldenBaseline --console=plain --no-daemon`
    passed in 41s.
  - Generated-file diff stayed clean.
- Resource notes:
  - Before these gates, disk had about 54 GiB free and memory had about
    14 GiB free.
  - No high-memory `python3.12` process was present.

## Next

- Run full plugin unit tests from this state.
- Then run PAX `migrateToBazel`, bounded audit, and APK/android-test APK build
  gates.

## 2026-06-26 19:52 SGT - Full Plugin Unit Tests Passed

- Command:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`.
- Result: passed in 36s.
- Notes:
  - Existing Gradle configuration-time resolution warnings appeared in legacy
    tests.
  - Existing Bazel pinning fixture output appeared in
    `DefaultArtifactPinnerTest`.

## Next

- Run PAX regenerate/build/audit gates from this updated code state.

## 2026-06-26 20:03 SGT - PAX Regenerate And APK Gates Passed

- Resource precheck:
  - Disk had about 63 GiB free under the PAX workspace.
  - File descriptor limit was `1048575`.
  - No high-memory `python3.12` process was present.
- PAX migrate:
  - Command:
    `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`.
  - Result: passed in 10m19s.
  - Notes:
    - `resolveWorkspaceDependencies`, `computeWorkspaceDependencies`,
      `collectWorkspaceTargetTagPlan`, `computeWorkspacePlan`,
      `collectTargetMavenRepoReferences`, and `finalizeWorkspacePlan` all ran.
    - Pinning reported all checked repos up to date.
    - `collectTargetMavenRepoReferences` emitted many fallback-compression
      warnings. It converged and completed, but the warning volume is a
      cleanup item.
- PAX bounded audit:
  - Command: `reports/scripts/audit-pax-bounded-baseline.sh`.
  - Result: passed.
  - Key output:
    - `app-gps-pax-debug`: 1452 deps, 0 tags, 6 `@debug_maven` deps.
    - `app-gps-pax-debug-android-test`: 1511 deps, 1957 tags,
      10 `@android_test_maven` deps.
    - No bucket-prefixed Maven labels in tags.
    - `bug-report-kit-implementation` active BUILD output absent.
    - WORKSPACE: 5156 lines, 24 `maven_install` entries.
- PAX generated diff hygiene:
  - Command: `git diff --check`.
  - Result: passed.
- PAX Bazel APK build:
  - Command:
    `./bazel.sh build --jobs=4 --disk_cache= --verbose_failures
    //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`.
  - Result: passed in 227.485s.
  - Notes:
    - Bazel reported duplicate-artifact debug warnings during repository setup;
      these did not fail analysis/build.

## Next

- Run broad Grazel gates from this exact state:
  - `./gradlew check --console=plain --no-daemon`.
  - `bazelisk build //...`.
  - `bazelisk test //...`.
- Re-run final diff checks after docs/logs are updated.

## 2026-06-26 20:38 SGT - Functional Exclude Follow-Ups

- Broad Gradle gate attempted:
  - Command: `./gradlew check --console=plain --no-daemon`.
  - Result: failed on pre-existing sample lint only:
    `sample-android/src/main/res/layout/activity_main.xml:73 MissingConstraints`
    and `sample-android/src/free/res/values-id/strings.xml:18 ExtraTranslation`.
  - Confirmed those sample files were not modified by this branch.
- Plugin functional gate then exposed two real regressions in
  `BuildVariantTest.migrateToBazelWithFlavorsWereUsed`.
- Regression 1:
  - Symptom: default `com.google.j2objc:j2objc-annotations` kept version `1.1`
    but lost the main declaration exclude in `dependencies.json`/WORKSPACE.
  - Root cause: app/default declared metadata was collected, but default deps
    inferred or globally merged from resolved roots could bypass declaration
    metadata before rendering.
  - Fix: enrich already-planned per-project and global main bucket outputs with
    matching declared metadata by short id. This does not create new buckets or
    declaration-only artifacts.
- Regression 2:
  - Symptom: `@android_test_maven` `org.hamcrest:hamcrest-library:1.3` lost
    the androidTest-only exclude while `resolveWorkspaceDependencies` still had
    it.
  - Root cause: `ComputeWorkspaceDependencies` flattened transitive deps, then
    merged a direct androidTest artifact with a transitive copy of the same
    artifact. The generic reducer intersected one-sided excludes to empty.
  - Fix: use a flatten-stage reducer that preserves excludes from the direct
    copy when the duplicate is direct-vs-transitive, while keeping existing
    direct-vs-direct exclude intersection behavior.
- Added regression tests:
  - `AggregatedDependencyResolverTest.default bucket keeps app declared exclude
    when dependency is inferred from leaves`.
  - `AggregatedDependencyResolverTest.android test global bucket keeps declared
    exclude from contributing project`.
  - `ComputeWorkspaceDependenciesTest.flattened classpath keeps direct excludes
    when same artifact also appears transitively`.
- Verification:
  - Focused resolver/compute unit checks passed.
  - `./gradlew :grazel-gradle-plugin:functionalTest --console=plain
    --no-daemon --tests
    "com.grab.grazel.migrate.BuildVariantTest.migrateToBazelWithFlavorsWereUsed"`
    passed in 1m22s.

## Next

- Run `./gradlew :grazel-gradle-plugin:check --console=plain --no-daemon`.
- Then continue local Bazel `build //...` and `test //...` gates, with resource
  checks first.

## 2026-06-26 20:47 SGT - Plugin Check Passed

- Resource precheck before plugin check:
  - Disk remained about 62 GiB free.
  - No high-memory `python3.12` process was present.
  - Existing Bazel worker JVMs were idle, so no cleanup was run.
- Command:
  `./gradlew :grazel-gradle-plugin:check --console=plain --no-daemon`.
- Result: passed in 3m36s.
- Notes:
  - Existing configuration-time resolution warnings appeared in legacy unit
    tests.
  - `DefaultArtifactPinnerTest` intentionally exercised the stale pin signature
    path before repinning a temp fixture.
  - Functional `BuildVariantTest.migrateToBazelWithFlavorsWereUsed` passed
    inside the full plugin check after the resolver/compute follow-up fixes.

## Next

- Run local Bazel `bazelisk build //...` and `bazelisk test //...` gates after
  resource checks.
- Re-run final diff checks and update the top-level current-status summary.

## 2026-06-26 20:48 SGT - Local Bazel Gate Blocked By Sample Crashlytics Manifest

- Resource precheck:
  - About 60-61 GiB free on repo and `/private/var/tmp`.
  - No high-memory `python3.12` process was present.
- Command:
  `bazelisk build //... --show_progress_rate_limit=30`.
- Result: failed during AAPT resource linking for
  `//sample-android:sample-android-demo-free-debug-android-test_lib_base`.
- Symptom:
  - AAPT could not open
    `bazel-out/arm64-v8a-fastbuild-android-ST-0b2165ce48ec/bin/sample-android/crashlytics-demo-free-debug_symlinked_manifest/AndroidManifest.xml`.
  - The symlink pointed to
    `_crashlytics-demo-free-debug_crashlytics/CrashlyticsManifest.xml`, but the
    Android-config output file was absent.
- Triage:
  - `sample-android/BUILD.bazel` has no working-tree diff.
  - The branch-vs-master diff for the crashlytics rule body is unchanged; the
    generated diff around `sample-android` is Maven repo label movement only.
  - `bazelisk clean` and rerun reproduced the same failure.
  - Host-config `CrashlyticsManifest.xml` exists; Android-config
    `CrashlyticsManifest.xml` is still missing.
  - `bazelisk aquery` shows the generated manifest is declared for both host
    and Android configs, but direct cquery/build of the generated file does not
    materialize the Android-config output before the resource link.
- Current classification:
  - Local `bazelisk build //...` is blocked by a sample crashlytics generated
    manifest ordering/configuration issue, not by the dependency refactor logic
    under test.
  - Do not claim the root Bazel gate passed. Use focused Gradle/plugin checks
    and PAX Bazel gates as the stronger dependency-refactor signal unless this
    sample Bazel issue is explicitly fixed or waived.

## Next

- Continue with diff checks and PAX verification gates for the dependency
  refactor.
- If root Bazel green is required before merge, isolate/fix the sample
  crashlytics manifest dependency separately; it is not currently tied to the
  dependency bucket/exclude changes.

## 2026-06-26 21:08 SGT - PAX migrateToBazel Passed After Follow-Up Fixes

- Resource checks during the run:
  - Disk stayed above 58 GiB free.
  - No high-memory `python3.12` process was present.
  - Active Gradle/Bazel/Coursier JVMs were expected for the migration/pinning
    phase; no cleanup was run.
- Command:
  `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace` in
  `/Users/arun.sampathkumar/work/pax-android`.
- Result: passed in 18m52s.
- Important observations:
  - `resolveWorkspaceDependencies`, `computeWorkspaceDependencies`,
    `collectWorkspaceTargetTagPlan`, and `computeWorkspacePlan` all completed.
  - `bug-report-kit-implementation` generated/format tasks were `NO-SOURCE`,
    matching the strict reachable-root decision for modules outside app/test
    roots.
  - `pinMavenArtifacts` repinned all expected repos and used forced versions
    through Coursier args, including `@android_test_maven`, `@test_maven`,
    `@maven`, variant repos, `@ksp_maven`, and `@lint_maven`.
  - The pinning phase remains expensive/noisy, especially for android/test
    repos. This is a pending optimization item, not a correctness failure.
- Next verification:
  - Run PAX `git diff --check`.
  - Run PAX Bazel APK build gate for `//app:app-gps-pax-debug.apk` and
    `//app:app-gps-pax-debug-android-test.apk`.
  - Inspect generated diff shape/pinfile size after the Bazel gate.

## 2026-06-26 21:12 SGT - PAX Generated Diff Check Passed

- Command:
  `git diff --check` in `/Users/arun.sampathkumar/work/pax-android`.
- Result: passed with no output.
- Scope:
  - This only checks generated-file whitespace/conflict markers.
  - It does not prove Bazel build correctness; the APK build gate remains next.

## 2026-06-26 22:01 SGT - PAX APK And Android-Test APK Gate Passed

- Resource checks before and during the build:
  - Disk stayed usable; the latest post-build check showed about 31 GiB free.
  - No high-memory `python3.12` process was present.
  - Bazel workers were active and expected during the build, so no active Bazel
    process was killed.
- Command:
  `./bazel.sh build --jobs=4 --disk_cache= --verbose_failures
  //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk` in
  `/Users/arun.sampathkumar/work/pax-android`.
- Result: passed after wrapper retry.
  - First attempt hit a transient remote-cache missing-blob error for
    `androidx_test_espresso_espresso_idling_resource_symbols/symbols.zip`.
  - The PAX wrapper detected the transient remote-cache error and retried.
  - Retry completed successfully in 3020.485s with 50,556 total actions.
- Correctness signal:
  - The previous `snp-ui-tests` missing-class failure did not recur.
  - App and android-test compile/package paths completed, including the
    android-test KAPT/Kotlin compile and final APK signing.
  - No dependency-reachability, missing Maven repo, or bucket-prefixed tag
    failure appeared.

## Next

- Run generated diff shape/pinfile size checks against the accepted current
  baseline.
- Run or explicitly classify the remaining PAX unit-test Bazel gate.
- Re-run final Grazel/PAX diff hygiene checks after any additional edits.

## 2026-06-26 22:10 SGT - PAX Generated Shape And Unit-Test Gate

- Bounded generated-shape audit:
  - Command: `reports/scripts/audit-pax-bounded-baseline.sh`.
  - Result: passed.
  - `app-gps-pax-debug`: 1452 deps, 0 tags, 6 `@debug_maven` deps.
  - `app-gps-pax-debug-android-test`: 1511 deps, 1957 tags,
    12 `@android_test_maven` deps, no bucket-prefixed Maven tags.
  - `bug-report-kit-implementation` active BUILD output remained absent.
  - WORKSPACE: 5327 lines, 24 `maven_install` entries.
- PAX diff shape:
  - `git diff --numstat`: 2230 files changed, 705176 insertions,
    772265 deletions.
  - BUILD files: tracked 2347, regenerated 2307; `git diff --name-status`
    showed 2185 modified, 45 deleted, 0 added.
  - Pinfile line counts versus PAX `HEAD`:
    - WORKSPACE: 3998 -> 5327.
    - `maven_install.json`: 17455 -> 14558.
    - `debug_maven_install.json`: 3599 -> 4387.
    - `android_test_maven_install.json`: 2788 -> 10493.
    - `test_maven_install.json`: 6128 -> 13088.
    - `lint_maven_install.json`: 244 -> 3510.
- Interpretation:
  - Correctness gates are green, and strict reachability is reducing active
    BUILD output instead of adding packages.
  - Pinfile/workspace bloat remains concentrated in typed test/lint repos and
    is still a follow-up architecture/performance item.
- PAX focused unit-test gate:
  - Discovery:
    - `./bazel.sh query 'kind(".*_test rule", //app:*)'` failed because the
      wrapper stripped query quoting before Bazel.
    - `bazelisk query 'kind(".*_test rule", //app:*)'` succeeded and showed
      app lint tests only.
    - A broad GPS PAX debug query confirmed many `*-gps-pax-debug-test`
      targets; do not repeat that broad query in main context because output is
      very large.
  - Command:
    `./bazel.sh test --jobs=4 --disk_cache= --test_output=errors
    //app-utils:app-utils-gps-pax-debug-test
    //app-test:app-test-gps-pax-debug-test
    //application-initializer:application-initializer-gps-pax-debug-test`.
  - Result: passed in 49.994s; 3/3 tests passed.
- Resource notes:
  - Disk was about 31 GiB free before the unit-test gate and about 30 GiB free
    during/after it.
  - Main large stores: private Bazel output root about 53 GiB, PAX
    `bazel-cache` about 17 GiB, Gradle caches about 5.1 GiB.
  - No high-memory `python3.12` process was present.

## Next

- Run final Grazel diff hygiene:
  - `git diff --check`.
  - `git diff --check master...HEAD`.
- Keep local Bazel root build blocker classified as the sample crashlytics
  generated manifest issue unless separately fixed.

## 2026-06-26 22:18 SGT - Final Diff Hygiene And Local Bazel Test Classification

- Grazel diff hygiene:
  - `git diff --check`: passed.
  - `git diff --check master...HEAD`: passed.
- Local Bazel test attempt:
  - Command: `bazelisk test //... --show_progress_rate_limit=30`.
  - Result: failed; 9 tests passed, one target failed to build, and four
    sample lint tests failed locally.
  - Failure 1: `//sample-android:sample-android-demo-paid-debug.lint_test`
    failed to build due the already-observed crashlytics generated manifest
    output missing at Android configuration:
    `sample-android/crashlytics-demo-free-debug_symlinked_manifest/AndroidManifest.xml`.
  - Failure 2: `flavors/sample-android-flavor` lint tests failed on duplicate
    generated `res_values` resource name `generated_value` across generated
    variant resource files.
- Classification:
  - The sample BUILD files are clean in the worktree but differ from master as
    generated branch output.
  - The branch-vs-master sample diffs inspected here are dependency-label
    movement (`@debug_maven` -> `@maven` / `@android_test_maven`) and do not
    introduce the duplicate generated `res_values` lint data.
  - Local root Bazel is not a green gate. Treat it as a remaining local-sample
    cleanup/blocker if root Bazel green is required before merge.
  - PAX remains the stronger dependency-refactor correctness signal for this
    slice: migrate, APK, android-test APK, bounded generated-shape audit, and
    focused unit tests are green from the latest generated state.
- Cleanup:
  - Disk fell to about 24 GiB free after PAX/local Bazel verification.
  - Ran `bazelisk clean --expunge` in Grazel and PAX; PAX `bazel-cache` and
    Gradle caches were preserved.
  - Free disk recovered to about 60 GiB.
  - Ran `bazelisk shutdown` in both Grazel and PAX after cleanup.
