# Item 19 - Target Reference Facts

## 2026-06-28 Progress

- Goal: remove the generated-target feedback path from `CollectTargetMavenRepoReferencesTask`.
  `GenerateBazelScriptsTask` should remain the production path that builds/render targets.
- Added `TargetReferenceFacts` as the structured repo/project reference fact model used before
  target rendering.
- Added `TargetReferenceFactsCollector` for the shared parsing rules:
  Maven dependency repos, `@maven`/bucket tag repos, structured project deps, and absolute
  `//path:target` string deps.
- Added `TargetReferenceFactsExtractor` in the target layer. It collects facts from existing
  extractor data models instead of calling `ProjectBazelFileBuilder.targets()`.
- Cut `CollectTargetMavenRepoReferencesTask` over to `TargetReferenceFactsExtractor`.
  The task no longer imports or calls `ProjectBazelFileBuilder`, `BazelTarget`, or
  `TargetMavenRepoReferencesCollector.fromTargets`.
- Kept `TargetMavenRepoReferencesCollector.fromTargets` as a compatibility/test helper only,
  and made it delegate to `TargetReferenceFactsCollector` so parsing logic is single-sourced.
- Preserved known empty-diff blind spots from the previous target-model collector:
  unit-test and app-owned instrumentation targets contribute deps/tags only; standalone
  `com.android.test` targets contribute deps/tags/lint checks/associates/instruments.

## Failure/Fix

- Focused Gradle test command first failed during Kotlin compile because a private member
  extension was used as `String::mavenRepoFromTag`, which Kotlin disallows. Fixed by using an
  explicit lambda.
- The next run failed during KAPT because the new Dagger `@Binds` interface seam exposed
  `TargetReferenceFactsExtractor` through generated Java stubs. The seam was unnecessary for one
  internal task consumer, so the fix was to inject the concrete target-layer service and remove
  the extra binding. This keeps the layer boundary without adding Dagger indirection.

## Verification

- Resource checks before Gradle commands showed about `26GiB` free on Data; no cleanup was
  triggered.
- Focused tests passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest" --tests "com.grab.grazel.tasks.internal.TargetMavenRepoReferencesCollectorTest" --tests "com.grab.grazel.gradle.dependencies.TargetReferenceFactsCollectorTest" --console=plain --no-daemon`.
- Grazel `./gradlew migrateToBazel --console=plain --no-daemon` passed.
- Grazel generated output stayed clean; only source/test files are changed.
- `git diff --check` passed.
- PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
  passed in `10m 55s`; PAX generated output stayed clean against local baseline commit
  `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`.
- PAX `git diff --check` passed.
- `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed:
  bucket count `11`, pinfile count `11`, total artifact roots `1945`, all unchanged.
- PAX `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
  passed in `223.159s`.
- PAX focused Bazel tests passed:
  `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test`.
- PAX worktree stayed clean after migrate/build/test.

## Remaining

- Commit Item 19 locally in Grazel before moving to Item 21.
- Follow-up cleanup candidate: fallback compression logs are noisy during PAX facts collection
  for projects without compression results. This is output noise only; generated files and size
  guard stayed unchanged.
