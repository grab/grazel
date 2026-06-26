# Item 4 - Remove Generated-Output Feedback Paths

This log tracks deletion of old feedback paths after Item 3 moved consumers onto
`WorkspacePlan` / `WorkspaceRenderPlan`. Keep entries short and grouped by deletion.

## Step 1 - Manifest path and task-graph decouple

### Target

- Delete generated project Maven repo manifests and `GeneratedBuildMavenRepos`.
- Root generation must read the workspace render plan, not project-generated manifests.
- `generateRootBazelScripts` must no longer depend on project `generateBazelScripts`
  tasks.

### Red checks

- Added `TargetMavenRepoReferencesCollectorTest`; it failed as expected because
  `TargetMavenRepoReferencesCollector` did not exist.
- Updated `reports/scripts/verify-default-task-graph.sh` to assert root generation
  has no project `generateBazelScripts` dependencies. It failed as expected on the
  parity-only legacy edge.

### Change

- Replaced `GeneratedBuildMavenRepos.fromTargets` with
  `TargetMavenRepoReferencesCollector.fromTargets` in the model-based
  `collectTargetMavenRepoReferences` task.
- Deleted project `referenced-maven-repos.txt` outputs and the
  `GeneratedBuildMavenRepos` file/manifest reader.
- Removed root-generation parity wiring that depended on project-generated
  manifests. Remaining parity consumers are left for the final parity cleanup step.

### Verification

- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.tasks.internal.TargetMavenRepoReferencesCollectorTest" --console=plain`
  passed.
- `reports/scripts/verify-default-task-graph.sh` passed.
- `./gradlew verifyGrazelGoldenBaseline -Pgrazel.internal.planParity=true --console=plain`
  passed in 14s with clean generated-file diff.

### PAX verification

- `./gradlew migrateToBazel -Pgrazel.internal.planParity=true --no-daemon --console=plain --stacktrace`
  passed in 10m24s.
- `git diff --check` passed in `/Users/arun.sampathkumar/work/pax-android`.
- Generated tag-prefix audit found zero bucket Maven labels inside `tags`
  arrays.
- `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures`
  passed in 279s with 1 executed action after cache checking.
- Resource notes: disk stayed around 15 GiB free. No cache cleanup was run.

## Step 2 - Pinner WORKSPACE-regex discovery

### Target

- Delete the pinner's `maven_install(name = "...")` WORKSPACE regex helper.
- Keep `maven_install_json` pin/unpin and corruption recovery mechanics.
- Keep remaining pinner parity temporarily, but filter the legacy view with
  `WorkspaceRenderPlan.materializedRepoNames` instead of rendered WORKSPACE text.

### Red check

- Structural search found the old
  `workspaceFile.materializedMavenInstallRepos()` call path before deletion.

### Change

- Pinner parity now uses `workspaceRenderPlan.materializedRepoNames` to filter the
  temporary legacy `WorkspaceDependencies` view.
- Deleted `File.materializedMavenInstallRepos()`.
- Renamed the legacy helper test to avoid implying generated WORKSPACE output is
  still the source of truth.

### Verification

- Structural search found no `materializedMavenInstallRepos` helper/call after the
  change.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.migrate.dependencies.DefaultArtifactPinnerTest" --console=plain`
  passed.
- `./gradlew verifyGrazelGoldenBaseline -Pgrazel.internal.planParity=true --console=plain`
  passed in 17s with clean generated-file diff.

### PAX verification

- `./gradlew migrateToBazel -Pgrazel.internal.planParity=true --no-daemon --console=plain --stacktrace`
  passed in 10m34s. This exercised `pinMavenArtifacts` with the render-plan
  materialized repo filter.
- `git diff --check` passed in `/Users/arun.sampathkumar/work/pax-android`.
- Generated tag-prefix audit found zero bucket Maven labels inside `tags`
  arrays.
- `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures`
  passed in about 280s with 117 total actions.
- Resource notes: disk dipped to about 12 GiB during migrate and was about
  13 GiB before the Bazel gate. No cache cleanup was run.

## Step 3 - Extractor-side tag derivation

### Target

- Tags in project extractors must use local direct tags plus `WorkspacePlan.tagPlan`.
- No extractor may recompute transitive Maven closure as a fallback.
- The workspace tag-plan collector remains the owner of Maven transitive tag closure.

### Red check

- Added no-plan extractor tests for Android library and Android instrumentation
  targets. Both failed before the production change because transitive child Maven
  tags leaked from extractor fallback.

### Change

- Removed Android library extractor's legacy Maven tag walk, cache, and
  `VariantBuilder` dependency.
- Replaced fallback `collectTransitiveMavenDeps` calls with empty plan defaults in
  Android unit test, Android instrumentation, Kotlin library, and Kotlin unit test
  extractors.

### Verification

- Focused red tests passed after the change:
  `DefaultAndroidLibraryDataExtractorTest.extract does not derive transitive maven tag labels without workspace plan`
  and
  `AndroidInstrumentationBinaryDataExtractorTest.extract does not derive transitive maven tags without workspace plan`.
- Extractor structural search found no `collectTransitiveMavenDeps` calls under
  `migrate/*`.
- Focused extractor/tag-plan test slice passed.
- `git diff --check`, `reports/scripts/verify-default-task-graph.sh`, and
  `reports/scripts/verify-sample-bucket-labels.sh` passed.
- `./gradlew verifyGrazelGoldenBaseline -Pgrazel.internal.planParity=true --console=plain`
  passed in 18s with clean generated-file diff.

### PAX verification

- `./gradlew migrateToBazel -Pgrazel.internal.planParity=true --no-daemon --console=plain --stacktrace`
  passed in 10m12s.
- `git diff --check` passed in `/Users/arun.sampathkumar/work/pax-android`.
- Generated tag-prefix audit found zero bucket Maven labels inside `tags`
  arrays.
- `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures`
  passed in 267.777s with 117 total actions.
- Resource notes: disk was about 14 GiB before and after this gate. No cache
  cleanup was run.
