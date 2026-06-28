# Item 25 - Merge Generate + Format Tasks

## Current Status

- 2026-06-28 +08 STARTED from clean local Grazel commit `fb2b9ab`
  (`Refine workspace plan cleanup`). Do not push Grazel.
- PAX regression workspace is `/Users/arun.sampathkumar/work/pax-android` on
  branch `arun/grazel-refactor` at baseline commit
  `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`. Do not commit PAX.
- Active spec:
  `reports/specs/2026-06-28-item25-merge-generate-format-tasks-design.md`.
- Goal: preserving task-graph cleanup. Merge each generate task with its
  formatting step, delete the standalone format task, preserve byte-identical
  final `BUILD.bazel`/`WORKSPACE` output, and keep the exact buildifier
  invocation/temp-copy isolation.
- Known unchanged waiver: `reports/scripts/verify-sample-bucket-labels.sh`
  fails on the pre-existing appcompat/constraintlayout exclude-union case.

## Decisions

- The task names that users call remain `generateBazelScripts` per project and
  `generateRootBazelScripts` at root. The old `formatBazelScripts`,
  `formatWorkSpace`, and `formatBuildBazel` task registrations should disappear
  from the live graph.
- The accepted tradeoff is documented by the spec: formatting is no longer a
  separate `@CacheableTask`; generation is already `@UntrackedTask`, so this
  avoids task graph complexity at the cost of rerunning buildifier as part of
  generation.
- Preserve final-file safety: buildifier formats a temp file first, then the
  task materializes the final source-tree output.

## Commands And Results

- `./gradlew :grazel-gradle-plugin:compileKotlin --console=plain --no-daemon`
  passed in 26s. This confirmed Gradle service injection for the merged task
  constructors.
- `reports/scripts/verify-default-task-graph.sh` passed after adding Item
  25-specific assertions: no standalone `format*` tasks in `migrateToBazel`
  dry-run, `generateBuildifierScript` before `generateRootBazelScripts`,
  `pinMavenArtifacts` after `generateRootBazelScripts`, and `migrateToBazel`
  after pinning.
- The verifier was later tightened and rerun to also require at least one
  project `generateBazelScripts` task in `migrateToBazel`, buildifier before
  every project generation task, and `postScriptGenerateTask` after all project
  generation tasks and before `migrateToBazel`.
- `./gradlew migrateToBazel --console=plain --no-daemon` passed in 15s. The
  task output showed `generateBuildifierScript`, merged project/root generation,
  then `pinMavenArtifacts`; there were no `formatBazelScripts`,
  `formatWorkSpace`, or `formatBuildBazel` tasks.
- Generated final files are empty-diff after local migrate:
  `git diff -- BUILD.bazel WORKSPACE '**/BUILD.bazel' '**/*.json'` produced no
  output.
- `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed in
  42s.
- `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed:
  11 buckets, 11 pinfiles, 1945 total artifact roots, no per-repo deltas.
- `git diff --check` and `git diff --check master...HEAD` passed.
- `reports/scripts/verify-sample-bucket-labels.sh` still fails only on the
  known pre-existing appcompat/constraintlayout exclude-union assertion.
- PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
  --rerun-tasks` first passed in `12m16s` after local PAX task-hook adaptation,
  but left a `WORKSPACE` deletion for `rules_java_builtin`. Root cause: the PAX
  hook matched the old unformatted
  `load("@bazel_tools//tools/build_defs/repo:git.bzl",  "git_repository")`
  line; Item 25 formats root output before the hook runs, so the line now has
  one space.
- After fixing that local PAX hook, a retry initially failed at
  `generateBuildifierScript` because the previous bad migrate had left the
  checked-out PAX `WORKSPACE` missing `rules_java_builtin`. This task runs
  Bazel against the current checked-out `WORKSPACE` before the PAX hook can
  patch root output. Restored the baseline `rules_java_builtin` block locally
  and reran from a valid baseline.
- PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
  --rerun-tasks` then passed in `10m03s`.
- PAX `git diff --check` passed after migrate.
- PAX generated BUILD/WORKSPACE/maven output is stable against the baseline.
  Only `generated/dependency_graph.json` has a one-line ordering-only diff in a
  `dependency_set`; classify as non-semantic PAX dependency-graph output drift
  unless a later gate proves otherwise.
- `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed after
  PAX migrate: 11 buckets, 11 pinfiles, 1945 total artifact roots, and no
  per-repo artifact-root deltas.
- PAX `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
  //app:app-gps-pax-debug-android-test.apk` passed in `215.146s`.
- PAX `./bazel.sh test --test_output=errors
  //app-utils:app-utils-gps-pax-debug-test
  //app-test:app-test-gps-pax-debug-test
  //application-initializer:application-initializer-gps-pax-debug-test`
  passed 3/3 in `16.593s`.

## Remaining Risks

- Task graph rewiring can accidentally break `pinMavenArtifacts` ordering if it
  no longer depends on the merged root task's final `WORKSPACE`.
- The helper extraction must preserve buildifier's observed filename and command
  line, otherwise final output could drift.
- PAX migrate, size guard, APK build, and focused Bazel tests are green.
- PAX buildifier generation depends on the checked-out `WORKSPACE` being valid
  enough to load `@grab_bazel_common//:buildifier`; after a failed local migrate
  that corrupts `WORKSPACE`, restore the baseline generated file before retrying.
