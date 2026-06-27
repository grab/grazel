# Dependency Refactor Execution Log

This is the short continuity pointer for the dependency-refactor goal. Keep detailed
evidence in item-specific logs so context compaction can recover state quickly.

## Active State

- 2026-06-28 +08 current goal pass:
  - Current anchor: `reports/specs/CURRENT-GOAL-ANCHOR.md`.
  - Current item: Item 13 - test/androidTest delta ownership, the only planned
    output-changing slice in this pass.
  - 2026-06-28 +08 resume checkpoint: Grazel worktree clean at
    `f667db5d237b7d8866cc705acd90b1436813c591`
    (`Record bucket ownership planner handoff`), branch
    `arun/dependencies-refactor` ahead of origin.
  - 2026-06-28 +08 Item 12 implementation progress: added pure
    `BucketOwnershipPlanner` / `OwnershipPlannerInput`, routed
    `AggregatedDependencyResolver` through the planner after
    `addDeclaredMetadataClosures()`, removed the old in-session ownership path,
    and passed focused planner/resolver/placement tests. Broader empty-diff,
    size-guard, and PAX verification are still pending.
  - 2026-06-28 +08 Item 12 verification checkpoint: full
    `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed;
    local `./gradlew migrateToBazel --console=plain --no-daemon` passed with no
    generated BUILD/WORKSPACE/json diff; `verify-default-task-graph.sh` passed;
    `verify-sample-bucket-labels.sh` still fails only on the known pre-existing
    appcompat/constraintlayout exclude-union waiver; Grazel and PAX
    `git diff --check` passed; PAX `migrateToBazel` passed in 11m31s; PAX size
    guard passed unchanged at 11 buckets / 11 pinfiles / 2015 roots; PAX debug
    APK + android-test APK build passed in 221.665s; focused PAX Bazel tests
    passed 3/3 in 18.774s.
  - Item 12 checkpoint committed locally at
    `ad9b4cc280c8cf9de63a1cbc1bdbd136f677ae76`
    (`Extract bucket ownership planner`). Grazel worktree was clean after this
    commit; PAX remains dirty with accepted generated baseline and must not be
    committed.
  - Current detailed log:
    `reports/specs/execution-log/item13-test-android-delta-ownership.md`.
  - 2026-06-28 +08 Item 13 PAX verification checkpoint:
    `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
    --rerun-tasks` passed in PAX in 18m; PAX `git diff --check` passed; size
    guard in `item13` mode passed with 11 buckets / 11 pinfiles and total
    artifact roots `2015 -> 1945` (`-70`). Scoped deltas were `test_maven`
    `230 -> 159` (`-71`) and `android_test_maven` `449 -> 450` (`+1`, exactly
    `androidx.compose.ui:ui-test-manifest=946598668`). All PAX BUILD tags were
    scanned for bucket-prefixed Maven labels and none were found. PAX debug APK
    + android-test APK build passed in `438.042s`; focused PAX Bazel tests
    passed 3/3 in `32.438s`; PAX `git diff --check` passed.
  - Item 13 spec/review reconciliation: runtime `-Pgrazel.internal.parity=delta`
    was not added because Item 12 had already removed the pre-Item-13 path.
    Specs now make the frozen Item 10 PAX baseline JSON + generated diff
    classification the parity source when the old path no longer exists. Scoped
    per-repo movement is allowed only when documented as typed ownership and
    guarded totals/scoped aggregate stay flat or shrink.
  - Item 13 review fix: added regression coverage for an androidTest direct root
    whose visible main owner has the same short id/version but does not cover its
    transitive closure; tightened test/androidTest subtraction so it preserves
    that root. Also documented the typed output-bucket behavior for a shared
    `debugUnitTest` bucket and pre-indexed covered dependencies by bucket to
    avoid repeated aggregate scans.
  - Item 10 PAX size guard checkpoint is committed at `9f363e0`
    (`Add PAX Maven size guard`).
  - Item 9 typed reachability checkpoint is committed at `d84f3db`
    (`Add typed dependency reachability graph`).
  - Item 11 checkpoint is committed at `2d159bd`
    (`Fail closed on typed dependency cycles`).
  - Item 11 implementation state: typed reachability SCCs now fail closed in
    the graph layer with typed nodes and diagnostic edge labels. The old
    `CollectTargetMavenRepoReferencesTask` local cyclic-group fixpoint was
    removed. Bucket ownership and Maven materialization were intentionally not
    changed in this item.
  - Item 11 regression coverage added for genuine typed SCC diagnostics, the
    known PAX `deliveries-model-food:test -> food-testkit:main ->
    deliveries-model-food:main` false-cycle shape, and collector rejection of
    synthetic cyclic groups.
  - Fresh local checks passed: focused graph/task tests, full
    `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`,
    `./gradlew migrateToBazel --console=plain --no-daemon`,
    `git diff --check`, `git diff --check master...HEAD`,
    `reports/scripts/verify-default-task-graph.sh`, generated
    BUILD/WORKSPACE/json diff check, and
    `reports/scripts/verify-pax-size-guard.sh --mode preserving`.
  - Known unchanged waiver:
    `reports/scripts/verify-sample-bucket-labels.sh` fails on the pre-existing
    one-sided appcompat/constraintlayout exclude-union case.
  - Fresh PAX checks passed on `/Users/arun.sampathkumar/work/pax-android`
    branch `arun/grazel-refactor` at `05d2b4801530`:
    `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
    --rerun-tasks` passed in `8m34s`;
    `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed in `224.788s`;
    `./bazel.sh test --test_output=errors
    //app-utils:app-utils-gps-pax-debug-test
    //app-test:app-test-gps-pax-debug-test
    //application-initializer:application-initializer-gps-pax-debug-test`
    passed with 3/3 tests in `18.600s`;
    PAX `git diff --check` passed.
  - PAX size guard remains unchanged after the PAX loop: 11 buckets, 11
    pinfiles, 2015 total artifact roots, no per-repo artifact-root deltas.
  - Resource note: current data-volume free space is about `34GiB`; avoid more
    heavy PAX work without the normal disk/memory/process precheck.
  - Next: execute Item 13 from clean commit `ad9b4cc`. Keep main/lint placement
    unchanged; test/androidTest may only move to resolved-identity delta
    ownership with non-increasing PAX size and classified generated diffs.
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
- 2026-06-27 02:55 +08 - Item 8 single-pass prototype:
  - Started from clean Grazel checkpoint `6e9e87b`.
  - Added a focused red regression showing the old fixpoint re-evaluated every
    project multiple times for a consumer-first reference chain. The test now
    passes with the single-pass collector.
  - Implementation: `CollectTargetMavenRepoReferencesTask` orders projects by
    reverse topological order, explicitly including test/androidTest variant
    graph edges, then populates the render plan before each project. The old
    fixpoint remains only behind `-Pgrazel.internal.reachabilityParity=true`.
  - Local verification passed:
    focused new red/green test;
    `WorkspacePlanTasksTest`, `TopologicalSorterTest`, and
    `DefaultDependencyGraphsTest`;
    `./gradlew migrateToBazel -Pgrazel.internal.reachabilityParity=true
    --console=plain --no-daemon`.
  - Sample generated output stayed unchanged after parity migrate. Next: run the
    PAX parity migrate before removing the old fixpoint.
