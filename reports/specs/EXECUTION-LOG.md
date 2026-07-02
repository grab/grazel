# Dependency Refactor Execution Log

This is the short continuity pointer for the dependency-refactor goal. Keep detailed
evidence in item-specific logs so context compaction can recover state quickly.

## Active State

- 2026-07-02 CURRENT TRUTH - Item39/40/41 goal:
  - Grazel is on `arun/dependencies-refactor` at the current local HEAD
    checkpoint (`refactor: tighten dependency task boundaries`). Do not push.
  - Active goal order from
    `/Users/arun.sampathkumar/.codex/attachments/8cab9421-a14c-4fe9-8b8b-58be07754ff0/pasted-text-1.txt`:
    status/docs checkpoint -> finish and commit proxy package-boundary cleanup
    -> Item39 RJE lockfile reconstructor shape -> Item40 small altitude hygiene
    -> Item41 branch-wide code quality hardening -> full Grazel/PAX final
    gates.
  - PAX regression workspace is `/Users/arun.sampathkumar/work/pax-android`,
    branch `arun/grazel-refactor`, commit `d4105d1f64bd` (`Update bazeline`).
    Current PAX worktree status is the maintainer-requested local proxy hook
    only: `M build.gradle`. Do not commit or push PAX.
  - Step-0 proxy package-boundary/spec checkpoint was committed locally as
    `c4e0cb2` (`docs: add next quality refactor specs`).
  - Item39 was committed locally as `c548db3` after local tests and PAX
    force-repin verification. Detailed evidence:
    `reports/specs/execution-log/item39-rje-lockfile-reconstructor-shape.md`.
  - Item39 PAX force-repin checkpoint: perturbing one
    `android_test_maven_install.json` hash caused `pinMavenArtifacts` to
    repin all 11 repos through the local Maven proxy. The run passed in
    `10m 6s`, served 787 artifacts and 788 POMs from the Gradle index,
    reported 0 artifact misses / 0 request failures, restored lockfiles to
    baseline, and left PAX status at `M build.gradle` only.
  - Item39 local verification passed: focused collaborator tests, full
    `:grazel-gradle-plugin:test`, local `migrateToBazel`, default task graph,
    PAX size guard, and diff checks. `verify-sample-bucket-labels.sh` still has
    only the documented pre-existing appcompat/constraintlayout exclude waiver.
  - Status-anchor checkpoint after `c548db3` was committed locally as
    `ec4c563` (`Update dependency refactor status anchor`), so context
    compaction sees Item40 as active.
  - Step-0 verification rerun on 2026-07-02:
    - `./gradlew :grazel-gradle-plugin:test --tests
      "com.grab.grazel.proxy.LocalMavenProxyServiceTest" --tests
      "com.grab.grazel.proxy.LocalMavenResolvedFactsTest" --tests
      "com.grab.grazel.proxy.MavenInstallLockfileFallbackIndexTest" --tests
      "com.grab.grazel.proxy.LocalMavenProxyServerTest" --tests
      "com.grab.grazel.tasks.internal.PinMavenArtifactsTaskTest"
      --console=plain --no-daemon` passed in `8s`;
    - `git diff --check` and `git diff --check master...HEAD` passed;
    - `./gradlew migrateToBazel --console=plain --no-daemon` passed in `10s`
      and produced no generated-output drift;
    - `reports/scripts/verify-default-task-graph.sh` passed;
    - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed
      with unchanged PAX counts `11/11/1945`.
  - Latest resource check before PAX force-repin: Data volume was 95% used
    with about 24 GiB free. No cache cleanup performed because the run fit;
    avoid expensive concurrent Gradle/Bazel jobs and re-check before the next
    PAX gate.
  - Item40 is complete and committed in the current local HEAD checkpoint
    (`refactor: tighten dependency task boundaries`). It implemented typed
    bucket/test facts, variant-owned KSP processor-root planning, explicit
    `usesService(...)` wiring, structural declared-metadata merge ordering, and
    a local variant-compression build-type lookup cleanup. Focused Item40 tests
    passed twice; the latest run included declared-metadata collector ordering
    coverage and passed in `28s`:
    `./gradlew :grazel-gradle-plugin:test --tests
    "com.grab.grazel.gradle.dependencies.BucketOwnershipPlannerTest" --tests
    "com.grab.grazel.gradle.variant.WorkspaceKspProcessorClasspathPlannerTest"
    --tests "com.grab.grazel.tasks.internal.CollectKspProcessorDependenciesTaskTest"
    --tests "com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadataMergerTest"
    --tests "com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadataCollectorTest"
    --console=plain --no-daemon`.
  - Item40 broader gates also passed: full `:grazel-gradle-plugin:test`
    (`38s`), local `migrateToBazel` (`9s` with no generated diff), default
    task graph, PAX size guard (`11/11/1945`), diff checks, PAX
    `migrateToBazel` (`10m 53s`, only `M build.gradle` in PAX), PAX APK build
    (`248.453s`), and focused PAX Bazel tests (`19.622s`, 3/3 targets passed).
    `verify-sample-bucket-labels.sh` still fails only on the documented
    pre-existing appcompat/constraintlayout exclude waiver.
  - Detailed Item40 evidence is in
    `reports/specs/execution-log/item40-small-altitude-hygiene.md`.
  - Item41 branch-wide code-quality hardening is now active. The inventory
    script was updated to the Item41 column shape and regenerated from scratch:
    `182` Kotlin rows (`main=116`, `test=64`, `functionalTest=2`), all
    intentionally pending for fresh reconciliation. Detailed evidence is in
    `reports/specs/execution-log/item41-branch-wide-code-quality-hardening.md`.
  - Next action: run scoped subagent reviews over the Item41 inventory,
    reconcile findings into fixes/inventory rows, then run simplify/adversarial
    review and the required Grazel/PAX gates.

- 2026-07-01 CURRENT TRUTH - Item34/35 goal:
  - Grazel is on `arun/dependencies-refactor` at local commit `85c6136`
    (`refactor: split workspace tag plan service`). Do not push.
    Item35 source/log changes are verified and ready for a local commit.
  - Current goal order reached final checkpoint: status/docs truth checkpoint,
    Item34 workspace tag plan service shape, Item35 progress reporting,
    simplify/adversarial review, and full Grazel/PAX verification are complete.
  - Items 30, 29, 31, 32, 28, and 33 are completed prerequisite work. Item32
    is true source-project declared-metadata fanout and must not be treated as
    pending implementation unless that task shape regresses.
  - PAX regression workspace is `/Users/arun.sampathkumar/work/pax-android`,
    branch `arun/grazel-refactor`, commit `cfa1057ed58c`, with accepted local
    dirty baseline only: `Constants.kt`, `Grazel.kt`, `ModuleLoggerTask.kt`,
    `generated/dependency_graph.json`, and untracked `Buildifier.kt`. Do not
    commit PAX.
  - Preserving guardrails remain active: generated Grazel output and accepted
    PAX generated baseline must stay unchanged; any generated diff is
    stop-and-investigate unless explicitly classified by an active spec.
  - Console/log output may change only for Item35 progress/summary reporting.
    Generated `BUILD.bazel`, `WORKSPACE`, and pin JSON output must remain
    empty-diff.
  - Item34 source checkpoint committed locally as `85c6136`
    (`refactor: split workspace tag plan service`).
  - Item34 checkpoint so far:
    - Split `WorkspacePlanService` into plan-only service plus
      `WorkspaceRenderPlanService` and `WorkspaceTargetTagPlanService`.
    - Removed `TargetTagPlan` pass-through from `WorkspacePlan` and
      `ComputeWorkspacePlanTask`; target tag lookup now hydrates directly from
      `target-tag-plan.json` in compression analysis, target-reference
      collection, and project BUILD generation.
    - Local focused compile passed:
      `./gradlew :grazel-gradle-plugin:compileKotlin :grazel-gradle-plugin:compileTestKotlin --console=plain --no-daemon`.
    - Focused tests passed after fixing a test-only missing parent directory:
      `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest" --tests "com.grab.grazel.gradle.dependencies.WorkspacePlanBuilderTest" --tests "com.grab.grazel.migrate.android.DefaultAndroidLibraryDataExtractorTest.extract uses target tag plan for maven tag labels" --console=plain --no-daemon`.
    - Local generation passed:
      `./gradlew migrateToBazel --console=plain --no-daemon`.
      `git diff --name-only -- '*.bazel' 'WORKSPACE' 'maven_install.json' 'maven_install_*.json'`
      returned empty, so committed generated Bazel output is unchanged.
      `git diff --check` passed.
  - Item35 checkpoint so far:
    - Added pure-JVM `ProgressReporter`, Gradle `withProgress` adapter, and
      progress/quiet summaries for the seven heavy task paths listed in the
      spec.
    - Simplify/adversarial review fixes applied:
      - KSP progress path streams visit results instead of building an
        intermediate flat list.
      - Target-reference progress derives totals from the concrete group list
        instead of accepting a separate progress-only count.
      - Declared-metadata single-task progress no longer calls Gradle
        `ProgressLogger` from worker coroutines; worker snapshots send results
        through a channel and the task-thread coroutine emits progress.
      - Added a declared-metadata regression test proving progress emits on the
        caller thread while snapshots run in parallel.
      - Removed mutable `WorkspaceTargetTagPlanService.populateTagPlan(...)`;
        the remaining test hydrates through JSON with `initTagPlan(...)`.
      - Declared-metadata summaries are prose-style quiet messages.
    - Full plugin unit tests passed:
      `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`.
    - Local generation passed:
      `./gradlew migrateToBazel --console=plain --no-daemon`.
    - Generated Bazel output diff remained empty; `git diff --check`,
      `verify-default-task-graph.sh`, and
      `verify-pax-size-guard.sh --mode preserving` passed.
    - `verify-sample-bucket-labels.sh` failed only on the documented
      pre-existing one-sided appcompat/constraintlayout exclude assertion.
    - PAX verification passed:
      `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
      passed in the final run in `10m 47s`;
      `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
      passed in `227.480s`;
      `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test`
      passed in `16.418s` with 3/3 test targets passing.
      PAX `git status --short` remained on the accepted dirty baseline only,
      and PAX `git diff --check` passed.
      `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed with
      unchanged counts: bucketCount 11, pinfileCount 11, totalArtifactRoots
      1945.
    - Next action: final local commit. Keep
      `reports/DEPENDENCY-PINNING-MAP.md` untracked unless the maintainer
      explicitly asks to include it.

- 2026-06-29 +08 HISTORICAL - KSP relocatability + Items 32/33 start:
  - Grazel is on `arun/dependencies-refactor` at local commit `c8dcdf4`
    (`Clean up dependency refactor source shape`). Do not push.
  - Current goal order: spec/status checkpoint -> KSP path-sensitivity
    relocatable-cache fix -> Item 32 true source-project declared metadata
    fanout -> Item 33 variant-layer declared config-role relocation ->
    simplify/adversarial review -> full Grazel/PAX verification.
  - Spec-only changes present before code work: Item 32 spec, Item 33 spec,
    and roadmap status/index updates. These match the approved current slice
    and should be locally committed before source edits.
  - PAX regression workspace is `/Users/arun.sampathkumar/work/pax-android`,
    branch `arun/grazel-refactor`, commit `cfa1057ed58c`, with accepted local
    dirty baseline only: `Constants.kt`, `Grazel.kt`, `ModuleLoggerTask.kt`,
    `generated/dependency_graph.json`, and untracked `Buildifier.kt`. Do not
    commit PAX.
  - Current root-task fanout baseline from the last verified Item 28 PAX run:
    full PAX `migrateToBazel --rerun-tasks` passed in `12m 12s`,
    `mode=PROJECT_TASK_FANOUT projects=2327 shards=2327
    aggregateJsonBytes=35247531 elapsedMs=554`. Item 32 must compare true
    source-project fanout against this baseline.
  - Preserving guardrails remain active: generated Grazel output and accepted
    PAX generated baseline must stay unchanged; any generated diff is
    stop-and-investigate unless explicitly classified by an active spec.
  - KSP relocatable-cache checkpoint:
    - Red test first: changed
      `CollectKspProcessorDependenciesTaskTest.ksp processor dependency task
      declares resolved roots and typed artifact inputs` to require
      `PathSensitivity.NONE` for both `kspClasspathFiles` and nested
      `KspArtifactInput.file`; focused test failed at the expected old
      `ABSOLUTE` assertion.
    - Fix: changed both KSP file inputs from `PathSensitivity.ABSOLUTE` to
      `PathSensitivity.NONE`. `shortId` remains the semantic artifact identity
      input; file content remains the file input.
    - Verification: focused red-green test passed, full
      `CollectKspProcessorDependenciesTaskTest` passed, and `git diff --check`
      passed.
  - Historical Item 32 source-project declared metadata fanout checkpoint was in progress
    with evidence in
    `reports/specs/execution-log/item32-true-project-declared-metadata-fanout.md`.
    Current implementation moves shard tasks from root-flat names such as
    `:collectSampleAndroidDeclaredDependencyMetadata` to source-project tasks
    named `:<project>:collectProjectDeclaredDependencyMetadata`; root
    `:mergeDeclaredDependencyMetadata` still consumes the shard output files
    and writes the same aggregate JSON. Local gates passed so far:
    `CollectDeclaredDependencyMetadataTaskTest`, focused `BuildVariantTest`
    fanout/up-to-date/parity tests, full
    `:grazel-gradle-plugin:test`, local `migrateToBazel`,
    `verify-default-task-graph.sh`, `verify-pax-size-guard.sh --mode
    preserving`, and both diff-check commands. `verify-sample-bucket-labels.sh`
    still fails only on the known pre-existing
    appcompat/constraintlayout exclude-union assertion.
  - Item 33 variant-layer declared configuration roles is locally green with
    evidence in
    `reports/specs/execution-log/item33-variant-layer-declared-config-roles.md`.
    Declaration bucket classification, compile-only declaration
    classification, `declarationBucketName()`, and `compileOnlyBucketName`
    moved to `gradle.variant`; the dependencies layer consumes typed role
    accessors. Focused tests, full plugin unit tests, local `migrateToBazel`,
    default task-graph verification, PAX size guard, and diff checks passed.
    PAX `migrateToBazel` passed in `28m` with
    `mode=PROJECT_TASK_FANOUT projects=2327 shards=2327
    aggregateJsonBytes=35247531 elapsedMs=442`; PAX APK build passed in
    `751.827s`; PAX focused Bazel tests passed in `23.001s`; PAX generated
    diff stayed exactly on the accepted dirty baseline.

- 2026-06-28 +08 CURRENT TRUTH - Item 25 PAX migrate checkpoint:
  - Grazel remains on `arun/dependencies-refactor` with Item 25 changes
    uncommitted. Last clean local commit before Item 25 is `fb2b9ab`
    (`Refine workspace plan cleanup`). Do not push.
  - PAX remains the regression workspace at
    `/Users/arun.sampathkumar/work/pax-android`, branch
    `arun/grazel-refactor`, baseline commit
    `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`. Do not commit PAX.
  - Item 25 local Grazel gates passed so far: compileKotlin,
    strengthened `verify-default-task-graph.sh`, local `migrateToBazel`,
    plugin tests, PAX size guard, and both Grazel diff-check commands.
    `verify-sample-bucket-labels.sh` still fails only on the known
    pre-existing appcompat/constraintlayout exclude-union assertion.
  - PAX build-logic needed a local compatibility update because the old hooks
    referenced removed format tasks. Local-only PAX changes retarget the hooks
    to `generateRootBazelScripts`, `generateBazelScripts`, and
    `generateBuildifierScript`, then format patched temp files through a small
    local buildifier helper. This is not committed.
  - First PAX rerun failed because a prior bad generated `WORKSPACE` was
    missing `rules_java_builtin`; `generateBuildifierScript` loads the current
    checked-out `WORKSPACE` before the PAX hook can patch it. Restored the
    baseline block locally and fixed the hook's string match to the formatted
    one-space `git_repository` load line. Subsequent PAX
    `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
    --rerun-tasks` passed in `10m03s`.
  - PAX generated BUILD/WORKSPACE/maven output is stable. The only generated
    drift after migrate is a one-line ordering-only change in
    `generated/dependency_graph.json`; treat it as classified non-semantic PAX
    graph-output drift unless a later gate proves otherwise.
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed after
    PAX migrate: 11 buckets, 11 pinfiles, 1945 total artifact roots, no
    per-repo deltas. PAX `git diff --check` passed.
  - PAX `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed in `215.146s`.
  - PAX `./bazel.sh test --test_output=errors
    //app-utils:app-utils-gps-pax-debug-test
    //app-test:app-test-gps-pax-debug-test
    //application-initializer:application-initializer-gps-pax-debug-test`
    passed 3/3 in `16.593s`.
  - Next gates: final local cleanup/logging/checks and a local Grazel commit if
    green.
- 2026-06-28 +08 CURRENT TRUTH - Item 25 start:
  - Grazel is on `arun/dependencies-refactor` at clean local checkpoint
    `fb2b9ab` (`Refine workspace plan cleanup`). Do not push.
  - PAX is the regression workspace at
    `/Users/arun.sampathkumar/work/pax-android`, branch
    `arun/grazel-refactor`, baseline commit
    `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`. Do not commit PAX.
  - Items 23, 26, 24, and 27 are complete and locally committed. Item 25
    (`reports/specs/2026-06-28-item25-merge-generate-format-tasks-design.md`)
    is active and must run last. Detailed active log:
    `reports/specs/execution-log/item25-merge-generate-format-tasks.md`.
  - Item 25 is preserving/empty-diff. Merge generate+format tasks per scope,
    delete the standalone format task class/registrations, preserve exact final
    BUILD/WORKSPACE output, keep buildifier temp-copy isolation, and document
    the accepted format cacheability tradeoff.
  - Before Item 25 edits, re-read the Item 25 spec and keep this current-truth
    block concise/current after every milestone. Older checkpoints below are
    historical context only; do not execute from them unless explicitly
    cross-checking a claim.
  - Item 27 final verification before this checkpoint: local Grazel focused
    tests, full
    `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`,
    and `./gradlew migrateToBazel --console=plain --no-daemon` passed after
    the final preserving fix. `reports/scripts/verify-default-task-graph.sh`,
    `reports/scripts/verify-pax-size-guard.sh --mode preserving`, and both
    Grazel diff-check commands passed. `reports/scripts/verify-sample-bucket-labels.sh`
    still fails only on the known pre-existing appcompat/constraintlayout
    exclude assertion.
  - PAX preserving drift root cause: Item 27 briefly made Android library and
    instrumentation generation honor every `WorkspaceRenderPlan`
    `referencedProjectTargets` entry. PAX has many existing `testImplementation
    project(...)` references to test-helper Android modules; that widened a
    preserving cleanup into active generation for helper modules outside the
    accepted baseline. The fix keeps target-reference facts, but restores
    Android library/instrumentation generation to bucket reachability only.
    Android app and standalone android-test builders still keep their existing
    referenced-target behavior from before Item 27.
  - PAX rerun after the fix:
    `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
    --rerun-tasks` passed in `11m58s`; PAX working tree stayed clean and
    `git diff --check` passed. Then
    `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed in `251.121s`; focused
    `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test
    //app-test:app-test-gps-pax-debug-test
    //application-initializer:application-initializer-gps-pax-debug-test`
    passed 3/3 in `24.115s`. Do not commit PAX.
- 2026-06-28 +08 historical checkpoints below:
  - The remaining Active State bullets are retained only as evidence for prior
    items. Current execution order is already narrowed to Item 25 from clean
    commit `fb2b9ab`.
