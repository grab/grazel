# Item 15 - Rendering Purity + Hygiene Execution Log

## 2026-06-28 +08 Start

- Start commit: `29ada0b083c3390de2ed3aa5eacd93fb2d6111fe`
  (`Slim compute workspace dependencies`).
- Grazel worktree was clean at start.
- Spec: `reports/specs/2026-06-27-item15-rendering-purity-hygiene-design.md`.
- Goal: behavior-preserving cleanup after the layering pass.
- Acceptance focus:
  - `commonAncestorsOf` / `closestCommonAncestorsOf` either wired in production
    and tested or deleted;
  - remove dead `CollectTargetMavenRepoReferencesTask` `readText()` and dead
    `materializedMavenRepos` fallback/defaults only after caller grep;
  - add direct `WorkspaceRenderPlanBuilder` tests for materialization from
    `repoPlan`, override-target closure BFS, and only-direct-owned roots;
  - confirm/document renderer purity while acknowledging existing model feedback
    through target builders / `WorkspacePlanService`.
- Subagents dispatched:
  - read-only rendering/dead-code audit;
  - read-only `WorkspaceRenderPlanBuilder` test-seam audit.
- Next: wait for audits, spot-check findings, implement the minimal
  behavior-preserving cleanup, then run local/PAX empty-diff gates.

## 2026-06-28 +08 Implementation

- Audit findings confirmed:
  - `commonAncestorsOf` / `closestCommonAncestorsOf` had no production callers;
    tests were speculative only.
  - `compressionResults.get().asFile.readText()` in
    `CollectTargetMavenRepoReferencesTask` discarded its value and was not
    needed for Gradle input tracking because `compressionResults` remains an
    `@InputFile`.
  - `MavenInstallArtifactsCalculator` had a stale nullable/default
    materialized repo fallback, while production root generation already passes
    `WorkspaceRenderPlan.materializedRepoNames`.
  - `WorkspaceRenderPlanBuilder` lacked direct tests for its own
    materialization and override-closure logic.
- Changes:
  - Added `WorkspaceRenderPlanBuilderTest` with direct fixtures for:
    - referenced + always materialized + aggregated repos;
    - transitive override-target BFS closure;
    - only direct-owned variant pin inputs materializing.
  - Deleted unused `BucketHierarchyGraph.commonAncestorsOf` and
    `closestCommonAncestorsOf`, plus their tests.
  - Removed discarded `readText()` from
    `CollectTargetMavenRepoReferencesTask`.
  - Removed `MavenInstallArtifactsCalculator`'s `referencedMavenRepos` fallback
    and nullable/default `materializedMavenRepos`; `WorkspaceBuilder` and the
    calculator now require explicit render-plan materialized repos.
  - Updated tests to pass explicit materialized repo sets. Calculator-focused
    tests use a named local helper that materializes all repos by default,
    preserving their existing intent without reintroducing production fallback.
- Focused verification passed:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon --tests
  "com.grab.grazel.gradle.dependencies.WorkspaceRenderPlanBuilderTest" --tests
  "com.grab.grazel.migrate.dependencies.MavenInstallArtifactsCalculatorTest"
  --tests "com.grab.grazel.gradle.variant.BucketHierarchyGraphTest" --tests
  "com.grab.grazel.migrate.AndroidWorkspaceRepositoriesTest" --tests
  "com.grab.grazel.migrate.DaggerWorkspaceRuleTest" --tests
  "com.grab.grazel.migrate.KotlinWorkspaceRulesTest"`.
- Renderer purity confirmation:
  - Project/root/workspace builders consume model/task inputs and render output;
    they do not parse generated files to infer ownership.
  - Pinner reads generated `WORKSPACE` and lock JSON for pin state/recovery only;
    pinnable repo selection comes from `WorkspacePlan` +
    `WorkspaceRenderPlan`.
  - Existing target-builder / `WorkspacePlanService` model feedback is not
    generated-file parsing and remains out of scope.

## Next

- Run full plugin tests, local `migrateToBazel`, task graph/sample scripts, and
  empty-diff checks.
- If local output remains unchanged, run the PAX preserving loop and size guard.

## 2026-06-28 +08 Verification

- Full plugin tests passed:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`.
- Local migration passed:
  `./gradlew migrateToBazel --console=plain --no-daemon`.
  No generated files were added to the Grazel diff.
- Local hygiene/script gates:
  - `git diff --check` passed.
  - `git diff --check master...HEAD` passed.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails only on the
    known appcompat/constraintlayout one-sided exclude-union waiver.
  - `./gradlew verifyGrazelGoldenBaseline --console=plain --no-daemon` fails
    only because it wraps that same known sample-label waiver after a successful
    local generation.
- PAX preservation:
  - Pre-migrate diff hash:
    `5f05c2380375f16b0c04c6fa5f14d3a1666cf94d6b36a5ce1e0814a1b6e43566`
  - Pre-migrate status hash:
    `b9b38774443602baa0adf251daeb236e68cd181e1f4ccdf74ee412a30822c6d6`
  - Pre-migrate dirty entries: `2231`.
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
    --rerun-tasks` passed in 11m 11s.
  - Post-migrate and post-build/test PAX diff/status hashes and dirty count
    stayed unchanged.
  - PAX `git diff --check` passed.
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed:
    `11` buckets, `11` pinfiles, `1945` total artifact roots, no per-repo
    deltas.
  - PAX APK build passed:
    `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk`.
  - PAX focused tests passed:
    `./bazel.sh test --test_output=errors
    //app-utils:app-utils-gps-pax-debug-test
    //app-test:app-test-gps-pax-debug-test
    //application-initializer:application-initializer-gps-pax-debug-test`.
- Resource notes:
  - Disk stayed around 24-26Gi free; no cache deletion was needed.
  - PAX `bazel-cache` stayed around 14G.
  - No high-RAM `python3.12` process was present.

## Status

- Item 15 implementation and verification are green.
- Remaining before checkpoint commit: final `git diff --check`, review staged
  diff, then commit Grazel changes only. Do not commit PAX.