- 2026-06-27 03:12 +08 - Item 8 PAX parity failure / Item 7 seam reminder:
  - PAX parity migrate failed in `:collectTargetMavenRepoReferences` with a real
    project graph cycle when the prototype sorted all variant edges:
    `:deliveries:deliveries-model-food -> :food-testkit ->
    :deliveries:deliveries-model-food`.
  - Root cause hypothesis: all-variant project edges are too broad for the
    target-reference single-pass ordering graph. Build-only sorting is acyclic
    but may miss test/androidTest reference activation; all-variant sorting
    includes test fixtures that legitimately create cycles. Do not remove the
    fixpoint until a cycle-aware, target-reference-appropriate ordering graph is
    proven on PAX.
  - Direct-root reduction remains the accepted Item 7 checkpoint at `6e9e87b`.
    The important seam is `WorkspacePlanBuilder ->
    WorkspaceDependencies.mavenInstallRootArtifactsByVariant()`:
    `variantDeps` direct ownership is selected before `MavenInstallRootArtifacts`
    expands each bucket with the Gradle-resolved closure. Further pin-size wins
    must reduce direct-owned roots in placement, not drop closure artifacts.
  - Latest measured PAX direct-root results from Item 7: after
    `ComputeWorkspaceDependencies`, direct roots are `default` 313, `test` 35,
    `androidTest` 20, `debug` 11, `hms` 10, `gps` 8, `lint` 2, leaf/flavor
    buckets 1. Expanded workspace-plan roots: `test_maven` 229 roots / 35
    direct, `android_test_maven` 448 roots / 20 direct, `maven` 667 roots / 313
    direct. Remaining bloat is therefore upstream bucket ownership/test-lint
    ownership debt, not a Coursier artifact-pruning problem.
  - Fresh JSON measurement from PAX `build/grazel`: `workspace-plan.json`
    currently has 13 candidate repos, 2117 total root artifacts, and 408 direct
    roots. Remaining direct-root overlap with `maven` is not a trivial duplicate:
    `test_maven` overlaps on 2 shortIds and `android_test_maven` overlaps on 11
    shortIds, but the retained bucket direct roots have different exclude
    metadata. Dropping them would change Gradle-resolved Coursier inputs, so this
    is not a safe size shortcut. The largest raw fanout roots are androidTest
    SDKs such as `com.moca:kyc-sdk`, `com.grab.geo.kampung.map:kampungmap-sdk`,
    PaySDK, OVO, and Kakao; future reduction needs a real ownership/exclude
    model improvement, not closure pruning.
- 2026-06-27 03:35 +08 - Item 8 SCC-local reachability direction:
  - Read-only explorer agreed the pure one-visit-per-project design is unsound:
    build-only ordering under-collects test/androidTest/lint references, while
    all-variant ordering includes legitimate PAX cycles. Minimal acceptable
    architecture is a conservative consumer->dependency ordering condensed into
    strongly connected components: process acyclic SCC singletons once,
    consumers-first, and run the old fixpoint only inside cyclic SCCs.
  - Implemented prototype `ProjectReachabilityOrder.consumersFirstGroups(...)`
    plus grouped collection in `CollectTargetMavenRepoReferencesTask`. The task
    still verifies parity against the old global fixpoint behind
    `-Pgrazel.internal.reachabilityParity=true`.
  - Focused tests passed:
    `TopologicalSorterTest.reachability groups return consumers first and
    condense cycles`, `WorkspacePlanTasksTest.collect target references fixes
    cyclic groups locally`, full `WorkspacePlanTasksTest`,
    `TopologicalSorterTest`, and `DefaultDependencyGraphsTest`.
  - Next: run sample parity migrate, then PAX parity migrate. If parity is
    green, remove the old global fixpoint/parity path per Item 8 acceptance; if
    parity fails, diagnose the exact missing reference edge before changing the
    collector further.