- 2026-06-28 +08 Item 24 start:
  - Local Item 26 checkpoint committed at `468dd5f`
    (`refactor: move workspace root inputs into variant layer`).
  - Maintainer constraint restated: keep Grazel changes local and never push;
    keep PAX as the regression baseline and never commit PAX.
  - Active item: Item 24 - branch-diff source shape hygiene.
  - Current detailed log:
    `reports/specs/execution-log/item24-source-shape-hygiene.md`.
  - Deterministic inventory command found 132 changed Kotlin files in
    `master...HEAD`: 85 production, 45 unit-test, and 2 functional-test files.
  - Subagent fanout started for dependency/variant, migrate/render, task-layer,
    and test-scope audits. Parent reconciliation will fix only preserving
    source/test shape issues and defer behavior/model redesign findings.
  - 2026-06-28 +08 Item 24 implementation checkpoint: fixed source-shape
    issues in `BucketOwnershipPlanner`, `DependencyBucketPlacementEngine`,
    encoded declared-edge parsing, KSP workspace classpath creation, current
    behavior comments, `DefaultDependenciesDataSourceTest`, `BuildVariantTest`,
    and `SourcePathTest`.
  - Subagent findings reconciled in
    `reports/specs/execution-log/item24-source-shape-hygiene.md`. Medium-risk
    architecture findings around Maven root artifact planning, target-reference
    reachability, renderer/pinner text contracts, and task annotation
    reflection tests were retained/deferred with rationale rather than silently
    ignored.
  - Focused unit batches passed for bucket ownership, placement, resolver, KSP,
    default dependency data source, and experiments extension tests. Functional
    `BuildVariantTest` + `SourcePathTest` first failed after replacing raw JSON
    string checks because KSP processor data is under `aggregatedRepos`; the
    structural helper was fixed, and the same functional command then passed in
    3m14s.
  - Item 24 final preserving gates passed: full
    `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`, local
    `./gradlew migrateToBazel --console=plain --no-daemon`,
    `reports/scripts/verify-default-task-graph.sh`,
    `reports/scripts/verify-pax-size-guard.sh --mode preserving`, and both
    Grazel diff-check commands. The sample bucket-label check still fails only
    on the known pre-existing appcompat/constraintlayout exclude waiver.
  - PAX baseline verification for Item 24 passed: PAX
    `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
    passed in 12m24s, PAX `git status --short` stayed clean, PAX
    `git diff --check` passed, and no PAX commit was made. Size guard stayed
    unchanged at 11 buckets, 11 pinfiles, 1945 total artifact roots.
  - Resource note: disk pressure was handled before the final PAX run with
    `bazelisk shutdown` and `bazelisk clean --expunge` in Grazel and PAX.
    PAX `bazel-cache` was preserved.
  - Item 24 locally committed
    (`refactor: tidy dependency refactor source shape`). Do not push.
- 2026-06-28 +08 Item 27 start:
  - Active item: Item 27 - branch-wide simplify + adversarial review before
    formatting.
  - Current detailed log:
    `reports/specs/execution-log/item27-branch-wide-simplify-adversarial.md`.
  - Start commit: `6a4c40a`
    (`refactor: tidy dependency refactor source shape`).
  - Maintainer constraints remain hard: keep Grazel changes local and never
    push; keep PAX as the regression baseline and never commit PAX.
  - `simplify-pass` skill was loaded and will run as four branch-diff review
    agents: reuse, simplification, efficiency, and altitude. Adversarial
    correctness review follows after simplify findings are fixed/rejected with
    code evidence.
  - 2026-06-28 +08 Item 27 simplify implementation checkpoint: fixed preserving
    reuse/simplification/altitude findings around JVM reachability duplication,
    render-plan mapping reuse, redundant workspace-plan fields, test-only
    target-reference wrapper, single-implementation `WorkspacePlanService`,
    lazy declared metadata collection, and Maven root artifact planning moving
    to the dependency layer / `WorkspacePlan.repoPlan`. Focused touched-area
    unit suite passed. Adversarial task/dependency/target-reference reviews are
    running before broad verification.
  - 2026-06-28 +08 continuation rule from maintainer: because Item 27
    adversarial review is adding/reshaping Kotlin code after Item 24, rerun an
    Item 24-style changed-file source-shape inventory/reconciliation after
    Item 27 fixes and before Item 25. Do not exit cleanup or claim completion
    until every changed Kotlin file is visited/accounted for and the full goal
    gates remain green.
  - Item 27 adversarial fix checkpoint: fixed KSP output parent creation,
    stale active `BUILD.bazel` disabling for non-migratable projects,
    `PinMavenArtifactsTask` getter annotations, test associate/instrument
    reference facts, render-plan materialization for self-overrides and
    override-target owner repos, and Android library referenced-target
    generation/fact extraction. Focused adversarial-fix suite passed; narrow
    post-fix review is running.
- 2026-06-28 +08 Item 26 checkpoint:
  - Current branch rule reconfirmed by maintainer: keep Grazel changes local and
    never push; keep PAX as the regression baseline and never commit PAX.
  - Active item: Item 26 - variant-owned workspace dependency root inputs.
  - Current detailed log:
    `reports/specs/execution-log/item26-variant-owned-root-inputs.md`.
  - Implementation state: `WorkspaceDependencyInputsRegistrar` now uses
    `VariantBuilder.onVariants` and delegates root-input intent to
    `WorkspaceDependencyRootInputPlanner`; variant-layer helpers own workspace
    main/test/androidTest/lint classpath roles; KSP processor classpath
    configuration construction moved to `WorkspaceKspConfigurations`.
  - Passed focused Item 26 tests, full
    `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`,
    local `./gradlew migrateToBazel --console=plain --no-daemon`,
    `reports/scripts/verify-default-task-graph.sh`,
    `reports/scripts/verify-pax-size-guard.sh --mode preserving`, and both
    Grazel diff-check commands.
  - Known unchanged waiver:
    `reports/scripts/verify-sample-bucket-labels.sh` still fails only on the
    pre-existing one-sided appcompat/constraintlayout exclude-union case.
  - PAX baseline verification passed on
    `/Users/arun.sampathkumar/work/pax-android` branch
    `arun/grazel-refactor` at
    `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`: migrate passed in 12m26s,
    debug APK + android-test APK build passed in 221.378s, focused Bazel tests
    passed 3/3 in 19.045s, PAX `git diff --check` passed, PAX working tree
    remained clean, and no PAX commit was made.
  - PAX size guard remained unchanged: 11 buckets, 11 pinfiles, 1945 total
    artifact roots. No cache deletion was needed; free space stayed around
    18-22 GiB during the PAX run.
- 2026-06-28 +08 final post-review checkpoint for Items 17-22:
  - Primary items 17, 18, 19, and 21 are implemented and locally committed.
  - Stretch Item 22 completed as Outcome B: measurement proved bucket set-math
    problem-essential for the verified PAX/sample shape, so no Phase 2 reshape was attempted.
  - Current PAX regression baseline is clean on branch `arun/grazel-refactor` at
    `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`.
  - `reports/specs/pax-size-baseline.json` now records that PAX baseline commit with
    `bucketCount=11`, `pinfileCount=11`, and `totalArtifactRoots=1945`.
  - Older Active State bullets below are historical checkpoints from Items 12/13 and earlier.
    They are retained for traceability but are superseded by this final checkpoint and the
    item-specific logs for Items 17-22.
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
  - `ArtifactPinner` necessarily reads/edits generated `WORKSPACE` and lock
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
- Item 15 checkpoint commit:
  `0c9af6814019dade55eb0bbb18cc650a52c7d650`
  (`Clean up rendering purity seams`). Grazel worktree clean after commit.

## 2026-06-28 Item 16 Simplify + Review Start

- Active item: Item 16 - simplify, adversarial review, and final verification.
- Start commit: `0c9af6814019dade55eb0bbb18cc650a52c7d650`.
- Spec: `reports/specs/2026-06-27-item16-simplify-review-verification-design.md`.
- Baseline sanity:
  - `reports/specs/pax-size-baseline.json` exists and records PAX branch
    `arun/grazel-refactor`, commit
    `05d2b4801530726ab722133c2ba32cbba9afeb67`, `bucketCount=11`,
    `pinfileCount=11`, `totalArtifactRoots=1945`.
  - PAX is intentionally dirty with the accepted generated-output baseline:
    diff hash
    `5f05c2380375f16b0c04c6fa5f14d3a1666cf94d6b36a5ce1e0814a1b6e43566`,
    status hash
    `b9b38774443602baa0adf251daeb236e68cd181e1f4ccdf74ee412a30822c6d6`,
    dirty entries `2231`.
  - This supersedes Item 16's stale "PAX git diff is clean" wording; current
    goal anchor and prior item logs use stable accepted PAX diff hash as the
    guardrail. PAX files remain uncommitted.
- Next: run simplify-pass reviewers over `git diff master...HEAD`; apply only
  behavior-preserving cleanup, then run adversarial review and final
  verification.

## 2026-06-28 Item 16 Simplify Pass Checkpoint

- Simplify-pass subagents completed reuse, simplification, efficiency, and
  altitude slices.
- Applied behavior-preserving cleanup:
  - Removed unused `GenerateRootBazelScriptsTask.workspacePlan` input and task
    wiring.
  - Simplified `TargetVariantReachability.isReachableTargetVariant` to the data
    it actually uses: `variantName` plus reachable-bucket predicate.
  - Reused `Project.isAndroidApplication` / `Project.isAndroidTest` helpers in
    dependency root wiring and declared metadata collection.
  - Removed duplicate normalization in
    `CollectTargetMavenRepoReferencesTask`.
- Focused verification passed:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.migrate.target.TargetVariantReachabilityTest" --tests
  "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest" --console=plain
  --no-daemon`.
- Batch verification after cleanup:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`
    passed.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed; no
    generated BUILD/WORKSPACE/json files changed.
  - `git diff --check` passed.
  - `git diff --check master...HEAD` passed.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` failed only on the known
    appcompat/constraintlayout one-sided exclude-union waiver.
- Deferred findings with rationale:
  - Dependency-root planning still deserves a dependency-layer planner, but that
    is architecture work beyond Item 16.
  - Structured target/model references should eventually replace label/tag
    scraping, but not in this behavior-preserving cleanup gate.
  - `WorkspacePlan.repoPlan` should become the single source for root artifact
    and override rendering in a future item.
  - `reachableMainBucketsByProject` task-output dedupe may be valid, but it
    changes workspace dependency JSON shape and needs an explicit compat/test
    decision.
  - Hot-path accumulation/index optimizations and unit/android-test root spec
    dedupe were logged but not required for this verified batch.

## 2026-06-28 Item 16 Adversarial Review Checkpoint

- Dependency-correctness adversarial review found no findings. It independently
  ran focused dependency tests, diff checks, task-graph verification, the PAX
  size guard in preserving mode, and static scans for `--force-version`,
  bucket-prefixed Maven tags, and expected sample buckets.
- Graph/reachability/SCC adversarial review found no findings. It independently
  ran focused target reachability, topological sorter, dependency graph,
  workspace-plan, and Android test extraction tests.
- Task/render/cache adversarial review findings:
  - Fixed stale PAX baseline documentation so active docs describe the accepted
    dirty PAX generated-output hash/count contract, not a clean PAX worktree.
  - Fixed stale Item 7 size text in `KNOWN-LIMITATIONS.md` and
    `REVIEW-GUIDE.md`; current machine baseline is `11` buckets, `11`
    pinfiles, `1945` artifact roots.
  - Removed unused `VariantCompressionService.referencedMavenRepos()`.
  - Documented remaining cacheability-boundary risk: root component handoff and
    KSP sidecar are cacheable by design for this slice but not fully
    relocatable.
  - Documented remaining renderer-model reference parsing risk: target
    references still come from label/tag strings, not structured target refs.
- Post-review focused verification passed after the dead API and docs cleanup:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.migrate.target.TargetVariantReachabilityTest" --tests
  "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest" --console=plain
  --no-daemon`.

## 2026-06-28 Item 16 Final Verification Start

- Resource precheck: disk had about 25Gi free on `/System/Volumes/Data`; no
  cache cleanup performed. Shut down known Grazel/PAX Bazel servers with
  `bazelisk shutdown` and stopped one Gradle daemon before final gates.
- `./gradlew check --console=plain --no-daemon` failed only on the documented
  sample lint waiver:
  `sample-android/src/main/res/layout/activity_main.xml:73 MissingConstraints`
  in `:sample-android:lintDemoFreeDebug`.
- `./gradlew migrateToBazel --console=plain --no-daemon` passed; no generated
  BUILD/WORKSPACE/json files changed.
- `reports/scripts/verify-default-task-graph.sh` passed.
- `reports/scripts/verify-sample-bucket-labels.sh` failed only on the known
  appcompat/constraintlayout one-sided exclude-union waiver.
- `git diff --check` passed.

### Item 41 branch-wide code-quality hardening

- Active after checkpoint `104b4c7` (`refactor: tighten dependency task
  boundaries`). Detailed working notes live in
  `reports/specs/execution-log/item41-branch-wide-code-quality-hardening.md`.
- Regenerated `reports/specs/source-shape-inventory.tsv` from scratch: 182
  Kotlin rows plus header, all requiring reconciliation.
- First read-heavy subagent partition pass completed; parent reconciliation is
  ongoing.
- Decision: retain `Collection<T>.quote` in `bazel/starlark/Statement.kt` as an
  intentional Starlark DSL convenience. Do not reintroduce
  `quoteStarlarkValues(...)` or convert these call sites to a free function.
- `git diff --check master...HEAD` passed.
- `bazelisk build //...` failed only on the documented local sample/rule waiver:
  missing
  `sample-android/crashlytics-demo-free-debug_symlinked_manifest/AndroidManifest.xml`
  during Android resource packaging.
- `bazelisk test //...` failed on the same documented local sample/rule waiver;
  `//sample-android:sample-android-full-free-debug.lint_test` failed to build
  because the crashlytics symlinked manifest could not be opened. Bazel reported
  9 tests passed, 1 failed to build, and 7 skipped.
- PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
  --rerun-tasks` passed in 11m17s from branch `arun/grazel-refactor` at
  `05d2b4801530726ab722133c2ba32cbba9afeb67`; pinning was up-to-date.
- PAX generated-output baseline reproduced exactly after migrate:
  diff hash
  `5f05c2380375f16b0c04c6fa5f14d3a1666cf94d6b36a5ce1e0814a1b6e43566`,
  status hash
  `b9b38774443602baa0adf251daeb236e68cd181e1f4ccdf74ee412a30822c6d6`,
  and `2231` dirty entries.
- PAX `git diff --check` passed after migrate.
- `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed against
  PAX: bucket count `11`, pinfile count `11`, and total artifact roots `1945`
  all matched the frozen baseline exactly.
- PAX `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
  //app:app-gps-pax-debug-android-test.apk` passed. Bazel reported `8548`
  total actions and completed successfully in 233.740s.
- PAX `./bazel.sh test --test_output=errors
  //app-utils:app-utils-gps-pax-debug-test
  //app-test:app-test-gps-pax-debug-test
  //application-initializer:application-initializer-gps-pax-debug-test` passed;
  all 3 requested test targets pass.
- PAX generated-output baseline remained unchanged after Bazel build/test:
  diff hash
  `5f05c2380375f16b0c04c6fa5f14d3a1666cf94d6b36a5ce1e0814a1b6e43566`,
  status hash
  `b9b38774443602baa0adf251daeb236e68cd181e1f4ccdf74ee412a30822c6d6`, and
  dirty-entry count `2231`.
- PAX `git diff --check` passed after Bazel build/test.

## 2026-06-28 Next Goal Anchor Cleanup

- Consulted current Codex manual guidance for Goal mode: goal text should act as both starting
  prompt and completion criteria, include measurable verification criteria, and use subagents
  deliberately for context-heavy work.
- Replaced `CURRENT-GOAL-ANCHOR.md` so the active compact anchor is Items 17, 18, 19, 21, and
  stretch Item 22, not the completed Items 10-16.
- Clarified the PAX baseline rule: a maintainer-authorized local generated-output baseline
  commit in PAX is allowed as a regression guard, but the Grazel goal must not push PAX or
  commit additional PAX changes unless explicitly asked.
- Reconciled `DO-NOT-REVISIT.md` and `REVIEW-GUIDE.md` with Item 19: target reference
  discovery should move to structured `TargetReferenceFacts`, and target builders should run
  once during generation.
- Remaining prep before execution: if the maintainer commits the current PAX generated-output
  baseline locally, record the PAX commit SHA in this log before starting item work.

## 2026-06-28 Goal Start Baseline

- Grazel branch: `arun/dependencies-refactor`; starting commit before spec-anchor commit:
  `afbdaa3fd1503248d2aff313ec94d03ef6a501fb`.
- PAX branch: `arun/grazel-refactor`; local generated-output baseline commit:
  `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`; PAX worktree clean at goal start.
- Active objective: Items 17, 18, 19, and 21 required; Item 22 stretch only after primary
  work is green, with Phase 2 gated by exact shadow parity and real complexity reduction.
- Next action: commit the current spec/anchor docs locally in Grazel so implementation starts
  from a clean worktree, then begin Item 17.

## 2026-06-28 Resource Guardrail Clarification

- Maintainer requested retaining the stricter storage/process wording from the older goal prompt.
- Updated `CURRENT-GOAL-ANCHOR.md` to require resource checks before every expensive
  Gradle/Bazel command, preserve Bazel disk cache by default, avoid aggressive `--jobs`, treat
  large private Bazel output roots as a deliberate cleanup trigger, and keep PAX `bazel-cache`
  deletion as a last resort.

## 2026-06-28 Item 17 Progress

- Added `reports/specs/execution-log/item17-bucket-set-math.md`.
- Consolidated general bucket ownership set-math helpers into `BucketSetMath.kt`; resolver and
  planner duplicate copies were removed while planner-specific test helpers stayed local.
- Resource check before Gradle work: about `28GiB` free on Data, PAX `bazel-cache` about `14G`,
  no stale Gradle/Bazel/Coursier or high-RAM `python3.12` process observed. Slow full `du`
  probes were stopped; no cleanup was triggered.
- Focused dependency tests passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --tests "com.grab.grazel.gradle.dependencies.DependencyBucketPlacementEngineTest" --tests "com.grab.grazel.gradle.dependencies.BucketOwnershipPlannerTest" --console=plain --no-daemon`.
- Grazel `./gradlew migrateToBazel --console=plain --no-daemon` passed and produced no generated
  output diff.

## 2026-06-28 Item 18 Progress

- Added `reports/specs/execution-log/item18-typed-dag-ordering.md`.
- Replaced SCC/condensation in `ProjectReachabilityOrder.consumersFirstGroups` with direct typed
  DAG Kahn ordering, removed `ProjectReachabilityGroup.cyclic`, and deleted the dead
  `CollectTargetMavenRepoReferencesTask` cyclic-group guard.
- Added order-preservation fixtures for consumer-before-app, independent ready-node tie-break,
  and same-project multi-source-set collapse.
- Focused graph/reference tests passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.TopologicalSorterTest" --tests "com.grab.grazel.gradle.dependencies.DefaultDependencyGraphsTest" --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest" --console=plain --no-daemon`.
- Grazel `./gradlew migrateToBazel --console=plain --no-daemon` passed with no generated-output
  diff.
- PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks` passed
  in `11m 3s`; PAX generated output stayed clean against local baseline
  `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`.
- PAX `git diff --check` passed and `reports/scripts/verify-pax-size-guard.sh --mode preserving`
  passed with bucket count `11`, pinfile count `11`, total artifact roots `1945`, all unchanged.
- PAX `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
  passed in `223.249s`.
- PAX focused Bazel tests passed:
  `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test`.

## 2026-06-28 Item 19 Progress

- Added `reports/specs/execution-log/item19-target-reference-facts.md`.
- Added structured `TargetReferenceFacts` and `TargetReferenceFactsCollector`.
- Added target-layer `TargetReferenceFactsExtractor` and cut
  `CollectTargetMavenRepoReferencesTask` over to facts, removing the production
  `ProjectBazelFileBuilder.targets()` feedback path from reference collection.
- Kept `TargetMavenRepoReferencesCollector.fromTargets` as compatibility/test support only and
  made it delegate to the shared facts collector.
- Compile failure root causes and fixes:
  private member-extension function reference changed to a lambda; unnecessary Dagger interface
  binding removed in favor of injecting the concrete target-layer facts extractor.
- Focused tests passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest" --tests "com.grab.grazel.tasks.internal.TargetMavenRepoReferencesCollectorTest" --tests "com.grab.grazel.gradle.dependencies.TargetReferenceFactsCollectorTest" --console=plain --no-daemon`.
- Grazel `./gradlew migrateToBazel --console=plain --no-daemon` passed and generated output
  stayed clean.
- `git diff --check` passed.
- PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
  passed in `10m 55s`; generated output stayed clean against local baseline
  `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`.
- PAX `git diff --check` passed.
- PAX size guard passed in preserving mode with bucket count `11`, pinfile count `11`, and
  total artifact roots `1945`, all unchanged.
- PAX `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
  passed in `223.159s`.
- PAX focused Bazel tests passed:
  `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test`.
- PAX worktree stayed clean after migrate/build/test.
- Remaining Item 19 cleanup note: `collectTargetMavenRepoReferences` prints many fallback
  compression messages for projects without compression results. This did not affect generated
  output or size metrics, but can be considered output hygiene in Item 21.

## 2026-06-28 Goal Prompt Prep - Storage Wording

- Maintainer re-emphasized that the next goal prompt must retain the older, stricter
  operational constraints wording for storage, process, and Bazel/Gradle cleanup.
- Updated `reports/specs/CURRENT-GOAL-ANCHOR.md` so future goal runs inherit:
  resource checks before every expensive Gradle/Bazel command; no `--disk_cache=` disabling;
  no aggressive `--jobs` unless diagnosing; watch `~/.gradle/caches`, PAX `bazel-cache`,
  `bazel-ccache`, and `/private/var/tmp/_bazel_*` or `/private/var/bazel`-like dirs; use
  `bazelisk shutdown`/`bazelisk clean --expunge` first; remove private Bazel roots or PAX
  `bazel-cache` only when genuinely needed; stop stale Gradle/Bazel/Coursier/high-RAM
  `python3.12` processes only when clearly stale/problematic.

## 2026-06-28 Item 21 Audit Notes

- Persisted the two read-only Item 21 subagent audit summaries to
  `reports/specs/execution-log/item21-simplify-pass.md`.
- Key decisions captured there: Group A deletions are production-call-safe with test updates;
  remove `CollectTargetMavenRepoReferencesTask.compressionResults` only as an input while
  keeping `dependsOn(analyzeVariantCompressionTask)`; reuse/move existing `isDeclaredMetadata`
  instead of creating a second declared-dependency predicate; preserve current
  `MavenInstallArtifactsCalculator` override-target ordering.

## 2026-06-28 Item 21 PAX Verification

- PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
  passed in `11m 30s`; generated output stayed clean against local baseline
  `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`.
- PAX `git diff --check` passed.
- PAX size guard passed in preserving mode with bucket count `11`, pinfile count `11`, and
  total artifact roots `1945`, all unchanged.
- PAX `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
  passed in `234.914s`.
- PAX focused Bazel tests passed:
  `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test`.
- PAX worktree stayed clean after migrate/build/test.
- Resource notes: checked disk/memory/process before expensive Gradle/Bazel commands; disk stayed
  around `21 GiB` free on Data during PAX gates; no cache deletion or process killing was needed.

## 2026-06-28 Item 21 Final Review Checkpoint

- Read-only code-quality/altitude subagent found no blocking Item 21 findings. It verified that
  shared dependency identity helpers preserve the old predicates, Maven install repo filtering
  still happens before root-artifact/override calculation, and `compressionResults` input removal
  keeps task ordering through `dependsOn(analyzeVariantCompressionTask)`.
- Read-only verification subagent found one missing Item 21 gate: functional tests. Ran it before
  commit.
