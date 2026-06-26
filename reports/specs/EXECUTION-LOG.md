# Dependency Refactor Execution Log

This is the short continuity pointer for the dependency-refactor goal. Keep detailed
evidence in item-specific logs so context compaction can recover state quickly.

## Active State

- Active item: Item 7 - Pin-size reduction via bucket ownership.
- Item 7/8 follow-up start commit: `9730083`
  (`Document Maven pin-size optimization constraints`).
- 2026-06-27 00:03 +08 clean-start check:
  - Grazel `git status --short` was clean.
  - Item 7 and Item 8 spec files were present.
  - Resource precheck before code work: about 68 GiB free on the data volume,
    `~/.gradle/caches` 5.8 GiB, PAX `bazel-cache` 17 GiB, private Bazel output
    root 36 GiB.
  - Idle Bazel server processes existed for Grazel and PAX, but no stale
    high-memory `python3.12`, Gradle, Coursier, or Bazel runaway was observed.
- Final verification checkpoint commit: `db05a6d`
  (`Finalize dependency refactor verification`).
- Current Grazel worktree after checkpoint: clean, branch ahead of origin.
- Item 1 baseline/safety-net checkpoint commit: `368a21f`
  (`Record PAX baseline safety gate`).
- Item 2 structured-planning checkpoint commit: `6393de1`
  (`Add workspace dependency planning seam`).
- Item 3 Step 1 pinner cutover checkpoint commit: `95c1036`
  (`Cut pinner over to workspace plan`).
- Item 3 Step 2 root-generation cutover checkpoint commit: `f5296bd`
  (`Cut root generation over to workspace render plan`).
- Item 3 Step 3 tag-producer cutover checkpoint commit: `8e22c01`
  (`Move target tag planning into workspace plan`).
- Item 4 Step 1 manifest/task-graph decouple checkpoint commit: `e00d404`
  (`Remove generated Maven repo manifests`).
- Item 4 Step 2 pinner regex-discovery deletion checkpoint commit: `a70de87`
  (`Remove pinner workspace repo discovery`).
- Item 4 Step 3 extractor fallback deletion checkpoint commit: `40c6bcd`
  (`Remove extractor Maven tag fallbacks`).
- Item 4 Step 4 parity cleanup checkpoint commit: `97d907c`
  (`Remove workspace plan parity flag`).
- Item 5 Step 5a exclude-intersection checkpoint commit: `afa62bc`
  (`Intersect dependency exclude metadata`).
- Item 5 Step 5b variant-provenance checkpoint commit: `e707bf4`
  (`Select Maven roots per variant provenance`).
- Latest passed local gates:
  - Item 10 full plugin unit tests:
    `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`
    passed in 36s.
  - Item 10 focused dependency/refactor tests passed.
  - `./gradlew verifyGrazelGoldenBaseline --console=plain --no-daemon`
    passed in 41s.
  - Item 8 full plugin unit tests:
    `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`
    passed in 41s.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` passed.
  - Grazel `git diff --check` passed after the latest log/doc update.
- Latest passed PAX gates:
  - Item 10 PAX `./gradlew migrateToBazel --no-daemon --console=plain
    --stacktrace` passed in 10m19s.
  - Item 10 bounded audit passed: no bucket-prefixed Maven tags,
    `bug-report-kit-implementation` active BUILD output absent, WORKSPACE 5327
    lines / 24 `maven_install` entries.
  - Item 10 PAX `./bazel.sh build --jobs=4 --disk_cache=
    --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed after an automatic
    transient remote-cache retry; the successful retry took 3020.485s.
  - Item 10 PAX `git diff --check` passed.
  - Item 10 PAX focused unit-test gate passed:
    `//app-utils:app-utils-gps-pax-debug-test`,
    `//app-test:app-test-gps-pax-debug-test`, and
    `//application-initializer:application-initializer-gps-pax-debug-test`.
  - Item 10 Grazel `git diff --check` and
    `git diff --check master...HEAD` passed.
  - Item 11 fresh broad `./gradlew check --console=plain --no-daemon`
    failed on unchanged sample-app lint:
    `sample-android/src/main/res/layout/activity_main.xml:73 MissingConstraints`.
  - Final post-checkpoint Grazel `git diff --check` and
    `git diff --check master...HEAD` passed.
  - PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`
    passed after strict reachability and collector fixes.
  - PAX `./bazel.sh build //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed after those fixes.
  - PAX focused unit-test gate passed for `app-utils`, `app-test`, and
    `application-initializer` GPS PAX debug test targets.
  - PAX `git diff --check` passed.
  - PAX working tree remains dirty from generated output; do not commit PAX
    changes.
  - PAX generated diff shape at final checkpoint: 2230 generated files changed,
    705176 insertions, 772265 deletions.