- 2026-06-27 03:58 +08 - Item 8 parity verification:
  - Sample parity migrate passed:
    `./gradlew migrateToBazel -Pgrazel.internal.reachabilityParity=true
    --console=plain --no-daemon`. Generated output stayed unchanged.
  - PAX parity migrate passed:
    `./gradlew migrateToBazel -Pgrazel.internal.reachabilityParity=true
    --no-daemon --console=plain --stacktrace` in about 10 minutes.
    The earlier all-variant topo cycle no longer occurs because SCC condensation
    handles cyclic components locally. The old global fixpoint and the
    SCC-local collector produced identical references on PAX.
  - Next action per Item 8: remove the temporary parity property and old global
    fixpoint path, then rerun focused tests, sample migrate, and PAX
    non-parity migrate/build/test gates.
- 2026-06-27 04:24 +08 - Item 8 non-parity generation:
  - Removed the temporary reachability parity property and old global fixpoint
    path. The collector now uses graph-ordered SCC groups: acyclic singleton
    projects run once, while cyclic project groups use a bounded local fixpoint.
  - Focused/surrounding Grazel tests passed after removal:
    `WorkspacePlanTasksTest`, `TopologicalSorterTest`, and
    `DefaultDependencyGraphsTest`.
  - Sample `./gradlew migrateToBazel --console=plain --no-daemon` passed with
    no generated-output drift.
  - PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`
    passed in 9m19s; `git diff --check` in PAX passed.
  - Next gates: run the bounded PAX baseline audit, PAX debug/android-test APK
    build, PAX focused Bazel unit tests, then local Grazel final checks before
    simplifying/reviewing/committing Item 8.
- 2026-06-27 04:33 +08 - Item 8 PAX verification:
  - Resource preflight before PAX build: about 40 GiB free, Gradle caches 8.3G,
    PAX `bazel-cache` 23G, private Bazel output root 51G. No cleanup was
    needed.
  - `reports/scripts/audit-pax-bounded-baseline.sh` passed after PAX migrate:
    audited Maven tags use normalized `@maven//:` labels, direct Maven tag
    audit passed for the android-test target, `bug-report-kit-implementation`
    active BUILD output is absent, WORKSPACE is 4772 lines, and there are 24
    `maven_install` entries.
  - PAX APK gate passed:
    `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` completed successfully in
    221.259s.
  - PAX focused unit gate passed:
    `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test
    //app-test:app-test-gps-pax-debug-test
    //application-initializer:application-initializer-gps-pax-debug-test`.
  - Next gates: Grazel `git diff --check`, task-graph/sample-bucket scripts,
    and broader plugin tests before cleanup/review/commit.
- 2026-06-27 04:39 +08 - Item 8 local verification:
  - Grazel `git diff --check` passed.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails only on the
    previously documented appcompat/constraintlayout one-sided exclude-union
    waiver; no new sample-bucket failure was observed.
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed.
  - Item 8 is green through PAX and local checks. Next: run quality-only
    simplify/review, rerun impacted checks if code changes, then commit locally.
- 2026-06-27 05:05 +08 - Item 8 simplify pass and re-verification:
  - Simplify reviewers found duplicated Kahn topological sorting, duplicated SCC
    sorting, an unnecessary initial empty render-plan mutation, repeated
    `canMigrate` checks inside cyclic SCCs, and a larger target-reference graph
    altitude caveat.
  - Applied behavior-preserving cleanup: extracted shared dependency-first
    ordering, reused sorted SCC members, iterated normalized edges without
    re-sorting, removed the unused initial render-plan mutation, and filtered
    migratable projects once per cyclic SCC.
  - Skipped broader graph-merge rewrite because it changes a shared seam beyond
    this verified slice.
  - Persisted the target-reference graph caveat in
    `reports/specs/KNOWN-LIMITATIONS.md`; current PAX verification is green, but
    a future cleanup can model non-configuration target edges explicitly.
  - Post-cleanup checks passed:
    - focused `TopologicalSorterTest`, `WorkspacePlanTasksTest`, and
      `DefaultDependencyGraphsTest`;
    - root `./gradlew migrateToBazel --console=plain --no-daemon`;
    - PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`
      in 10m02s;
    - PAX `git diff --check`;
    - `reports/scripts/audit-pax-bounded-baseline.sh` with WORKSPACE 4772 lines,
      no bucket-prefixed Maven tags in audited targets, and
      `bug-report-kit-implementation` absent;
    - PAX APK build gate in 221.448s;
    - PAX focused unit-test gate;
    - Grazel `git diff --check`;
    - `reports/scripts/verify-default-task-graph.sh`;
    - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`.
  - `reports/scripts/verify-sample-bucket-labels.sh` still has only the known
    appcompat/constraintlayout one-sided exclude-union waiver.
  - Next: adversarial review of current diff, fix any real findings, then commit
    locally. Do not commit PAX output.

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
- Item 7 is green for the current direct-root optimization slice. Correctness
  remains primary; size reduction came from bucket ownership before transitive
  expansion, not from dropping Gradle-resolved closure artifacts.
- Item 8 is green for the verified PAX slice with the documented target-reference
  ordering limitation in `KNOWN-LIMITATIONS.md`: ordering is Gradle-project-edge
  based plus fallback path order, not a first-class target-reference graph.
- Keep follow-up execution notes itemized; do not append long essays to this
  file.

## 2026-06-27 Direct Root Sizing Audit

