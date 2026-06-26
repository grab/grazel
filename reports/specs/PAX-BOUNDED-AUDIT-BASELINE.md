# PAX Bounded Audit Baseline

- PAX root: `/Users/arun.sampathkumar/work/pax-android`
- Generated from: `reports/scripts/audit-pax-bounded-baseline.sh`
- Expected precondition: run `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace` in PAX first.

## Target Counts And Tag Shape

### //app-gps-pax-debug

- deps: 1452
- tags: 0
- @maven tags: 0
- @direct tags: 0
- @self tags: 0
- @debug_maven deps: 6
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

- WORKSPACE lines: 4772
- maven_install entries: 24