- Passed `./gradlew :grazel-gradle-plugin:functionalTest --console=plain --no-daemon` in
  `5m 4s`.
- `git status --short` after functional tests showed only Item 21 source/test/docs changes and
  the new `DependencyIdentity.kt`; no generated fixture drift.
- `git diff --check master...HEAD` was run by the verification subagent and exited clean.

## 2026-06-28 Item 22 Outcome B

- Added `reports/specs/execution-log/item22-setmath-ownership-experiment.md`.
- Ran temporary measurement instrumentation on sample and PAX, then removed all instrumentation
  before completion.
- PAX measurement completed via
  `./gradlew resolveWorkspaceDependencies --no-daemon --console=plain --stacktrace --rerun-tasks -Pgrazel.internal.bucketPlacementReport=build/grazel/bucket-placement-measurement.json`.
- Resource notes during PAX measurement: disk was tight but stable around `19-21 GiB` free on
  Data; stale Bazel servers/workers were shut down gracefully with `bazelisk shutdown` /
  `./bazel.sh shutdown`; no cache deletion was performed.
- Measurement result: PAX had `48628` placements and `0` unknown classifications. Active
  problem-essential paths included `429` inferred common-descendant placements, `6049` leaf
  residual placements, `5666` selected leaf hierarchy placements, `432` default fallback
  coverage decisions, and `2359` leaf residual exact-artifact coverage decisions.
- Sample also exercised the retained set-math: `54` inferred common-descendant placements,
  `4` leaf residual placements, `55` default fallback coverage decisions, `28` hierarchy
  superset-closure decisions, and `16` leaf residual superset-closure decisions.
- Decision: do not proceed to Item 22 Phase 2. A declaration-driven replacement is not
  justified under the empty-diff contract without exact shadow parity. Current set-math is
  reclassified as proven problem-essential for the verified PAX/sample shape.

## 2026-06-28 Final Review Fixes

- Read-only final reviewers found no critical issues.
- Fixed the remaining Item 17 compliance miss by deleting the dead
  `AggregatedDependencyResolver.withoutDeclaredPlaceholdersCoveredByDefault` copy. The
  planner-private helper remains because it is still used by Item 13 behavior.
- Tightened `TargetReferenceFactsCollector` tag parsing to count only normalized `@maven//:`
  tags. Bucket-prefixed Maven repo references still come from structured `MavenDependency`
  facts, not compile-filter tags.
- Updated `TargetReferenceFactsCollectorTest` with a red/green check proving bucket-prefixed
  tags are ignored.
- Updated Item 19 logs with the required target-builder invocation metric: `0` invocations
  during reference collection.
- Updated Item 22 spec status to Outcome B and refreshed `pax-size-baseline.json` metadata to
  current clean PAX baseline `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`.

## 2026-06-28 Final Review Fix Verification

- Focused red/green check: `TargetReferenceFactsCollectorTest` failed before the production
  parser change when bucket-prefixed tag repos were still counted, then passed after tightening
  tag parsing to normalized `@maven//:` only.
- Focused green checks passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.TargetReferenceFactsCollectorTest" --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --console=plain --no-daemon`
  and
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.TargetReferenceFactsCollectorTest" --tests "com.grab.grazel.tasks.internal.TargetMavenRepoReferencesCollectorTest" --console=plain --no-daemon`.
- Full plugin unit tests passed:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`.
- Plugin functional tests passed:
  `./gradlew :grazel-gradle-plugin:functionalTest --console=plain --no-daemon` in `3m 44s`.
- Grazel local generation passed:
  `./gradlew migrateToBazel --console=plain --no-daemon`.
- `reports/scripts/verify-default-task-graph.sh` passed.
- `reports/scripts/verify-sample-bucket-labels.sh` still hits the known pre-existing
  one-sided appcompat/constraintlayout exclude waiver; no new sample bucket-label failure was
  introduced.
- Root `bazelisk build //...` still hits the documented local sample/rule waiver:
  `sample-android/crashlytics-demo-free-debug_symlinked_manifest/AndroidManifest.xml` cannot
  open the Android-transitioned `CrashlyticsManifest.xml`. Reconfirmed that the focused
  `//sample-android:crashlytics-demo-free-debug_crashlytics_setup_manifest` target succeeds
  and materializes the host-config output, while the broad Android-config consumer remains the
  previously documented local sample waiver.
- Root `bazelisk test //...` still hits the same documented waiver; `9` tests passed before
  `//sample-android:sample-android-demo-free-debug.lint_test` failed to build on the same
  missing Crashlytics manifest path.
- Grazel `git diff --check` and `git diff --check master...HEAD` passed.
- PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
  passed in `10m 56s`; PAX generated output stayed clean against local baseline
  `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`.
- PAX `git diff --check` passed and PAX `git status --short` stayed clean.
- PAX size guard passed in preserving mode with bucket count `11`, pinfile count `11`, and
  total artifact roots `1945`, all unchanged.
- PAX `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
  passed in `225.881s`.
- PAX focused Bazel tests passed:
  `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test`.
- Resource notes: checked disk/memory/process before expensive Gradle/Bazel commands; disk was
  tight but stable around `17-19 GiB` free on Data; stopped no Gradle daemon because none was
  running after migrate; no cache deletion was performed.

## 2026-06-28 Next Goal Start: Items 23, 26, 24, 27, 25

- Goal source: `/Users/arun.sampathkumar/.codex/attachments/e715213e-f220-4897-8cd0-41915310448a/pasted-text-1.txt`.
- Current execution order is hard-gated by `reports/specs/ALTITUDE-LAYERING-ROADMAP.md`:
  Item 23 -> Item 26 -> Item 24 -> Item 27 -> Item 25 -> final verification/review.
- Committed approved spec-only updates before production work:
  `5e08bb169c704f0fbfb25f357e36e0fb04345350` (`docs: add next refactor goal specs`).
- Grazel branch at goal start: `arun/dependencies-refactor`.
- Grazel worktree after spec commit: clean.
- PAX regression workspace:
  `/Users/arun.sampathkumar/work/pax-android`, branch `arun/grazel-refactor`,
  commit `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`.
- PAX worktree at goal start: clean. Do not commit or push PAX changes unless explicitly
  instructed by maintainer.
- Active item: Item 23 target reference model hygiene.
- Key hard gates carried forward:
  generated Grazel output empty-diff, PAX generated baseline unchanged, no PAX-only hacks,
  no closure dropping / `--force-version`, normalized `@maven//:` compile-filter tags only,
  and no early exit before Items 23, 26, 24, 27, and 25 satisfy their acceptance criteria.

## 2026-06-28 Item 23 Progress: Target Reference Model Hygiene

- Red check: changed `WorkspacePlanTasksTest` local variables to require
  `TargetReferenceFacts` from `collectTargetMavenRepoReferences(...)`; focused test compile
  failed because the helper still returned `TargetMavenRepoReferences`.
- Implementation:
  - removed the duplicate `TargetMavenRepoReferences` data model;
  - changed reference collection/finalization to read and write `TargetReferenceFacts`;
  - deleted production `TargetMavenRepoReferencesCollector`;
  - deleted the old collector test and moved live-API coverage into
    `TargetReferenceFactsCollectorTest`;
  - removed the stale test-only merge wrapper in favor of `mergeTargetReferenceFacts(...)`.
- Acceptance scan: no production `TargetMavenRepoReferences` model, conversion helper, or
  production collector remains. The remaining `TargetMavenRepoReferences` text is task/helper
  naming only.
- Verification passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest" --tests "com.grab.grazel.gradle.dependencies.TargetReferenceFactsCollectorTest" --console=plain --no-daemon`
  and
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`.
- `git diff --check` passed.
- Remaining Item 23 gates: local `migrateToBazel`, default task graph, sample bucket labels,
  PAX migrate/build/test baseline loop, size guard, and local commit if all remain green.

## 2026-06-28 Item 23 Verification Complete

- Grazel generation passed:
  `./gradlew migrateToBazel --console=plain --no-daemon`.
- `reports/scripts/verify-default-task-graph.sh` passed.
- `reports/scripts/verify-sample-bucket-labels.sh` still hits the documented pre-existing
  one-sided appcompat/constraintlayout exclude waiver; not an Item 23 regression.
- `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed with unchanged PAX
  counts: bucket count `11`, pinfile count `11`, total artifact roots `1945`.
- `git diff --check` and `git diff --check master...HEAD` passed.
- PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
  passed in `12m 26s`; generated output stayed clean against baseline
  `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`.
- PAX `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
  passed in `224.543s`.
- PAX focused Bazel tests passed:
  `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test`.
- PAX `git status --short` stayed clean and PAX `git diff --check` passed.
- Resource notes: checked disk/memory/process before expensive runs; disk stayed around
  `19-20 GiB` free after PAX migrate/build; no cache deletion or process kill was needed.
- Item 23 status: complete pending local commit.

## 2026-06-28 Item 26 Progress: Variant-Owned Workspace Dependency Root Inputs

- Item 23 was locally committed as `4210235` (`refactor: collapse target reference model`).
- Active item: Item 26.
- PAX remains baseline-only on branch `arun/grazel-refactor` at
  `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`; do not commit PAX.
- Detailed Item 26 state is tracked in
  `reports/specs/execution-log/item26-variant-owned-root-inputs.md`.
- Implemented registrar altitude cleanup:
  `WorkspaceDependencyInputsRegistrar` now uses `VariantBuilder.onVariants`, delegates root-input
  planning to `WorkspaceDependencyRootInputPlanner`, and wires task inputs without owning AGP
  variant/configuration naming.
- Implemented KSP sidecar altitude cleanup:
  KSP declaration-bucket scanning and `grazelKspProcessorClasspath` construction moved to
  `gradle.variant.WorkspaceKspConfigurations`; `CollectKspProcessorDependenciesTask` keeps only
  task input wiring and KSP artifact/class extraction.
- Subagent branch-diff altitude scans were reconciled: current slice fixes registrar/KSP task
  violations; broader display-name parsing, target reachability/model cleanup, and Maven render
  package-boundary concerns are logged as future/wider-slice work rather than silent Item 26
  regressions.
- Verification passed:
  focused lazy/eager variant parity test, KSP task source guard, and focused Item 26 suite:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.variant.VariantTest" --tests "com.grab.grazel.gradle.variant.DefaultVariantBuilderTest" --tests "com.grab.grazel.gradle.dependencies.WorkspaceDependencyRootInputPlannerTest" --tests "com.grab.grazel.tasks.internal.ResolveWorkspaceDependenciesTaskTest" --tests "com.grab.grazel.tasks.internal.CollectKspProcessorDependenciesTaskTest" --console=plain --no-daemon`.
- Remaining Item 26 gates: preserving Grazel generation/task/diff checks, PAX baseline loop,
  then local-only Grazel commit if green.

## 2026-06-28 Current Truth: Item 27 Source-Shape Rerun

- Current Grazel commit before Item 27 local commit: `6a4c40a`.
- Active item: Item 27 branch-wide simplify/adversarial cleanup. Item 25 must remain last.
- Context-hygiene rule is now part of `CURRENT-GOAL-ANCHOR.md`: keep current truth near the
  top, mark stale legacy checkpoints historical/superseded, and condense noisy status after
  commits, verification gates, failures, and compactions.
- Item 27 source-shape rerun inventory covered 134 changed Kotlin files: 87 production,
  45 unit-test, and 2 functional-test files. Four scoped review agents covered
  dependencies/variant, migrate/bazel/DI, tasks/internal, and tests.
- Fixed rerun findings:
  - removed brittle source-text tests and added behavior coverage for KSP output parent
    creation;
  - fixed stale comments/KDoc and stale functional-test wording/path construction;
  - kept compressed target-name reachability and instrumentation referenced-target fixes from
    the adversarial pass.
- Deferred explicitly, not silently: typed declared project metadata, duplicated declaration
  bucket detection, KSP absolute-path cacheability, root-component `@Input` cacheability,
  reusable per-project target models, pinner/render stringly boundaries, and the empty-target
  non-concrete active `BUILD.bazel` disable policy. The latter caused sample generated-output
  drift for tracked empty `flavors`/`lint` BUILD files, so it was backed out of Item 27. These
  need separate model/cacheability/output-changing items and are not empty-diff Item 27 cleanup.
- Focused source-shape rerun test passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.tasks.internal.CollectKspProcessorDependenciesTaskTest" --tests "com.grab.grazel.tasks.internal.ResolveWorkspaceDependenciesTaskTest" --tests "com.grab.grazel.migrate.target.TargetReferenceFactsDataMappingTest" --tests "com.grab.grazel.migrate.target.TargetVariantReachabilityTest" --tests "com.grab.grazel.gradle.dependencies.WorkspaceRenderPlanBuilderTest" --console=plain --no-daemon`.
- Resource note before focused test: Data volume remained tight but usable at about 29 GiB free;
  no cache deletion or process kill was needed.
- Local Item 27 gates after rerun:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed and generated output stayed
    empty-diff after backing out the empty-target non-concrete `BUILD.bazel` policy change.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails on the documented one-sided
    appcompat/constraintlayout exclude waiver.
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed with unchanged counts:
    bucket count `11`, pinfile count `11`, total artifact roots `1945`.
  - `git diff --check` and `git diff --check master...HEAD` passed.
- Next required gate: PAX migrate/build/test loop from `/Users/arun.sampathkumar/work/pax-android`;
  PAX must stay baseline-only and must not be committed.

## 2026-06-29 Current Goal Start: Items 30, 29, 31, 28

- Current Grazel branch: `arun/dependencies-refactor`.
- Current Grazel commit after approved spec-only baseline commit:
  `47fe3e7d2b79a0c9860037487e52cf16f677c6ec`
  (`docs: tighten dependency refactor follow-up specs`).
- Active item: Item 30 workspace resolution input boundary.
- PAX regression workspace: `/Users/arun.sampathkumar/work/pax-android`, branch
  `arun/grazel-refactor`, commit `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`.
- Live PAX worktree at this checkpoint is not clean and must not be committed by Codex:
  `build-logic/project/src/main/kotlin/grazel/Constants.kt`,
  `build-logic/project/src/main/kotlin/grazel/Grazel.kt`,
  `build-logic/project/src/main/kotlin/grazel/task/ModuleLoggerTask.kt`,
  `generated/dependency_graph.json`, and untracked
  `build-logic/project/src/main/kotlin/grazel/task/Buildifier.kt`.
  Treat that as the local maintainer baseline state unless the maintainer gives new direction.
- Current execution order: Item 30 -> Item 29 -> Item 31 -> Item 28 -> simplify/adversarial
  review -> final Grazel/PAX gates.
- Item 30 hard focus: remove JSON model payloads from workspace-root task wiring, transport root
  metadata through Gradle file inputs/outputs, keep `ResolvedComponentResult` task inputs intact,
  add the JSON phase inventory and verifier, and preserve generated output.
- Reminder for compaction: never replace `ResolvedComponentResult` because it is live-looking;
  Gradle made this cacheable and this branch intentionally keeps the master-like input contract.

## 2026-06-29 Item 30 Complete: Workspace Resolution Input Boundary

- Implemented file-backed workspace root metadata:
  `CollectWorkspaceDependencyRootMetadataTask` writes root metadata JSON, and
  `ResolveWorkspaceDependenciesTask` consumes it as an `@InputFile`.
- Kept `workspaceDependencyRootComponents: ListProperty<ResolvedComponentResult>` as an `@Input`;
  this remains intentional and cacheable by Gradle design.
- Removed eager `Json.encodeToString(rootInput.toMetadata())` from
  `WorkspaceDependencyInputsRegistrar`; root metadata now flows through managed Gradle input
  properties and is serialized only in the metadata task action.
- Fixed the KSP sidecar cache input smell in the same item: replaced absolute path string artifact
  mapping with nested `KspArtifactInput(shortId, RegularFileProperty)` and changed
  `KspProcessorClassExtractor` to consume `Map<String, File>`.
- Added `reports/scripts/verify-json-phase-inventory.sh` and
  `reports/specs/execution-log/item30-json-phase-inventory.tsv`. Remaining
  `CollectDeclaredDependencyMetadataTask.declaredDependencyMetadataJson` JSON-string payload is
  explicitly marked as Item 29-owned debt.
- Verification passed:
  focused task/extractor tests, `collectWorkspaceDependencyRootMetadata` plus up-to-date rerun,
  `collectKspProcessorDependencies` plus up-to-date rerun,
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`,
  `./gradlew migrateToBazel --console=plain --no-daemon`,
  `reports/scripts/verify-json-phase-inventory.sh`,
  `reports/scripts/verify-default-task-graph.sh`,
  `reports/scripts/verify-pax-size-guard.sh --mode preserving`,
  `git diff --check`, and `git diff --check master...HEAD`.
- Known local waiver unchanged:
  `reports/scripts/verify-sample-bucket-labels.sh` still fails on the documented pre-existing
  one-sided appcompat/constraintlayout exclude case.
- PAX gates passed:
  `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks` in `12m 6s`,
  then `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
  in `216.504s`, plus PAX `git diff --check`.
- PAX status remained at the maintainer/local baseline dirty shape; no PAX commit was made.
- Next active item: Item 29 declared metadata aggregation modes.

## 2026-06-29 Item 29 Start: Declared Metadata Aggregation Modes

- Starting Grazel commit: `f0bfa47` (`refactor: file-back workspace root metadata`).
- Worktree was clean before Item 29.
- Active item: Item 29 declared metadata aggregation modes.
- Item 29 objective: remove build-file-glob cache proxies and JSON-string task input payloads from
  declared dependency metadata collection, add experiment-controlled `SINGLE_TASK` and
  `PROJECT_TASK_FANOUT` modes, and keep the downstream aggregate
  `build/grazel/declared-dependency-metadata.json` contract unchanged.
- TDD plan: first add failing tests for the forbidden task shape, experiment default/override,
  deterministic fanout merge ordering, and mode task annotations; then implement the smallest
  shared snapshot/merge boundary that keeps generated output empty-diff.
- Provider API note from maintainer discussion: prefer provider-based late Gradle semantics where
  safe, but do not map live Gradle/AGP model reads into task inputs unless a full PAX task graph
  proves the snapshot boundary is late and complete. Item 29 proved that even memoized provider
  shard inputs can be realized/fingerprinted too early under `migrateToBazel`, so the current
  fanout implementation uses untracked per-project shard tasks that snapshot in `@TaskAction`.
- Provider API reminder for later items: when Gradle live objects need to be read late, first
  consider `Provider`/managed-property wiring instead of eager configuration-phase reads. The safe
  shape is either single-realization/memoized provider use, or provider-produced serializable/file
  task inputs. Do not spread an un-memoized live Gradle snapshot provider across many task
  properties, because each realization can observe or create different Gradle/AGP state.
- Item 29 focused implementation state:
  - Added `DeclaredDependencyMetadataAggregationMode` with default `SINGLE_TASK` and alternate
    `PROJECT_TASK_FANOUT`.
  - Deleted the old build-file glob / JSON string declared metadata task input shape.
  - `SINGLE_TASK` is explicitly untracked and writes the aggregate declared metadata JSON.
  - `PROJECT_TASK_FANOUT` uses untracked per-project shard tasks plus cacheable merge task, all
    feeding the same aggregate file contract.
  - Local timing snapshot before the untracked-shard correction: root sample `SINGLE_TASK`
    10 projects / 145281 bytes / about 482 ms; root sample fanout 10 shards / 145281 bytes /
    merge about 18 ms after shard tasks. Rerun focused fanout timing after the correction before
    using this as Item 31 evidence.
  - Focused unit and functional tests plus JSON inventory passed. Details in
    `reports/specs/execution-log/item29-declared-metadata-aggregation-modes.md`.
- Item 29 PAX default-mode gate:
  `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks` passed in PAX
  in `11m 6s`.
  `collectDeclaredDependencyMetadata` reported `mode=SINGLE_TASK projects=2327
  aggregateJsonBytes=35247531 elapsedMs=11524`.
  PAX status remained at the accepted local baseline dirty set, and PAX `git diff --check` passed.
  Temporary PAX fanout experiment edit will be reverted after the parity run; do not commit PAX.
- Item 29 fanout parity investigation:
  - PAX `PROJECT_TASK_FANOUT` migrate passed in `10m 46s` and generated output stayed at the
    accepted local baseline; merge logged `projects=2327 shards=2327 aggregateJsonBytes=34839457
    elapsedMs=442`.
  - A default metadata-only rerun logged `projects=2327 aggregateJsonBytes=37082273
    elapsedMs=9593`; saved aggregate comparison showed variant-list mismatch between modes.
  - Root cause fixed in task shape: fanout eagerly copied a mutable `variantsByProject` callback
    map at `projectsEvaluated`, while the single task read the same map later during task action.
    Added `DeclaredProjectMetadataPlanner` so both modes consume one frozen sorted project/variant
    source. Added a regression test proving the plan freezes mutable variant callback collections.
  - A read-only subagent also flagged that `VariantBuilder.onVariants()` and `build()` are not
    equivalent for flavored projects. Trying to route `onVariants()` through `build()` removed
    synthetic hierarchy nodes and caused generated `BUILD.bazel`/`WORKSPACE` drift, so that cleanup
    was backed out from Item 29 and must be handled as a separate preserving architecture item if
    pursued. Current Item 29 keeps existing variant hierarchy behavior and fixes only the mode
    parity boundary.
  - Focused tests passed after the fix:
    `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.extension.ExperimentsExtensionTest" --tests "com.grab.grazel.tasks.internal.CollectDeclaredDependencyMetadataTaskTest" --tests "com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadataMergerTest" --tests "com.grab.grazel.gradle.variant.DefaultVariantBuilderTest" --console=plain --no-daemon`.
    Functional mode checks passed:
    `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.computeWorkspaceDependenciesIsUpToDateWithoutInputChanges" --tests "com.grab.grazel.migrate.BuildVariantTest.projectTaskFanoutDeclaredMetadataModeProducesStableWorkspaceDependencies" --tests "com.grab.grazel.migrate.BuildVariantTest.declaredMetadataAggregationModesProduceSameAggregateJson" --console=plain --no-daemon`.
  - Local `./gradlew migrateToBazel --console=plain --no-daemon` passed after backing out the
    unsafe variant-builder cleanup; `generateRootBazelScripts` restored the transient
    `WORKSPACE` ordering drift caused by the earlier temporary missing `debug_maven_install.json`.
    `reports/scripts/verify-json-phase-inventory.sh` passed after updating Item 29 line numbers.
