# Item 6 Execution Log - Simplify, Review, Verification

## Scope

- Source spec: `reports/specs/2026-06-26-item6-simplify-review-verification-design.md`.
- Starting checkpoint: `e707bf4` (`Select Maven roots per variant provenance`).
- This item is behavior-preserving relative to the Item 5 baseline unless a
  review finding requires an explicit fix with its own verification note.

## Standing Gates

- Use subagents for read-heavy review slices and final adversarial review.
- Check disk, CPU, and memory before expensive Gradle/Bazel/PAX commands.
- Do not commit or revert PAX-side changes.
- Keep notes concise and itemized; do not pull old long logs into main context.

## 2026-06-26 16:05 SGT - Item 6 start

- Item 5 completed and committed locally:
  `e707bf48ef746faf49c0650f3cd18e72db9b19f0`.
- Last PAX verification from Item 5b:
  - `migrateToBazel` passed in 11m15s.
  - Debug APK + android-test APK Bazel build passed on retry in 2169.372s.
  - PAX `git diff --check` passed.
  - PAX tag-prefix audit returned `0`.
- Resource state:
  - Disk restored to ~27 GiB free after PAX `bazelisk clean --expunge`.
  - PAX `bazel-cache` is ~13 GiB and retained for now.

## Next

- Start Item 6 with the `resolve()` extraction audit/refactor.
- Preserve generated output against the Item 5 baseline after each
  behavior-preserving cleanup.

## 2026-06-26 16:20 SGT - `resolve()` Extraction

- Read-heavy subagent audits completed:
  - Extraction audit recommended a conservative `ResolutionSession` split:
    declared metadata lookup, reachability, root collection, declared metadata
    augmentation, main bucket planning, test bucket planning, and result
    materialization.
  - Verification audit recommended focused resolver/planner/workspace tests,
    then `verifyGrazelGoldenBaseline`; PAX only after local goldens are clean.
- Implemented a behavior-preserving extraction in
  `AggregatedDependencyResolver`:
  - public `resolve()` now delegates to a private `ResolutionSession`;
  - root closure collection, declared metadata augmentation, main bucket
    planning, test bucket planning, and final result assembly are named methods;
  - `ResolvedComponentsVisitor`, merge semantics, ordering, KSP default-bucket
    handling, and root failure behavior were left unchanged.
- Verification:
  - Focused test loop passed:
    `AggregatedDependencyResolverTest`, `DependencyBucketPlacementEngineTest`,
    `BucketHierarchyGraphTest`, `ComputeWorkspaceDependenciesTest`,
    `WorkspacePlanBuilderTest`, and `MavenInstallArtifactsCalculatorTest`.
  - `./gradlew verifyGrazelGoldenBaseline --console=plain --no-daemon`
    passed in 47s with a clean generated-file diff.
  - Grazel `git diff --check` passed.
- Resource note:
  - Disk stayed around 27 GiB free during the local verification.

## Next

- Commit the behavior-preserving extraction checkpoint if the final diff review
  stays clean.
- Continue Item 6 with simplify/review cleanup; do not run PAX until local
  cleanup goldens remain clean.

## 2026-06-26 16:40 SGT - Simplify Pass

- Ran the simplify-pass review with four read-only subagents:
  reuse, simplification, efficiency, and altitude.
- Applied behavior-preserving fixes:
  - removed an unreachable `sawBinaryRoot` guard in `AggregatedDependencyResolver`;
  - removed duplicated `CandidateMavenRepo.repoName`; repo identity now comes
    from the `WorkspacePlan.repoPlan` key;
  - removed unused serialized `WorkspaceRenderPlan` detail fields and kept only
    `materializedRepoNames`;
  - changed `WorkspaceRenderPlanBuilder` override-target closure expansion from
    repeated full rescans to a queue of newly materialized repos;
  - added cached tag lookup in `WorkspacePlanService` and switched extractors to
    `WorkspacePlanService.tagsFor(...)`;
  - precomputed fallback owners in `VariantScopedArtifacts`;
  - cached built variants during one `WorkspaceTargetTagPlanCollector.collect`
    pass;
  - loaded the workspace plan in `FinalizeWorkspacePlanTask` through
    `WorkspacePlanService.initPlan(...)`.
