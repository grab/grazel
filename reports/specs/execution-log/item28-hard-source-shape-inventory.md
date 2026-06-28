# Item 28 - Hard Source-Shape Inventory Remediation

## 2026-06-29 Start

- Starting commit: `9173c50` (`refactor: default declared metadata aggregation to fanout`).
- Grazel worktree was clean before starting Item 28.
- PAX baseline remains `/Users/arun.sampathkumar/work/pax-android` branch
  `arun/grazel-refactor` at `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`, with the accepted local
  dirty baseline set only. Do not commit PAX.
- Confirmed prerequisites:
  - Item 30 committed as `f0bfa47`.
  - Item 29 committed as `4c2eeb1`.
  - Item 31 committed as `9173c50`.
- Initial changed Kotlin scope:
  - `135` files from `git diff --name-only --diff-filter=ACMR master...HEAD -- '*.kt'`.
  - Area split: `86` main, `47` test, `2` functionalTest.
- Added deterministic inventory script:
  `reports/scripts/source-shape-inventory.sh`.
- Initial inventory generated:
  `reports/specs/source-shape-inventory.tsv`.
- Initial detector distribution:
  - `none`: 64 files.
  - `generic_collection_receiver`: 5 files.
  - `private_helper_model`: 9 files.
  - `project_extension`: 10 files.
  - `reflection_or_dynamic_access`: 13 files.
  - `unchecked_cast`: 24 files.
  - `source_string_assertion`: 22 files.
  - `comment_or_context_artifact`: 19 files.
- Scoped read-only subagents dispatched:
  - dependencies/variant cluster;
  - task-boundary cluster;
  - migrate/bazel/di cluster;
  - test/functional cluster.

## Working Notes

- Item 28 is preserving: any generated Grazel or PAX output drift is stop-and-investigate.
- The initial inventory intentionally has `pending` rows. It is not completion evidence until every
  row is terminal and the script has been rerun after final edits.

## 2026-06-29 Remediation Checkpoint 1

- Applied source-shape cleanup across the highest-signal production clusters:
  - dependency/variant helpers now use explicit role-named parameters instead of generic
    collection receivers where the receiver hid state;
  - `WorkspaceTargetTagPlanCollector` no longer uses private `Project.` receiver helpers for
    collector policy;
  - `BucketHierarchyGraph` sorting helpers now take role-named parameters instead of generic
    collection/map receivers;
  - helper models moved toward file-local model sections where safe, including aggregation and
    bucket ownership result models;
  - removed unused `GenerateBazelScriptsTask.variantCompressionResults` and its wiring;
  - renamed the root generation task local workspace dependency model to avoid shadowing the task
    input property;
  - consolidated repeated Gradle `ExternalModuleDependency` downcasts in tests behind named
    fixture helpers.
- Left deliberate larger-shape items for classification rather than silent rewrite:
  - encoded declared project dependency edge strings are a future typed-metadata schema change;
  - `ResolvedDependency.dependencies` string notation is model debt outside this preserving item;
  - task metadata/exclude-rule JSON schema remains file-backed and task-action decoded, but a
    deeper typed nested-input model is a future task-boundary item.
- Verification:
  - `./gradlew :grazel-gradle-plugin:compileKotlin :grazel-gradle-plugin:compileTestKotlin --console=plain --no-daemon`
    passed in `20s`.
  - `reports/scripts/source-shape-inventory.sh` reran after edits.
  - Current detector distribution after the batch: `77 none`, `12 unchecked_cast`,
    `9 source_string_assertion`, `8 reflection_or_dynamic_access`, `8 private_helper_model`,
    `4 source_string_assertion,comment_or_context_artifact`,
    `2 unchecked_cast,source_string_assertion,comment_or_context_artifact`,
    `2 unchecked_cast,source_string_assertion`,
    `2 reflection_or_dynamic_access,unchecked_cast,source_string_assertion`,
    `2 reflection_or_dynamic_access,unchecked_cast`,
    `2 project_extension,source_string_assertion`, `2 project_extension`,
    `2 comment_or_context_artifact`, `1 reflection_or_dynamic_access,unchecked_cast,comment_or_context_artifact`,
    `1 private_helper_model,source_string_assertion`, and `1 generic_collection_receiver,unchecked_cast`.
- Additional read-only subagents dispatched for a final production-row audit and test-row audit
  against the current tree. Parent reconciliation remains required before TSV rows can be terminal.

## 2026-06-29 Remediation Checkpoint 2

- Reconciled the final production/test audit findings:
  - `BucketSetMath` receiver API is now explicit role-named functions; call sites in
    `BucketOwnershipPlanner`, `DependencyBucketPlacementEngine`, and tests were updated.
  - `DeclaredDependencyMetadataCollector` no longer exposes generic `Iterable<Configuration>` or
    `Map<String, ProjectExcludeRules>` receiver helpers; `Dependencies.kt` no longer uses the
    local collection receiver helper.
  - `DependencyGraphs.kt` no longer has the branch-introduced `Project.sourceNode` receiver helper.
  - `DefaultDependencyResolutionServiceTest` no longer uses executable `TODO` in the fake service.
  - `DefaultAndroidTestDataExtractorTest` now asserts structured `ProjectDependency` fields rather
    than `toString()`/regex labels.
  - `BuildVariantTest` parses `dependencies.json` exclude rules for the selected-fallback checks
    instead of searching the raw JSON string.
  - `DefaultArtifactPinnerTest` constructs `DefaultArtifactPinner` directly from the
    `GrazelExtension` instead of downcasting the Dagger-provided interface.
