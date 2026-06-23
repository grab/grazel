# Dependency Refactor Active Anchor

Last updated: 2026-06-23.

Read this file first after compaction/resume, then read
`reports/dependencies-refactor-current-status.md` for the evidence ledger. Do not load legacy long
logs in main context; use subagents for bounded archaeology.

## Current Goal

Get the dependency refactor to merge-ready correctness and architecture:

- root app / `com.android.test` Gradle resolution is the expensive source of truth;
- variant APIs and cheap declared metadata drive ownership, excludes, bucket shape, compileOnly, and
  typed test/androidTest classification;
- module generation stays local and consumes dependency-service/workspace data;
- active project `BUILD.bazel` files are generated only for selected roots and projects reachable
  from selected root graphs;
- PAX `migrateToBazel`, debug APK, and android-test APK pass with acceptable generated diff shape;
- shortcut fixes, wrong-altitude graph walks, and broad WORKSPACE bloat are removed before final
  review.

## Current Invariants

- Gradle-resolved versions and artifact-edge closures win over declared versions.
- Maven `tags` are local classpath-filter metadata and normalize to `@maven//:artifact_name`.
- Actual `deps` keep owning repos such as `@maven`, `@debug_maven`, `@android_test_maven`,
  `@lint_maven`, or `@ksp_maven`.
- Target tags are own direct Maven roots plus their Gradle-resolved closure, plus direct project
  tags. Do not union child project Maven closures into parent tags.
- Do not restore `MavenTagClosureCollector` or extractor-side project dependency walking.
- Existing graph work is real: `BucketHierarchyGraph` and `DependencyBucketPlacementEngine` own
  graph-backed bucket placement. Audit/finish that path; do not restart DAG bucketing from scratch.

## Latest Decisions

- Strict reachability-scoped generation is now the intended behavior. PAX
  `bug-report-kit-implementation` is outside the selected app graph, so it should not get an active
  generated `BUILD.bazel` in this slice.
- No declared-Maven fallback closure for unreachable modules. Declared metadata stays cheap support
  data; selected root resolution stays authoritative.
- Stale active `BUILD.bazel` for skipped/unreachable modules should be renamed to
  `BUILD.bazelignore`; formatting should skip intentionally absent generated inputs.
- Old Grazel master only synthesized automatic override targets for inherited/transitive artifacts
  (`!dependency.direct`). Direct Maven deps were not broadly rewritten to default owner.
- WORKSPACE bloat mitigation: non-default repos should only synthesize default-owner override
  targets for default artifacts present in that repo's rooted selected closure, not unrelated default
  artifacts.
- Keep the narrow direct override-carrier behavior only if it preserves extra child closure and is
  covered by tests.
- Test hygiene: do not add reflection-based architecture assertions over task methods/annotations.
  Prefer functional Gradle behavior checks for task graph, up-to-date, and invalidation guarantees.

## Verified In This Run

- Resolver shortcut removed:
  `AggregatedDependencyResolverTest.declared main dependencies from generated non app modules require binary root reachability`
  failed before the fix and passed after.
- Strict generation gate:
  `BuildVariantTest.migrateToBazelIgnoresUnreachableNonAppModules` passed. The unreachable fixture
  module's stale `BUILD.bazel` was renamed and its formatting task skipped.
- WORKSPACE override bloat fix:
  `MavenInstallArtifactsCalculatorTest` failed with broad default overrides and passed after scoping
  default-owner overrides to rooted artifacts.
- Reflection-test cleanup:
  branch-added task/API reflection tests were removed; `BuildVariantTest` now verifies
  `resolveWorkspaceDependencies` task graph, up-to-date, declaration/edge/exclude invalidation, and
  KSP invalidation behavior directly. The resolver negative-path test now uses a concrete fake root.

## Resource Rules

- Before expensive Gradle/Bazel/PAX commands, check disk, CPU, memory, and stray Gradle/Bazel
  processes.
- Stop idle Gradle/Bazel processes when they create real pressure.
- Prefer `bazelisk clean --expunge`; delete PAX `bazel-cache` only when genuinely needed.
- Check for high-RAM `python3.12` processes during resource checks and kill them before proceeding
  if they are consuming significant memory.

## Next Gates

- Run focused graph/placement/resolver tests and relevant `BuildVariant` slices.
- Run `reports/scripts/verify-default-task-graph.sh`, `reports/scripts/verify-sample-bucket-labels.sh`,
  and `git diff --check`.
- Run PAX:
  `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`,
  `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`,
  and `git diff --check`.
- Compare PAX `WORKSPACE`, lockfiles, bucket counts, generated `BUILD.bazel`, and direct/transitive
  tag/deps shape against old PAX master/current baseline. Bucket reduction is acceptable; unexplained
  bloat is not.
- After green correctness, run simplify-pass and adversarial review; fix real findings or document
  rejected findings with rationale.