- Confirmed the pre-expansion seam: `WorkspaceDependencies.variantDeps` in
  `build/grazel/dependencies.json` is the bucketed direct/root artifact set
  produced by `ComputeWorkspaceDependencies`; `mavenInstallRootArtifactsByVariant`
  expands those roots to the full Gradle-resolved direct-plus-transitive closure
  used by `maven_install.artifacts`.
- Accepted Item 7 baseline before further optimization:
  - materialized direct roots: `androidTest=20`, `debug=11`, `default=313`,
    `gps=8`, `gps*Debug=1` each, `hms=10`, `hmsPaxDebug=1`, `lint=2`,
    `test=35`;
  - materialized expanded roots: `androidTest=448`, `debug=235`,
    `default=667`, `gps=119`, each leaf debug repo `46`, `hms=122`,
    `lint=63`, `test=229`; total expanded roots `2071`.
- Tried a local declaration-owned non-default pruning experiment to reduce
  inferred default direct roots. It reduced `default` from `313/667` to
  `309/659`, but regressed `test` from `35/229` to `39/318` and total
  materialized expanded roots from `2071` to `2148`.
- Decision: reject and revert that experiment. Future direct-root reduction must
  prove it lowers materialized expanded roots, especially `test_maven`, before
  keeping code. Do not shrink by dropping Gradle-resolved transitive closure or
  by adding Coursier conflict-masking options.
- Implemented a narrower direct-root reduction in
  `AggregatedDependencyResolver`: after per-project planning and global bucket
  merge, remove only non-default declared placeholders that are covered by an
  exact default direct root with the same resolved identity/version/jetifier and
  identical exclude rules. This intentionally leaves resolved non-default roots
  and exclude-divergent declarations in place.
- Local verification passed for focused/global placeholder tests plus the
  relevant dependency placement and workspace-plan tests.
- PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
  --rerun-tasks` passed in 12m09s. PAX `git diff --check` passed.
- PAX pre-expansion count deltas versus the accepted Item 7 baseline:
  - `debug_maven`: direct roots `11 -> 4`, expanded root artifacts `235 -> 211`,
    overrides `224 -> 207`;
  - `gps_maven`: direct roots `8 -> 5`, expanded root artifacts `119 -> 112`,
    overrides `111 -> 107`;
  - other materialized repos unchanged.
- PAX final pin JSON deltas versus the accepted Item 7 baseline:
  - `debug_maven_install.json`: artifacts `246 -> 222`, dependencies
    `212 -> 192`, packages `58 -> 56`;
  - `gps_maven_install.json`: artifacts `123 -> 116`, dependencies
    `106 -> 100`, packages unchanged at `27`;
  - `test_maven`, `android_test_maven`, `maven`, `hms_maven`, `lint_maven`,
    leaf debug repos, `pax_maven`, and `ksp_maven` unchanged.
- Remaining duplicate direct roots are mostly typed test/flavor ownership cases,
  plus the intentionally retained `kampungmap-sdk` default/debug duplicate
  because exclude rules differ. Next gate: PAX APK/android-test build after
  resource check.
- PAX Bazel verification passed:
  `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
  //app:app-gps-pax-debug-android-test.apk` completed successfully in
  1911.919s. This verifies the direct-root reduction still preserves the
  Gradle-resolved direct-plus-transitive closure needed for both the debug APK
  and android-test APK.
- Post-build hygiene: Grazel `git diff --check` passed; PAX `git diff --check`
  passed. Disk was tight after the long build (`16GiB` free on the data volume),
  so avoid another large verification run before deciding whether cleanup is
  needed.
- Subagent adversarial review found no resolved-vs-declared, closure-dropping,
  force-version, bucket-label tag, or PAX-hack issue. It repeated the known Item
  8 risk that a target-only relationship absent from Gradle project graphs can be
  discovered too late outside the verified PAX shape. Decision: keep this as a
  documented limitation for the checkpoint rather than reintroducing the global
  fixpoint.
- Fresh focused Grazel verification passed after review/log cleanup:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon --tests
  "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --tests
  "com.grab.grazel.gradle.dependencies.TopologicalSorterTest" --tests
  "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest"`.
- PAX focused Bazel unit-test gate passed after the checkpoint commit:
  `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test
  //app-test:app-test-gps-pax-debug-test
  //application-initializer:application-initializer-gps-pax-debug-test`.
  Result: 3 of 3 tests passed in 269.519s. PAX `git diff --check` passed.
  Disk fell to `10GiB` free afterward, so clean PAX Bazel output root before any
  further heavy run.
- Ran `bazelisk clean --expunge` in PAX after verification; disk recovered to
  `40GiB` free without deleting PAX `bazel-cache`.
- Final cheap checks after the amended checkpoint:
  - `reports/scripts/audit-pax-bounded-baseline.sh` passed. Current PAX shape:
    WORKSPACE `4552` lines, `22` maven_install entries, no bucket-prefixed Maven
    tags in audited targets, and `bug-report-kit-implementation` absent.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - Grazel `git diff --check` and `git diff --check master...HEAD` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails only on the
    known one-sided appcompat/constraintlayout exclude-union waiver.

## 2026-06-28 Altitude Layering Goal Start

- Active goal: finish the altitude-layering dependency refactor to review-ready
  state using `CURRENT-GOAL-ANCHOR.md`, `ALTITUDE-LAYERING-ROADMAP.md`, and
  item specs `10 -> 9 -> 11 -> 12 -> 13 -> 14 -> 15 -> 16`.
- Starting Grazel commit before the new execution slice:
  `de0417fb40ca4bfd1f9345a38ce41ede4ec393c4`.
