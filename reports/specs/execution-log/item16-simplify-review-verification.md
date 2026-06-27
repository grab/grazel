# Item 16 - Simplify, Review, and Final Verification Execution Log

## 2026-06-28 +08 Start

- Start commit: `0c9af6814019dade55eb0bbb18cc650a52c7d650`
  (`Clean up rendering purity seams`).
- Grazel worktree was clean at start.
- Spec: `reports/specs/2026-06-27-item16-simplify-review-verification-design.md`.
- Goal: quality/review/final verification only; no behavior changes.
- Baseline sanity:
  - `reports/specs/pax-size-baseline.json` exists.
  - Baseline records PAX repo `/Users/arun.sampathkumar/work/pax-android`,
    branch `arun/grazel-refactor`, commit
    `05d2b4801530726ab722133c2ba32cbba9afeb67`.
  - Guarded counts are `11` buckets, `11` pinfiles, `1945` artifact roots.
  - PAX accepted generated state is intentionally dirty, not clean:
    - diff hash:
      `5f05c2380375f16b0c04c6fa5f14d3a1666cf94d6b36a5ce1e0814a1b6e43566`
    - status hash:
      `b9b38774443602baa0adf251daeb236e68cd181e1f4ccdf74ee412a30822c6d6`
    - dirty entries: `2231`
  - Treat stable PAX diff hash as the guardrail, per current goal anchor and
    prior item logs. Do not commit PAX.

## Next

- Run simplify-pass reviewers over `git diff master...HEAD`.
- Apply only behavior-preserving simplifications.
- Run adversarial review and final verification.

## 2026-06-28 +08 Simplify Pass

- Simplify-pass subagents completed reuse, simplification, efficiency, and
  altitude slices.
- Applied behavior-preserving cleanup:
  - Removed unused `GenerateRootBazelScriptsTask.workspacePlan` input and its
    `TasksManager` wiring; root generation consumes `workspaceRenderPlan`.
  - Simplified `TargetVariantReachability.isReachableTargetVariant` to accept
    only `variantName` and the reachable-bucket predicate; removed unused
    `buildType`/`flavors` parameters and test call noise.
  - Reused `Project.isAndroidApplication` / `Project.isAndroidTest` helpers in
    workspace dependency root wiring and declared metadata collection.
  - Wrote already-normalized `TargetMavenRepoReferences` directly from
    `CollectTargetMavenRepoReferencesTask` instead of normalizing twice.
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
    waiver: `WORKSPACE must not union one-sided appcompat exclude onto
    androidx.constraintlayout:constraintlayout`.
- Deferred simplify findings because they are broader than Item 16's
  behavior-preserving scope or need explicit compatibility decisions:
  - Move dependency-root selection policy from `tasks/internal` to a dependency
    layer planner.
  - Replace target-reference label/tag scraping with structured target/model
    references.
  - Make `WorkspacePlan.repoPlan` the single source for root artifact and
    override rendering.
  - Avoid copying `reachableMainBucketsByProject` into every
    `ResolveDependenciesResult`; this changes task output shape and needs a
    deliberate test/compat decision.
  - Optimize immutable accumulation and repeated root/transitive scans in
    collection/planning hot paths.
  - Refactor duplicated unit-test/android-test root registration specs; safe but
    not needed for the verified cleanup batch.

## 2026-06-28 +08 Adversarial Review

- Dependency-correctness reviewer found no findings. Checks included
  `git diff --check`, `git diff --check master...HEAD`,
  `reports/scripts/verify-default-task-graph.sh`, the known failing
  `verify-sample-bucket-labels.sh` waiver, focused dependency unit tests,
  `reports/scripts/verify-pax-size-guard.sh --mode preserving`, and static scans
  for `--force-version`, bucket-prefixed Maven tags, and expected sample buckets.
- Graph/reachability/SCC reviewer found no findings. Checks included focused
  tests for target reachability, topological sorting, dependency graphs,
  workspace-plan tasks, and Android test extraction, plus diff checks.
- Task/render/cache reviewer findings:
  - Fixed stale PAX baseline docs: Item 16, roadmap, review guide, and known
    limitations now describe the accepted dirty PAX generated-output hash/count
    contract and current size baseline (`11` buckets, `11` pinfiles, `1945`
    roots).
  - Fixed dead render-plan residue by removing unused
    `VariantCompressionService.referencedMavenRepos()`.
  - Logged cacheability boundary risk in `KNOWN-LIMITATIONS.md` instead of
    changing annotations: root component handoff and KSP sidecar remain cacheable
    for this slice, but are not fully relocatable.
  - Logged renderer-model reference parsing in `KNOWN-LIMITATIONS.md` as a
    future cleanup; it is not generated-file feedback but still relies on label
    string parsing.
- Post-review focused verification passed after the dead API and docs cleanup:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.migrate.target.TargetVariantReachabilityTest" --tests
  "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest" --console=plain
  --no-daemon`.

## 2026-06-28 +08 Final Verification

- Resource precheck: disk had about 25Gi free on `/System/Volumes/Data`; no
  cleanup performed. Shut down known Grazel/PAX Bazel servers with
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
- `git diff --check master...HEAD` passed.
- `bazelisk build //...` failed only on the documented local sample/rule waiver:
  missing
  `sample-android/crashlytics-demo-free-debug_symlinked_manifest/AndroidManifest.xml`
  during Android resource packaging.
- `bazelisk test //...` failed on the same documented local sample/rule waiver;
  `//sample-android:sample-android-full-free-debug.lint_test` failed to build
  because the crashlytics symlinked manifest could not be opened. Bazel reported
  9 tests passed, 1 failed to build, and 7 skipped.
- PAX verification started from `/Users/arun.sampathkumar/work/pax-android`
  branch `arun/grazel-refactor` at `05d2b4801530726ab722133c2ba32cbba9afeb67`.
  `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
  --rerun-tasks` passed in 11m17s; pinning was up-to-date.
- PAX generated-output baseline reproduced exactly after migrate:
  `git diff --binary | shasum -a 256` =
  `5f05c2380375f16b0c04c6fa5f14d3a1666cf94d6b36a5ce1e0814a1b6e43566`,
  `git status --short` hash =
  `b9b38774443602baa0adf251daeb236e68cd181e1f4ccdf74ee412a30822c6d6`, and
  dirty-entry count remained `2231`.
- PAX `git diff --check` passed after migrate.
- `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed against
  PAX: bucket count `11`, pinfile count `11`, and total artifact roots `1945`
  all matched the frozen baseline exactly.
- PAX `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
  //app:app-gps-pax-debug-android-test.apk` passed. Bazel reported `8548`
  total actions, with disk/remote cache hits, and completed successfully in
  233.740s.
- PAX `./bazel.sh test --test_output=errors
  //app-utils:app-utils-gps-pax-debug-test
  //app-test:app-test-gps-pax-debug-test
  //application-initializer:application-initializer-gps-pax-debug-test` passed.
  Bazel reported 3 test targets passing.
- PAX generated-output baseline remained unchanged after Bazel build/test:
  diff hash
  `5f05c2380375f16b0c04c6fa5f14d3a1666cf94d6b36a5ce1e0814a1b6e43566`,
  status hash
  `b9b38774443602baa0adf251daeb236e68cd181e1f4ccdf74ee412a30822c6d6`, and
  dirty-entry count `2231`.
- PAX `git diff --check` passed after Bazel build/test.