- Current detailed logs:
  - `reports/specs/execution-log/item6-simplify-review-verification.md`
  - `reports/specs/execution-log/item7-pax-bazel-package-reachability.md`
  - `reports/specs/execution-log/item8-pax-generated-shape.md`
  - `reports/specs/execution-log/item9-maven-pinfile-bloat.md`
  - `reports/specs/execution-log/item10-adversarial-followups.md`
  - `reports/specs/execution-log/item11-final-verification-waivers.md`

## Item 7 Follow-Up Notes

- 2026-06-27 00:20 +08 - Focused Item 7 TDD slice:
  - Red test added for androidTest declared dependency inheritance:
    `explicit app android test declaration is inherited from main when main
    owns same resolved dependency` failed before the fix because declared
    androidTest metadata bypassed main-coverage filtering.
  - Fix: `plannedTestBuckets` now applies the main-coverage predicate to
    declared and non-declared test dependencies. Declared test metadata is
    considered inherited only when a direct main dependency has the same
    short id and version; a different resolved version remains a test delta.
  - Red test added for materialization ownership:
    `render plan does not materialize variant repo without direct owned roots`
    failed before the fix because `pinInputs.isNotEmpty` materialized
    override/transitive-only candidate repos.
  - Fix: `WorkspaceRenderPlanBuilder` now materializes variant repos only
    when they have a direct non-override root; aggregated repos remain
    materialized when present.
  - Added `BucketHierarchyGraph.commonAncestorsOf` and
    `closestCommonAncestorsOf` with tests for unambiguous and ambiguous
    multi-parent DAGs. This records the set-valued graph contract needed for
    further Item 7 bucket ownership work.
  - Passed focused command:
    `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon
    --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest"
    --tests "com.grab.grazel.gradle.dependencies.DependencyBucketPlacementEngineTest"
    --tests "com.grab.grazel.gradle.variant.BucketHierarchyGraphTest"
    --tests "com.grab.grazel.gradle.dependencies.WorkspacePlanBuilderTest"
    --tests "com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependenciesTest"
    --tests "com.grab.grazel.migrate.dependencies.MavenInstallArtifactsCalculatorTest"`.
- 2026-06-27 00:45 +08 - Local Grazel sample migrate resumed after
  compaction:
  - The prior `./gradlew migrateToBazel --console=plain` process no longer
    existed after compaction, but generated files were present and dirty.
  - Resource check before further work: data volume had about 69 GiB free;
    `~/.gradle/caches` 5.8 GiB, PAX `bazel-cache` 17 GiB, and private Bazel
    root 36 GiB. No cleanup was justified.
  - `git diff --check` passed.
  - Generated sample diff is small: `WORKSPACE` adds one
    `androidx.appcompat:appcompat` exclusion to
    `androidx.constraintlayout:constraintlayout`; `maven_install.json` hash and
    dependency edge update accordingly.
  - Workspace render plan invariant check passed: no materialized variant repo
    lacks a direct non-override root.
  - Next checks: run local task graph/sample bucket scripts and then PAX migrate
    plus bounded audit/build/test gates before deciding whether Item 7 is green.