- Starting worktree contained only the uncommitted spec-planning edits from the
  previous discussion: current-goal anchor, altitude roadmap amendments, Item
  9/10/11/12/13/14/15 spec updates, new Item 16 spec, and the superseded
  altitude input doc. No production-code changes were present at goal start.
- Current active item: Item 10, frozen PAX baseline and automated size guard.
- PAX baseline source for this goal:
  `/Users/arun.sampathkumar/work/pax-android` branch `arun/grazel-refactor`,
  commit `05d2b4801530726ab722133c2ba32cbba9afeb67`. PAX changes must never be
  committed.
- Resource check before Item 10 work: data volume had about `77GiB` free; no
  obvious stale Gradle/Bazel/Coursier or high-RAM `python3.12` process appeared
  in the top memory list. Avoid unnecessary cache deletion.
- `git diff --check` passed for the spec-planning diff before starting Item 10.

## 2026-06-28 Item 10 PAX Size Guard

- Committed the docs-only altitude goal plan locally as `08263b4` before Item
  10 implementation.
- Added `reports/scripts/verify-pax-size-guard.sh` and
  `reports/specs/pax-size-baseline.json`.
- Corrected the baseline identity source before accepting the guard: raw
  `WORKSPACE` artifact strings undercount because PAX uses `maven.artifact(...)`
  and list concatenation such as `DAGGER_ARTIFACTS + [...]`. The guard now
  records active pin JSON `__INPUT_ARTIFACTS_HASH` entries as sorted
  `artifact=hash` strings.
- Subagent independently confirmed PAX branch/SHA
  `arun/grazel-refactor` / `05d2b4801530726ab722133c2ba32cbba9afeb67`, dirty
  generated baseline state, and counts.
- Frozen PAX size baseline:
  - active `maven_install` repos: `11`
  - active pin JSON files: `11`
  - total materialized artifact roots: `2015`
  - per-repo root counts: `android_test_maven=449`, `debug_maven=212`,
    `gps_maven=113`, `gps_moveit_debug_maven=48`,
    `gps_ovo_debug_maven=48`, `hms_maven=123`, `ksp_maven=5`,
    `lint_maven=65`, `maven=674`, `pax_maven=48`, `test_maven=230`.
- Updated `reports/specs/PAX-BOUNDED-AUDIT-BASELINE.md`; corrected its
  `maven_install` count to count active `maven_install(` blocks only, not
  pinned helper calls.
- Verification:
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed.
  - `reports/scripts/verify-pax-size-guard.sh --mode item13` passed.
  - Negative smoke check with a temporary too-small baseline failed as expected.
  - `bash -n reports/scripts/verify-pax-size-guard.sh
    reports/scripts/audit-pax-bounded-baseline.sh` passed.
  - `reports/scripts/audit-pax-bounded-baseline.sh` passed.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed and left no
    generated-output diff.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails only on the
    known one-sided appcompat/constraintlayout exclude-union waiver.
  - Grazel `git diff --check` and `git diff --check master...HEAD` passed.
  - PAX `git diff --check` passed. PAX files remain uncommitted.
- Detailed Item 10 continuation notes are in
  `reports/specs/execution-log/item10-pax-size-guard.md`.

## 2026-06-28 Item 13 Test/androidTest Delta Ownership Checkpoint

- Active item: Item 13, test/androidTest delta ownership. Starting checkpoint
  for this slice was `ad9b4cc280c8cf9de63a1cbc1bdbd136f677ae76`.
- Implementation summary:
  - `DependencyBucketPlacementPlan` now exposes descendant leaf knowledge from
    the bucket hierarchy graph.
  - `BucketOwnershipPlanner` removes a broad test/androidTest dependency only
    when every concrete scoped leaf can see a same-resolved-identity main/test
    owner. Version-divergent scoped deps remain scoped-owned.
  - Inherited parent outputs are normalized back to typed scoped buckets so
    test/androidTest placement does not leak through the untyped parent name.
- Grazel verification before PAX:
  - Focused planner/resolver/placement tests passed.
  - Full `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`
    passed.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed and left no
    generated BUILD/WORKSPACE/json diff.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails only on the
    known one-sided appcompat/constraintlayout exclude-union waiver.
  - Grazel `git diff --check` and `git diff --check master...HEAD` passed.
- PAX verification:
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
    --rerun-tasks` passed in about 18 minutes; PAX `git diff --check` passed.
  - `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed in `438.042s`.
  - `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test
    //app-test:app-test-gps-pax-debug-test
    //application-initializer:application-initializer-gps-pax-debug-test`
    passed in `32.438s`; all 3 tests passed.
- PAX size/diff classification:
  - Before re-baselining, `verify-pax-size-guard.sh --mode item13` passed:
    bucket count `11 -> 11`, pinfile count `11 -> 11`, total artifact roots
    `2015 -> 1945` (`-70`).
  - Scoped repo movement was classified as `test_maven -71` and
    `android_test_maven +1`; the single android-test addition was
    `androidx.compose.ui:ui-test-manifest=946598668`.
  - All PAX `BUILD.bazel` files were scanned for bucket-prefixed Maven labels
    inside `tags = [...]`; none were found.
  - Accepted the reduction and rewrote
    `reports/specs/pax-size-baseline.json` to the new PAX baseline
    `bucketCount=11`, `pinfileCount=11`, `totalArtifactRoots=1945`.
  - Re-ran `verify-pax-size-guard.sh --mode item13` after the rewrite; it
    passed with no deltas.
- PAX generated files remain uncommitted by design. The PAX working tree is
  dirty from `migrateToBazel`; do not reset or commit it without explicit user
  instruction.
