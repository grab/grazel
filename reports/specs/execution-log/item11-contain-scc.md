# Item 11 Execution Log — SCC Diagnostic Containment

## Current Slice

- Active item: `reports/specs/2026-06-27-item11-contain-scc-design.md`
- Goal: eliminate false SCC handling from the normal path and keep SCC only as a typed-node
  diagnostic fallback for proven genuine cycles.
- Baseline before this slice: Item 9 committed at `d84f3db`
  (`Add typed dependency reachability graph`).
- Behavior expectation: preserving against the post-Item-9 generated baseline unless the
  typed-SCC audit proves a genuine previously hidden correctness issue.

## Decisions

- Start from Item 9's typed reachability model: `DependencyGraphNode(project, sourceSet)` and
  `DependencyGraphEdge` values.
- Treat the known PAX `deliveries-model-food:test -> food-testkit:main ->
  deliveries-model-food:main` shape as false until typed projection proves otherwise.
- Bucket ownership and Maven materialization remain out of scope for this item.
- Remove the target-reference local SCC/fixpoint path. If a typed SCC appears, fail in
  `ProjectReachabilityOrder` with typed nodes and diagnostic edge labels instead of treating
  SCC as a normal collection strategy.

## Implementation

- Added typed reachability diagnostic edges with labels for `ConfigurationEdge`,
  `AndroidTestTargetProjectEdge`, source-set inheritance, and unknown edge values.
- `ProjectReachabilityOrder` now computes SCCs on the typed reachability graph and fails
  closed for any genuine typed cycle. The diagnostic prints `:project[SourceSet]` nodes and
  intra-component labeled edges.
- `CollectTargetMavenRepoReferencesTask` no longer has `collectCyclicProjectGroup` or a
  bounded local fixpoint. It processes ordered acyclic groups once and asserts that cyclic
  groups must have failed earlier in the graph layer.
- Added regression coverage for:
  - genuine typed SCC diagnostic failures;
  - the known PAX `testImplementation` back-edge shape remaining acyclic as typed nodes;
  - the collector rejecting synthetic cyclic groups instead of retrying/fixpointing.

## Verification

- Focused graph/task tests passed:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon --tests
  "com.grab.grazel.gradle.dependencies.TopologicalSorterTest" --tests
  "com.grab.grazel.gradle.dependencies.DefaultDependencyGraphsTest" --tests
  "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest"`.
- Full plugin unit tests passed:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`.
- Local Grazel migrate passed:
  `./gradlew migrateToBazel --console=plain --no-daemon`.
- Local diff/check gates passed:
  `git diff --check`, `git diff --check master...HEAD`,
  `reports/scripts/verify-default-task-graph.sh`, generated BUILD/WORKSPACE/json diff check,
  and `reports/scripts/verify-pax-size-guard.sh --mode preserving`.
- Known waiver remains unchanged:
  `reports/scripts/verify-sample-bucket-labels.sh` fails on the pre-existing
  one-sided appcompat/constraintlayout exclude-union case.
- Fresh PAX migrate passed:
  `/Users/arun.sampathkumar/work/pax-android ./gradlew migrateToBazel --no-daemon
  --console=plain --stacktrace --rerun-tasks` in `8m34s`.
- PAX APK build passed:
  `/Users/arun.sampathkumar/work/pax-android ./bazel.sh build --verbose_failures
  //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk` in `224.788s`.
- PAX focused unit tests passed:
  `/Users/arun.sampathkumar/work/pax-android ./bazel.sh test --test_output=errors
  //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test
  //application-initializer:application-initializer-gps-pax-debug-test` with 3/3 tests
  passing in `18.600s`.
- PAX `git diff --check` passed after migrate/build/test.
- PAX size guard passed in preserving mode after the PAX loop: 11 buckets, 11 pinfiles, 2015
  total artifact roots, no per-repo artifact-root deltas.

## Remaining Risk

- `CollectTargetMavenRepoReferencesTask` still re-evaluates target builders while collecting
  target repo references. Item 15 owns rendering-purity cleanup for this in-task model feedback
  shape.
- The PAX working tree remains intentionally dirty with accepted generated output on branch
  `arun/grazel-refactor`; never commit PAX changes from this goal.