- 2026-06-27 01:05 +08 - Local script failure triage:
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` failed because regenerated
    `WORKSPACE` unioned the one-sided
    `androidx.appcompat:appcompat` exclude onto
    `androidx.constraintlayout:constraintlayout`.
  - A temporary comparison worktree at `/tmp/grazel-compare-973` was created at
    clean commit `9730083`; `./gradlew migrateToBazel --console=plain
    --no-daemon` succeeded there, then the same sample-bucket script failed with
    the same regenerated `WORKSPACE` / `maven_install.json` diff.
  - Root-cause status: this exclude drift predates the current Item 7 edits.
    It is not caused by the new test inheritance or materialized-root changes.
  - Decision: document this as a pre-existing local generated-output waiver for
    the Item 7 pass instead of patching the flatten stage. The required
    distinction is owner/path-level provenance (`core` should remain, the
    one-sided appcompat exclude should not), but `ComputeWorkspaceDependencies`
    has already collapsed project/path ownership. A flatten-only patch would be
    a shortcut and risks regressing the Item 10 hamcrest/test exclude fix.
  - Follow-up: this should be revisited with the provenance work, not hidden in
    renderer or flatten-stage special cases.
- 2026-06-27 01:20 +08 - Pre-PAX Item 7 resource/diff baseline:
  - PAX worktree was already dirty from the accepted generated baseline:
    2230 files changed, 705176 insertions, 772265 deletions. Do not commit PAX.
  - Current PAX generated size baseline before this pass:
    `WORKSPACE` 5327 lines; `android_test_maven_install.json` 10493 lines;
    `test_maven_install.json` 13088 lines; all `*_maven_install.json` total
    45622 lines.
  - Storage: about 68 GiB free; `~/.gradle/caches` 5.8 GiB, PAX
    `bazel-cache` 17 GiB, private Bazel root 36 GiB. No cleanup justified.
  - Shut down the temporary comparison worktree Bazel server.
  - Next command: PAX `./gradlew migrateToBazel --no-daemon --console=plain
    --stacktrace` with wrapper defaults.
- 2026-06-27 00:35 +08 - Resumed after context loss/restart:
  - Active objective file re-read; current goal remains Item 7 first, then Item
    8 only after Item 7 is green.
  - Grazel HEAD is `9730083`; worktree is dirty with the Item 7 test
    inheritance/materialized-root/common-ancestor changes, regenerated sample
    `WORKSPACE`/`maven_install.json`, and this execution log.
  - No active PAX Gradle migrate process was found. PAX generated files still
    match the previously recorded dirty baseline: `WORKSPACE` 5327 lines and
    all `*_maven_install.json` files 45622 total lines.
  - Resource check: data volume has about 68 GiB free; `~/.gradle/caches` is
    6.1 GiB, PAX `bazel-cache` 17 GiB, private Bazel root 36 GiB. No cleanup is
    justified. Keep caches; do not pass aggressive `--jobs` or disable disk
    cache with `--disk_cache=`.
  - Next command is a fresh PAX `./gradlew migrateToBazel --no-daemon
    --console=plain --stacktrace` using wrapper defaults.
- 2026-06-27 00:45 +08 - PAX migrate verification gap:
  - PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`
    passed in 8m35s, but key planning/render tasks reported up-to-date:
    `computeWorkspaceDependencies`, `computeWorkspacePlan`, and
    `finalizeWorkspacePlan`.
  - PAX generated size and diff shape remained unchanged from the accepted
    baseline: 2230 changed files, `WORKSPACE` 5327 lines, all
    `*_maven_install.json` files 45622 total lines. `git diff --check` passed.
  - `reports/scripts/audit-pax-bounded-baseline.sh` passed:
    android-test tags contain no bucket-prefixed Maven labels, and
    `bug-report-kit-implementation` active BUILD output is absent.
  - Verification caveat: because workspace planning/render tasks were
    up-to-date, this does not prove the Item 7 render-plan materialization code
    was exercised on PAX. Root cause is Gradle's task up-to-date behavior in
    the composite setup; do not claim Item 7 PAX verification from this run.
  - Next command: run PAX `./gradlew migrateToBazel --no-daemon
    --console=plain --stacktrace --rerun-tasks` to force the relevant planner,
    renderer, generators, and pin checks to execute under current Grazel code.