- Deferred simplify findings that are larger architecture/data-flow slices:
  - rendering `MavenInstallData` directly from `WorkspacePlan.repoPlan` instead
    of recalculating from `WorkspaceDependencies`;
  - replacing the target-model pre-generation repo-reference pass with a shared
    structured target planning model;
  - replacing override-target string parsing with structured referenced-repo
    data in the plan model;
  - extracting common target-tag merge helper and larger test fixture cleanup.
- Verification:
  - Focused test loop passed, including resolver, bucket placement, workspace
    plan/tag collector, pinner, and workspace task tests.
  - `./gradlew verifyGrazelGoldenBaseline --console=plain --no-daemon` passed in
    16s with a clean generated-file diff.
  - Grazel `git diff --check` passed.

## Next

- Commit the simplify-pass cleanup checkpoint.
- Start adversarial correctness review over the full branch diff.

## 2026-06-26 17:05 SGT - Adversarial Review Findings

- Ran four read-only adversarial review subagents over the full branch diff:
  dependency/bucketing, workspace/pinner, task graph/cacheability, and
  target extraction/reachability.
- Findings accepted for fixes:
  - Fresh or mixed pinning can leave `WORKSPACE` half-pinned if a
    `*_maven_install.json` is newly created after root generation. Need the
    pinned macro block and `maven_install_json` activation to be updated
    atomically, with a regression test.
  - Declared main metadata can still carry declared versions into non-default
    buckets instead of Gradle-resolved values. Declared metadata must be only
    an ownership/exclude hint unless hydrated from a matching resolved root
    dependency; `compileOnly` remains the explicit cheap metadata exception.
  - Resolved project-node dependencies are currently bucketed under the binary
    root project in some paths, while test subtraction is project-scoped. Need
    owner-project attribution or an equivalent covered-dependency model so
    library test buckets inherit main deps correctly.
  - `MAIN_LEAF` routes `TEST_VARIANT` into android-test closures. Split
    `TEST_VARIANT` and `ANDROID_TEST_VARIANT`.
  - Target reachability uses dependency bucket names as a proxy for target
    variant reachability; exact reachable target identities are needed to avoid
    over-generating siblings.
  - `GenerateBazelScriptsTask` can still write active empty BUILD files for
    unreachable projects. Generation and repo-reference collection need the
    same reachable target set, with no active BUILD when a project has no
    reachable targets.
  - `FinalizeWorkspacePlanTask` uses timestamped compression output as a
    cacheable input even though it only needed ordering.
  - `WorkspacePlanService` mutable plan state needs synchronization for
    parallel Gradle task access.
- Lower priority accepted tests:
  - Add non-reflection tests for PAX-shaped reachable leaf vs sibling variants,
    unreachable modules, and fresh pin activation.

## Next

- Fix the accepted adversarial findings in focused batches with tests.
- Re-run local focused tests and `verifyGrazelGoldenBaseline` after each
  coherent fix batch.

## 2026-06-26 18:35 SGT - Reachability Fixes During Golden Verification

- `verifyGrazelGoldenBaseline` exposed two strict-reachability regressions:
  - Android library targets were filtered by app leaf names like
    `demoFreeDebug` before `VariantMatcher` mapped them to the actual library
    variant/bucket (`debug`). This deleted `sample-android-library/BUILD.bazel`.
  - `sample-android/BUILD.bazel` still referenced
    `//lint/custom-lint-rules:custom-lint-rules` from `lint_options`, but the
    Kotlin lint module was disabled because compile-classpath reachability does
    not include non-compile target references.
- Fix direction:
  - Android library generation now filters after matching, using the selected
    project variant (`MatchedVariant.variant`) for reachability.
  - The target-reference pass now records structured project paths as well as
    Maven repos, including project dependencies inside lint config data.
  - The finalized render plan carries those project paths, and Kotlin target
    generation keeps a project reachable if a reachable generated target model
    references it.
- Focused verification passed:
  - `TargetVariantReachabilityTest`
  - `TargetMavenRepoReferencesCollectorTest`
  - `WorkspacePlanTasksTest`

## Next

- Re-run `verifyGrazelGoldenBaseline`.
- If golden is clean, run the broader focused adversarial test set and
  `git diff --check`.