- Verification:
  - First compile after the set-math rewrite failed due to one malformed call-site parenthesis in
    `DependencyBucketPlacementEngine.kt`; root cause was mechanical rewrite syntax, fixed in the
    same file.
  - `./gradlew :grazel-gradle-plugin:compileKotlin :grazel-gradle-plugin:compileTestKotlin --console=plain --no-daemon`
    then passed in `17s`.
  - `./gradlew :grazel-gradle-plugin:compileKotlin :grazel-gradle-plugin:compileTestKotlin :grazel-gradle-plugin:compileFunctionalTestKotlin --console=plain --no-daemon`
    passed in `10s` after the final test-shape edits.
  - `reports/scripts/source-shape-inventory.sh` reran after edits.
- TSV status:
  - `135` changed Kotlin files and `135` TSV data rows.
  - No pending or blank status/action/rationale/verification fields remain.
  - Terminal review statuses: `21 fixed`, `71 no_issue`, `43 retained_problem_essential`.
  - Remaining retained detector hits are documented as DSL algebra, Gradle API class-token/cast
    boundaries, generated-output renderer assertions, fixture file setup, or existing extractor
    `Project` helpers; no generic collection receiver hit remains.

## 2026-06-29 Verification Checkpoint 3

- Focused failure/fix notes:
  - `CollectKspProcessorDependenciesTaskTest` initially failed because the source-shape rename
    left a stale reflective getter lookup for `getKspDirectDependencies`; updated the test to the
    live `getKspDirectDependencyShortIds` accessor and reran the focused test.
  - `reports/scripts/verify-json-phase-inventory.sh` initially failed because moving a helper
    model changed the `AnalyzeVariantCompressionTask.kt` `Json.encodeToString` line from `136` to
    `150`; updated the inventory line after verifying the encode still happens in task action
    scope.
- Grazel verification:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed in `39s`.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed in `10s`.
  - `reports/scripts/verify-json-phase-inventory.sh` passed.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails only on the known pre-existing
    appcompat/constraintlayout exclude waiver.
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed unchanged: bucket count
    `11`, pinfile count `11`, total artifact roots `1945`, no per-repo deltas.
  - `git diff --check` and `git diff --check master...HEAD` passed.
- PAX verification:
  - Disk dropped to about `8.3 GiB` free before the PAX loop, so `bazelisk clean --expunge` was run
    in `/Users/arun.sampathkumar/work/pax-android`; this freed space without deleting
    `pax-android/bazel-cache`.
  - PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks` passed
    in `12m 32s` and logged `mode=PROJECT_TASK_FANOUT projects=2327 shards=2327
    aggregateJsonBytes=35247531 elapsedMs=658`.
  - A pre-compaction PAX APK build wrapper became idle with no command-log growth; killed only that
    stale wrapper/client and reran the same verification.
  - PAX `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed in `139.050s`.
  - PAX focused unit tests passed:
    `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test
    //app-test:app-test-gps-pax-debug-test
    //application-initializer:application-initializer-gps-pax-debug-test`
    completed in `19.310s` with `3 tests pass`.
  - PAX `git diff --check` passed and `git status --short` remains exactly the accepted local
    baseline dirty set: `Constants.kt`, `Grazel.kt`, `ModuleLoggerTask.kt`,
    `generated/dependency_graph.json`, and untracked `Buildifier.kt`.
- Remaining work before Item 28 completion: run the required simplify pass and adversarial review
  over the branch diff, fix or explicitly reject concrete findings, rerun impacted gates, and
  commit Grazel locally only after green.

## 2026-06-29 Simplify-Pass Checkpoint 4

- Ran the required simplify-pass review with four read-only subagents:
  - reuse,
  - simplification,
  - efficiency,
  - altitude.
- Applied local preserving findings:
  - `BucketOwnershipPlanner`: removed stored `aggregateMainCoveredDeps`; the aggregate is now
    derived from `MainBucketPlanResult.coveredDependencies()`.
  - `DependencyBucketPlacementEngine`: memoizes candidate dependencies by bucket during a single
    placement plan instead of recomputing the same closure for full-coverage detection and bucket
    selection.
  - `BucketSetMath`: avoids per-dependency filtered-list allocation in
    `withoutDependenciesCoveredByShortId` and uses `retainAll` for owner intersections.
  - `BucketHierarchyGraph`: builds leaf-descendant maps by inverting ancestor data once instead of
    scanning every leaf for every node.
  - `MavenInstallRootArtifacts`: passes excluded fallback roots instead of allocating a filtered
    transitive-classpath map per scoped variant.
  - `CollectDeclaredDependencyMetadataTask`: collapsed duplicated task-output wrapper state.
  - `BuildVariantTest`: shared one dependency JSON object traversal between short-id and exclude
    artifact helpers.
  - `reports/scripts/source-shape-inventory.sh`: added `mktemp` cleanup via `trap`.
  - Shared Gradle component dependency fixture moved into `com.grab.grazel.fake.addDependencyTo`.
