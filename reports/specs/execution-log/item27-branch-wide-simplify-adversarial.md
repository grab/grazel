# Item 27: Branch-Wide Simplify + Adversarial Review

## Current Status

- PAX preserving drift is fixed and PAX migrate/build/test gates passed.
- Final local rerun after the last preserving fix passed, except for the known
  pre-existing sample bucket-label exclude waiver.
- Root cause fixed in this item: Android library/instrumentation generation was
  temporarily widened to honor all `referencedProjectTargets`. PAX has existing
  `testImplementation project(...)` references to test-helper Android modules;
  generating those helper modules is an output-changing behavior, not a
  branch-wide preserving cleanup. Target-reference facts remain collected, but
  Android library/instrumentation generation now stays bucket-reachability
  driven. Android app and standalone android-test referenced-target behavior is
  unchanged from the pre-Item-27 baseline.

## Status

- Started after local Item 24 checkpoint `6a4c40a`
  (`refactor: tidy dependency refactor source shape`).
- Grazel branch: `arun/dependencies-refactor`; changes stay local and must not be pushed.
- PAX baseline workspace: `/Users/arun.sampathkumar/work/pax-android` on
  `arun/grazel-refactor` at `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`; do not commit PAX.
- Active spec:
  `reports/specs/2026-06-28-item27-branch-wide-simplify-adversarial-review-design.md`.

## Scope

- Whole branch diff from `master...HEAD`, including production code, tests, scripts, generated
  samples, and specs.
- Preserving item: generated Grazel output and PAX generated baseline must remain empty-diff.
- Required first pass: invoke the `simplify-pass` skill and reconcile four review angles:
  reuse, simplification, efficiency, and altitude.
- Required second pass: adversarial correctness review over the branch diff after simplify fixes.

## Initial State

- Grazel worktree clean at start.
- PAX worktree clean at start.
- `git diff --stat master...HEAD` shows 241 files changed; review agents will inspect the diff
  in-repo by range to keep the parent context manageable.
- Disk check before review: roughly 29 GiB free on the Data volume.

## Review Plan

- Launch four simplify-pass review agents in parallel:
  reuse, simplification, efficiency, altitude.
- Parent reconciles findings, spot-checks code, and applies only preserving fixes.
- Then launch adversarial correctness review agents focused on dependency/value source, task
  graph/cacheability, generated-output/PAX guards, and test coverage.
- Confirmed findings are fixed in this item or rejected with concrete code evidence.

## Findings And Fixes

- Simplify-pass review completed across the branch diff with four angles:
  reuse, simplification, efficiency, and altitude. The stale subagent sessions
  were closed after their final reports were captured.
- Fixed preserving reuse findings:
  - Centralized duplicate JVM reachability logic in `TargetVariantReachability`
    and reused it from `KotlinLibraryTargetBuilder` and
    `TargetReferenceFactsExtractor`.
  - Reused `TargetReferenceFacts.asRenderPlan()` in
    `FinalizeWorkspacePlanTask`.
- Fixed preserving simplification findings:
  - Removed redundant `CandidateMavenRepo.variantName`; repo identity now comes
    from the `repoPlan` key.
  - Removed stored `projectPaths` / `referencedProjectPaths` from
    `TargetReferenceFacts` / `WorkspaceRenderPlan`; those are now derived from
    the target maps.
  - Removed the test-only `collectTargetMavenRepoReferences` wrapper and moved
    tests to the grouped collector API.
  - Collapsed `WorkspacePlanService` from interface plus single
    `DefaultWorkspacePlanService` implementation into one abstract Gradle
    `BuildService` type.
- Fixed preserving efficiency/altitude findings:
  - `CollectDeclaredDependencyMetadataTask` no longer serializes declared
    metadata in `projectsEvaluated`; it now supplies the JSON through a lazy
    provider while keeping Gradle declaration files as cache inputs.
  - Moved `MavenInstallRootArtifacts` into the dependency layer and cut
    `MavenInstallArtifactsCalculator` over to `WorkspacePlan.repoPlan` pin
    inputs / override targets instead of recomputing root artifacts during
    WORKSPACE rendering.