## 2026-06-26 18:55 SGT - Grazel Golden Gate Restored

- Additional finding:
  - Empty parent package markers (`flavors/BUILD.bazel`, `lint/BUILD.bazel`)
    were being renamed because `MigrationChecker` can treat aggregator
    projects as migratable through their dependency subgraph.
  - That is not the same as an unreachable concrete target project.
- Fix:
  - `GenerateBazelScriptsTask` disables active BUILD files only for concrete
    target projects (`Android`, `Java`, or `Kotlin`) that generate no reachable
    targets.
  - Non-concrete aggregator/simple-directory projects are left untouched.
- Verification:
  - Focused compile/tests for target references, workspace plan tasks, and
    reachability passed.
  - `./gradlew verifyGrazelGoldenBaseline --console=plain --no-daemon` passed;
    generated-file diff is clean.

## Next

- Run the broader focused adversarial test set.
- Run `git diff --check`.
- Then proceed to PAX migration/build validation after resource checks.

## 2026-06-26 19:05 SGT - Local Focused Verification

- Broader focused test set passed:
  - `MavenRulesTest`
  - `DefaultArtifactPinnerTest`
  - `WorkspacePlanTasksTest`
  - `TargetMavenRepoReferencesCollectorTest`
  - `TargetVariantReachabilityTest`
  - `DependencyBucketPlacementEngineTest`
  - `AggregatedDependencyResolverTest`
- `git diff --check` passed.
- Removed untracked `flavors/BUILD.bazelignore` and `lint/BUILD.bazelignore`
  generated by the earlier failed run.
- Resource checkpoint before PAX:
  - Disk free: ~33 GiB on the shared volume.
  - No runaway `python3.12` process observed.

## Next

- Run PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`.
- Then run the agreed Bazel APK/android-test build gate.

## 2026-06-26 19:35 SGT - PAX Migration Wiring Failure

- PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`
  failed after Grazel dependency resolution, workspace planning, compression,
  and BUILD generation had run.
- Failure was PAX build-logic validation, not dependency resolution:
  `ModuleLoggerTask.inputBuildBazel` was required, but strict reachability now
  intentionally omits `build/grazel/BUILD.bazelignore` for disabled modules.
- Affected examples in the failure summary:
  - `:grab-test-recorder-noop:patchBuildBazel`
  - `:geo:geo-indoor-map-noop:patchBuildBazel`
  - `:apex-cfm:cfm-ui-tests:patchBuildBazel`
  - `:snp:snp-ui-tests:patchBuildBazel`
- Local PAX-only validation fix applied:
  - `build-logic/project/src/main/kotlin/grazel/task/ModuleLoggerTask.kt`
    now marks `inputBuildBazel` as `@Optional` and `@SkipWhenEmpty`.
- This aligns PAX patching with the new Grazel contract: a disabled/unreachable
  module may have no staged BUILD file, and downstream patch tasks must skip.

## Next

- Re-run PAX `migrateToBazel`.
- If it passes, run the debug APK and android-test APK Bazel build gate.

## 2026-06-26 19:58 SGT - PAX Migrate Gate Passed

- Command:
  - `cd /Users/arun.sampathkumar/work/pax-android`
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`
- Result:
  - Passed in `10m 29s`.
  - `4563 actionable tasks: 4537 executed, 26 up-to-date`.
- Important checkpoints:
  - Root dependency/planning path completed:
    `collectDeclaredDependencyMetadata`, `collectKspProcessorDependencies`,
    `resolveWorkspaceDependencies`, `computeWorkspaceDependencies`,
    `collectWorkspaceTargetTagPlan`, and `computeWorkspacePlan`.
  - The previously failing disabled module patch tasks now reported
    `NO-SOURCE`, including `grab-test-recorder-noop`, `geo-indoor-map-noop`,
    `apex-cfm:cfm-ui-tests`, and `snp:snp-ui-tests`.
  - `pinMavenArtifacts` checked all expected repos and skipped repinning
    because artifacts were up-to-date.
- Resource checkpoint after migration:
  - Disk free: ~30 GiB on the shared volume.

## Next

- Run PAX Bazel build gate:
  - `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
