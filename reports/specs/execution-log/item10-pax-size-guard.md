# Item 10 Execution Log — PAX Size Guard

## 2026-06-28 Start

- Goal item: freeze the current PAX generated Maven size baseline and add a
  fail-closed guard for later altitude-layering items.
- Grazel starting commit for this item: `08263b4` after the docs-only goal-plan
  checkpoint.
- PAX baseline source:
  `/Users/arun.sampathkumar/work/pax-android`, branch `arun/grazel-refactor`,
  commit `05d2b4801530726ab722133c2ba32cbba9afeb67`.
- PAX worktree is intentionally dirty with generated output from the accepted
  baseline. Do not commit PAX changes.
- Resource check before work: about `77GiB` free on the data volume; no obvious
  stale Gradle/Bazel/Coursier or high-RAM `python3.12` process in the top memory
  list.

## Baseline Measurement

- Added `reports/scripts/verify-pax-size-guard.sh`.
- Initial implementation tried to count raw `WORKSPACE` artifact strings, then
  was corrected before acceptance because `WORKSPACE` can contain
  `maven.artifact(...)` and list-concatenated artifacts such as
  `DAGGER_ARTIFACTS + [...]`.
- Decision: the machine baseline identity is each active pin JSON's
  `__INPUT_ARTIFACTS_HASH`, encoded as sorted `artifact=hash` strings. This
  preserves materialized Coursier input identity including exclusions.
- Subagent independently confirmed the PAX branch/SHA and counts:
  - active `maven_install` repos: `11`
  - active pin JSON files: `11`
  - total artifact roots: `2015`
  - total resolved artifacts observed in pin JSONs: `2184`
  - `debug_unit_test_maven_install.json` is deleted in PAX status but not active
    in `WORKSPACE`, so it is not counted.
  - `pax_maven_install.json` is active but untracked in PAX; it is counted.

## Current Validation

- `reports/scripts/verify-pax-size-guard.sh --write-baseline` wrote
  `reports/specs/pax-size-baseline.json`.
- `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed against
  the new baseline.
- `reports/scripts/verify-pax-size-guard.sh --mode item13` passed against the
  new baseline.
- `bash -n reports/scripts/verify-pax-size-guard.sh
  reports/scripts/audit-pax-bounded-baseline.sh` passed.
- `jq -e` schema/count sanity check passed for bucket count `11`, pinfile count
  `11`, total artifact roots `2015`, and `11` repos.
- Negative smoke check passed: a temporary baseline with
  `totalArtifactRoots = 1` made `verify-pax-size-guard.sh` fail as expected.
- `reports/scripts/audit-pax-bounded-baseline.sh` passed after correcting its
  `maven_install` count to match active `maven_install(` blocks only.
- `./gradlew migrateToBazel --console=plain --no-daemon` passed in Grazel.
  It left no generated-output diff.
- `reports/scripts/verify-default-task-graph.sh` passed.
- `reports/scripts/verify-sample-bucket-labels.sh` failed only on the known
  one-sided appcompat/constraintlayout exclude-union waiver.
- Grazel `git diff --check` and `git diff --check master...HEAD` passed.
- PAX `git diff --check` passed. PAX files remain uncommitted.

## Remaining Item 10 Work

- Commit Item 10 locally only after checks pass. Do not push and do not commit
  PAX changes.
