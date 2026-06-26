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