- Detailed Item 13 notes are in
  `reports/specs/execution-log/item13-test-android-delta-ownership.md`.

## 2026-06-28 Item 13 Scoped Sibling Closure Follow-Up

- After review tightened test/androidTest inheritance to require root-local
  closure coverage, PAX `migrateToBazel` passed but the Item 13 size guard
  regressed from the accepted baseline: total roots `1945 -> 1959`, all from
  `android_test_maven` `450 -> 464`.
- Root cause: two android-test direct roots,
  `com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk` and
  `com.grab.identity:identity-ui`, have the same main/default resolved root
  identity, but their androidTest root-local closure includes
  `androidx.annotation:annotation-jvm`. That closure is already carried by a
  different scoped androidTest root (`ovo.id.sdk:common`) in the accepted
  baseline, so keeping the two duplicate direct roots was unnecessary bloat.
- Decision: main may cover a scoped test/androidTest root when same resolved
  root identity holds and closure not covered by main is carried by another
  root in the same scoped bucket. The candidate root's own closure is excluded
  from that calculation. This preserves Coursier's full closure while avoiding
  duplicate direct ownership.
- TDD/verification so far:
  - Added red regression test:
    `android test base bucket drops main root when scoped sibling carries extra
    closure`.
  - Implemented sibling-closure accounting in `BucketOwnershipPlanner`.
  - Focused tests passed:
    `android test base bucket drops main root when scoped sibling carries extra
    closure`,
    `android test base bucket keeps direct root when main owner does not cover
    its closure`, and
    `android test base bucket drops inherited root when only override target
    differs`.
- Next: rerun full local Grazel gates, then PAX migrate/size guard and PAX
  build/test gates before accepting Item 13 again.

## 2026-06-28 Item 13 Merged Base Scoped Sibling Recovery

- Full local Grazel verification after the scoped sibling fix passed:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`
  - `./gradlew migrateToBazel --console=plain --no-daemon`
- PAX `migrateToBazel` was re-run with the latest local included-build Grazel
  changes:
  `cd /Users/arun.sampathkumar/work/pax-android && ./gradlew migrateToBazel
  --no-daemon --console=plain --stacktrace --rerun-tasks`.
  Result: passed in `13m 4s`, 4590 tasks executed, all 11 Maven repos pinned.
- `reports/scripts/verify-pax-size-guard.sh --mode item13` passed against the
  accepted Item 13 baseline:
  - bucket count `11 -> 11`
  - pinfile count `11 -> 11`
  - total artifact roots `1945 -> 1945`
  - no per-repo artifact root deltas.
- Grazel `git diff --check` and PAX `git diff --check` both passed.
- Root cause for the remaining `+3` before this fix: final
  `android_test_maven` merges multiple projects into the same base repo, so
  closure coverage for a direct androidTest root can be supplied by another
  project's androidTest root after merging. The earlier sibling-closure fix was
  still too project-local for this base-bucket case.
- Decision: merged base `test`/`androidTest` cleanup may use inherited
  default/test coverage at the final base repo level. Do not broaden this to
  arbitrary leaf buckets yet: `CoveredDependency` is keyed by bucket name, not
  project/leaf provenance, and global leaf cleanup could remove a root needed
  by another project with the same artifact in one leaf.
- Pre-build resource check: about 27 GiB free on the data volume, memory has
  about 20 GiB unused, no high-RAM `python3.12` process observed. The broad
  cache `du` was stopped because it was too slow; no cleanup was performed.
- PAX APK build gate passed:
  `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
  //app:app-gps-pax-debug-android-test.apk`.
  Result: build completed successfully in `224.076s`.
- PAX focused Bazel test gate passed:
  `./bazel.sh test --test_output=errors
  //app-utils:app-utils-gps-pax-debug-test
  //app-test:app-test-gps-pax-debug-test
  //application-initializer:application-initializer-gps-pax-debug-test`.
  Result: 3 of 3 test targets passed in `18.668s` from cache.
- Final hygiene for this checkpoint:
  - Grazel `git diff --check` passed.
  - PAX `git diff --check` passed.
  - `reports/scripts/verify-pax-size-guard.sh --mode item13` passed again with
    no deltas.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails only on the
    known appcompat/constraintlayout one-sided exclude-union waiver.
  - `git diff --check master...HEAD` passed.
- Item 13 local implementation checkpoint commit:
  `ef08b7c4f96942081961258d4b596680677af493`
  (`Implement test Android delta ownership`). PAX generated files remain
  uncommitted by design.

## 2026-06-28 Item 14 Slim CWD Start

- Active item: Item 14 - slim `ComputeWorkspaceDependencies` to a value-holder.
- Start commit: `a625c98c771ed47bbce2bc985bbb4489fd52e5fe`
  (`Record Item 13 checkpoint`), with clean Grazel worktree.
- Spec contract:
  - behavior-preserving only;
  - move default duplicate ownership decisions out of CWD only if output remains
    byte-identical;
  - move final override-target synthesis toward the plan/render layer only with
    parity/empty-diff proof;
  - CWD should retain value/index work: grouping, max-version arbitration,
    flattening, transitive indices, reachable-main indices, and KSP aggregation.
- Immediate approach: use read-only subagents for CWD responsibility and
  verification/parity audits, then implement the smallest behavior-preserving
  seam. If a relocation risks changing `variantTransitiveClasspath` or
  override labels, stop and keep the current behavior until a safer seam is
  identified.