- Item 29 PAX provider-timing fix:
  - A second PAX fanout migrate passed in `10m 56s`, but aggregate parity still failed:
    `SINGLE_TASK` bytes/hash `35247531` /
    `81b33d01d3ead2fe4c55fa8a1f4d6214299619e3cbe26b55c7e17a8790c927c5`,
    fanout bytes/hash `34839457` /
    `75f3083ee0f4d224641af17e68dc73cbacffea4808cc445e75dc8939ea100c2f`.
  - Structural compare found all 2327 projects in both modes; 1167 projects differed because eager
    fanout shard snapshots missed late default-bucket declarations such as `api kotlin-stdlib`,
    databinding artifacts, `androidx.annotation`, and some plugin-added implementation deps.
  - First attempted fix wired `CollectProjectDeclaredDependencyMetadataTask` typed inputs from a
    memoized provider per shard. This delayed the live model read enough for sample/direct checks,
    but PAX full `migrateToBazel` still showed task-graph timing drift.
  - Added regression coverage:
    `CollectDeclaredDependencyMetadataTaskTest.fanout shard input snapshots declared metadata after task registration`.
  - Focused task test and local functional fanout parity tests passed after the provider fix.
- Item 29 task-graph timing correction:
  - PAX fanout full `migrateToBazel` after the provider-timing fix passed in `11m 42s`, but
    aggregate parity still failed. The full-migrate fanout aggregate was `34839727` bytes,
    SHA-256 `b3ba6b39115acbcb4ace206ba32fc0b0e3b303a717231389f8786d3ca2b79e47`.
  - Fresh direct PAX `collectDeclaredDependencyMetadata` produced `37082273` bytes,
    SHA-256 `3b837d08a6055e363359bd7b0ca21ccfcbe013dc528a457efa0df6df74b5a5df`, with
    `10031 ms` task-action time.
  - Fresh direct PAX `mergeDeclaredDependencyMetadata --rerun-tasks` produced the exact same
    bytes/hash with merge time `549 ms` and wall time `3m 1s`.
  - Decision/fix: `CollectProjectDeclaredDependencyMetadataTask` is now an explicit
    `@UntrackedTask` and snapshots its assigned project/variant source inside `@TaskAction`,
    matching `SINGLE_TASK`. `MergeDeclaredDependencyMetadataTask` remains cacheable because it
    reads only shard JSON files.
  - Durable spec update: Item 29 and roadmap now say fanout shard tasks are untracked; cacheable
    per-project shard snapshots are out of scope unless a future stable snapshot producer proves
    full PAX parity.
  - Focused verification after the correction:
    `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.tasks.internal.CollectDeclaredDependencyMetadataTaskTest" --console=plain --no-daemon`
    passed.
  - Remaining: rerun full PAX fanout `migrateToBazel` after the untracked shard correction, compare
    aggregate/generation baseline, then revert the temporary PAX experiment toggle before
    continuing.
- Item 29 full PAX fanout verification after untracked shard correction:
  - PAX `PROJECT_TASK_FANOUT` full `migrateToBazel --rerun-tasks` passed in `12m 6s`.
  - `mergeDeclaredDependencyMetadata` logged `projects=2327 shards=2327
    aggregateJsonBytes=35247531 elapsedMs=622`.
  - Saved aggregate
    `/tmp/pax-fanout-declared-dependency-metadata-after-untracked-full-migrate.json`
    matched the earlier full-migrate `SINGLE_TASK` aggregate byte-for-byte:
    `35247531` bytes, SHA-256
    `81b33d01d3ead2fe4c55fa8a1f4d6214299619e3cbe26b55c7e17a8790c927c5`.
  - Important interpretation: direct metadata-only task runs still produce a larger
    `37082273`-byte aggregate because their task graph scope differs. Item 29 parity is judged
    full-migrate-to-full-migrate, since that is the actual generation path.
  - PAX `git diff --check` passed. The temporary PAX `build.gradle` fanout experiment toggle was
    reverted; PAX is back to the accepted local baseline dirty set and must not be committed.
  - Remaining Item 29 gates: run/re-run default-mode PAX final gate if needed, then PAX Bazel APK
    build/test gates, Grazel broad gates, and commit Grazel locally at a clean green checkpoint.
- Item 29 final default/PAX build gates:
  - Grazel `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed in `45s`.
  - Grazel `./gradlew migrateToBazel --console=plain --no-daemon` passed in `10s`.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-json-phase-inventory.sh` passed after refreshing the Item 29 JSON call
    line numbers.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails with the known pre-existing
    one-sided appcompat exclude waiver:
    `WORKSPACE must not union one-sided appcompat exclude onto androidx.constraintlayout:constraintlayout`.
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed: bucket count `11`,
    pinfile count `11`, total artifact roots `1945`, no per-repo deltas.
  - PAX default `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
    passed in `9m 36s`; `SINGLE_TASK` declared metadata stayed at `35247531` bytes.
  - PAX `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed in `243.304s`.
  - PAX `git diff --check` passed and status remained the accepted local baseline dirty set only.
  - Item 29 can be checkpoint-committed locally; PAX focused Bazel test targets remain a later
    final-goal gate unless rerun before moving to the next item.

## 2026-06-29 Item 31 Start

- Starting commit: `4c2eeb1` (`refactor: add declared metadata aggregation modes`).
- Active item: Item 31 - declared metadata fanout default decision.
- Decision from Item 29 evidence: switch the default declared metadata aggregation mode to
  `PROJECT_TASK_FANOUT`. The full PAX generation path has byte-identical metadata and generated
  output parity across both modes; fanout shard tasks remain intentionally untracked, while the
  deterministic merge task remains cacheable.
- PAX baseline remains `/Users/arun.sampathkumar/work/pax-android` branch
  `arun/grazel-refactor` at `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`, with the accepted local
  baseline dirty set only. Do not commit PAX.
- Item 31 verification:
  - Changed the default mode to `PROJECT_TASK_FANOUT`; `SINGLE_TASK` remains an explicit override.
  - Focused `ExperimentsExtensionTest` passed.
  - Local `./gradlew migrateToBazel --console=plain --no-daemon` passed in `10s` and logged
    `mode=PROJECT_TASK_FANOUT projects=10 shards=10 aggregateJsonBytes=145401 elapsedMs=16`.
  - `reports/scripts/verify-default-task-graph.sh` initially failed because it still expected the
    old default `:collectDeclaredDependencyMetadata` task. Updated the verifier to require shard
    tasks plus `:mergeDeclaredDependencyMetadata` and to reject the single-task aggregate on the
    default path; the script then passed.
  - `reports/scripts/verify-json-phase-inventory.sh` passed.
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed unchanged.
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed in `38s`.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails only on the known pre-existing
    appcompat/constraintlayout exclude waiver.
  - PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks` passed
    in `12m 11s` and logged `mode=PROJECT_TASK_FANOUT projects=2327 shards=2327
    aggregateJsonBytes=35247531 elapsedMs=1044`.
  - PAX size guard stayed unchanged: bucket count `11`, pinfile count `11`, total artifact roots
    `1945`, no per-repo deltas.
  - PAX `git diff --check` passed and status stayed at the accepted local baseline dirty set.
  - PAX `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed in `214.420s`.
  - Disk was tight (`10-11 GiB` free) but stable; no cleanup was performed because the incremental
    Bazel build completed successfully.
  - Provider API note for later: use providers for late typed Gradle reads when they feed stable
    serialized/file-backed task inputs. Do not treat provider-mapped live Gradle model inputs as
    cacheable without full PAX task-graph proof; current fanout shards remain intentionally
    untracked and the merge remains cacheable.

## 2026-06-29 Item 28 Start

- Starting commit: `9173c50` (`refactor: default declared metadata aggregation to fanout`).
- Active item: Item 28 - hard source-shape inventory remediation.
- Grazel worktree was clean at start. PAX remains at branch `arun/grazel-refactor`, commit
  `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`, with the accepted local baseline dirty set only.
- Confirmed Items 30, 29, and 31 are complete and locally committed.
- Initial changed Kotlin scope from `master...HEAD`: `135` files (`86` main, `47` test,
  `2` functionalTest).
- Added and ran `reports/scripts/source-shape-inventory.sh`; initial
  `reports/specs/source-shape-inventory.tsv` is generated with pending rows.
- Initial detector distribution: `64` no-flag files, `5` generic collection receiver files,
  `9` private helper model files, `10` project extension files, `13` reflection/dynamic access
  files, `24` unchecked-cast files, `22` source-string assertion files, and `19` comment/context
  artifact files. Counts are candidates, not final decisions.
- Dispatched scoped read-only subagents for dependencies/variant, tasks, migrate/bazel/di, and
  test/functional clusters. Parent will reconcile row-level findings into the TSV.
- Item 28 remediation checkpoint:
  - Cleaned the highest-signal source-shape issues from the first audit pass, including explicit
    role-parameter rewrites in dependency/task/tag-plan helpers, removal of the dead
    `GenerateBazelScriptsTask.variantCompressionResults` input/wiring, and localized test fixture
    downcasts.
  - `./gradlew :grazel-gradle-plugin:compileKotlin :grazel-gradle-plugin:compileTestKotlin --console=plain --no-daemon`
    passed in `20s`.
  - `reports/scripts/source-shape-inventory.sh` reran after edits; the TSV still intentionally has
    pending rows and must be reconciled before Item 28 can complete.
  - Added final read-only production/test row-audit subagents against the current tree.
- Item 28 reconciliation checkpoint:
  - Finished the second cleanup pass from the final production/test audits:
    explicit `BucketSetMath`/declared-metadata helpers, structured Android test dependency
    assertions, parsed JSON checks in `BuildVariantTest`, direct `DefaultArtifactPinner`
    construction, and removal of the executable `TODO` fake.
  - Mechanical set-math rewrite briefly failed compile in `DependencyBucketPlacementEngine.kt`;
    root cause was one extra parenthesis, fixed before continuing.
  - Compile gates passed:
    `./gradlew :grazel-gradle-plugin:compileKotlin :grazel-gradle-plugin:compileTestKotlin --console=plain --no-daemon`
    in `17s`, then
    `./gradlew :grazel-gradle-plugin:compileKotlin :grazel-gradle-plugin:compileTestKotlin :grazel-gradle-plugin:compileFunctionalTestKotlin --console=plain --no-daemon`
    in `10s`.
  - `reports/scripts/source-shape-inventory.sh` reran after final edits.
  - `reports/specs/source-shape-inventory.tsv` now has `135` changed Kotlin rows, matching
    `git diff --name-only --diff-filter=ACMR master...HEAD -- '*.kt'`; no pending or blank row
    fields remain. Terminal row statuses: `21 fixed`, `71 no_issue`,
    `43 retained_problem_essential`.
  - Provider API reminder for future task-boundary work: prefer providers for late typed Gradle
    reads into stable file/serializable task inputs; do not convert this Item 28 preserving cleanup
    into a provider/cacheability behavior change.
- Item 28 verification checkpoint:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed in `39s`.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed in `10s`.
  - `reports/scripts/verify-json-phase-inventory.sh`, `reports/scripts/verify-default-task-graph.sh`,
    `reports/scripts/verify-pax-size-guard.sh --mode preserving`, `git diff --check`, and
    `git diff --check master...HEAD` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails only on the known pre-existing
    appcompat/constraintlayout exclude waiver.
  - PAX storage became tight (`8.3 GiB` free), so `bazelisk clean --expunge` was run in the PAX
    repo. `pax-android/bazel-cache` was preserved.
  - PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks` passed
    in `12m 32s` and logged `mode=PROJECT_TASK_FANOUT projects=2327 shards=2327
    aggregateJsonBytes=35247531 elapsedMs=658`.
  - PAX `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed in `139.050s` after restarting a stale
    pre-compaction wrapper/client.
  - PAX focused unit tests passed in `19.310s`:
    `//app-utils:app-utils-gps-pax-debug-test`, `//app-test:app-test-gps-pax-debug-test`, and
    `//application-initializer:application-initializer-gps-pax-debug-test`.
  - PAX `git diff --check` passed and status remains exactly the accepted local dirty baseline.
  - Remaining work: simplify pass, adversarial branch-diff review, post-review verification, and
    local Grazel commit only after green.
- Item 28 simplify-pass checkpoint:
  - Ran four read-only simplify-pass reviewers: reuse, simplification, efficiency, and altitude.
  - Applied local preserving cleanup: derived main covered deps instead of storing them, memoized
    per-bucket placement candidates, reduced hot set/list allocations, inverted leaf-descendant
    graph construction, avoided per-variant transitive-classpath filtering allocation, collapsed
    duplicated declared-metadata task-output state, shared dependency JSON traversal in
    `BuildVariantTest`, added `mktemp` cleanup to the source-shape inventory script, and moved the
    duplicated resolved-component edge helper into `com.grab.grazel.fake`.
  - Deferred larger altitude findings for follow-up architecture work: structured override target
    data, regex-free target reference facts, removing consumer-first render-plan feedback, and
    typed variant-owned bucket metadata.
  - Post-fix compile passed in `13s`; focused dependency tests passed in `12s`; source-shape
    inventory reran and still has `135` terminal rows for `135` changed Kotlin files.
  - Remaining work: adversarial branch-diff review, broad verification, PAX final guard, and local
    Grazel commit only after green.
- Item 28 adversarial/post-review checkpoint:
  - Adversarial review found one real singleton-cache bug in `WorkspaceTargetTagPlanCollector`;
    fixed by clearing both `variantsByProjectPath` and `transitiveMavenDepsCache` at collection
    boundaries.
  - Fixed `AnalyzeVariantCompressionTask` to create the compression output parent directory before
    writing the JSON file.
  - Fixed `reports/scripts/source-shape-inventory.sh` to inventory the union of committed branch
    diff, working-tree changes, staged changes, and untracked Kotlin files; this caught
    `ManifestValuesBuilder.kt`, now reviewed as the 136th terminal row.
  - Tried the reviewer-suggested master-like Maven repository narrowing in
    `MavenInstallArtifactsCalculator`, but `./gradlew migrateToBazel --console=plain --no-daemon`
    proved it changes generated output by removing Google Maven from `ksp_maven`, `lint_maven`,
    and `test_maven` pins. Because Item 28 is preserving, reverted that behavior/test and logged it
    as a follow-up output-changing candidate.
  - `reports/scripts/verify-json-phase-inventory.sh` failed after source reshaping because JSON
    encode/write helper line numbers moved in `AnalyzeVariantCompressionTask.kt` and
    `CollectDeclaredDependencyMetadataTask.kt`; verified all sites remain task-action/file-backed
    and updated only the inventory coordinates.
  - Current source-shape inventory: `136` rows, no pending/blank fields, terminal statuses
    `21 fixed`, `72 no_issue`, `43 retained_problem_essential`.
- Final verification checkpoint after simplify/adversarial fixes:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed in `45s`.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed in `20s` and left generated
    `WORKSPACE`, `BUILD.bazel`, and Maven pin JSONs empty-diff.
  - `reports/scripts/verify-json-phase-inventory.sh` passed.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed unchanged:
    `bucketCount=11`, `pinfileCount=11`, `totalArtifactRoots=1945`, no per-repo deltas.
  - `git diff --check` and `git diff --check master...HEAD` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails only on the known pre-existing
    appcompat/constraintlayout exclude waiver.
  - PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks` passed
    in `12m 12s` and logged `mode=PROJECT_TASK_FANOUT projects=2327 shards=2327
    aggregateJsonBytes=35247531 elapsedMs=554`.
  - Because PAX disk was low, ran `bazelisk clean --expunge` in
    `/Users/arun.sampathkumar/work/pax-android` before the APK gate; preserved
    `pax-android/bazel-cache`.
  - PAX `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed in `267.070s`.
  - PAX focused tests passed in `22.898s`:
    `//app-utils:app-utils-gps-pax-debug-test`, `//app-test:app-test-gps-pax-debug-test`, and
    `//application-initializer:application-initializer-gps-pax-debug-test`.
  - PAX `git diff --check` passed and `git status --short` remains exactly the accepted local
    baseline dirty set: `Constants.kt`, `Grazel.kt`, `ModuleLoggerTask.kt`,
    `generated/dependency_graph.json`, and untracked `Buildifier.kt`.

## 2026-06-29 - Item 33 post-commit simplify-pass checkpoint

- Baseline commit before this pass: `5868d22`.
- Scope: committed branch diff through Item 33 only. The unstaged future Item34
  roadmap/spec docs were intentionally left out of this simplify pass.
- Four simplify-pass reviewers completed: reuse, simplification, efficiency,
  and altitude. Parent reconciled and applied only preserving, local cleanups.
- Applied cleanups:
  - `WorkspaceTargetTagPlanCollector` now reuses `VariantGraphKey.from(variant)`
    and `TEST_VARIANT` instead of reconstructing those facts locally.
  - `TargetReferenceFactsCollector` derives project target references from the
    canonical `ProjectDependency.toString()` Bazel label, avoiding a second
    local target-name formula.
  - `CollectTargetMavenRepoReferencesTask` no longer normalizes accumulated
    reference facts twice in the single-pass collection path.
  - `DeclaredDependencyMetadataCollector` shares exclude-rule grouping for
    all-dependency and declared-dependency callers while preserving their
    separate caller contracts.
  - `DependencyBucketPlacementEngine` memoizes selected descendant leaves per
    bucket inside one plan and removes the duplicate graph coverage helpers.
- One compile failure occurred during cleanup:
  - Symptom: Kotlin platform declaration clash on
    `extractExcludeRulesByShortId(Iterable)`.
  - Root cause: a private generic extension erased to the same JVM signature as
    the existing public `Iterable<Configuration>` helper.
  - Fix: renamed the private helper to an explicit-parameter function,
    `excludeRulesByShortId(dependencies = ...)`.
- Deferred larger simplify findings as architecture follow-ups, not shortcuts:
  root component resolution/traversal sharding; moving target Maven repo
  reference collection out of the task layer; extracting KSP processor
  dependency collection out of its task action; replacing target-reference
  regex/string parsing with fuller typed facts; moving owner inference away
  from bucket-name string facts; moving Maven tag helper calculation out of the
  migrate package.
- Focused verification passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.TargetReferenceFactsCollectorTest" --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest" --tests "com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadataCollectorTest" --tests "com.grab.grazel.gradle.dependencies.WorkspaceTargetTagPlanCollectorTest" --tests "com.grab.grazel.gradle.dependencies.DependencyBucketPlacementEngineTest" --console=plain --no-daemon`.
- `git diff --check` passed.
- Remaining current-goal work: adversarial branch-diff review, broad local
  verification, PAX final guard if non-doc code remains, and local Grazel commit
  only after green.

## 2026-06-29 - Item 33 adversarial review checkpoint

- Ran four read-only adversarial reviewers focused on dependency correctness,
  Gradle task/cache boundaries, generated-output/PAX risk, and altitude/code
  quality.
- Confirmed and fixed the current uncommitted P1:
  `TargetReferenceFactsCollector` briefly derived `ProjectDependency` facts by
  parsing `ProjectDependency.toString()`, which could key references by Bazel
  filesystem label path instead of Gradle `project.path`. Fixed by exposing the
  canonical target-name part on `ProjectDependency` while keeping the project
  key as `dependencyProject.path`.
- Added regression coverage:
  `TargetReferenceFactsCollectorTest.structured project references use Gradle path instead of rendered label path`.
- Rejected one reviewer blocker as conflicting with the maintained design:
  `ResolvedComponentResult` root components remain `@Input` on the cacheable
  resolver tasks by explicit maintainer decision and prior master behavior.
  Existing tests assert this for KSP root components; no Gradle validation
  failure has been observed.
- Classified as follow-up/output-changing candidates, not hidden shortcuts:
  - KSP aggregated repo pin inputs currently materialize direct processors, not
    expanded full KSP transitive closure. This may need a dedicated correctness
    item because changing it moves generated pin JSON/WORKSPACE shape.
  - `DefaultOverrideCarrierPlanner` intentionally promotes lower non-direct
    variant artifacts to a higher default repo version. This is codified by
    existing tests and needs a separate Gradle-resolved-version correctness
    decision before changing behavior.
  - Declared project dependency edges and root exclude metadata still encode
    some structured data as strings. They are file/provider-backed but should
    be converted to serializable structured models in a follow-up.
  - `CollectTargetMavenRepoReferencesTask` still owns reference accumulation
    orchestration; Item34 is the planned service-shape follow-up.
- Verification after the P1 fix passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.TargetReferenceFactsCollectorTest" --console=plain --no-daemon`
  and the broader touched-area focused test command from the simplify checkpoint.
- Remaining current-goal work: broad Grazel verification, PAX final guard,
  final logs, and local commit only after green.

## 2026-06-29 - Item 33 final verification after simplify/adversarial fixes

- Local Grazel verification passed:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`
    (`40s`).
  - `./gradlew migrateToBazel --console=plain --no-daemon` (`35s`), with no
    local generated-output diff.
  - `reports/scripts/verify-default-task-graph.sh`.
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving`: 11 buckets,
    11 pinfiles, 1945 total artifact roots, no per-repo deltas.
  - `reports/scripts/verify-json-phase-inventory.sh` after refreshing moved
    line numbers in the inventory.
  - `git diff --check` and `git diff --check master...HEAD`.