- 2026-06-27 01:05 +08 - Forced PAX migrate result:
  - PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
    --rerun-tasks` passed in 10m17s with all 4590 tasks executed.
  - This run confirmed the relevant Item 7 paths executed:
    `resolveWorkspaceDependencies`, `computeWorkspaceDependencies`,
    `collectWorkspaceTargetTagPlan`, `computeWorkspacePlan`, and
    `finalizeWorkspacePlan` all ran; root `WORKSPACE`/`BUILD.bazel` were
    regenerated.
  - Post-forced generated shape is unchanged from the accepted PAX baseline:
    2230 changed files, `WORKSPACE` 5327 lines, all `*_maven_install.json`
    files 45622 total lines.
  - PAX `git diff --check` passed.
  - `reports/scripts/audit-pax-bounded-baseline.sh` passed:
    `app-gps-pax-debug` deps 1452/tags 0; `app-gps-pax-debug-android-test`
    deps 1511/tags 1957; no bucket-prefixed Maven tags; direct Maven tags
    present when tags are emitted; `bug-report-kit-implementation` active BUILD
    output absent.
  - Decision: current Item 7 slice is PAX correctness-neutral and prevents
    override/transitive-only variant repos from materializing where the plan
    shape exposes them, but it does not reduce current PAX pinfile size. Run the
    required PAX Bazel build/test gates on this verified generated state, then
    continue Item 7 ownership optimization because size reduction remains
    incomplete.
- 2026-06-27 01:09 +08 - PAX APK build gate:
  - PAX `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed.
  - Elapsed time: 554.648s. Bazel reported 54591 total actions with disk and
    remote cache enabled; no `--jobs` override and no `--disk_cache=` override
    were used.
  - This verifies the current generated PAX baseline still builds both the app
    APK and android-test APK after the current Item 7 changes.
  - Next gate: run the selected PAX Bazel unit tests after a fresh
    disk/memory/process check.
- 2026-06-27 01:12 +08 - PAX selected Bazel unit-test gate:
  - Resource check before the run: data volume about 47 GiB free;
    `~/.gradle/caches` 6.1 GiB, PAX `bazel-cache` 22 GiB, private Bazel root
    49 GiB. This is below the cleanup threshold; caches were preserved.
  - PAX `./bazel.sh test --test_output=errors
    //app-utils:app-utils-gps-pax-debug-test
    //app-test:app-test-gps-pax-debug-test
    //application-initializer:application-initializer-gps-pax-debug-test`
    passed.
  - Elapsed time: 48.791s. Bazel reported 11757 total actions and `Executed 3
    out of 3 tests: 3 tests pass.`
  - PAX `git diff --check` passed after the test run.
  - Status: the current Item 7 slice passes the PAX migrate/audit/APK/test
    correctness gates, but it still has no PAX pin-size reduction. Continue
    bucket ownership optimization before calling Item 7 green.
- 2026-06-27 01:30 +08 - Item 7 direct-root reduction root cause and focused fix:
  - Pre-expansion PAX `build/grazel/workspace-dependency-results.json` showed
    broad typed bucket direct roots: `test` 66 direct roots with 51 overlapping
    `default`; `androidTest` 241 direct roots with 228 overlapping `default`.
  - Downstream `ComputeWorkspaceDependencies` reduced those to `test` 46 and
    `androidTest` 44 direct roots, but `mavenInstallRootArtifactsByVariant()`
    then correctly expanded each remaining direct root to its resolved closure,
    producing `test_maven` 486 and `android_test_maven` 509 repo inputs.
  - Root cause: test/androidTest subtraction only removed roots covered by main
    when resolved owner identity matched exactly. In PAX, inherited typed roots
    often had the same shortId/version as main but empty excludes while the main
    root carried declared excludes, so they stayed test-owned and expanded large
    closures.
  - Fix: typed test buckets now inherit a same-version main direct root when
    there is no declared test dependency for that artifact. Explicit test
    declarations with distinct excludes remain test-owned deltas.
  - Added resolver tests for inherited same-version root with different excludes
    and explicit test declaration with distinct excludes.
  - Verification: focused red test failed before the fix; after the fix, the two
    targeted tests passed. The focused dependency/placement suite passed:
    `AggregatedDependencyResolverTest`, `DependencyBucketPlacementEngineTest`,
    `BucketHierarchyGraphTest`, `WorkspacePlanBuilderTest`,
    `ComputeWorkspaceDependenciesTest`, and
    `MavenInstallArtifactsCalculatorTest`.
