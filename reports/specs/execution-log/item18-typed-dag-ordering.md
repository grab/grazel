# Item 18 - Typed DAG Reachability Ordering

## 2026-06-28

- Active spec: `reports/specs/2026-06-28-item18-retire-scc-typed-dag-ordering-design.md`.
- Change made:
  - Replaced `ProjectReachabilityOrder.consumersFirstGroups` SCC/condensation ordering with
    direct typed DAG Kahn ordering through `dependencyFirstOrder`.
  - Kept fail-closed typed cycle diagnostics with typed node names and diagnostic edge labels.
  - Removed `ProjectReachabilityGroup.cyclic` and the dead cyclic-group guard in
    `CollectTargetMavenRepoReferencesTask`.
  - Added explicit order fixtures for consumer-before-app, independent ready-node tie-break, and
    same-project multi-source-set collapse.
  - Removed SCC helpers: `stronglyConnectedComponents`, `finishOrder`, `reverseGraph`, and
    `sortComponentIndexes`.
- Verification:
  - `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.TopologicalSorterTest" --tests "com.grab.grazel.gradle.dependencies.DefaultDependencyGraphsTest" --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest" --console=plain --no-daemon`
    passed.
  - `rg` confirmed no production/test references remain to `ProjectReachabilityGroup.cyclic`,
    SCC helpers, or condensed graph ordering.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed with no Grazel generated-output
    diff.
  - PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
    passed in `11m 3s`.
  - PAX generated output remained clean against local baseline commit
    `cfa1057ed58ccb2a795a5f679f072a8f604ff48e`; `git status --short` was empty and
    `git diff --check` passed.
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed:
    bucket count `11`, pinfile count `11`, total artifact roots `1945`, all unchanged.
  - PAX `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
    passed in `223.249s`.
  - PAX `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test`
    passed; 3/3 requested tests pass.
- Resource notes:
  - Disk was tight but stable, around `26GiB` free on `/System/Volumes/Data` before PAX Bazel
    gates.
  - No stale Gradle/Bazel/Coursier or high-RAM `python3.12` process was observed.
  - No cache deletion was triggered.