- Known pre-existing/local waiver unchanged:
  `reports/scripts/verify-sample-bucket-labels.sh` still fails on the existing
  one-sided appcompat/constraintlayout exclude assertion.
- PAX verification on
  `/Users/arun.sampathkumar/work/pax-android` branch `arun/grazel-refactor`
  commit `cfa1057ed58ccb2a795a5f679f072a8f604ff48e` passed:
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
    (`11m21s`), with declared metadata metric
    `mode=PROJECT_TASK_FANOUT projects=2327 shards=2327 aggregateJsonBytes=35247531 elapsedMs=481`.
  - PAX `git diff --check`.
  - `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
    (`215.433s`), build completed successfully.
  - `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test`
    (`16.790s`), 3 test targets passed.
  - Final PAX `git diff --check` and size guard passed.
- PAX generated state remained exactly on the accepted local baseline:
  `Constants.kt`, `Grazel.kt`, `ModuleLoggerTask.kt`,
  `generated/dependency_graph.json`, and untracked `Buildifier.kt`.
- Resource checks before PAX gates showed roughly `28-29GiB` free; no cache
  cleanup was needed.
- Item33 cleanup landed in local commit `2a780b5`
  (`refactor: tighten item33 cleanup`). Future Item34/35 work starts from that
  checkpoint.

## 2026-07-01 - Item 32 status correction for context survival

- Clarified that Item32, true project declared-metadata fanout, is already
  implemented and should not be treated as pending implementation work.
- Grounded evidence in code:
  - `CollectProjectDeclaredDependencyMetadataTask.register(...)` now uses
    `metadataSource.project.tasks.register("collectProjectDeclaredDependencyMetadata")`.
  - Each shard writes to the source project build directory:
    `build/grazel/declared-dependency-metadata/project.json`.
  - The root `mergeDeclaredDependencyMetadata` task remains the cacheable
    file-backed merge and consumes shard `RegularFileProperty` providers.
  - `reports/scripts/verify-default-task-graph.sh` expects
    `:sample-android:collectProjectDeclaredDependencyMetadata` and rejects the
    old root-flat `:collectSampleAndroidDeclaredDependencyMetadata` shape.
- Updated durable docs:
  - `reports/specs/ALTITUDE-LAYERING-ROADMAP.md`: Item32 status is now
    `completed`.
  - `reports/specs/2026-06-29-item32-true-project-declared-metadata-fanout-design.md`:
    added a completion note and warning not to re-open Item32 unless the task
    shape regresses.
- Remaining related work is follow-up hygiene/performance observation only, not
  the source-project fanout conversion itself.

## 2026-07-01 - Item 34/35 goal start and status-doc checkpoint

- Current Grazel branch/status at goal start:
  `arun/dependencies-refactor` at `2a780b5`
  (`refactor: tighten item33 cleanup`).
- Current PAX regression workspace:
  `/Users/arun.sampathkumar/work/pax-android`, branch `arun/grazel-refactor`,
  commit `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`, with the accepted local
  dirty baseline only:
  `Constants.kt`, `Grazel.kt`, `ModuleLoggerTask.kt`,
  `generated/dependency_graph.json`, and untracked `Buildifier.kt`.
  Do not commit PAX.
- Active execution order for this goal:
  status/docs truth checkpoint -> Item 34 -> Item 35 -> simplify/adversarial
  review -> final Grazel/PAX gates.
- Status/doc corrections made before code work:
  - Item30/31/33 spec headers now say completed.
  - Item33 roadmap status now says completed.
  - Item32 spec, roadmap, and item log now explicitly say source-project
    declared-metadata fanout has landed and is not pending implementation.
  - Item34 is marked approved for this goal, with validation still required
    before implementation.
  - Roadmap current active order now points to Item34/35 instead of the old
    Item30/29/31/28 goal.
- Untracked `reports/DEPENDENCY-PINNING-MAP.md` is a read-only reference map
  not required by the Item34/35 goal checkpoint; leave it uncommitted unless a
  later step explicitly needs it.
- Resource checkpoint: about `43GiB` free on `/System/Volumes/Data`; no cleanup
  needed before local doc/status work.

## 2026-07-01 - Item 36/37/38 local Maven resolution goal start

- Current Grazel branch/status at goal start:
  `arun/dependencies-refactor` at `7abc85d`
  (`Improve task progress message format`).
- Current PAX regression workspace:
  `/Users/arun.sampathkumar/work/pax-android`, branch `arun/grazel-refactor`,
  commit `d4105d1f64bd`, clean status. This committed PAX state is the
  regression baseline for Item 36-38. Do not commit PAX.
- Active execution order for this goal:
  status/docs truth checkpoint -> Item 36 -> Item 37 -> Item 38 ->
  simplify/adversarial review -> final Grazel/PAX gates.
- Approved spec/reference docs present and used as current truth:
  - `reports/DEPENDENCY-PINNING-MAP.md`
  - `reports/specs/2026-07-01-item36-local-maven-resolution-gradle-facts-design.md`
  - `reports/specs/2026-07-01-item37-local-maven-resolution-proxy-service-design.md`
  - `reports/specs/2026-07-01-item38-local-maven-resolution-pin-integration-design.md`
  - `reports/specs/ALTITUDE-LAYERING-ROADMAP.md`
- Durable decisions captured before code work:
  - Gradle fact hydration belongs in `gradle/dependencies`.
  - The proxy serves HTTP over hydrated facts only; it must not become a Gradle
    API facade.
  - `ArtifactPinner`/`PinMavenArtifactsTask` owns experiment-gated pin
    orchestration.
  - `MavenInstallLockfileReconstructor` remains pure Kotlin.
  - Item 38 must force/exercise cold or changed pinning, not only the
    already-pinned skip path.
  - Missing Gradle-resolved artifacts and known-component POM failures are hard
    failures; unknown parent/BOM metadata fallback is allowed only when counted.
- Resource checkpoint: about `41GiB` free on `/System/Volumes/Data`; no cache
  cleanup needed before local doc/status work.

## 2026-07-01 - Item 36 Gradle facts implementation checkpoint

- Implemented the first Item 36 fact layer:
  - `RepositoryAuth` plus proxy-only `RepositoryWithAuth` facts in
    `gradle/Repository.kt`.
  - `LocalMavenResolvedFacts`, `ResolvedArtifactIndexBuilder`,
    `ResolvedComponentIndexBuilder`, and lazy memoized `GradlePomFileResolver`
    in `gradle/dependencies/LocalMavenResolvedFacts.kt`.
- Important review decision:
  - The spec said to add auth directly to `Repository`, but that model is an
    existing `GenerateDownloaderConfigTask` input. To avoid fingerprinting
    header tokens in a task that does not use them, legacy `Repository`
    remains unchanged and auth is captured in the new proxy-only
    `RepositoryWithAuth` model. This preserves Item 36 intent while keeping
    existing generated-output and task-input behavior stable.
- Subagent audit result:
  - Altitude was acceptable: live Gradle types remain inside
    `gradle/dependencies`; proxy/pinner-facing surface is files, strings, and
    `PomFileResolver`.
  - Medium finding fixed by splitting `RepositoryWithAuth` from `Repository`.
  - Low test-depth finding addressed with external-vs-project component index
    coverage.
- Verification run:
  - `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.RepositoryAuthTest" --tests "com.grab.grazel.gradle.dependencies.LocalMavenResolvedFactsTest" --console=plain --no-daemon`
    passed.
  - Resource checkpoint before generated-output check: about `41GiB` free,
    memory pressured but no stale high-RAM `python3.12`; no cleanup needed.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed.
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`
    passed.
  - `git diff --check` passed.
- Generated-output check:
  - Local `migrateToBazel` left no generated BUILD/WORKSPACE/json drift beyond
    the intended source/test code changes.
- Remaining Item 36 risk:
  - `LocalMavenResolvedFactsBuilder` is not yet wired into Item 37/38 runtime
    flow. That is expected for Item 36; Item 37/38 must exercise real PAX cold
    pinning and record artifact/component/POM counts.

## 2026-07-01 - Item 37 proxy service implementation checkpoint

- Implemented dormant local Maven proxy service:
  - Ktor CIO server/client dependency on plugin main classpath, pinned to
    `2.3.13` for Kotlin `1.9.25` compatibility.
  - Pure HTTP `LocalMavenProxyServer` over hydrated facts.
  - Gradle `LocalMavenProxyService` wrapper under `gradle/dependencies`, exposed
    through Dagger but not requested by any task yet.
- Serve behavior covered by focused tests:
  - artifact file hits,
  - checksum generation,
  - lazy known POM resolver hits,
  - unknown POM origin fallback,
  - basic/header auth replay,
  - write-through cache,
  - missing resolved artifact hard-fail,
  - known POM failure hard-fail,
  - same-path concurrent origin miss de-duplication.
- Simplify pass was run for reuse, simplification, efficiency, and altitude:
  - Removed duplicate intermediate proxy repository DTOs while retaining a
    single explicit Gradle-service-model to HTTP-origin-model boundary mapping.
  - Added proxy-owned origin/auth DTOs to keep HTTP replay independent from
    Gradle repository models.
  - Fixed efficiency issues by using coroutine `Mutex`, removing per-path mutex
    entries after use, and streaming checksum digest input from files.
  - Rejected JDK HTTP rewrite and stats removal because the approved Item 37
    spec requires Ktor CIO and proxy stats for Item 38.
  - Rejected eager POM materialization because the approved Item 36/37 design
    requires lazy memoized POM resolution behind `PomFileResolver`; Item 38 must
    configure it during task execution and not leak Gradle live types into HTTP
    routes.
- Verification so far:
  - `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.migrate.dependencies.LocalMavenProxyServerTest" --console=plain --no-daemon`
    passed.
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`
    passed.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed after final
    cleanup and left no generated-output drift.
  - `git diff --check` passed after final cleanup.
- Item 37 remaining risk:
  - The service is intentionally dormant until Item 38 wires pinner execution.
    Item 38 must prove PAX cold pinning, stats output, and lockfile reuse with
    the experiment flag enabled.

## 2026-07-01 - Item 38 pin integration in progress checkpoint

- Active detailed log:
  - `reports/specs/execution-log/item38-local-maven-resolution-pin-integration.md`
- Persisted decisions:
  - PAX repository configuration must transfer to the proxy through Gradle's
    existing repository model and the proxy-only `RepositoryWithAuth` facts.
    Because the proxy reads Gradle repository/auth data, this should be
    compatible with PAX, but verification must prove it. No PAX-only repository
    hacks.
  - Use the Grazel repo itself as the first flag-on proxy test before full PAX
    verification.
  - The pinner must start/hydrate the proxy only after `shouldRunPinning`
    decides repin is required; skip path should remain cheap.
  - Root configurations for proxy fact hydration must come from the existing
    variant-owned root input planning in `WorkspaceDependencyInputsRegistrar`,
    not ad hoc configuration-name reconstruction in the pinner.
- Implemented so far:
  - Pure Kotlin lockfile reconstruction and WORKSPACE repository rewrite seams
    with focused tests.
  - Local pinning workspace helper.
  - Explicit pin worker `await()` before reconstruction.
  - Initial task/service wiring for flag-on local Maven resolution.
- Verification so far:
  - Focused Item38 tests passed:
    `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.migrate.dependencies.DefaultArtifactPinnerTest.assert maven install json generation is successful" --tests "com.grab.grazel.migrate.dependencies.LocalMavenPinningWorkspaceTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallWorkspaceRepositoryRewriterTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallLockfileReconstructorTest" --console=plain --no-daemon`
- Next gates:
  - Finish real pinner validation/error behavior.
  - Run Grazel flag-off generated-output gate.
  - Run Grazel flag-on cold/changed pinning as first proxy integration test.
  - Run simplify-pass after the large Item38 slice is locally green.
  - Then run PAX from the clean committed baseline; never commit PAX.

## 2026-07-01 - Item 38 forced proxy pin green

- Detailed log updated:
  - `reports/specs/execution-log/item38-local-maven-resolution-pin-integration.md`
- Root cause resolved:
  - RJE rejected reconstructed lockfiles because JSON null shasums were being
    read with `jsonPrimitive.content`, turning Starlark `None` into the string
    `"null"`. This propagated through metadata artifacts and changed many
    resolved hashes.
  - The reconstructor now preserves JSON null as Kotlin null/Starlark `None`
    for shasums and uses Bazel's documented first-entry `dict.popitem()`
    behavior when porting RJE's manual traversal.
- Evidence:
  - Forced Grazel flag-on repin passed:
    `./gradlew pinMavenArtifacts --console=plain --no-daemon --stacktrace`
    after intentionally corrupting only root `maven_install.json` hash and
    temporarily enabling `experiments.localMavenResolution`.
  - Successful run summary:
    `Local Maven resolution served 189 artifacts from Gradle index, 113 POMs
    from Gradle cache, 0 unknown metadata POMs from origin, 210 known alternate
    artifact misses, 0 artifact misses, in 19913ms`.
  - Focused tests passed:
    `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.migrate.dependencies.LocalMavenProxyServerTest" --tests "com.grab.grazel.migrate.dependencies.LocalMavenPinningWorkspaceTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallLockfileReconstructorTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallWorkspaceRepositoryRewriterTest" --tests "com.grab.grazel.tasks.internal.PinMavenArtifactsTaskTest" --console=plain --no-daemon`.
- Cleanup:
  - Restored generated lockfiles from `build/item38-lockfile-baseline`.
  - Removed temporary root `build.gradle` flag-on mutation; experiment remains
    default-off.
- Next gates:
  - simplify-pass for Item38.
  - PAX migrate/build/test from the committed clean baseline; do not commit PAX.

## 2026-07-01 - Item 38 local gates before simplify pass

- Flag-off local gate:
  - Resource checkpoint: about 34GiB free; no cleanup needed.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed with
    `experiments.localMavenResolution` default-off and pinning skipped as
    up-to-date.
  - Generated BUILD/WORKSPACE/json diff was empty after the run.
  - `git diff --check` passed.
- Unit test gate:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed.
- Active:
  - Running simplify-pass over Item38 via four subagents: reuse,
    simplification, efficiency, and altitude.

## 2026-07-01 - Item 38 repository signature correction

- Detailed log updated:
  - `reports/specs/execution-log/item38-local-maven-resolution-pin-integration.md`
- Root cause:
  - Earlier forced-pin work fixed null shasums, but RJE still rejected
    reconstructed lockfiles when the repository input signature was recomputed
    from lockfile output URL keys.
  - RJE signs `repository_ctx.attr.repositories`, i.e. the effective list of
    JSON repository input strings after Starlark variable expansion. Lockfile
    output repository keys omit configured repositories that served no artifacts
    and cannot be used for that input hash.
- Fix:
  - `GenerateRootBazelScriptsTask` now writes
    `build/grazel/maven/maven-install-repository-inputs.json`, a typed sidecar
    generated from the exact `MavenInstallData` set used to render `WORKSPACE`.
  - `PinMavenArtifactsTask` consumes that sidecar as an `@InputFile`.
  - Local lockfile reconstruction hashes the supplied per-repo canonical
    repository input strings and fails closed if an active repo is missing.
- Verification:
  - Focused tests passed:
    `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.migrate.dependencies.MavenInstallLockfileReconstructorTest" --tests "com.grab.grazel.migrate.dependencies.LocalMavenPinningWorkspaceTest" --tests "com.grab.grazel.migrate.dependencies.DefaultArtifactPinnerTest" --console=plain --no-daemon`.
  - Forced flag-on local proxy repin passed:
    `./gradlew pinMavenArtifacts --console=plain --no-daemon --stacktrace`.
  - RJE validation accepted all repos after reconstruction; summary:
    `189 artifacts from Gradle index, 113 POMs from Gradle cache, 0 origin POM
    fallbacks, 210 alternate artifact misses, 21433ms`.
- Cleanup:
  - Restored all checked-in lockfiles from `build/item38-lockfile-baseline`.
  - Removed the temporary root `build.gradle` flag-on mutation.
- Next:
  - Run default-off generated-output/local gates again after this correction.
  - Continue simplify/review and PAX verification for Item38.

## 2026-07-01 - Item 38 simplify-pass cleanup

- Ran the required four-agent simplify-pass over the current Item38 diff:
  reuse, simplification, efficiency, and altitude.
- Applied behavior-preserving findings:
  - `MavenRules.DefaultMavenRepository.build()` now uses the same
    credentialed URL helper as repository-input sidecar generation.
  - Local lockfile reconstruction now requires canonical repository input
    strings; the old production fallback that re-derived them from lockfile
    output repository keys was removed.
  - `LocalMavenPinningWorkspace` now scopes proxy WORKSPACE rewrites with
    `withProxyRepositories { ... }` so canonical restoration is owned by the
    workspace helper instead of caller-managed nullable state.
  - Gradle local Maven facts now expose neutral `metadataOnlyGavs`; the pinner
    layer translates those to override-target short IDs.
  - `GradleModuleCacheFileResolver` now caches Gradle module-cache file
    listings by coordinates to avoid repeated directory scans for alternate
    artifact path probes.
  - Reconstructor tests now share setup and pass canonical repository input
    facts explicitly.
- Deferred/rejected findings:
  - Full RJE lockfile hash/rendering is intentionally version-coupled for this
    experiment; correctness is guarded by focused tests and live RJE
    validation. A future item can wrap this as an explicit RJE-version seam.
  - Replacing the proxy WORKSPACE regex rewrite with a structured proxy render
    mode is a broader renderer/pinner seam refactor, not a safe simplify-pass
    change after the hash path was verified.
  - Moving local Maven facts to an upstream serialized task output is a larger
    task-boundary/cacheability design item. Current Item38 keeps the flag-on
    path inside pinning for a first-level proxy experiment.
  - Batching post-reconstruction RJE validation was deferred; the extra
    validation is intentionally strict while the experiment is still new.
- Verification:
  - `git diff --check` passed before focused tests.
  - Focused tests passed:
    `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.migrate.dependencies.MavenInstallLockfileReconstructorTest" --tests "com.grab.grazel.migrate.dependencies.LocalMavenPinningWorkspaceTest" --tests "com.grab.grazel.migrate.dependencies.DefaultArtifactPinnerTest" --tests "com.grab.grazel.gradle.dependencies.LocalMavenResolvedFactsTest" --console=plain --no-daemon`.
- Next:
  - Rerun default-off `migrateToBazel` generated-output gate.
  - Rerun forced flag-on local proxy pin gate because proxy lifecycle was
    simplified.
  - Then proceed to PAX verification from the clean committed PAX baseline.

## 2026-07-01 - Item 38 post-cleanup local gates

- Default-off generated-output gate:
  - Resource checkpoint: about 28GiB free; no cleanup performed.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed.
  - Generated BUILD/WORKSPACE/json/downloader/databinding diff was empty.
  - `git diff --check` passed.
- Forced flag-on local proxy pin gate:
  - Temporarily enabled `experiments.localMavenResolution` in root
    `build.gradle`.
  - Corrupted only `maven_install.json.__INPUT_ARTIFACTS_HASH.repositories` to
    force pinning.
  - `./gradlew pinMavenArtifacts --console=plain --no-daemon --stacktrace`
    passed after the scoped proxy rewrite cleanup.
  - RJE validation accepted reconstructed lockfiles for all active repos.
  - Summary:
    `Local Maven resolution served 189 artifacts from Gradle index, 113 POMs
    from Gradle cache, 0 unknown metadata POMs from origin, 210 known alternate
    artifact misses, in 24639ms`.
  - Restored temporary root `build.gradle` flag and all checked-in lockfiles
    from `build/item38-lockfile-baseline`.
  - Generated-output diff is empty again; `git diff --check` passed.
- Next:
  - Run broader local plugin tests if resources allow.
  - Run PAX migrate/build/test from the committed clean PAX baseline; do not
    commit PAX.

## 2026-07-01 - Item 38 broader local unit gate

- `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed
  after simplify-pass cleanup.
- Known noisy output observed but not new:
  - configuration-time resolution warnings in legacy tests,
  - compression fallback messages in extractor tests,
  - expected pinner out-of-date error output inside pinner tests.
- Next:
  - Run PAX verification from `/Users/arun.sampathkumar/work/pax-android`
    clean baseline.

## 2026-07-01 - Item 38 PAX migrate gate

- PAX baseline:
  - Repo: `/Users/arun.sampathkumar/work/pax-android`.
  - Branch: `arun/grazel-refactor`.
  - Commit: `d4105d1f64bd2f1930e1030e42647a214002c48d`.
  - Worktree was clean before running migrate.
- Resource checkpoint before migrate:
  - `/System/Volumes/Data` had about 27GiB free.
  - PAX `bazel-cache` was about 14G.
  - `/private/var/tmp/_bazel_arun.sampathkumar` was about 59G.
  - No cleanup before migrate; under the 90G private-root threshold.
- Command:
  - `cd /Users/arun.sampathkumar/work/pax-android`
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
- Result:
  - Passed in 13m 3s.
  - Pinning skipped as up to date.
  - Observed task timings:
    - `mergeDeclaredDependencyMetadata`: 2327 projects across 2327 shards in
      731ms.
    - `resolveWorkspaceDependencies`: 496 deps across 2451 roots in 23800ms.
    - `collectWorkspaceTargetTagPlan`: 17090 targets in 19594ms.
    - `analyzeVariantCompression`: 2096 projects in 56660ms.
    - `collectTargetMavenRepoReferences`: 2327 modules in 38402ms.
  - PAX `git status --short`, `git diff --check`, and `git diff --stat` were
    empty after migrate. The committed PAX baseline did not move.
- Disk cleanup before Bazel gates:
  - After migrate, `/System/Volumes/Data` dropped to about 11GiB free.
  - Ran `./bazel.sh shutdown` and `./bazel.sh clean --expunge` in PAX first.
  - The wrapper clean returned successfully but left many stale Bazel output
    bases; free space only rose to about 16GiB.
  - Confirmed stale Bazel servers from temporary JUnit workspaces, stopped
    them with `pkill -f "workspace_directory=/private/var/folders/.*/T/junit"`,
    then ran `bazelisk shutdown` in Grazel.
  - Removed stale `/private/var/tmp/_bazel_arun.sampathkumar`. Some protected
    files emitted permission errors, but cleanup reclaimed the stale output
    roots.
  - Final cleanup checkpoint: about 73GiB free and the private Bazel root down
    to about 294M. PAX `bazel-cache` was preserved.
- Next:
  - Run PAX APK build gate.
  - Run PAX focused Bazel test gate.
  - Keep PAX uncommitted and verify generated diff remains empty.

## 2026-07-01 - Item 38 PAX APK build gate

- Pre-build state:
  - PAX generated diff remained empty after migrate.
  - Disk was healthy after cleanup: about 73-75GiB free.
- Command:
  - `cd /Users/arun.sampathkumar/work/pax-android`
  - `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
