# Dependency Refactor Current Truth

Last updated: 2026-06-22.

This file was compacted from a long historical log. Do not use it as an append-only transcript.
For resume/compaction, read `reports/dependencies-refactor-active-anchor.md` first, then this file
only if more context is needed.

## Current Architecture

- Expensive dependency resolution runs from root app / `com.android.test` binary classpaths.
- Gradle-resolved component identity is the source of truth for selected versions and transitive
  artifact closure.
- Cheap declared metadata remains useful for ownership, excludes, bucket shape, and test/androidTest
  classification, but must not replace Gradle-selected versions.
- Variant APIs should keep driving the layers: variant construction first, then root/aggregate
  dependency tasks, then workspace/bucket data, then local module generation.
- Module extractors should consume precomputed dependency/service data. They should not reconstruct
  project graph reachability or run expensive resolution.

## Current Tag Contract

- Maven classpath-filter `tags` are normalized metadata in the `@maven//:artifact_name` shape.
- Actual `deps` keep their owning repos such as `@debug_maven`, `@lint_maven`, `@gps_maven`, or
  `@android_test_maven`.
- A target's tags should contain:
  - direct project deps as `@direct//...`;
  - that target's own direct Maven roots;
  - the Gradle-resolved transitive artifact closure for those own direct Maven roots.
- Do not union Maven closures from child project targets into the parent target's tags.
- Do not use transitive `exports` as a substitute for tag closure.

## References

- Grazel master: known to compile PAX, but slow and possibly suboptimal.
- PAX master/generated baseline: known to compile, but may include legacy over-allowing in tags.
- Use both as references, not blind targets. Prove ownership before copying behavior.

## Rejected Shortcut

- The deleted `MavenTagClosureCollector` / graph-aware parent-tag expansion fixed one missing
  annotation symptom but bloated PAX tag output and sat at the wrong layer.
- Do not reintroduce extractor-side project graph walking for Maven tag closure.

## Current Verification State

- Local focused dependency tests passed after cleanup:
  - `DefaultDependenciesDataSourceTest`
  - `DefaultAndroidLibraryDataExtractorTest`
  - `ComputeWorkspaceDependenciesTest`
  - `DefaultDependencyResolutionServiceTest`
  - `MavenInstallArtifactsCalculatorTest`
- Local Grazel `migrateToBazel`, default task graph verification, sample bucket label verification,
  and `git diff --check` passed after the latest cleanup.
- PAX `migrateToBazel` passed after the `deliveries-menu-items` ownership fix and again after the
  duplicate-annotation databinding filter fix.
- PAX debug APK + android-test APK passed after the duplicate-annotation fix:
  `//app:app-gps-pax-debug.apk` and `//app:app-gps-pax-debug-android-test.apk`.
- The previous `androidx.annotation.VisibleForTesting` compile failure is resolved by adding an
  explicit PAX module dependency. Do not treat it as an open Grazel tag-broadening issue.
- The duplicate `@maven//:androidx_annotation_annotation` failure was caused by emitting annotation
  directly to databinding-enabled app targets while grab-bazel-common databinding macros inject it.
  Current fix: filter databinding-provided artifacts from direct deps only for databinding targets.
- `//app:app-gps-pax-debug.lint_test` fails on `SerializedNameDefaultValue` errors inside external
  Maven AARs, and the current audit points to preexisting PAX baseline exposure rather than a
  missing-class/dependency failure.
- PAX APK build still prints rules_jvm_external duplicate-version debug messages for annotation,
  databinding, Dagger, and Kotlin artifacts. They are warnings in the passing build, but remain a
  cleanup item if Coursier warning elimination is required; bounded audit points to preexisting
  `WORKSPACE` composition, not duplicated generated JSON rows.
- Bounded generated PAX target audit found no obvious tag-contract violation: `app-gps-pax-debug`
  deps count is unchanged vs `HEAD`, no direct annotation dep is emitted while databinding is
  enabled, and android-test tags are unique/normalized.

## Current Blocker Discipline

- If a missing-class failure appears again, produce focused evidence for the failing
  module/variant:
  - exact Gradle configurations contributing to the target;
  - declared Maven deps seen by Grazel for those configurations;
  - selected bucket/repo label for the missing artifact;
  - transitive closure lookup result used by `collectTransitiveMavenDeps`;
  - generated `deps` and `tags` for the failing Bazel target.
- Good feedback mechanisms:
  - temporary focused Gradle task or `logger.quiet` diagnostics;
  - small generated diagnostic file under `build/grazel/...`;
  - targeted `jq`/script reads of existing JSON;
  - focused Bazel target build before full APK gates.
- If evidence shows the PAX module uses a Maven artifact without declaring it, prefer a PAX
  `build.gradle` declaration plus regeneration over broadening Grazel tags.
- If evidence shows Grazel dropped valid owned metadata, add a focused Grazel regression test first
  and fix the lowest correct layer.

## Context Maintenance

- Keep `reports/dependencies-refactor-active-anchor.md` under roughly 150 lines.
- Keep this file compact. If it starts growing into a transcript, rewrite it into current truth.
- Detailed command outcomes belong in `reports/dependencies-refactor-current-status.md`, but stale
  or superseded sections should be compacted after each major milestone.
- Use subagents for old-history archaeology only with a narrow question and required line citations.