- Explicitly deferred/rejected for this preserving item:
  - structured `OverrideTarget` instead of Bazel label strings,
  - regex-free target reference facts,
  - removing the consumer-first `WorkspacePlanService` mutation feedback,
  - typed variant-owned bucket metadata instead of bucket-name interpretation.
  These are real altitude concerns, but they require schema/model behavior work and belong to a
  follow-up architecture item, not Item 28 source-shape cleanup.
- Verification after simplify fixes:
  - `./gradlew :grazel-gradle-plugin:compileKotlin :grazel-gradle-plugin:compileTestKotlin
    :grazel-gradle-plugin:compileFunctionalTestKotlin --console=plain --no-daemon` passed in
    `13s`.
  - `reports/scripts/source-shape-inventory.sh` reran.
  - Focused dependency tests passed in `12s`:
    `BucketOwnershipPlannerTest`, `DependencyBucketPlacementEngineTest`,
    `AggregatedDependencyResolverTest`, and `ResolvedComponentsVisitorTest`.
  - Inventory remains `135` changed Kotlin rows for `135` changed Kotlin files; no pending or
    blank row fields.

## 2026-06-29 Adversarial/Post-Review Checkpoint 5

- Applied confirmed adversarial findings:
  - `WorkspaceTargetTagPlanCollector` now clears `transitiveMavenDepsCache` along with
    `variantsByProjectPath` at collection boundaries. Root cause: singleton cache state could leak
    across repeated task/service use.
  - `AnalyzeVariantCompressionTask` now creates the compression output parent directory before
    writing the JSON output.
  - `reports/scripts/source-shape-inventory.sh` now inventories committed branch diff,
    working-tree changes, staged changes, and untracked Kotlin files. This caught
    `ManifestValuesBuilder.kt`, which was reviewed as a new terminal row.
- Rejected/deferred findings with evidence:
  - `ResolvedComponentResult` task inputs remain intentional/master-like/cacheable per maintainer
    direction; not converted to untracked/provider-only inputs in this preserving source-shape
    item.
  - Master-like Maven repository narrowing was tried in `MavenInstallArtifactsCalculator`, but
    `./gradlew migrateToBazel --console=plain --no-daemon` proved it changes generated output by
    removing Google Maven from `ksp_maven`, `lint_maven`, and `test_maven` pins. Reverted that
    behavior/test and kept it as a future output-changing candidate, not an Item 28 change.
- Inventory status after final rerun:
  - `reports/scripts/source-shape-inventory.sh` wrote `136` data rows.
  - No pending or blank status/action/rationale/verification fields remain.
  - Terminal review statuses: `21 fixed`, `72 no_issue`, `43 retained_problem_essential`.
- JSON inventory repair:
  - `reports/scripts/verify-json-phase-inventory.sh` failed because source reshaping moved JSON
    helper call line numbers in `AnalyzeVariantCompressionTask.kt` and
    `CollectDeclaredDependencyMetadataTask.kt`.
  - Verified all moved sites still run in task actions and use file-backed Gradle inputs/outputs,
    then updated only the TSV coordinates.
- Final Grazel gates:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed in `45s`.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed in `20s`; generated
    `WORKSPACE`, `BUILD.bazel`, and Maven pin JSONs are empty-diff.
  - `reports/scripts/verify-json-phase-inventory.sh` passed.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed unchanged:
    `bucketCount=11`, `pinfileCount=11`, `totalArtifactRoots=1945`, no per-repo deltas.
  - `git diff --check` and `git diff --check master...HEAD` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails only on the known pre-existing
    appcompat/constraintlayout exclude waiver.
- Final PAX gates:
  - PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks` passed
    in `12m 12s`, with `mode=PROJECT_TASK_FANOUT projects=2327 shards=2327
    aggregateJsonBytes=35247531 elapsedMs=554`.
  - Ran `bazelisk clean --expunge` in `/Users/arun.sampathkumar/work/pax-android` before the APK
    gate because disk was low; preserved `pax-android/bazel-cache`.
  - PAX `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed in `267.070s`.
  - PAX focused tests passed in `22.898s`:
    `//app-utils:app-utils-gps-pax-debug-test`, `//app-test:app-test-gps-pax-debug-test`, and
    `//application-initializer:application-initializer-gps-pax-debug-test`.
  - PAX `git diff --check` passed and `git status --short` remains exactly the accepted local
    baseline dirty set: `Constants.kt`, `Grazel.kt`, `ModuleLoggerTask.kt`,
    `generated/dependency_graph.json`, and untracked `Buildifier.kt`.
