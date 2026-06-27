# PAX Bounded Audit Baseline

- PAX root: `/Users/arun.sampathkumar/work/pax-android`
- Generated from: `reports/scripts/audit-pax-bounded-baseline.sh`
- Expected precondition: run `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace` in PAX first.
- Size guard source: `reports/specs/pax-size-baseline.json`
- Size guard script: `reports/scripts/verify-pax-size-guard.sh`

## Maven Size Guard Baseline

- PAX branch/SHA: `arun/grazel-refactor` `05d2b4801530726ab722133c2ba32cbba9afeb67`
- Active `maven_install` repos: 11
- Active pin JSON files: 11
- Total materialized artifact roots: 2015
- Per-repo artifact identities: recorded in `pax-size-baseline.json` from each active pin JSON
  `__INPUT_ARTIFACTS_HASH`, encoded as sorted `artifact=hash` strings.
- Per-repo artifact root counts:
  - `android_test_maven`: 449
  - `debug_maven`: 212
  - `gps_maven`: 113
  - `gps_moveit_debug_maven`: 48
  - `gps_ovo_debug_maven`: 48
  - `hms_maven`: 123
  - `ksp_maven`: 5
  - `lint_maven`: 65
  - `maven`: 674
  - `pax_maven`: 48
  - `test_maven`: 230

## Target Counts And Tag Shape

### //app-gps-pax-debug

- deps: 1452
- tags: 0
- @maven tags: 0
- @direct tags: 0
- @self tags: 0
- @debug_maven deps: 2
- @android_test_maven deps: 0
- Maven tag shape: no bucket-prefixed Maven labels in tags
- direct Maven deps: normalized @maven tag present for each direct Maven dep when the target emits tags
- direct Maven tag audit: skipped: target emits no tags attr

### //app-gps-pax-debug-android-test

- deps: 1511
- tags: 1957
- @maven tags: 616
- @direct tags: 1340
- @self tags: 1
- @debug_maven deps: 0
- @android_test_maven deps: 10
- Maven tag shape: no bucket-prefixed Maven labels in tags
- direct Maven deps: normalized @maven tag present for each direct Maven dep when the target emits tags

## Strict Reachability Spot Check

- bug-report-kit-implementation active BUILD output: absent

## Workspace Shape

- WORKSPACE lines: 4552
- maven_install entries: 11