- Result:
  - Passed.
  - First attempt hit a transient remote-cache missing blob for
    `food-root-gps-pax-debug-stubs_r.srcjar`.
  - The PAX wrapper retried automatically and the retry completed
    successfully.
  - Final successful invocation reported:
    - elapsed time: 471.860s.
    - 50452 total actions.
    - 42091 disk cache hits, 1634 remote cache hits.
- Post-build checks:
  - PAX `git status --short` was empty.
  - PAX `git diff --check` passed.
  - `/System/Volumes/Data` had about 75GiB free.
  - `/private/var/tmp/_bazel_arun.sampathkumar` was about 4.0G.
- Next:
  - Run focused PAX Bazel test gate.

## 2026-07-01 - Item 38 PAX focused test gate

- Command:
  - `cd /Users/arun.sampathkumar/work/pax-android`
  - `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test`
- Result:
  - Passed.
  - Reported `Executed 0 out of 3 tests: 3 tests pass`.
  - Elapsed time: 20.232s.
  - 11701 total actions, 9857 disk cache hits.
- Post-test checks:
  - PAX `git status --short` was empty.
  - PAX `git diff --check` passed.
  - Disk remained healthy: about 75GiB free and private Bazel root about 4.0G.
- Item38 PAX status:
  - PAX migrate passed.
  - PAX debug APK and android-test APK build passed.
  - PAX focused Bazel tests passed.
  - PAX committed generated baseline did not move.

## 2026-07-01 - Item 38 final local guards

- `git diff --check` passed.
- `git diff --check master...HEAD` passed.
- `reports/scripts/verify-default-task-graph.sh` passed.
- `reports/scripts/verify-sample-bucket-labels.sh` result:
  - Failed on the known documented pre-existing waiver:
    `WORKSPACE must not union one-sided appcompat exclude onto androidx.constraintlayout:constraintlayout`.
  - Investigation:
    - `WORKSPACE` was not dirty.
    - `HEAD:WORKSPACE` already contains both
      `androidx.appcompat:appcompat` and `androidx.core:core` exclusions on
      `androidx.constraintlayout:constraintlayout`.
    - This exact failure is already recorded in the execution logs and review
      guide as a pre-existing/local waiver, not an Item38 regression.
- `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed:
  - `bucketCount`: baseline 11, current 11.
  - `pinfileCount`: baseline 11, current 11.
  - `totalArtifactRoots`: baseline 1945, current 1945.
  - Per-repo artifact deltas: none.

## 2026-07-01 - Item 38 lockfile-only artifact fallback follow-up

- Maintainer constraint captured:
  - RJE/Bazel behavior must be mirrored from source, not guessed from generated
    JSON. The relevant source-backed anchors are `private/rules/coursier.bzl`,
    `V3LockFile.java`, `Coordinates.java`, and `StarlarkRepr.java`.
- Root cause after forced local proxy repin:
  - The remaining concrete `500` misses were exact active-lockfile artifacts,
    not arbitrary missing Gradle facts.
  - `collection-ktx` was already fixed by the workspace closure change; this
    follow-up addressed lockfile replay artifacts such as POM-packaging jar
    probes.
- Decision:
  - Keep the proxy strict. Only exact active-lockfile artifact paths may fall
    back to origin/cache; all other concrete artifact misses still fail closed.
- Verification:
  - Focused proxy/parser/workspace tests passed.
  - Forced local proxy `migrateToBazel --rerun-tasks` passed with
    `18 lockfile artifact fallbacks`, `23 metadata-only artifact fallbacks`,
    `0 artifact misses`, and no proxy `500` lines.
- Detailed notes:
  - See `reports/specs/execution-log/item38-local-maven-resolution-pin-integration.md`
    section `2026-07-01 lockfile-only artifact fallback follow-up`.

## 2026-07-01 - Item 38 merged-origin lockfile artifact correction

- Additional root cause:
  - Serving Gradle-cached POMs for any proxy repo index can make Coursier bind a
    component to `/r/0`; if the jar only exists in a later configured origin,
    RJE writes `jar: null` and `skipped`, removing package metadata.
  - This broke `kotlinx.parcelize.Parcelize` after forced local proxy repin.
- Source authority:
  - RJE `V3LockFile.java` and `private/rules/coursier.bzl` confirm skipped/null
    lockfile entries mean no downloaded file/SHA and no generated `http_file`.
- Fix:
  - Exact active-lockfile artifact paths now try the requested origin first and
    then the remaining configured origins in deterministic order.
  - The merged-origin lookup is not broad fallback: it only applies to exact
    active lockfile artifact paths; all other concrete misses still fail closed.
- Verification:
  - Focused proxy tests passed, including repo-0 miss/repo-1 hit and all-origin
    fail-closed cases.
  - Forced local proxy `migrateToBazel --rerun-tasks` passed with:
    157 Gradle artifact hits, 112 Gradle POM hits, 13 origin fallbacks,
    44 lockfile artifact fallbacks, 10 metadata-only artifact fallbacks,
    153 known alternate probes, and 0 artifact misses.
  - `maven_install.json`, `debug_maven_install.json`, and
    `android_test_maven_install.json` all have `nil_sha=0` and `skipped=0`.
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed.
  - Default-off `./gradlew migrateToBazel --console=plain --no-daemon` passed.
  - `bazelisk build //... --verbose_failures --remote_download_outputs=all`
    passed.
  - Default `bazelisk build //... --verbose_failures` still fails on the known
    symlinked-manifest remote-output materialization issue, not a dependency
    regression.
  - `reports/scripts/verify-default-task-graph.sh` and
    `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails on the known
    pre-existing appcompat/constraintlayout exclude-union waiver.

## 2026-07-01 - Item 38 default-output regression correction

- A PAX default migrate run found a real regression against the committed
  baseline:
  - Command:
    `cd /Users/arun.sampathkumar/work/pax-android && ./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`.
  - Result:
    the command passed, but generated diffs appeared in `WORKSPACE`,
    `debug_maven_install.json`, `android_test_maven_install.json`,
    `hms_maven_install.json`, and `test_maven_install.json`.
- Root cause:
  - The fixed-point closure expansion in `MavenInstallRootArtifacts.kt`
    changed default `maven_install.artifacts` roots even when
    `experiments.localMavenResolution` was off.
  - This violated Item 38's default/flag-off byte-identical requirement.
- Fix:
  - Restored `MavenInstallRootArtifacts.kt` to the previous non-fixed-point
    root-artifact expansion.
  - Removed the `WorkspacePlanBuilderTest` that asserted the leaked
    closure-forcing behavior.
- Verification:
  - Focused workspace/proxy tests passed after the fix.
  - Grazel default `./gradlew migrateToBazel --console=plain --no-daemon`
    passed and left generated output clean.
  - PAX default migrate rerun passed in 13m 40s and left the PAX committed
    baseline clean: `git status --short`, `git diff --stat`, and
    `git diff --check` produced no output.
  - PAX default APK build passed:
    `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
    completed successfully in 217.132s.
  - PAX focused Bazel tests passed:
    `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test`
    completed successfully in 16.364s; 3 test targets passed from cache.
  - PAX `git status --short` and `git diff --check` stayed clean after both
    Bazel gates.
  - Forced Grazel proxy repin was rerun after this correction by temporarily
    enabling `experiments.localMavenResolution` and corrupting only
    `maven_install.json.__INPUT_ARTIFACTS_HASH.repositories`.
  - The forced repin passed:
    `./gradlew pinMavenArtifacts --console=plain --no-daemon --stacktrace`.
  - Passing proxy summary:
    `Local Maven resolution served 156 artifacts from Gradle index, 110 POMs
    from Gradle cache, 0 origin fallbacks, 44 lockfile artifact fallbacks,
    9 metadata-only artifact fallbacks, 153 known alternate artifact probes,
    0 artifact misses, 732456897 bytes served, in 19442ms`.
  - Cleanup after forced repin removed the temporary experiment flag; generated
    `WORKSPACE` and `*_maven_install.json` outputs remained clean.
- Decision:
  - Item 38 must not change default artifact-root generation. Any proxy-only
    lockfile serving/reconstruction behavior must be kept behind
    `experiments.localMavenResolution`.

## 2026-07-01 - Regression guard before continuing Item 38

- Maintainer reminder accepted: do not regress already-working code before the
  proxy experiment began.
- Fresh status check:
  - Grazel is on `arun/dependencies-refactor` at `2cbac7d` with only Item 38
    proxy experiment files and logs dirty.
  - PAX is clean on `arun/grazel-refactor` at
    `d4105d1f64bd2f1930e1030e42647a214002c48d`.
- Guardrail:
  - The committed PAX baseline is the regression oracle for flag-off behavior.
  - Any PAX generated-output diff, PAX APK/test failure, or Grazel generated
    diff outside the explicitly enabled proxy experiment is stop-and-investigate.
- Current experimental blocker:
  - PAX flag-on forced proxy migrate still fails in root `maven` on
    `com.grab.rtc:sinch:6.25.8`; the default flag-off PAX migrate/build/test
    path was already reverified clean after the default-output correction.

## 2026-07-01 - Item 38 repository-input hook decision

- Fresh guard:
  - Grazel remains at `2cbac7d` with only Item 38 proxy experiment files and
    logs dirty.
  - PAX is clean at committed baseline
    `d4105d1f64bd2f1930e1030e42647a214002c48d`.
- PAX flag-on forced proxy migrate after the null-shasum/non-POM active
  lockfile fix moved past the earlier `com.grab.rtc:sinch:6.25.8` HTTP 500.
  The proxy served the POM/AAR/JAR. The next blocker is RJE repository input
  signature validation.
- Root cause:
  - Grazel writes `build/grazel/maven/maven-install-repository-inputs.json`
    from its Maven install model including `DAGGER_REPOSITORIES`.
  - PAX build logic later removes `+ DAGGER_REPOSITORIES` from final
    `WORKSPACE`.
  - The pinner reconstructs lockfiles from the stale sidecar, so root `maven`
    gets repository hash `-1395933409` while final PAX RJE attributes expect
    `-2080637180`.
- Decision:
  - Do not parse generated `WORKSPACE` as pinner feedback.
  - Do not add a PAX-only hack in Grazel.
  - Add a typed customer-side Maven-install hook that omits named external
    repository variables for a named `maven_install` repo. The filtered model
    must drive both WORKSPACE rendering and the repository-input sidecar.

## 2026-07-01 - Item 38 customer hook and lockfile baseline preservation

- Customer-side repository-input hook implemented:
  - `mavenInstall.excludeExternalRepositoryVariables(repoName, variableNames...)`
    filters external repository variables for a named `maven_install`.
  - The filtered `MavenInstallData` now drives both WORKSPACE rendering and
    `build/grazel/maven/maven-install-repository-inputs.json`; the pinner no
    longer needs to infer final repository inputs from generated Starlark.
  - Focused tests cover named-repo filtering and repo-name scoping.
- PAX experiment with temporary local config:
  - Added local-only PAX hook:
    `excludeExternalRepositoryVariables("maven", "DAGGER_REPOSITORIES")`.
  - Temporarily enabled `experiments.localMavenResolution` and corrupted the
    root repository hash to force repin.
  - Forced PAX `migrateToBazel --rerun-tasks` passed and the sidecar for root
    `maven` contained only the two final PAX repositories.
- Follow-up fixes from that run:
  - Lockfile URL reconstruction now writes canonical repository URLs with a
    trailing slash for lockfile artifact URLs. This fixes bad paths like
    `mobile--androidandroidx/...` while leaving WORKSPACE repository strings
    unchanged.
  - Reconstructor now accepts an optional baseline lockfile snapshot and
    preserves baseline shasums/skipped entries when artifact facts are
    otherwise unchanged. This avoids replacing RJE's previously accepted
    lockfile metadata with semantically equivalent but byte-different values.
  - Without a baseline, cold/missing-lockfile behavior still adds POM-packaging
    artifacts to `skipped` so cold proxy pinning remains complete.
- Verification:
  - Focused tests passed:
    `MavenInstallArtifactsCalculatorTest`,
    `MavenInstallLockfileReconstructorTest`,
    `LocalMavenPinningWorkspaceTest`, and `DefaultArtifactPinnerTest`.
  - Grazel default `./gradlew migrateToBazel --console=plain --no-daemon`
    completed before this log update and left generated BUILD/WORKSPACE/
    maven-install outputs clean.
  - `git diff --check` passed.
- Next gate:
  - Re-run the PAX flag-on forced proxy migrate with the customer hook and
    baseline-preservation fix. Expected outcome is no localhost residue, zero
    artifact misses, and byte-clean generated lockfiles except for temporary
    local PAX config edits that must be restored.

## 2026-07-01 - Item 38 PAX forced proxy run green

- PAX forced local-proxy validation completed with temporary local PAX config:
  `experiments.localMavenResolution=true`,
  `excludeExternalRepositoryVariables("maven", "DAGGER_REPOSITORIES")`, and a
  deliberately corrupted root `maven_install.json` repository hash.
- Command:
  `cd /Users/arun.sampathkumar/work/pax-android && ./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`.
- Result:
  - `BUILD SUCCESSFUL in 12m 45s`, `4749 actionable tasks: 4749 executed`.
  - Proxy summary: `788` Gradle artifact hits, `808` Gradle POM hits, `0`
    origin fallbacks, `97` lockfile artifact fallbacks, `0` metadata-only
    artifact fallbacks, `1710` known alternate probes, `0` artifact misses,
    `1921031937` bytes served, `108703ms`.
  - `mergeDeclaredDependencyMetadata`: 2327 projects/shards in 683ms.
  - `resolveWorkspaceDependencies`: 496 deps across 2451 roots in 27171ms.
- Generated-output check:
  - Only `build.gradle` was dirty from the two temporary local config lines.
  - Active `WORKSPACE` and `*_maven_install.json` files were byte-clean against
    the committed PAX baseline.
  - No `127.0.0.1` or `localhost` residue was found in generated outputs.
  - `git diff --check` passed.
- Next:
  - Restore temporary PAX config, keep PAX uncommitted/clean, then run final PAX
    APK build and focused Bazel test gates.

## 2026-07-01 - Item 38 PAX APK build gate

- Restored the temporary PAX `build.gradle` config after the forced proxy
  migrate; PAX was clean before the Bazel gate.
- Command:
  `cd /Users/arun.sampathkumar/work/pax-android && ./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`.
- Result:
  build completed successfully in `214.653s`; Bazel reported `1 total action`.
- Next:
  - Run focused PAX Bazel test gate and final PAX cleanliness checks.

## 2026-07-01 - Item 38 PAX final gates passed

- Focused PAX test command:
  `cd /Users/arun.sampathkumar/work/pax-android && ./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test`.
- Result:
  - Build/test completed successfully in `17.093s`.
  - `Executed 0 out of 3 tests: 3 tests pass`.
- Final PAX cleanliness:
  - `git status --short` returned no output.
  - `git diff --check` returned no output.
  - No `127.0.0.1` or `localhost` residue was found in `WORKSPACE` or active
    `*_maven_install.json` files.
- PAX was not committed or pushed.

## 2026-07-01 - Item 38 Grazel local gates before simplify/review

- Grazel generated-output cleanliness:
  - `git diff --name-only -- '*.bazel' WORKSPACE '*maven_install.json' '*_maven_install.json' bazel_downloader.cfg`
    returned no output.
  - `git diff --check` returned no output.
  - `git diff --check master...HEAD` returned no output.
- Task/size scripts:
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed:
    bucketCount `11`, pinfileCount `11`, totalArtifactRoots `1945`, all
    unchanged from the committed PAX baseline.
  - `reports/scripts/verify-sample-bucket-labels.sh` failed on the known
    pre-existing waiver:
    `WORKSPACE must not union one-sided appcompat exclude onto androidx.constraintlayout:constraintlayout`.
- Grazel unit/local generation gates:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed
    in `43s`.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed in `9s`.
  - Local timing from that migrate:
    `Resolved 45 deps across 54 roots in 108ms`,
    `Collected target tags for 32 targets in 66ms`,
    `Analyzed variant compression for 2 projects in 61ms`,
    `Collected target references across 10 modules in 158ms`.
- Review status:
  - Requested simplify-pass review agents for reuse, simplification,
    efficiency, and altitude.
  - Requested adversarial correctness/merge-risk review agent.
  - Do not stop until real findings are fixed or explicitly rejected with
    concrete code evidence and post-fix verification is rerun.

## 2026-07-02 - Item 38 post-review hardening in progress

- Adversarial/simplify review findings acted on so far:
  - Removed the unbounded request-time Gradle module-cache artifact fallback
    from `LocalMavenProxyServer`. The proxy can now serve concrete artifacts
    only from the precomputed explicit artifact index, metadata-only fallback,
    or active-lockfile fallback path.
  - `MavenArtifactFileResolver` was removed from the proxy service/server
    boundary; Gradle module cache lookup remains only in the fact builder that
    pre-indexes known GAVs.
  - Made lockfile baseline merge fail closed when current proxy output changes
    shasums for otherwise-identical artifact metadata, or when current output
    skips an artifact that existed in the baseline.
  - Fixed credentialed repository rewrite: `MavenInstallRepositoryRewrite` now
    carries `proxyToCanonicalUrl` for final reconstruction and
    `canonicalToProxyUrl` aliases for temporary `WORKSPACE` rewrite. The
    canonical URL restored to lockfiles is selected from generated repository
    input specs, so `includeCredentials=true/false` follows the actual render.
  - Cleaned proxy-source shape per Item 24 style: replaced policy-heavy hidden
    receiver helpers in the proxy/lockfile files with explicit helper
    parameters and reused `MavenCoordinates.mavenRelativePaths` for lockfile
    artifact path construction.
- Focused verification:
  - `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.LocalMavenProxyServiceTest" --tests "com.grab.grazel.migrate.dependencies.LocalMavenPinningWorkspaceTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallLockfileArtifactPathsTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallLockfileReconstructorTest" --tests "com.grab.grazel.migrate.dependencies.LocalMavenProxyServerTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallArtifactsCalculatorTest" --console=plain --no-daemon`
    passed in `23s`.
- Still pending before stop:
  - Re-run forced PAX local-proxy migrate because stricter shasum/skipped
    behavior may expose real drift that the earlier baseline preservation hid.
  - Re-run PAX build/test gates if forced migrate stays clean.
  - Re-run simplify/adversarial review after these fixes; do not stop before
    findings are fixed or logged with concrete evidence.

## 2026-07-02 - Item 38 forced PAX proxy validation after hardening

- First forced PAX proxy rerun:
  - Command:
    `cd /Users/arun.sampathkumar/work/pax-android && ./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`.
  - Temp PAX setup:
    `experiments.localMavenResolution.set(true)`,
    `excludeExternalRepositoryVariables("maven", "DAGGER_REPOSITORIES")`,
    and one forced `maven_install.json` repository hash edit.
  - Result:
    failed in `pinMavenArtifacts` after about `13m`.
  - Root cause:
    `com.grab.rtc:sinch` is a rules_jvm_external skipped/null-file companion
    coordinate while `com.grab.rtc:sinch:aar` is the concrete artifact. The
    proxy served the POM/AAR/JAR/checksums successfully, but the new
    fail-closed skipped-baseline guard treated any skipped baseline artifact as
    missing.
  - Focused reproduction note:
    running standalone `pinMavenArtifacts` is not equivalent on PAX; it failed
    earlier in `collectWorkspaceTargetTagPlan` due release/test build-type
    matching. Use full `migrateToBazel` for this proxy path.
- Fix:
  - `MavenInstallLockfileReconstructor` now fails only when a current skipped
    baseline artifact is absent from current `artifacts`.
  - It still validates shasums for present artifact records.
  - Added a regression test for the skipped plain coordinate with `jar: null`
    plus concrete `:aar` companion shape.
- Verification:
  - `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.migrate.dependencies.MavenInstallLockfileReconstructorTest" --console=plain --no-daemon`
    passed in `20s`.
  - Full forced PAX proxy migrate passed:
    `BUILD SUCCESSFUL in 12m 10s`, `4749 actionable tasks: 4749 executed`.
  - Proxy summary:
    `761 artifacts from Gradle index`, `808 POMs from Gradle cache`,
    `0 origin fallbacks`, `123 lockfile artifact fallbacks`,
    `0 metadata-only artifact fallbacks`, `1713 known alternate artifact probes`,
    `0 artifact misses`, `1921023543 bytes served`, `97636ms`.
  - No localhost residue found in tracked `WORKSPACE`/maven-install files.
  - PAX temp tracked edits were restored; `git status --short`,
    `git diff --check`, and localhost scan returned clean/no matches.