- Rejected/deferred with current rationale:
  - `ResolvedComponentResult` task inputs remain intentional for this branch:
    the maintainer explicitly chose the master-like cacheable handoff of
    Gradle's resolved root component. Do not flip these to `@Internal` in this
    cleanup without a separate cacheability design.
  - The deprecated `limitDependencyResolutionParallelism` DSL remains as a
    compatibility no-op; deleting public DSL is not an empty-diff cleanup.
  - Reusable per-project target models and typed declared project-edge metadata
    are larger model reshapes, not safe to smuggle into this preserving pass.
  - Moving `WorkspaceTargetTagPlanCollector.bestVariantKeyForTagClosure` into
    the variant layer is valid altitude work, but it needs a focused seam/test
    item because it changes who owns variant fallback semantics.
- Adversarial correctness fixes after the first focused suite:
  - `TargetReferenceFactsExtractor` now includes typed `associates` /
    `instruments` for Android unit tests, instrumentation tests, and Kotlin
    unit tests. Added direct mapping coverage in
    `TargetReferenceFactsDataMappingTest`.
  - `CollectKspProcessorDependenciesTask` creates the output parent directory
    before writing JSON.
  - `GenerateBazelScriptsTask` keeps the existing non-migratable active
    `BUILD.bazel` disable behavior. Extending that behavior to all empty-target
    non-concrete projects caused generated sample drift (`flavors`/`lint` empty
    BUILD files), so that broader behavior change is deferred outside this
    preserving item.
  - `PinMavenArtifactsTask` path sensitivity annotations now target getters.
  - `WorkspaceRenderPlanBuilder` materializes direct self-override roots based
    on effective override targets and follows override-target closure to repos
    with planned artifacts, avoiding undefined override repos without
    materializing arbitrary candidates.
  - Android library generation and target-reference fact extraction now include
    render-plan referenced target names using the shared
    `generatedTargetNameFor` helper.
  - First narrow post-fix review found one remaining blocker: Android build
    referenced-target matching used raw `matchedVariant.nameSuffix` instead of
    variant-compressed suffixes, and did not account for emitted macro aliases
    such as `_kt` and `lib_...`.
  - Fixed the blocker in `TargetVariantReachability`: generated target names now
    resolve suffixes through `VariantCompressionService.resolveSuffix`, and
    referenced generated target matching accepts the base target, `_lib`, `_kt`,
    and `lib_` aliases. Added regression coverage in
    `TargetVariantReachabilityTest`.
- Maintainer continuation note: after Item 27 adversarial fixes finish, rerun
  an Item 24-style changed-file source-shape inventory/reconciliation before
  exiting this cleanup phase. Every changed Kotlin file must be visited and
  accounted for; broad tests alone are not enough.
- Item 24-style source-shape rerun:
  - Deterministic changed-Kotlin inventory found 134 files in the branch/current
    worktree scope at the time of the rerun: 87 production, 45 unit-test, and 2
    functional-test files.
  - Four scoped review agents covered the inventory by area:
    dependencies/variant, migrate/bazel/DI, tasks/internal, and tests.
  - Fixed small source-shape findings from that rerun: stale KDoc/comments,
    source-text assertion tests, stale ignored-test wording/path construction,
    compressed target-name reachability, and KSP parent directory behavior.
  - Deferred with evidence rather than hidden cleanup: typed declared project
    metadata, duplicated declaration-bucket detection, KSP absolute path
    cacheability, root-component `@Input` cacheability, reusable per-project
    target models, pinner/render stringly boundaries, and the empty-target
    non-concrete active `BUILD.bazel` disable policy. These require separate
    model/cacheability/output-changing items; they are not empty-diff Item 27
    cleanup.

## Verification