- Subagent audit result:
  - default duplicate collapse can move out of CWD if copied exactly;
  - final flattened default coverage / override-carrier synthesis should not
    move solely to `WorkspaceRenderPlanBuilder` because
    `DependencyResolutionService` still consumes `WorkspaceDependencies.variantDeps`
    and their `overrideTarget`s;
  - safest preserving seam is dedicated helpers called by CWD while keeping
    serialized `WorkspaceDependencies` byte-identical.
- TDD:
  - Added failing seam tests for missing `DefaultBucketDependencyReducer` and
    `DefaultOverrideCarrierPlanner`.
  - Verified red via focused Gradle test compile failure: unresolved references
    for both helper classes.
  - Implemented helpers by moving the existing CWD default-dedup and
    override-carrier predicates/logic unchanged.
  - Focused green tests passed:
    `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon --tests
    "com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependenciesTest"
    --tests "com.grab.grazel.gradle.dependencies.DefaultBucketDependencyReducerTest"
    --tests "com.grab.grazel.gradle.dependencies.DefaultOverrideCarrierPlannerTest"
    --tests "com.grab.grazel.gradle.dependencies.BucketOwnershipPlannerTest"
    --tests "com.grab.grazel.gradle.dependencies.WorkspacePlanBuilderTest"
    --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest"`.
    Result: passed in 8s.
- Local Grazel gates:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`
    passed in 37s.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed in 16s;
    generated outputs remained unchanged.
  - `git diff --check` passed.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `git diff --check master...HEAD` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails only on the
    known appcompat/constraintlayout one-sided exclude-union waiver.
  - `./gradlew verifyGrazelGoldenBaseline --console=plain --no-daemon` also
    fails only because it wraps that same sample-label waiver after a successful
    local generation.
- Next: snapshot current PAX dirty diff hash, run PAX migrate, verify the PAX
  diff hash remains unchanged, then run preserving size guard and PAX build/test
  gates.

## 2026-06-28 Item 14 PAX Preservation Gate

- PAX pre-migrate baseline snapshot on `/Users/arun.sampathkumar/work/pax-android`
  branch `arun/grazel-refactor` at
  `05d2b4801530726ab722133c2ba32cbba9afeb67`:
  - `git diff --binary | shasum -a 256`:
    `5f05c2380375f16b0c04c6fa5f14d3a1666cf94d6b36a5ce1e0814a1b6e43566`
  - `git status --short | shasum -a 256`:
    `b9b38774443602baa0adf251daeb236e68cd181e1f4ccdf74ee412a30822c6d6`
  - dirty entries: `2231`.
- PAX migration passed:
  `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`.
  Result: build successful in 11m 39s; `computeWorkspaceDependencies` executed
  through the extracted helper path, pinning was up-to-date.
- PAX generated state after migration matched the accepted Item 13 baseline:
  - diff hash unchanged:
    `5f05c2380375f16b0c04c6fa5f14d3a1666cf94d6b36a5ce1e0814a1b6e43566`
  - status hash unchanged:
    `b9b38774443602baa0adf251daeb236e68cd181e1f4ccdf74ee412a30822c6d6`
  - dirty entries unchanged: `2231`
  - `git diff --check` passed.
- PAX size guard passed in preserving mode:
  `reports/scripts/verify-pax-size-guard.sh --mode preserving`.
  Counts remained `bucketCount=11`, `pinfileCount=11`,
  `totalArtifactRoots=1945`, with no per-repo deltas.
- PAX APK build gate passed:
  `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
  //app:app-gps-pax-debug-android-test.apk`.
  Result: build completed successfully in 226.264s.
- PAX focused Bazel test gate passed:
  `./bazel.sh test --test_output=errors
  //app-utils:app-utils-gps-pax-debug-test
  //app-test:app-test-gps-pax-debug-test
  //application-initializer:application-initializer-gps-pax-debug-test`.
  Result: 3 of 3 test targets passed in 19.423s.
- Final PAX hygiene:
  - `git diff --check` passed.
  - diff hash/status hash/dirty count still matched the accepted baseline after
    build and tests.
- Parity note: Item 14 did not add a temporary
  `-Pgrazel.internal.parity=cwd` switch. The preserving proof for this slice is
  exact helper extraction with focused seam tests, unchanged local generated
  output, unchanged PAX diff hash, unchanged PAX size guard, and passing PAX
  build/test gates.
- Resource notes:
  - Disk stayed tight but usable at about 24Gi free on
    `/System/Volumes/Data`; no cache deletion was performed.
  - PAX `bazel-cache` stayed about 14G.
  - No high-RAM `python3.12` process was present.
- Item 14 checkpoint commit:
  `29ada0b083c3390de2ed3aa5eacd93fb2d6111fe`
  (`Slim compute workspace dependencies`). Grazel worktree clean after commit.

## 2026-06-28 Item 15 Rendering Purity + Hygiene Start

- Active item: Item 15 - rendering purity and hygiene.
- Start commit: `29ada0b083c3390de2ed3aa5eacd93fb2d6111fe`.
- Spec: `reports/specs/2026-06-27-item15-rendering-purity-hygiene-design.md`.
- Contract:
  - behavior-preserving / golden empty-diff;
  - wire or delete speculative `commonAncestorsOf` helpers;
  - remove proven dead `readText()` and `materializedMavenRepos` fallback/defaults;
  - add direct `WorkspaceRenderPlanBuilder` tests for materialized repos,
    override-target closure, and only-direct-owned roots;
  - document renderer purity without promising removal of in-task model feedback.