- 2026-06-27 01:38 +08 - Local Grazel verification after direct-root fix:
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed; pinning
    probe reported artifacts up-to-date.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` still failed on the known
    pre-existing appcompat/constraintlayout exclude-union check documented
    earlier in this Item 7 log; no change was made in that flattening area.
  - `git diff --check` passed.
  - Next: force PAX migrate with current composite Grazel code and measure
    pre-expansion direct roots plus generated pinfile size.
- 2026-06-27 02:05 +08 - PAX direct-root reduction follow-up:
  - Forced PAX migrate reached `collectTargetMavenRepoReferences` and failed
    because `com.component.secure.grabPax:gd-grabPax-staging` declared from
    `:payment:payments-core:testCompileOnly` for bucket `test` was not found
    in lookup buckets `[test_maven, maven]`.
  - Measurement before the failure showed the intended size direction:
    raw `test` direct roots dropped `66 -> 36`; raw `androidTest` direct roots
    dropped `241 -> 232`; expanded `test_maven` roots dropped `486 -> 206`;
    expanded `android_test_maven` roots dropped `509 -> 465`.
  - Root cause: the test-bucket subtraction compared each test bucket against
    all main buckets for the project. That let a non-visible `pax` main bucket
    cover a broad `test` declaration even though target lookup for that
    declaration can only see `test_maven` and `maven`.
  - Fix: test-bucket coverage is now limited to main buckets visible from that
    specific test bucket. `test` can inherit from `default`; typed buckets like
    `debugUnitTest` / leaf unit tests can inherit from their corresponding main
    ancestors. A resolver regression test covers the non-visible main bucket
    case.
  - Verification: targeted resolver tests for inherited test roots, explicit
    test excludes, and non-visible main coverage passed. Next: rerun PAX
    migrate and remeasure generated pin roots.
- 2026-06-27 01:53 +08 - PAX migrate after visibility-aware test coverage:
  - PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
    --rerun-tasks` passed in 13m 46s with the local composite Grazel changes.
  - Pre-expansion `workspace-dependency-results.json` direct-root counts:
    `default` 313, `androidTest` 278, `test` 85, `debug` 37/8, `hms` 11,
    `gps` 9, `lint` 2 direct plus 64 non-direct, and leaf/flavor buckets 1.
  - Post-`ComputeWorkspaceDependencies` direct-root counts:
    `default` 313, `androidTest` 44, `test` 44, `debug` 11, `hms` 10,
    `gps` 8, `lint` 2, and leaf/flavor buckets 1.
  - Expanded `workspace-plan.json` root artifact counts:
    `maven` 667, `android_test_maven` 518, `test_maven` 319,
    `debug_maven` 235, `hms_maven` 122, `gps_maven` 119, `lint_maven` 63,
    leaf/flavor repos 46, and `ksp_maven` 4.
  - Current result is mixed: `test_maven` improved materially from the earlier
    486-root baseline to 319 while preserving PAX migrate correctness, but
    `android_test_maven` is slightly worse than the earlier 509-root baseline.
    Continue by analyzing pre-expansion direct roots before changing code; the
    intended win must come from reducing direct roots, not dropping resolved
    transitive closure or masking Coursier conflicts.
- 2026-06-27 02:03 +08 - Direct-root reduction fixes after pre-expansion audit:
  - Confirmed the relevant lever is pre-expansion bucket output from
    `workspace-dependency-results.json`; `maven_install.artifacts` then expands
    direct roots plus their Gradle-resolved transitive closure.
  - Added failing resolver coverage for AndroidTest inheriting a direct root
    already owned by `test`. Root cause was that the placement plan only exposed
    leaf ancestors and `testBucketExtendsFrom()` erased base-bucket ancestry, so
    base `androidTest` lost its Variant API parent `test`.
  - Fix: `DependencyBucketPlacementPlan` now carries ancestors for all buckets,
    base test buckets preserve `extendsFrom` without self-cycles, and AndroidTest
    subtraction can inherit graph-visible test buckets.
  - Added failing coverage for `testCompileOnly` declared metadata already owned
    by visible main. Root cause was branch ordering in
    `withoutTestDependenciesCoveredBy`: declared metadata with no separate
    declared-test dependency was treated as an inherited unresolved root and
    repository mismatch prevented coverage.
  - Fix: declared metadata is tested as declared metadata first, while still
    requiring same version and compatible excludes.
  - Verification: new focused tests failed before their fixes and now pass. The
    focused dependency/bucket suite passed:
    `AggregatedDependencyResolverTest`, `DependencyBucketPlacementEngineTest`,
    `BucketHierarchyGraphTest`, `WorkspacePlanBuilderTest`,
    `ComputeWorkspaceDependenciesTest`, and
    `MavenInstallArtifactsCalculatorTest`.
  - Next: rerun PAX migrate and remeasure direct roots plus expanded pin inputs.
