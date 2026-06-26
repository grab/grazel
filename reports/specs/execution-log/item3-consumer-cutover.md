# Item 3 - Consumer Cutover

This log tracks consumer switches onto `WorkspacePlan` / `WorkspaceRenderPlan`.
Keep entries short and grouped by step.

## Step 1 - Pinner

### Change

- `PinMavenArtifactsTask` now reads `workspace-plan.json` and
  `workspace-render-plan.json` as task inputs and passes the parsed plan models to
  `DefaultArtifactPinner`.
- `DefaultArtifactPinner` now selects pinnable repos from
  `WorkspacePlan.repoPlan` filtered by `WorkspaceRenderPlan.materializedRepoNames`.
- Off-by-default `-Pgrazel.internal.planParity=true` support is wired for the pinner:
  when enabled, the pinner compares plan-derived repos with the legacy
  `WorkspaceDependencies` + WORKSPACE-regex derivation and fails on mismatch.
- The pin/unpin `maven_install_json` toggle and `shouldRunPinning`'s
  `#maven_install_json` scan remain WORKSPACE-based by design; those are pin-state
  mechanics, not repo discovery.
- Legacy helpers `File.materializedMavenInstallRepos()` and
  `WorkspaceDependencies.pinnableMavenInstallRepos()` remain for Item 4 deletion or
  other old consumers.

### Verification

- Focused pinner test, including pure parity assertion coverage:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.migrate.dependencies.DefaultArtifactPinnerTest" --console=plain`
  passed.
- Local task graph:
  `reports/scripts/verify-default-task-graph.sh` passed.
- Local bucket labels:
  `reports/scripts/verify-sample-bucket-labels.sh` passed.
- Local golden with parity enabled:
  `./gradlew verifyGrazelGoldenBaseline -Pgrazel.internal.planParity=true --console=plain`
  passed in 12s with clean generated-file diff.

### Notes

- A read-only subagent confirmed the hidden risk that an empty materialized repo set
  must mean "pin none" for the new plan helper; the old
  `WorkspaceDependencies.pinnableMavenInstallRepos(emptySet())` still means "include
  all" and must not be reused for the pinner cutover.
- `pinMavenArtifacts` local golden probes exactly the repos in the render plan:
  `android_test_maven`, `debug_maven`, `ksp_maven`, `lint_maven`, `maven`,
  `test_maven`.

### PAX verification

- `./gradlew migrateToBazel -Pgrazel.internal.planParity=true --no-daemon --console=plain --stacktrace`
  passed in about 11 minutes.
- `git diff --check` passed in `/Users/arun.sampathkumar/work/pax-android`.
- Disk before the Bazel build gate was tight but usable: `/System/Volumes/Data`
  had about 16 GiB free. No cache cleanup was run before the build gate.
- `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures`
  passed in about 4m43s. Bazel reported 45 actions, 44 disk-cache hits.

## Step 2 - Root generation

### Change

- `GenerateRootBazelScriptsTask` now reads `workspace-plan.json` and
  `workspace-render-plan.json`.
- Normal root WORKSPACE generation uses
  `WorkspaceRenderPlan.materializedRepoNames` instead of reading generated
  project Maven repo manifests.
- The old project-manifest path remains behind
  `-Pgrazel.internal.planParity=true` only. In parity mode, root generation
  rebuilds a render plan from the old manifest references and fails if either
  referenced repo names or materialized repo names differ.
- `WorkspaceBuilder` now accepts materialized repo names, and
  `MavenInstallArtifactsCalculator` treats explicit materialized repo names as
  exact repo selection. The legacy `referencedMavenRepos` fallback still keeps
  its old broad behavior when no references are provided.

### Verification

- Focused calculator tests:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.migrate.dependencies.MavenInstallArtifactsCalculatorTest" --console=plain`
  passed.
- Local task graph:
  `reports/scripts/verify-default-task-graph.sh` passed.
- Local bucket labels:
  `reports/scripts/verify-sample-bucket-labels.sh` passed.
- `git diff --check` passed.
- Local golden with parity enabled:
  `./gradlew verifyGrazelGoldenBaseline -Pgrazel.internal.planParity=true --console=plain`
  passed in 13s with clean generated-file diff.