- Still pending before stop:
  - Run normal PAX migrate/build/test guard from the clean committed baseline.
  - Re-run final simplify/adversarial review after the post-review fixes.

## 2026-07-02 - Item 38 clean-baseline PAX guard after proxy hardening

- Normal PAX migrate from restored committed baseline passed:
  `BUILD SUCCESSFUL in 9m 17s`, `4749 actionable tasks: 4749 executed`.
- PAX APK guard passed:
  `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
  completed successfully in `232.395s`.
- PAX focused unit-test guard passed:
  `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test`
  reported `Executed 0 out of 3 tests: 3 tests pass`.
- PAX cleanliness:
  `git status --short` and `git diff --check` returned no output; localhost/proxy
  residue scan over tracked `WORKSPACE` and maven-install JSON files returned no
  matches.
- Resource checkpoint before continuing:
  disk had about `49Gi` free on `/System/Volumes/Data`; no cleanup performed.
- Still pending before stop:
  - Run Grazel local gates after the post-review fixes.
  - Perform final altitude/source-shape scan for the proxy feature.
  - Re-run simplify/adversarial review and fix or document findings.

## 2026-07-02 - Item 38 local Grazel guard after proxy hardening

- `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed in
  `45s`.
- `./gradlew migrateToBazel --console=plain --no-daemon` passed in `14s`.
  Useful timing lines:
  `mergeDeclaredDependencyMetadata` `16ms`,
  `resolveWorkspaceDependencies` `90ms`,
  `collectWorkspaceTargetTagPlan` `76ms`,
  `analyzeVariantCompression` `66ms`,
  `collectTargetMavenRepoReferences` `147ms`.
- `reports/scripts/verify-default-task-graph.sh` passed.
- `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed:
  PAX bucket count `11`, pinfile count `11`, total artifact roots `1945`, all
  unchanged from baseline SHA `d4105d1f64bd2f1930e1030e42647a214002c48d`.
- `git diff --check` and `git diff --check master...HEAD` passed.
- Known pre-existing/accepted failing guard:
  `reports/scripts/verify-sample-bucket-labels.sh` still reports
  `WORKSPACE must not union one-sided appcompat exclude onto androidx.constraintlayout:constraintlayout`.
- Still pending before stop:
  - Final altitude/source-shape scan for the proxy feature.
  - Final simplify/adversarial review and post-fix verification if findings are
    accepted.

## 2026-07-02 - Item 38 altitude review fixes

- Subagent altitude/correctness review findings accepted and fixed:
  - POM serving no longer uses a live server callback into Gradle/cache. The
    fact layer now builds an explicit `pomIndex`; the server only reads that map.
  - Active-lockfile artifact fallback no longer searches every origin. It uses
    only the requested proxy origin and fails closed when that origin misses.
  - Active-lockfile and metadata-only fallbacks can no longer mask missing
    artifacts for known Gradle components; known concrete artifact misses now
    fail before those fallback classes.
  - External repository URLs from generated repository inputs are now typed data
    in `MavenInstallRepositoryInputs.repositoryUrlsByName`; proxy origin/rewrite
    planning includes external URLs instead of parsing render JSON or leaving
    external variables unproxied.
  - Removed remaining hidden receiver helpers in the touched
    `LocalMavenResolvedFacts` path.
- Focused verification after these fixes:
  - `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.LocalMavenResolvedFactsTest" --tests "com.grab.grazel.gradle.dependencies.LocalMavenProxyServiceTest" --tests "com.grab.grazel.migrate.dependencies.LocalMavenProxyServerTest" --tests "com.grab.grazel.migrate.dependencies.LocalMavenPinningWorkspaceTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallLockfileReconstructorTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallLockfileArtifactPathsTest" --console=plain --no-daemon`
    passed in `17s`.
- Deferred/rejected from review for this slice:
  - Active-lockfile fallback still reads existing lockfiles as compatibility debt,
    but it is now bounded to non-known components and the requested origin.
  - Replacing the lockfile hash reconstruction's internal mutable Starlark model
    is larger than this post-review fix; keep it as a follow-up unless final
    simplify/adversarial review classifies it as blocking.
- Still pending before stop:
  - Re-run local generation and PAX guards because proxy origin planning changed.
  - Re-run final simplify/adversarial review after those guards.

## 2026-07-01 - Item 38 proxy altitude/style follow-up

- Additional altitude/style review fixes landed after the prior checkpoint:
  - Maven repository path parsing moved to neutral `com.grab.grazel.maven`.
  - Lockfile hash reconstruction now uses a sealed `StarlarkValue` model instead
    of raw `Any?`/`Map<String, Any?>`.
  - Metadata-only `override_targets` removal is scoped to the
    `override_targets` block.
  - Metadata-only GAV classification is now Gradle-component-only; configured
    extra override artifacts remain fail-closed unless concrete artifacts are
    indexed.
  - Added metadata-only checksum and active-lockfile filtering tests.
- Focused proxy/facts/lockfile test suite passed in `14s`.
- Review decisions retained:
  - Eager `pomIndex` is intentional to keep the HTTP server free of live
    Gradle/cache callbacks.
  - Active lockfile fallback and baseline lockfile reconstruction remain bounded
    compatibility debt for rules_jvm_external/PAX behavior and must stay
    verified by forced PAX repin.
- Still pending:
  - Forced PAX local-proxy repin after these latest changes.
  - Restore PAX temp changes after forced repin.
  - Normal PAX/Grazel gates, then simplify/adversarial review.

## 2026-07-02 - Item 38 proxy contract correction after forced PAX failure

- Forced PAX local-proxy repin exposed a real proxy contract bug:
  `knownArtifactGavs` conflated pinnable roots/final artifacts with
  Gradle-resolved concrete component artifacts, so lockfile-only artifacts in
  `test_maven`/`android_test_maven` failed with proxy `HTTP 500` before exact
  active-lockfile fallback could serve them.
- Fixed by removing `knownArtifactGavs` from the proxy contract. Concrete
  hard-fail is now based on `knownComponentGavs` only:
  known Gradle components still fail closed if their concrete artifact is
  missing; non-root pinnable artifacts are still fail-closed unless their exact
  active lockfile path authorizes the bounded requested-origin fallback.
- Also fixed review/readability points:
  - introduced `LocalMavenResolutionPinContextFactory` instead of a raw
    function type;
  - changed metadata-only override-target filtering into an explicit scanner.
- Verification:
  - Focused proxy/facts/pinner tests passed in `20s`, and after the scanner
    cleanup passed again in `17s`.
  - Forced PAX local-proxy repin passed in `12m 55s` with `0` artifact misses:
    `762` Gradle-index artifact hits, `808` Gradle-index POM hits, `71`
    lockfile artifact fallbacks, `52` metadata-only artifact fallbacks, `1710`
    alternate artifact probes, `1921510632` bytes, `111930ms` proxy time.
  - Other PAX timings from the same forced run:
    declared metadata fanout `2327` projects/`2327` shards in `622ms`;
    dependency resolution `496` deps/`2451` roots in `25296ms`;
    target tag collection `17090` targets in `17399ms`.
  - PAX temp experiment/lockfile perturbations restored; PAX `git status` is
    clean.
- Still pending:
  - Normal PAX baseline migrate/build/test gates.
  - Broad Grazel gates after final altitude/style fixes.
  - Final simplify/adversarial review.

## 2026-07-02 - Item 38 proxy altitude/style follow-up

- Mapped the proxy feature by layer:
  Gradle dependency layer owns facts/cache/POM indexing plus proxy lifecycle and
  HTTP serving; task layer adapts root configuration facts and repository input
  files into proxy configuration; pinner/migrate layer owns temporary WORKSPACE
  rewrite, pin script execution, lockfile reconstruction, validation, and
  logging; neutral Maven path syntax sits in `com.grab.grazel.maven`.
- Fixed confirmed leaks/style issues:
  - moved proxy server/auth/origin/stats types into `gradle.dependencies`;
  - removed the risky unused service `baseUrl()` entrypoint;
  - made `LocalMavenProxyService.configure(...)` return mappings from the same
    origin plan used by the configured server;
  - added server-origin tracking to prevent stale mapping reuse;
  - renamed the generated-output canonical URL field;
  - replaced raw POM lookup lambdas with `PomArtifactQuery`/`PomCacheLookup`;
  - added explicit `canonicalMavenRelativePath(...)`;
  - removed a changed receiver-style Maven repository helper.
- Deferred with rationale:
  - metadata-only `override_targets` removal still scans temporary WORKSPACE
    text. Proper fix is a typed temporary pin-workspace render path, not another
    parser tweak. Current scanner is tested and kept to avoid destabilizing the
    already verified forced PAX proxy run.
- Verification:
  - Focused proxy/facts/pinner tests passed in `19s` after package/API move and
    `23s` after style cleanup.
- Additional cleanup:
  - moved `LocalMavenResolutionPinContext` out of the workspace helper and next
    to the pinner factory;
  - replaced parallel repository-input spec/url maps with typed
    `MavenInstallRepositoryInput(repositoryInputSpec, canonicalUrl)` entries.
    RJE hash input strings are preserved exactly, while canonical URLs now ride
    with the corresponding spec.
  - Focused proxy/facts/pinner/calculator tests passed in `28s`.
- Still pending:
  - Normal PAX baseline migrate/build/test gates.
  - Broad Grazel gates.
  - Final simplify/adversarial review.

## 2026-07-02 - Item 38 simplify-pass follow-up

- Ran four-angle simplify-pass review over the Item 38 proxy/pinner slice.
- Accepted fixes:
  - reused `RepositoryAuth` instead of duplicate proxy auth model;
  - reused `mavenInstallJsonName(...)`;
  - reused `MavenCoordinates` for artifact conversion and metadata short IDs;
  - reused `repositoryInputSpec(...)` in tests;
  - parsed concrete proxy GAV once per request branch;
  - shared active-lockfile iteration;
  - removed dead concrete-path wrapper;
  - computed supported Maven repositories once per calculator invocation.
- Rejected/deferred:
  - keep named callback seams instead of raw function types;
  - keep neutral proxy mappings instead of returning pinner rewrite data from
    the Gradle service;
  - keep exact RJE repository input strings paired with typed URLs;
  - defer larger performance/architecture items: batched POM queries, batched
    pin-status probes, origin streaming, checksum memoization, temp-file
    baseline lockfiles, lazy backup hashes, typed temporary WORKSPACE render,
    typed external repo bundles, and versioned RJE lockfile adapter.
- Verification:
  - focused proxy/facts/pinner/calculator suite passed in `29s`;
  - full `:grazel-gradle-plugin:test` passed in `39s` after first simplify
    wave.
- Still pending:
  - rerun focused suite after final simplify patch set;
  - normal PAX baseline migrate/build/test gates;
  - broad Grazel gates;
  - final adversarial review.

## 2026-07-02 - Item 38 post-altitude verification gates

- PAX normal baseline gate:
  - Resource check before/around PAX gates: about `28-32GiB` free; PAX Bazel
    private output root about `17G`; PAX `bazel-cache` about `14G`; no cleanup
    was needed.
  - `/Users/arun.sampathkumar/work/pax-android ./gradlew migrateToBazel
    --no-daemon --console=plain --stacktrace --rerun-tasks` passed in
    `11m 43s`.
  - Normal migrate timings:
    declared metadata fanout `2327` projects/`2327` shards in `625ms`;
    dependency resolution `496` deps/`2451` roots in `23823ms`;
    target tag collection `17090` targets in `15960ms`.
  - PAX generated output stayed on the committed baseline: `git status
    --short` and `git diff --shortstat` were empty after migrate.
  - `/Users/arun.sampathkumar/work/pax-android ./bazel.sh build
    --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed in `235.478s`.
  - `/Users/arun.sampathkumar/work/pax-android ./bazel.sh test
    --test_output=errors //app-utils:app-utils-gps-pax-debug-test
    //app-test:app-test-gps-pax-debug-test
    //application-initializer:application-initializer-gps-pax-debug-test`
    passed in `20.240s`; `3 tests pass`.
  - PAX `git diff --check` passed and PAX `git status --short` remained clean.
- Grazel gates:
  - Focused proxy/facts/pinner/calculator suite passed in `29s` after final
    simplify fixes.
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed
    in `44s` after final altitude/style fixes.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed in `12s`.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed:
    bucket count `11`, pinfile count `11`, total artifact roots `1945`, all
    unchanged from PAX baseline `d4105d1f64bd2f1930e1030e42647a214002c48d`.
  - `git diff --check` and `git diff --check master...HEAD` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails with the
    known sample assertion: `WORKSPACE must not union one-sided appcompat
    exclude onto androidx.constraintlayout:constraintlayout`. This is not new
    to the proxy slice and remains an accepted pre-existing waiver.
- Still pending:
  - final adversarial review after the verified gates;
  - apply any confirmed review findings, rerun impacted checks, and update this
    log before final response.

## 2026-07-02 - Item 38 final altitude review and verification

- Final read-only review status:
  - Altitude/style review returned four actionable findings and one deferred
    debt item.
  - Correctness and verification review agents did not return before timeout;
    both were closed and should not be treated as sign-offs.
- Accepted altitude/style fixes:
  - Moved local Maven pinner-context construction out of `PinMavenArtifactsTask`
    into `LocalMavenResolutionPinContextAdapter`; the task now wires Gradle
    providers/services and the pinner layer owns pin-context shaping.
  - Replaced pinner dependency on `LocalMavenProxyStats` with neutral
    `LocalMavenResolutionStats`/`LocalMavenResolutionStatsProvider`.
  - Moved `MavenInstallRepositoryRewrite` into its own pinner-layer file and
    kept proxy mappings neutral in the Gradle proxy service.
  - Promoted lockfile artifact-key parsing into
    `MavenInstallLockfileArtifactKey` and reused it for active lockfile path
    extraction, POM-packaging detection, and resolved-artifact hash suffixes.
  - Replaced new receiver-style conversion helpers in the proxy path with
    explicit named functions to match the branch style preference for visible
    parameter names over hidden receivers.
  - Fixed `GradlePomFileIndexBuilder` so additional GAVs can still fall back to
    Gradle's module-cache POM files when they are not present as resolved graph
    component IDs.
- Deferred, explicitly:
  - `LocalMavenPinningWorkspace` still edits temporary generated WORKSPACE text
    to prune metadata-only override targets during proxy pinning. This remains
    bounded to the temporary pinning workspace; the altitude-correct follow-up is
    typed temporary pin-workspace rendering, not more string surgery.
- Final Grazel verification after these fixes:
  - Focused proxy/facts/pinner/calculator suite passed in `23s`.
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed
    in `37s`.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed in `9s`.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed:
    bucket count `11`, pinfile count `11`, total artifact roots `1945`, all
    unchanged.
  - `git diff --check` and `git diff --check master...HEAD` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails with the
    known appcompat/constraintlayout exclude assertion; accepted pre-existing
    waiver, not proxy-related.
- Final PAX verification after the last style/altitude fixes:
  - PAX baseline branch/SHA: `arun/grazel-refactor`
    `d4105d1f64bd2f1930e1030e42647a214002c48d`.
  - Resource checks before final PAX run showed about `27GiB` free; after final
    migrate about `21GiB` free. No cache cleanup was performed.
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
    --rerun-tasks` passed in `11m 5s`.
  - Final migrate timings:
    declared metadata fanout `2327` projects/`2327` shards in `418ms`;
    dependency resolution `496` deps/`2451` roots in `22294ms`;
    target tag collection `17090` targets in `17104ms`;
    variant compression `2096` projects in `46640ms`;
    target reference collection `2327` modules in `33425ms`.
  - PAX generated output stayed clean: `git status --short`,
    `git diff --shortstat`, and `git diff --check` were empty/passed.
  - `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed in `225.308s`.
  - `./bazel.sh test --test_output=errors
    //app-utils:app-utils-gps-pax-debug-test
    //app-test:app-test-gps-pax-debug-test
    //application-initializer:application-initializer-gps-pax-debug-test`
    passed in `16.851s`; `3 tests pass`.

## 2026-07-02 - Item 38 forced sample proxy repin follow-up

- The prior Item 38 "final" status is superseded until post-fix proxy/PAX
  verification is complete. Normal flag-off PAX was green, but the forced sample
  local-proxy repin exposed a repository-selection edge case.
- Repro:
  - Temporarily enabled `experiments.localMavenResolution` in root
    `build.gradle` and perturbed root `maven_install.json` hash to force pinning.
  - `./gradlew pinMavenArtifacts --console=plain --no-daemon --stacktrace`
    failed for root `@unpinned_maven`.
- Root cause:
  - Active lockfile artifact origin fallback converted a single repository miss
    into HTTP `500`, preventing Coursier from trying later repositories.
  - After correcting that, repo-independent write-through cache/POM serving
    still let repo `0` appear to own artifacts that belong to repo `1`.
- Fix:
  - Active lockfile origin fallback now preserves repository miss semantics.
  - Origin write-through cache is scoped by repository index.
  - Gradle cached POMs are served without origin lookup only when the proxy can
    also serve concrete artifact bytes from the Gradle artifact index.
  - Active lockfile facts now expose both paths and GAVs; lockfile GAVs feed the
    Gradle/cache fact builder for module-cache artifact serving.
- Verification so far:
  - Focused proxy + lockfile path tests passed.
  - Forced sample proxy repin passed in
    `build/item38-debug/sample-forced-after-repo-scoped-proxy.log`.
  - Forced sample proxy summary: `163` artifact hits, `159` POM hits, `106`
    origin fallbacks, `22` lockfile artifact fallbacks, `15` metadata-only
    artifact fallbacks, `180` alternate probes, `0` artifact misses,
    `731322798` bytes served, `33720ms`.
  - No `localhost`/`127.0.0.1` leaks in generated sample files.
  - Temporary forced-run edits were removed. Next: rerun impacted Grazel gates
    and forced PAX proxy verification.

## 2026-07-02 - Item 38 post-adversarial altitude/source-shape pass

- Ran final read-only altitude and source-shape reviews over the local Maven
  proxy/pinner feature.
- Accepted fixes:
  - moved `LocalMavenResolutionPinContextAdapter` out of `migrate/dependencies`
    into `gradle/dependencies`, so Gradle proxy/fact hydration does not live in
    the pinner model package;
  - replaced eager POM indexing with lazy memoized `PomFileResolver` owned by
    the Gradle facts layer and consumed by the proxy only for Gradle-backed
    POMs;
  - scoped temporary WORKSPACE repository URL rewriting to
    `maven_install(... repositories = [...])` blocks only.
- Conscious remaining debt:
  - temporary WORKSPACE mutation is still present in
    `LocalMavenPinningWorkspace`; solving it correctly needs a typed temporary
    pin workspace or isolated Bazel workspace, not a larger regex patch;
  - `maven-install-repository-inputs.json` remains the file-backed model
    transport from root WORKSPACE generation to pinning. It is parsed in task
    action, not configuration phase.
- Focused verification after these fixes passed:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.gradle.dependencies.LocalMavenResolvedFactsTest" --tests
  "com.grab.grazel.gradle.dependencies.LocalMavenProxyServerTest" --tests
  "com.grab.grazel.gradle.dependencies.LocalMavenProxyServiceTest" --tests
  "com.grab.grazel.migrate.dependencies.MavenInstallWorkspaceRepositoryRewriterTest"
  --tests "com.grab.grazel.tasks.internal.PinMavenArtifactsTaskTest"
  --console=plain --no-daemon` in `22s`.
- Still pending before final/commit: forced sample proxy repin, broad Grazel
  gates, forced PAX proxy verification, and normal PAX flag-off build/test
  guard after the lazy POM/scoped rewriter changes.

### Forced sample proxy repin after altitude fixes

- Temporarily enabled `experiments.localMavenResolution` and perturbed root
  `maven_install.json` repository hash to force sample repinning.
- `./gradlew pinMavenArtifacts --console=plain --no-daemon --stacktrace >
  build/item38-debug/sample-forced-after-lazy-pom-altitude.log 2>&1` passed in
  `39s`.
- Proxy summary: `163` artifact hits, `159` POM hits, `0` origin fallbacks,
  `22` lockfile artifact fallbacks, `15` metadata-only artifact fallbacks,
  `180` alternate probes, `0` artifact misses, `731322798` bytes served,
  `29855ms`.
- No `localhost`/`127.0.0.1` leak in sample `WORKSPACE` or lockfiles.
- Temporary sample edits were removed; sample generated files are clean.
- Remaining: broad Grazel gates, forced PAX proxy verification, and normal PAX
  flag-off build/test guard.

### Local Grazel gates after altitude fixes

- Resource check before broad local gates: about `56GiB` free on the data
  volume; no cleanup performed.
- `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed in
  `40s`.