- Subagents dispatched:
  - rendering/dead-code audit for helper usage, `readText()`,
    `materializedMavenRepos`, and renderer/pinner generated-file parsing;
  - `WorkspaceRenderPlanBuilder` responsibility/test audit.
- Subagent + spot-check findings:
  - `commonAncestorsOf` / `closestCommonAncestorsOf` had no production callers;
    they were speculative helpers covered only by tests.
  - `CollectTargetMavenRepoReferencesTask` had a discarded
    `compressionResults.get().asFile.readText()`; the file remains a declared
    `@InputFile`, so the read was not needed for task inputs.
  - `MavenInstallArtifactsCalculator` still had a nullable
    `materializedMavenRepos` default plus an old `referencedMavenRepos` fallback;
    production root generation already passes `WorkspaceRenderPlan.materializedRepoNames`
    explicitly.
  - `WorkspaceRenderPlanBuilder` owns materialized repo derivation and override
    target BFS closure; existing coverage was indirect through
    `WorkspacePlanBuilderTest`.
- Implementation:
  - Added direct `WorkspaceRenderPlanBuilderTest` coverage for always/reference/
    aggregated materialization, transitive override-target closure, and
    direct-owned variant repo filtering.
  - Deleted unused `BucketHierarchyGraph.commonAncestorsOf` and
    `closestCommonAncestorsOf` plus their speculative tests.
  - Deleted the discarded `readText()` from
    `CollectTargetMavenRepoReferencesTask`.
  - Removed the calculator's `referencedMavenRepos` fallback and nullable
    `materializedMavenRepos` defaults; `WorkspaceBuilder` and the calculator now
    require an explicit render-plan materialized repo set.
  - Updated tests to pass explicit materialized repos or use a named
    test-local helper that materializes all repos for calculator-focused tests.
- Focused verification:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon --tests
  "com.grab.grazel.gradle.dependencies.WorkspaceRenderPlanBuilderTest" --tests
  "com.grab.grazel.migrate.dependencies.MavenInstallArtifactsCalculatorTest"
  --tests "com.grab.grazel.gradle.variant.BucketHierarchyGraphTest" --tests
  "com.grab.grazel.migrate.AndroidWorkspaceRepositoriesTest" --tests
  "com.grab.grazel.migrate.DaggerWorkspaceRuleTest" --tests
  "com.grab.grazel.migrate.KotlinWorkspaceRulesTest"` passed in 17s.
- Purity note:
  - `ProjectBazelFileBuilder`, `RootBazelFileBuilder`, and `WorkspaceBuilder`
    render from model/extension/task inputs and do not parse generated
    `BUILD.bazel`, `WORKSPACE`, or pin JSON to infer Maven ownership.
  - `ArtificatPinner` necessarily reads/edits generated `WORKSPACE` and lock
    JSON for pin toggling/recovery, but repo ownership comes from
    `WorkspacePlan` + `WorkspaceRenderPlan`, not generated file parsing.
  - Existing target-model feedback through target builders /
    `WorkspacePlanService` remains acknowledged and out of scope for this item.
- Local Grazel verification:
  - Full plugin tests passed:
    `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`
    in 37s.
  - Local migration passed:
    `./gradlew migrateToBazel --console=plain --no-daemon` in 16s; no
    generated files were added to the diff.
  - `git diff --check` passed.
  - `git diff --check master...HEAD` passed.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails only on the
    known appcompat/constraintlayout one-sided exclude-union waiver.
  - `./gradlew verifyGrazelGoldenBaseline --console=plain --no-daemon` fails
    only because it wraps that same sample-label waiver after successful local
    generation.
- PAX preservation loop:
  - Pre-migrate PAX accepted baseline snapshot on
    `/Users/arun.sampathkumar/work/pax-android`, branch `arun/grazel-refactor`
    at `05d2b4801530726ab722133c2ba32cbba9afeb67`:
    - diff hash:
      `5f05c2380375f16b0c04c6fa5f14d3a1666cf94d6b36a5ce1e0814a1b6e43566`
    - status hash:
      `b9b38774443602baa0adf251daeb236e68cd181e1f4ccdf74ee412a30822c6d6`
    - dirty entries: `2231`.
  - PAX migration passed:
    `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
    --rerun-tasks` in 11m 11s; pinning stayed up-to-date.
  - PAX generated state stayed byte-identical:
    - diff hash unchanged:
      `5f05c2380375f16b0c04c6fa5f14d3a1666cf94d6b36a5ce1e0814a1b6e43566`
    - status hash unchanged:
      `b9b38774443602baa0adf251daeb236e68cd181e1f4ccdf74ee412a30822c6d6`
    - dirty entries unchanged: `2231`
    - PAX `git diff --check` passed.
  - PAX size guard passed:
    `reports/scripts/verify-pax-size-guard.sh --mode preserving`; counts stayed
    `bucketCount=11`, `pinfileCount=11`, `totalArtifactRoots=1945`, with no
    per-repo deltas.
  - PAX APK build gate passed:
    `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` in 225.416s.
  - PAX focused test gate passed:
    `./bazel.sh test --test_output=errors
    //app-utils:app-utils-gps-pax-debug-test
    //app-test:app-test-gps-pax-debug-test
    //application-initializer:application-initializer-gps-pax-debug-test`;
    3/3 targets passed in 18.446s.
  - Final PAX diff hash/status hash/dirty count remained unchanged after build
    and tests.
- Resource notes:
  - Disk stayed tight but usable, about 24-26Gi free on
    `/System/Volumes/Data`; no cleanup was performed.
  - PAX `bazel-cache` remained about 14G.
  - No high-RAM `python3.12` process was present.
