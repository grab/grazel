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