- `./gradlew migrateToBazel --console=plain --no-daemon` passed in `10s`.
- `reports/scripts/verify-default-task-graph.sh` passed.
- `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed with
  unchanged PAX baseline counts: bucket count `11`, pinfile count `11`, total
  artifact roots `1945`.
- `git diff --check` and `git diff --check master...HEAD` passed.
- `reports/scripts/verify-sample-bucket-labels.sh` still fails on the known
  appcompat/constraintlayout exclude assertion; accepted pre-existing waiver.

### Forced PAX proxy verification after altitude fixes

- PAX baseline workspace before the forced run: branch `arun/grazel-refactor`,
  commit `d4105d1f64bd2f1930e1030e42647a214002c48d`, clean.
- Temporary forced-run edits enabled `localMavenResolution`, excluded the
  external Dagger repository variable for proxy pinning, and perturbed the root
  `maven_install.json` repository hash.
- `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
  --rerun-tasks > build/item38-debug/pax-forced-proxy-after-lazy-pom-altitude-migrate.log
  2>&1` passed in `13m58s`.
- Timings: declared metadata fanout `2327` projects/`2327` shards in `1230ms`;
  workspace dependency resolution `496` deps/`2451` roots in `23850ms`;
  variant compression `2096` projects in `62363ms`; local Maven proxy pinning
  in `104161ms`.
- Proxy summary: `788` artifacts from Gradle index, `788` POMs from Gradle
  index, `283` origin fallbacks, `45` lockfile artifact fallbacks, `52`
  metadata-only artifact fallbacks, `1710` alternate probes, `0` artifact
  misses, `1921510962` bytes served.
- No `localhost`/`127.0.0.1` leak in PAX generated `WORKSPACE` or lockfiles.
- Temporary PAX edits were removed; PAX status is clean after the forced run.
- Normal flag-off PAX guard:
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
    --rerun-tasks > build/item38-debug/pax-normal-after-altitude-migrate.log
    2>&1` passed in `9m55s`.
  - Timings: declared metadata fanout `2327` projects/`2327` shards in
    `616ms`; workspace dependency resolution `496` deps/`2451` roots in
    `24317ms`; target tags `17090` targets in `16655ms`; variant compression
    `2096` projects in `64684ms`.
  - `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed in `215.080s`.
  - `./bazel.sh test --test_output=errors
    //app-utils:app-utils-gps-pax-debug-test
    //app-test:app-test-gps-pax-debug-test
    //application-initializer:application-initializer-gps-pax-debug-test`
    passed in `16.360s`; Bazel reported `3 tests pass`.
  - PAX `git diff --check` passed and PAX status is clean.
- Remaining: final simplify-pass and adversarial review over the green proxy
  slice; fix real findings and rerun impacted gates.

### Item 38 simplify/altitude follow-up

- Simplify-pass findings fixed in code:
  - shared basic-auth Maven repository URL injection helper;
  - deleted task-local proxy context adapter/provider/configurator glue;
  - made Gradle module-cache indexing skip GAVs already present in resolved
    artifacts;
  - made POM resolution cache-first with Gradle `ArtifactResolutionQuery`
    fallback;
  - propagated full proxy counters into the pinner summary;
  - used `mavenInstallJsonName` in touched paths/tests;
  - renamed the task helper to `pinnableRepoResolutionGavs` and removed unclear
    collection flattening.
- Current altitude map:
  - Gradle layer owns resolved facts, module-cache/POM lookup, build service,
    proxy server, and repository-origin mapping;
  - task layer owns the bridge from file-backed task inputs plus live
    configurations to the migrate-layer `LocalMavenResolutionPinContext`;
  - migrate/pinner layer owns temporary pinning workspace mutation, repository
    rewriting, and lockfile reconstruction;
  - renderer output remains canonical; proxy URLs must never persist.
- Deferred with rationale:
  - live `WORKSPACE` text mutation during pinning remains the bounded proxy shim;
    future improvement is an isolated temporary pin workspace renderer;
  - lockfile hash reconstruction remains dense because it mirrors
    rules_jvm_external semantics;
  - origin fallback response streaming is a later performance cleanup.
- Focused verification after these fixes:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.gradle.dependencies.LocalMavenResolvedFactsTest" --tests
  "com.grab.grazel.gradle.dependencies.LocalMavenProxyServiceTest" --tests
  "com.grab.grazel.migrate.dependencies.MavenInstallLockfileArtifactPathsTest"
  --tests "com.grab.grazel.migrate.dependencies.LocalMavenPinningWorkspaceTest"
  --tests "com.grab.grazel.tasks.internal.PinMavenArtifactsTaskTest" --tests
  "com.grab.grazel.bazel.rules.MavenRulesTest" --console=plain --no-daemon`
  passed in `21s`.
- Remaining: reconcile the two read-only proxy audit agents, rerun impacted
  proxy/PAX gates, then commit Grazel locally if clean. Do not push and do not
  commit PAX.

### Item 38 post-simplify audit fix

- Read-only altitude audit found that
  `MavenInstallLockfileReconstructor` applied POM-packaging skip normalization
  only on the no-baseline path. Fixed by applying the normalization after both
  baseline merge and no-baseline reconstruction.
- Added regression test:
  `reconstruct marks new pom packaging artifacts skipped when baseline lockfile
  exists`.
- Verification:
  - `./gradlew :grazel-gradle-plugin:test --tests
    "com.grab.grazel.migrate.dependencies.MavenInstallLockfileReconstructorTest"
    --console=plain --no-daemon` passed in `18s`.
  - forced sample proxy repin passed in `33s` with `0` artifact misses and `0`
    known POM failures; summary included `156` Gradle artifact hits, `159`
    Gradle POM hits, `0` origin fallbacks, `141` origin failures, `22`
    lockfile fallbacks, `15` metadata-only fallbacks, `842` checksum hits,
    `318` write-through cache hits, `730992384` bytes served in `24207ms`.
  - Temporary sample edits were removed; `WORKSPACE` and `*maven_install.json`
    contain no `localhost`/`127.0.0.1`.
- Remaining: rerun broad Grazel gates and PAX guard after this final
  reconstructor fix.

### Item 38 local gates after final audit fix

- `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed in
  `38s`.
- `./gradlew migrateToBazel --console=plain --no-daemon` passed in `9s`.
- `reports/scripts/verify-default-task-graph.sh` passed.
- `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed with
  unchanged PAX baseline counts: bucket count `11`, pinfile count `11`, total
  artifact roots `1945`.
- `git diff --check` and `git diff --check master...HEAD` passed.
- `reports/scripts/verify-sample-bucket-labels.sh` still fails with the known
  pre-existing appcompat/constraintlayout assertion.
- Remaining: PAX guard after final audit fix, final review reconciliation, then
  local Grazel commit if clean.

### Item 38 PAX forced-proxy final hook validation

- No-hook forced PAX proxy run failed for this workspace because PAX removes
  `+ DAGGER_REPOSITORIES` from final `WORKSPACE` after Grazel captures
  repository inputs; rules_jvm_external reported root lockfile repository hash
  mismatch `-1395933409 vs -2080637180`.
- Correct integration is the existing customer-side hook:
  `excludeExternalRepositoryVariables("maven", "DAGGER_REPOSITORIES")`.
- Forced PAX run with that hook passed in `11m10s`:
  `build/item38-debug/pax-forced-proxy-final-with-hook-migrate.log`.
- Proxy counters: `787` Gradle artifact hits, `788` Gradle POM hits, `0` origin
  fallbacks, `0` artifact misses, `0` known POM failures, `45` lockfile
  fallbacks, `52` metadata-only fallbacks, `3716` checksum hits, `849`
  write-through cache hits, `1921502568` bytes served in `89746ms`.
- Temporary PAX flag/hook/lockfile edits were removed; PAX status is clean.
- Altitude decision: active lockfile facts in the pinner task are retained as a
  pinner-layer compatibility seam for repinning already-pinned artifacts. They
  do not feed dependency ownership or generated target modelling.
- Naming cleanup made that seam explicit in code by renaming the helper to
  `activeMavenInstallLockfileFallbackFacts` /
  `mavenInstallLockfileFallbackFacts`.
- Focused proxy/reconstructor tests after the rename passed in `20s`.
- Final local gates:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed
    in `41s`;
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed in `12s`;
  - `reports/scripts/verify-default-task-graph.sh` passed;
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed with
    unchanged counts `11/11/1945`;
  - `git diff --check` and `git diff --check master...HEAD` passed;
  - `reports/scripts/verify-sample-bucket-labels.sh` still has the known
    pre-existing appcompat/constraintlayout failure.
- Final PAX loop:
  - migrate passed in `11m19s`;
  - APK build for `//app:app-gps-pax-debug.apk` and
    `//app:app-gps-pax-debug-android-test.apk` passed in `214.992s`;
  - focused tests
    `//app-utils:app-utils-gps-pax-debug-test`,
    `//app-test:app-test-gps-pax-debug-test`, and
    `//application-initializer:application-initializer-gps-pax-debug-test`
    passed in `17.313s`;
  - PAX `git diff --check` passed and PAX status is clean.

### Item 38 post-final byte-identity correction

- Completion audit found a hard done gap: forced proxy repinning had passed
  functionally, but the PAX hook validation was not byte-identical because
  reconstructed lockfiles added baseline-existing POM-packaging artifacts, for
  example `androidx.compose:compose-bom:pom`, to `skipped`.
- Root cause: `MavenInstallLockfileReconstructor` synthesized all
  POM-packaging artifact keys into `skipped`. Vanilla rules_jvm_external did
  not skip some POM artifacts that were already present in baseline lockfiles,
  so baseline skip state must be preserved.
- Fix: parse baseline artifact names once and only synthesize `skipped` entries
  for newly introduced POM-packaging roots. Baseline-existing POM artifacts keep
  their original skip state.
- Regression test added:
  `reconstruct preserves baseline pom packaging skipped state`.
- Focused verification:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.migrate.dependencies.MavenInstallLockfileReconstructorTest"
  --console=plain --no-daemon` passed in `12s`.
- Forced sample proxy repin after the fix passed in `40s`; direct generated
  output checks found no `localhost`/`127.0.0.1` in `WORKSPACE` or
  `*maven_install.json`, and sample generated files were byte-identical.
- Forced PAX proxy repin with the customer-side hook
  `excludeExternalRepositoryVariables("maven", "DAGGER_REPOSITORIES")` passed
  in `13m13s`:
  `build/item38-debug/pax-forced-after-baseline-pom-preserve-migrate.log`.
  Proxy summary: `787` Gradle artifact hits, `788` Gradle POM hits, `0` origin
  fallbacks, `30` origin failures, `45` lockfile fallbacks, `52`
  metadata-only artifact fallbacks, `1713` alternate artifact probes, `0`
  artifact misses, `0` known POM failures, `3716` checksum hits, `849`
  write-through cache hits, `1921502568` bytes served in `96949ms`.
- PAX generated diff after forced repin was byte-identical; the only diff was
  the temporary `build.gradle` hook/experiment toggle. Temporary edits were
  removed and PAX status is clean.
- Final lightweight checks after the follow-up diff:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed
    in `44s`;
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed in `10s`;
  - `reports/scripts/verify-default-task-graph.sh` passed;
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed with
    unchanged counts `11/11/1945`;
  - `git diff --check` and `git diff --check master...HEAD` passed;
  - generated `WORKSPACE` and Maven install JSON files contain no
    `localhost`/`127.0.0.1`;
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails with the
    known pre-existing appcompat/constraintlayout assertion.
- Final forced PAX proxy repin was rerun after this review fix to avoid
  relying on inference. With temporary
  `excludeExternalRepositoryVariables("maven", "DAGGER_REPOSITORIES")`,
  `experiments.localMavenResolution`, and a perturbed root
  `maven_install.json` repository hash, PAX
  `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
  --rerun-tasks` passed in `13m37s`:
  `build/item38-debug/pax-forced-after-skipped-merge-review-fix-migrate.log`.
  Proxy summary: `787` Gradle artifact hits, `788` Gradle POM hits, `0`
  origin fallbacks, `30` origin failures, `45` lockfile fallbacks, `52`
  metadata-only artifact fallbacks, `1713` alternate artifact probes, `0`
  artifact misses, `0` known POM failures, `3716` checksum hits, `849`
  write-through cache hits, `1921502568` bytes served in `118134ms`.
- Final forced PAX generated diff was byte-identical: only the temporary
  `build.gradle` lines appeared, and no generated Maven install JSON or
  `WORKSPACE` contained `localhost`/`127.0.0.1`. Temporary PAX edits were
  removed and PAX status is clean.
- Final PAX Bazel gates were rerun on the clean baseline after the review fix:
  - `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed in `220.593s`;
  - `./bazel.sh test --test_output=errors
    //app-utils:app-utils-gps-pax-debug-test
    //app-test:app-test-gps-pax-debug-test
    //application-initializer:application-initializer-gps-pax-debug-test`
    passed in `16.392s` with `3 tests pass`;
  - PAX `git diff --check` passed and PAX status is clean.

### Item 38 review follow-up on skipped merge

- Read-only final review found a remaining byte-identity edge case: the
  baseline POM skip preservation filtered synthesized POM skips, but still
  merged `currentSkipped` wholesale. If the proxy/current lockfile marked a
  baseline-existing POM artifact as skipped while the baseline did not, the
  reconstructed lockfile could still drift.
- Fix: keep raw `currentSkipped` for the existing safety check, but filter
  baseline artifact names out before merging current skipped entries into the
  reconstructed lockfile. Baseline skipped entries are then sourced only from
  the baseline lockfile.
- Regression test added:
  `reconstruct ignores current skipped state for baseline pom packaging
  artifact`.
- Focused verification:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.migrate.dependencies.MavenInstallLockfileReconstructorTest"
  --console=plain --no-daemon` passed in `19s`.
- Post-review lightweight gates:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed
    in `40s`;
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed in `10s`;
  - `reports/scripts/verify-default-task-graph.sh` passed;
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed with
    unchanged counts `11/11/1945`;
  - `git diff --check` and `git diff --check master...HEAD` passed;
  - generated `WORKSPACE` and Maven install JSON files contain no
    `localhost`/`127.0.0.1`;
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails with the
    known pre-existing appcompat/constraintlayout assertion.

### Item 38 post-goal proxy review polish

- Review follow-up decisions:
  - moved HTTP proxy serving infrastructure from `gradle.dependencies` to
    `com.grab.grazel.proxy`; kept `LocalMavenProxyService` in
    `gradle.dependencies` because it is the Gradle BuildService wiring layer;
  - kept unknown external repository variables fail-closed, but made the error
    actionable with the `excludeExternalRepositoryVariables("<repo>",
    "<variable>")` opt-out hook;
  - split POM resolver outcomes into `Found`, `Unknown`, and `Unavailable` so
    known-but-unreadable Gradle POMs are not conflated with origin fallback
    misses;
  - added `requestFailures` so route/origin infrastructure exceptions are not
    counted as known POM failures;
  - documented the RJE pin script assumption: scripts embed proxy URLs while
    WORKSPACE is temporarily proxied, then WORKSPACE is restored before script
    execution;
  - local proxy reconstruction now requires a baseline lockfile before handling
    POM-packaging artifacts, avoiding silent first-ever pin classification.
- TDD red run:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.gradle.dependencies.LocalMavenResolvedFactsTest" --tests
  "com.grab.grazel.gradle.dependencies.LocalMavenProxyServerTest" --tests
  "com.grab.grazel.migrate.dependencies.MavenInstallLockfileReconstructorTest"
  --console=plain --no-daemon` failed on missing `PomFileResolution`,
  `requestFailures`, and `requireBaselineForPomPackagingArtifacts`.
- Verification after fixes:
  - focused suite rerun with package-adjusted
    `com.grab.grazel.proxy.LocalMavenProxyServerTest` passed in `18s`;
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed
    in `40s`;
  - `git diff --check` and `git diff --check master...HEAD` passed after
    marking moved proxy files intent-to-add for whitespace coverage;
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed in `10s` and
    produced no generated-file diff;
  - `reports/scripts/verify-default-task-graph.sh` passed;
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed with
    unchanged PAX counts `11/11/1945`.
- PAX was not mutated or committed in this follow-up. Current PAX diff is only
  the maintainer-requested default proxy hook in `build.gradle`; generated PAX
  files remain clean against the committed baseline.

### Item 38 post-polish forced PAX proxy repin

- Forced PAX repin after the proxy package/review polish by perturbing the root
  `maven_install.json` repository hash, keeping a temporary backup at
  `/tmp/pax-maven-install.grazel-force-pin.bak`.
- Command:
  `cd /Users/arun.sampathkumar/work/pax-android && ./gradlew migrateToBazel
  --no-daemon --console=plain --stacktrace --rerun-tasks`.
- Result: passed in `13m10s` with `4749` executed tasks. The local Maven proxy
  path was exercised for all materialized repos and `pinMavenArtifacts`
  reported each repo up to date after repin.
- Proxy summary from the run: `787` artifacts from Gradle index, `788` POMs
  from Gradle index, `0` origin fallbacks, `30` origin failures, `45` lockfile
  artifact fallbacks, `52` metadata-only artifact fallbacks, `1713` known
  alternate artifact probes, `0` artifact misses, `0` known POM failures, `0`
  request failures, `3716` checksum hits, `849` write-through cache hits,
  `1921502568` bytes served in `75041ms`.
- Post-run PAX guardrails:
  - root `maven_install.json` is byte-identical to the backup after repin;
  - generated Maven install JSONs and `WORKSPACE` contain no
    `localhost`/`127.0.0.1`;
  - PAX status remains only the maintainer-requested `build.gradle` proxy hook;
  - PAX `git diff --check` passed;
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed with
    unchanged counts `11/11/1945`.

### Item 41 source-shape checkpoint

- Maintainer feedback: retain the existing `Collection<T>.quote` Starlark DSL
  extension; replacing it with a free function was too aggressive for this
  branch-wide hygiene pass.
- Reverted the temporary quote cleanup. `quoteStarlarkValues` no longer exists
  in main sources, and `Statement.kt`/rules quote call sites are no longer part
  of the dirty diff.
- Verification: `./gradlew :grazel-gradle-plugin:test --console=plain
  --no-daemon --quiet` passed in `8s`.

### Item 38 proxy package-boundary cleanup

- Maintainer feedback: proxy-related classes should live under
  `com.grab.grazel.proxy`; having only the HTTP server there leaves the feature
  split across `gradle.dependencies` and `migrate.dependencies`.
- Moved proxy content/service classes to the proxy package:
  - `LocalMavenProxyService`;
  - `LocalMavenResolvedFacts` and its Gradle/cache/POM hydrators;
  - lockfile fallback allowlist extraction, renamed from vague "facts" naming
    to `MavenInstallLockfileFallbackIndex` with explicit
    `allowedOriginArtifactPaths` and `additionalComponentGavs`.
- Kept `LocalMavenPinningWorkspace` and `LocalMavenResolutionPinContext` in
  `migrate.dependencies` because they own pin-flow orchestration rather than
  proxy content serving/hydration.
- Verification:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.proxy.LocalMavenProxyServiceTest" --tests
  "com.grab.grazel.proxy.LocalMavenResolvedFactsTest" --tests
  "com.grab.grazel.proxy.MavenInstallLockfileFallbackIndexTest" --tests
  "com.grab.grazel.proxy.LocalMavenProxyServerTest" --tests
  "com.grab.grazel.tasks.internal.PinMavenArtifactsTaskTest"
  --console=plain --no-daemon` passed in `27s`.
- `git diff --check` passed.

### Item 41 simplify-pass reconciliation

- Simplify-pass ran after the branch-wide source-shape cleanup. Parent
  reconciliation accepted the `BucketOwnershipPlanner` map-merge reuse finding
  and kept the maintainer-requested `Collection<T>.quote` Starlark DSL
  extension.
- Deferred findings that would change model/performance altitude rather than
  preserving source shape: KSP direct-ID micro-optimization, provider
  materialization, proxy/lockfile package reshapes, and broader target/tag
  helper extraction.
- Focused verification after the accepted cleanup passed in `21s`:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.gradle.dependencies.BucketOwnershipPlannerTest"
  --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest"
  --tests "com.grab.grazel.gradle.variant.WorkspaceKspProcessorClasspathPlannerTest"
  --console=plain --no-daemon --quiet`.
- Adversarial review follow-up fixed three preserving issues:
  - restored the defensive KSP configuration read boundary and added focused
    coverage for unavailable KSP configurations;
  - converted policy-heavy `AggregatedDependencyRootMetadata` and
    `DependencyBucketPlacementPlan` receiver helpers to explicit-parameter
    helpers;
  - replaced the `AggregatedDependencyResolverTest` Java proxy fixture with an
    existing Mockito-based `ResolvedComponentResult` mock.
- The source-shape inventory script now records private domain receiver helpers
  as `domain_receiver_extension`. It is an advisory detector; rows still need
  parent/subagent classification because algebraic DSL helpers such as
  `Collection<T>.quote` remain intentionally retained.
- Focused verification after these fixes passed in `15s`:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest"
  --tests "com.grab.grazel.gradle.dependencies.BucketOwnershipPlannerTest"
  --tests "com.grab.grazel.gradle.variant.WorkspaceKspProcessorClasspathPlannerTest"
  --console=plain --no-daemon --quiet`.

### Item 41 final verification checkpoint

- Full plugin unit test passed:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon
  --quiet`.
- `reports/scripts/source-shape-inventory.sh` was rerun after final fixes; the
  inventory has no blank/pending status cells. The retained
  `Collection<T>.quote` extension is classified as an intentional Starlark DSL
  exception.
- Grazel hygiene passed: `git diff --check` and
  `git diff --check master...HEAD`.
- Grazel `migrateToBazel --console=plain --no-daemon` passed in `10s`.
- `reports/scripts/verify-default-task-graph.sh` passed.
- `reports/scripts/verify-sample-bucket-labels.sh` failed on the existing
  `androidx.constraintlayout:constraintlayout` appcompat/core exclusion guard.
  Current generation produced no local generated diff for the checked files,
  and `HEAD:WORKSPACE` plus `master:WORKSPACE` already contain the same
  exclusion block, so this is recorded as a pre-existing guard/baseline mismatch
  rather than an Item41 regression.
- PAX `migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
  passed in `11m 43s`; generated output remained stable with only
  `build.gradle` dirty in PAX.
- PAX `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
  //app:app-gps-pax-debug-android-test.apk` passed in `216s`.
- PAX `./bazel.sh test --test_output=errors
  //app-utils:app-utils-gps-pax-debug-test
  //app-test:app-test-gps-pax-debug-test
  //application-initializer:application-initializer-gps-pax-debug-test` passed
  in `22s`.
- PAX `git diff --check` passed.
- `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed with
  unchanged counts `bucketCount=11`, `pinfileCount=11`,
  `totalArtifactRoots=1945`.