### PAX verification

- `./gradlew migrateToBazel -Pgrazel.internal.planParity=true --no-daemon --console=plain --stacktrace`
  passed in 10m44s. Root generation parity did not report mismatches.
- `git diff --check` passed in `/Users/arun.sampathkumar/work/pax-android`.
- `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures`
  passed in about 4m41s. Bazel reported 1 action after cache checking.
- Step 2 checkpoint commit: this commit.

## Step 3 - Tag-producing extractors

### Current decision

- Do not derive a tag plan by reading generated `BUILD.bazel` output; generated
  files are the endpoint, not an upstream planning input.
- Do not make `ComputeWorkspacePlanTask` read live Gradle project state directly.
  If tag planning needs Gradle model data, collect it in an earlier task as
  stable JSON, then feed that JSON into the cacheable workspace plan task.
- `AnalyzeVariantCompressionTask` extracts Android library data before final
  target generation, so any extractor dependency on `WorkspacePlan.tagPlan` must
  be available before compression analysis.
- Full tag lists include cheap target-local `@self` / `@direct` labels whose
  exact names can depend on final target suffix/compression decisions. The
  planned first cut should therefore move the expensive Maven transitive closure
  part of tags into the plan, while keeping local target formatting in extractors
  unless a cleaner non-circular full-plan shape is proven.

### Open check before code

- Confirm whether a pre-plan collection task can compute Maven tag closures from
  Gradle variant/dependency APIs without instantiating Bazel targets.
- Keep parity checks enabled where possible: plan-sourced Maven tag labels must
  match the old extractor calculation before removing old paths.

### Change

- Added `CollectWorkspaceTargetTagPlanTask`, which runs after
  `computeWorkspaceDependencies` and writes stable `target-tag-plan.json` from
  Gradle variant/dependency APIs. It does not read generated `BUILD.bazel` files
  or instantiate Bazel targets.
- `ComputeWorkspacePlanTask` now consumes that JSON and stores it in
  `WorkspacePlan.tagPlan`.
- Tag-producing extractors now use `WorkspacePlan.tagPlan` for Maven tag labels
  and keep only target-local `@self` / `@direct` formatting locally. The legacy
  extractor-side Maven closure remains only as a no-plan fallback until Item 4
  removes old feedback paths.
- `AnalyzeVariantCompressionTask`, `CollectTargetMavenRepoReferencesTask`, and
  `GenerateBazelScriptsTask` initialize `WorkspacePlanService` from the plan
  file so cache/up-to-date execution does not depend on in-memory task order.

### Verification

- Focused plan/task/collector/extractor tests passed:
  `WorkspacePlanBuilderTest`, `WorkspacePlanTasksTest`,
  `WorkspaceTargetTagPlanCollectorTest`,
  `DefaultAndroidLibraryDataExtractorTest`, and
  `AndroidInstrumentationBinaryDataExtractorTest`.
- Local task graph: `reports/scripts/verify-default-task-graph.sh` passed.
- Local bucket labels: `reports/scripts/verify-sample-bucket-labels.sh` passed.
- `git diff --check` passed.
- Local golden with parity enabled:
  `./gradlew verifyGrazelGoldenBaseline -Pgrazel.internal.planParity=true --console=plain`
  passed in 13s with clean generated-file diff.

### PAX verification

- `./gradlew migrateToBazel -Pgrazel.internal.planParity=true --no-daemon --console=plain --stacktrace`
  passed in about 11m01s.
- `git diff --check` passed in `/Users/arun.sampathkumar/work/pax-android`.
- `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures`
  passed in about 5m00s. Bazel reported 41 actions after cache checking.
- Generated tag-prefix audit scanned the 2208 changed Bazel files and found
  zero bucket Maven labels inside `tags` arrays. Maven tag labels stayed on
  `@maven//...`; bucket repos remain usable for actual dependency labels.
- Resource notes: `/System/Volumes/Data` stayed around 16 GiB free. Bazel
  memory peaked around 4.4 GiB RSS during the build gate. No Gradle/Bazel cache
  cleanup was run.
- Step 3 checkpoint commit: `8e22c01`
  (`Move target tag planning into workspace plan`).