- Focused post-fix suite passed:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.tasks.internal.CollectDeclaredDependencyMetadataTaskTest"
  --tests "com.grab.grazel.gradle.dependencies.WorkspacePlanBuilderTest"
  --tests "com.grab.grazel.gradle.dependencies.WorkspaceRenderPlanBuilderTest"
  --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest" --tests
  "com.grab.grazel.migrate.dependencies.MavenInstallArtifactsCalculatorTest"
  --tests "com.grab.grazel.migrate.DaggerWorkspaceRuleTest" --tests
  "com.grab.grazel.migrate.AndroidWorkspaceRepositoriesTest" --tests
  "com.grab.grazel.migrate.KotlinWorkspaceRulesTest" --tests
  "com.grab.grazel.migrate.target.TargetVariantReachabilityTest"
  --console=plain --no-daemon`.
- Adversarial correctness review findings have been reconciled; the remaining
  live issue is the PAX preserving-gate drift recorded above.
- Later focused adversarial-fix suite passed:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.gradle.dependencies.WorkspaceRenderPlanBuilderTest"
  --tests "com.grab.grazel.migrate.target.TargetReferenceFactsDataMappingTest"
  --tests "com.grab.grazel.migrate.target.TargetVariantReachabilityTest"
  --tests
  "com.grab.grazel.tasks.internal.CollectKspProcessorDependenciesTaskTest"
  --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest"
  --console=plain --no-daemon`.
- First narrow post-fix review found the compressed-suffix/macro-alias blocker;
  after the fix, the same focused adversarial-fix suite passed again.
- Second narrow post-fix review did not add more source edits before broad
  verification.
- Source-shape rerun focused test passed after cleanup fixes:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.tasks.internal.CollectKspProcessorDependenciesTaskTest"
  --tests "com.grab.grazel.tasks.internal.ResolveWorkspaceDependenciesTaskTest"
  --tests "com.grab.grazel.migrate.target.TargetReferenceFactsDataMappingTest"
  --tests "com.grab.grazel.migrate.target.TargetVariantReachabilityTest"
  --tests
  "com.grab.grazel.gradle.dependencies.WorkspaceRenderPlanBuilderTest"
  --console=plain --no-daemon`.
- Full plugin unit suite passed:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`.
- Local generation passed and produced no generated-output diff:
  `./gradlew migrateToBazel --console=plain --no-daemon`.
- `reports/scripts/verify-default-task-graph.sh` passed.
- `reports/scripts/verify-sample-bucket-labels.sh` still fails on the documented
  one-sided appcompat/constraintlayout exclude assertion; this remains the
  pre-existing waiver from earlier item logs, not an Item 27 regression.
- `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed with
  unchanged counts: bucket count `11`, pinfile count `11`, total artifact roots
  `1945`, and no per-repo artifact root deltas.
- `git diff --check` and `git diff --check master...HEAD` passed.
- PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
  --rerun-tasks` passed in `12m12s`, and PAX `git diff --check` passed, but
  PAX `git status --short` showed four untracked `BUILD.bazel` files listed in
  Current Status. The PAX Bazel build/test gates are intentionally blocked
  until this preserving drift is removed.
- After narrowing Android library/instrumentation generation back to bucket
  reachability, focused reference/reachability tests passed:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.migrate.target.TargetReferenceFactsDataMappingTest"
  --tests "com.grab.grazel.migrate.target.TargetVariantReachabilityTest"
  --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest"
  --console=plain --no-daemon`.
- Local `./gradlew migrateToBazel --console=plain --no-daemon` passed after
  the narrowing.
- PAX rerun from a clean working tree passed:
  `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
  --rerun-tasks` in `11m58s`; PAX `git status --short` stayed clean and
  `git diff --check` passed.
- PAX build gate passed:
  `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
  //app:app-gps-pax-debug-android-test.apk` in `251.121s`.
- PAX focused Bazel test gate passed 3/3:
  `./bazel.sh test --test_output=errors
  //app-utils:app-utils-gps-pax-debug-test
  //app-test:app-test-gps-pax-debug-test
  //application-initializer:application-initializer-gps-pax-debug-test`
  in `24.115s`.
- Final local gates after the PAX pass:
  - Full `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`
    passed in `43s`.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed with
    unchanged counts: bucket count `11`, pinfile count `11`, total artifact
    roots `1945`, and no per-repo deltas.
  - `git diff --check` and `git diff --check master...HEAD` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails only on the
    known pre-existing appcompat/constraintlayout exclude assertion:
    `WORKSPACE must not union one-sided appcompat exclude onto
    androidx.constraintlayout:constraintlayout`.