- 2026-06-27 02:36 +08 - Aggregate main coverage for test direct-root reduction:
  - PAX audit showed `test_maven` no longer overlapped default direct roots, but
    `android_test_maven` still kept direct roots that were already identical
    `@maven` roots from another reachable main project.
  - Decision: because Maven repos are global, test/androidTest bucket planning
    may reuse final aggregate main bucket ownership for identical resolved roots.
    Declared metadata and exclude compatibility remain project-local and must
    still pass the existing declared/root exclude checks.
  - Added failing coverage for unit-test and AndroidTest buckets reusing an
    identical default dependency owned by another project. Updated the old
    project-scoped subtraction test to the new global repo ownership invariant.
  - Fix: `MainBucketPlanResult` now exposes final aggregate main covered deps
    from default, hierarchy, and filtered leaf buckets; test bucket planning
    combines project-local and aggregate main coverage before applying
    test-specific pruning.
  - Verification: the new regression passed, and the focused dependency/bucket
    suite passed. Next: rerun PAX migrate, remeasure pre-expansion direct roots
    and final pin counts, then run PAX Bazel gates if shape is acceptable.
- 2026-06-27 02:45 +08 - PAX verification after reducing test direct roots:
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
    --rerun-tasks` in PAX passed in 11m 34s. The pinning path used normal
    `maven_install.artifacts` constraints; no Coursier force-version shortcut
    was introduced.
  - Pre-transitive direct roots in `workspace-dependency-results.json` are the
    intended optimization lever. After the aggregate-main coverage fix:
    `default` 313, `test` 66, `androidTest` 29, `debug` 37/8, `hms` 11,
    `gps` 9, `lint` 2 direct plus 64 non-direct, leaf/flavor buckets 1.
  - After `ComputeWorkspaceDependencies`, direct roots are: `default` 313,
    `test` 35, `androidTest` 20, `debug` 11, `hms` 10, `gps` 8, `lint` 2,
    leaf/flavor buckets 1.
  - Expanded workspace-plan roots now show the expected transitive reduction:
    `test_maven` 229 roots / 35 direct, `android_test_maven` 448 roots / 20
    direct, `maven` 667 roots / 313 direct. Compared with the prior measured
    state this reduced `test_maven` roots 319 -> 229 and
    `android_test_maven` roots 464 -> 448 without dropping resolved closure.
  - Final pin counts: `test_maven_install.json` 288 artifacts / 7374 lines,
    `android_test_maven_install.json` 467 artifacts / 8496 lines,
    `maven_install.json` 715 artifacts / 14558 lines.
  - PAX gates passed:
    `git diff --check`;
    `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` in 448s;
    `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test
    //app-test:app-test-gps-pax-debug-test
    //application-initializer:application-initializer-gps-pax-debug-test` with
    3/3 tests passing.
  - Resource note: disk stayed around 40-42 GiB free. The Bazel server was
    stopped between the APK build and unit tests because worker heaps left only
    about 1.6 GiB unused memory; memory recovered to about 22 GiB unused. No
    cache directories were deleted.
