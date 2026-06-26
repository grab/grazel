# Dependency Refactor Review Guide

This branch rewires dependency resolution and workspace generation around one structured
planning layer while keeping Gradle-resolved values as the source of truth.

## What Changed

- Root app and `com.android.test` classpaths are resolved once and used as the resolved
  value source for workspace planning.
- `WorkspacePlan` / `WorkspaceRenderPlan` now own candidate Maven repos, materialized
  repos, tag decisions, target references, and pin inputs.
- Root generation, Maven pinning, and tag-producing extractors now read from those plans
  instead of scraping generated BUILD/WORKSPACE output.
- Generated-output feedback paths were removed: project Maven repo manifests,
  WORKSPACE repo discovery, extractor-side transitive tag fallbacks, and temporary plan
  parity code.
- Exclude merging now uses intersection where Gradle semantics require it.
- Non-default Maven repo artifacts are selected from variant-scoped Gradle-resolved
  provenance instead of a global `shortId` winner.
- Strict reachability now disables active BUILD output for concrete projects with no
  reachable generated targets, while keeping explicitly referenced project targets alive.

## Important Invariants

- Gradle-resolved artifacts and versions are authoritative.
- Declared metadata is cheap metadata only; declared versions do not select resolved
  values.
- Coursier is constrained through `maven_install.artifacts`; do not add
  `--force-version` shortcuts.
- Compile-filter Maven tags use `@maven//:` labels only.
- Candidate Maven repos are not automatically materialized.
- PAX generated files are verification output only and must not be committed from this
  Grazel branch.

## PAX Diff Shape

The current generated PAX shape is build-correct for the main debug APK and android-test
APK gates, but it is not byte-identical to PAX master.

Accepted diff classes:
- fewer/folded Maven repos where bucket dedupe improved;
- deleted or ignored active BUILD files for unreachable concrete projects;
- dependency moves caused by exclude intersection and variant-scoped provenance;
- default-owned artifacts rooted in child repos for Coursier pinning while redirecting
  Bazel labels back to `@maven`.

Known remaining concern:
- test/android-test/lint Maven pin files are still larger than desired. See
  `reports/specs/KNOWN-LIMITATIONS.md`.

## Current Verification Evidence

Latest recorded PAX gates:
- `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace` passed.
- `./bazel.sh build --jobs=4 --disk_cache= --verbose_failures
  //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
  passed after one automatic transient remote-cache retry.
- Focused PAX unit-test gate passed for:
  `//app-utils:app-utils-gps-pax-debug-test`,
  `//app-test:app-test-gps-pax-debug-test`, and
  `//application-initializer:application-initializer-gps-pax-debug-test`.
- PAX `git diff --check` passed after generation.
- PAX bounded audit passed: no bucket-prefixed Maven labels in tags, strict
  reachability spot-check for `bug-report-kit-implementation` passed, WORKSPACE had
  5327 lines and 24 `maven_install` entries.

Latest recorded Grazel gates:
- `:grazel-gradle-plugin:test` passed.
- `:grazel-gradle-plugin:check` passed.
- `verify-default-task-graph.sh` passed.
- `verify-sample-bucket-labels.sh` passed.
- `verifyGrazelGoldenBaseline` passed before the latest PAX reachability fixes.
- Grazel `git diff --check` passed after the latest log update.

Documented local waivers:
- Fresh `./gradlew check --console=plain --no-daemon` failed on unchanged
  sample-app lint at
  `sample-android/src/main/res/layout/activity_main.xml:73 MissingConstraints`.
- Local `bazelisk build //...` / `bazelisk test //...` are not green because of
  sample/rule hygiene issues, not dependency-refactor regressions:
  crashlytics generated manifest output missing in Android configuration, and duplicate
  `generated_value` `res_values` in `flavors/sample-android-flavor`.
- See `reports/specs/KNOWN-LIMITATIONS.md` and
  `reports/specs/execution-log/item11-final-verification-waivers.md`.

## Review Hotspots

- `AggregatedDependencyResolver` and its `ResolutionSession` orchestration.
- `WorkspacePlanService`, `WorkspacePlan`, and render-plan materialization boundaries.
- `TargetMavenRepoReferencesCollector` and exact referenced project target propagation.
- Android/Kotlin target reachability filtering and BUILD disabling behavior.
- Maven pin activation in `MavenRules` and `ArtificatPinner`.
- Tests for exclude intersection, provenance, reachability, and pinning.