- 2026-06-27 02:48 +08 - Item 7 local/audit checkpoint:
  - Re-read the active Item 7/8 goal from the Codex attachment and reconciled
    current status. Item 7 is the active item until the green checkpoint is
    committed; Item 8 must wait until after that checkpoint.
  - `reports/scripts/audit-pax-bounded-baseline.sh` passed against the current
    PAX generated output. Updated `reports/specs/PAX-BOUNDED-AUDIT-BASELINE.md`
    to the accepted current shape: app android-test has `@debug_maven deps: 0`,
    `@android_test_maven deps: 10`, no bucket-prefixed Maven tags, and
    `bug-report-kit-implementation` active BUILD output is absent.
  - PAX size comparison to HEAD/master generated baseline:
    materialized pinfiles decreased from 17 to 12 and `maven_install` calls from
    28 to 24. Input artifact roots increased from 1784 to 2094 (+17.4%): the
    growth is concentrated in `android_test_maven`, `test_maven`, and
    `lint_maven`, while `maven` decreased 719 -> 674 and several unit/leaf repos
    disappeared. This is accepted for Item 7 because correctness gates are green
    and the increase stays within the earlier 10-20% discussion band, but it
    remains a documented size debt rather than an ideal end state.
  - Local Grazel gates:
    `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed;
    `./gradlew migrateToBazel --console=plain --no-daemon` passed;
    `reports/scripts/verify-default-task-graph.sh` passed;
    `reports/scripts/verify-sample-bucket-labels.sh` still fails only on the
    known clean-9730083 appcompat/constraintlayout exclude-union waiver.
  - No cache deletion was needed. Disk remained around 40 GiB free before local
    gates.
  - Local checkpoint commit: current HEAD after this checkpoint
    (`Reduce Maven bucket direct roots`).

## Item Logs

- Item 1: `reports/specs/execution-log/item1-baseline.md`
- Item 2: `reports/specs/execution-log/item2-structured-planning.md`
- Item 3: `reports/specs/execution-log/item3-consumer-cutover.md`
- Item 4: `reports/specs/execution-log/item4-remove-feedback-paths.md`
- Item 5: `reports/specs/execution-log/item5-provenance-exclude.md`
- Item 6: `reports/specs/execution-log/item6-simplify-review-verification.md`
- Follow-up strict reachability: `reports/specs/execution-log/item7-pax-bazel-package-reachability.md`
- Follow-up generated shape: `reports/specs/execution-log/item8-pax-generated-shape.md`
- Follow-up pin-file bloat/backout: `reports/specs/execution-log/item9-maven-pinfile-bloat.md`
- Follow-up adversarial fixes: `reports/specs/execution-log/item10-adversarial-followups.md`
- Follow-up final verification waivers:
  `reports/specs/execution-log/item11-final-verification-waivers.md`

## Standing Constraints

- PAX uses local composite include build wiring; no publish step is required.
- Do not commit PAX-side changes.
- Use subagents for bounded read-heavy audits/final reviews, not uncontrolled parallel
  writes.
- Check storage, CPU, and memory before expensive Gradle/Bazel work.
- Do not disable Bazel disk cache with `--disk_cache=` and do not add aggressive
  `--jobs` flags unless diagnosing a specific resource issue; prefer the
  wrappers' default behavior.
- If storage is genuinely low or private Bazel output roots grow very large
  (around 90 GiB or more), prefer `bazelisk shutdown` / `bazelisk clean
  --expunge` in the relevant repo first. Remove stale private Bazel output
  roots only after checking they are not active. In PAX delete `bazel-cache`
  only as a last resort because preserving it keeps verification fast.

## Current Remaining Work

- Item 5 is complete at local commit `e707bf4`; do not push without explicit
  instruction.
- Item 6 is review-ready for the dependency-refactor slice with documented
  local waivers:
  - root `./gradlew check` is blocked by unchanged sample-app lint;
  - root `bazelisk build //...` / `bazelisk test //...` are blocked by
    sample/rule hygiene issues: crashlytics generated manifest output missing
    in Android configuration, plus sample-flavor duplicate generated
    `res_values`;
  - PAX dependency-refactor gates are green.
- Item 7 is the current output-changing follow-up. Correctness is primary; size
  reduction is the target. Do not shrink pinfiles by dropping Gradle-resolved
  closure artifacts or by adding Coursier force-version shortcuts. Improve
  bucket ownership in the existing variant-driven placement layer.
- Item 8 follows only after Item 7 is green: replace target-reference fixpoint
  collection with a reverse-topological single pass after parity verification.
- Keep follow-up execution notes itemized; do not append long essays to this
  file.
