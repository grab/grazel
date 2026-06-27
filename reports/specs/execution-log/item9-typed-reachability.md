# Item 9 Execution Log — Typed Reachability

## Current Slice

- Active item: `reports/specs/2026-06-26-item9-reachability-target-edges-design.md`
- Goal: make reachability ordering source-set aware so `test` / `androidTest` edges do not
  collapse into false project-level cycles, and add the `com.android.test -> target app`
  ordering edge.
- Baseline before this slice: Item 10 committed at `9f363e0`.
- Checkpoint commit: `d84f3db` (`Add typed dependency reachability graph`).

## Decisions

- `DependencyGraphs.variantGraphs` now carries `DependencyGraphEdge` values instead of raw
  `Configuration` values.
- Existing dependency edges are wrapped as `ConfigurationEdge(configuration)`.
- `AndroidTestTargetProjectEdge(targetProjectPath)` is added from a `com.android.test`
  project to its `TestExtension.targetProjectPath` app project.
- Typed reachability uses `DependencyGraphNode(project, sourceSet)` with `Main`, `Test`, and
  `AndroidTest` source sets.
- Dependency edge targets are projected to `Main`; test/androidTest source-set identity is
  preserved until after topological ordering.
- `ProjectReachabilityOrder` derives project groups from typed ordering by first project
  occurrence. This keeps task callers project-oriented while preventing false SCCs from
  source-set collapse.
- For `com.android.test`, AGP exposes variants through `TestExtension.applicationVariants`;
  those are still keyed as `AndroidBuild` in existing Grazel APIs, so typed reachability
  classifies the source as `AndroidTest` when `project.isAndroidTest`.

## Verification So Far

- Red/green guard: a PAX-shaped false cycle (`a:test -> b:main`, `b:main -> a:main`) first
  failed under project-only ordering, then passed after typed projection.
- Review-driven red/green guards:
  - A genuine main SCC (`a:main <-> b:main`) still remains cyclic even if `a:test` appears
    earlier in the typed order.
  - `test` source-set nodes now explicitly inherit their same project's `main` node.
- Focused tests passed:
  - `TopologicalSorterTest`
  - `DefaultDependencyGraphsTest`
  - `JvmProjectGraphKeyTest`
  - `DefaultAndroidTestDataExtractorTest`
- Full plugin unit test passed:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`
- Grazel migration passed:
  - `./gradlew migrateToBazel --console=plain --no-daemon`
- Formatting/diff checks passed:
  - `git diff --check`
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving`
- Local task graph passed:
  - `reports/scripts/verify-default-task-graph.sh`
- Local generated output check:
  - After `migrateToBazel`, `git diff --name-only -- '**/BUILD.bazel' WORKSPACE '**/*.json'`
    was empty.
- Known local waiver still present:
  - `reports/scripts/verify-sample-bucket-labels.sh` fails on the pre-existing one-sided
    appcompat/constraintlayout exclude-union check.
- PAX migration passed after typed reachability changes:
  - `/Users/arun.sampathkumar/work/pax-android ./gradlew migrateToBazel --no-daemon
    --console=plain --stacktrace --rerun-tasks`
  - Result: `BUILD SUCCESSFUL in 12m 6s`, `4590 actionable tasks: 4590 executed`.
- PAX generated diff hygiene passed:
  - `git diff --check`
- PAX size guard passed after migration:
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving`
  - Result unchanged: 11 buckets, 11 pinfiles, 2015 total artifact roots.
- PAX debug APK + android-test APK build passed:
  - `/Users/arun.sampathkumar/work/pax-android ./bazel.sh build
    --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk`
  - Result: build completed successfully in `2140.633s`, `54526` total actions.
  - Signal: the android-test support graph compiled through the large
    `//app:app-gps-pax-debug-android-test_lib_kt` kapt/compile step, so the
    typed test reachability change did not reintroduce the earlier missing
    test dependency/tag failure.
- PAX selected Bazel unit-test gate passed:
  - `/Users/arun.sampathkumar/work/pax-android ./bazel.sh test
    --test_output=errors //app-utils:app-utils-gps-pax-debug-test
    //app-test:app-test-gps-pax-debug-test
    //application-initializer:application-initializer-gps-pax-debug-test`
  - Result: 3 of 3 tests passed in `374.828s`.
- PAX post-test checks passed:
  - `git diff --check`
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving`
  - Size remained unchanged: 11 buckets, 11 pinfiles, 2015 total artifact roots.
- Resource note:
  - PAX gates reduced free data-volume disk to about `21GiB`; do not start more
    heavy PAX verification without deliberate cleanup.

## Remaining Gates

- None for Item 9 checkpoint. Advance to Item 11 after updating the main execution log.
- Sample-bucket verification has the known one-sided exclude-union waiver if still rerun.

## Fresh Local Pre-Checkpoint Verification

- Re-read the active goal anchor, roadmap, and this Item 9 spec after context compaction.
- Resource check: data volume had about `21GiB` free after prior PAX gates. PAX Bazel
  shutdown cleared stale PAX worker processes before local verification. No high-RAM
  `python3.12` process was present.
- Passed:
  - `git diff --check`
  - `git diff --check master...HEAD`
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`
  - `./gradlew migrateToBazel --console=plain --no-daemon`
  - `reports/scripts/verify-default-task-graph.sh`
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving`
  - generated output identity check:
    `git diff --name-only -- **/BUILD.bazel WORKSPACE **/*.json` emitted no files.
- Known waiver reproduced:
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails only on the pre-existing
    one-sided appcompat/constraintlayout exclude-union check.

## Risks To Review

- `ValueGraph` stores a single edge value per project pair in a variant graph. If a normal
  configuration edge and `AndroidTestTargetProjectEdge` share the same source/target pair,
  topology is still preserved, but edge meaning would be lossy for future edge-value readers.
  Current production reachability consumes topology/projection, not the edge value.
- `ProjectReachabilityOrder` still returns project groups to the existing task API. This is a
  deliberate Item 9 bridge: typed ordering prevents false SCCs, but target reference
  collection is still whole-project until a later task model can operate at source-set/target
  altitude.
- `com.android.test` target edges are stored under existing AndroidBuild variant keys because
  AGP exposes `TestExtension.applicationVariants`; typed projection classifies the source as
  `AndroidTest`. Current reachability caller includes all variant types. If a future caller
  filters only `VariantType.AndroidTest`, it will need a source-set-aware filter rather than
  a raw variant-type predicate.

## Review Findings Addressed

- Fixed: project de-dupe no longer removes projects from cyclic typed components just because
  the same project appeared earlier as a non-cyclic source-set component.
- Fixed: non-main source-set nodes now add an explicit inherited edge to the same project's
  `Main` node.
- Read-only PAX/generated-shape audit:
  - Independent `verify-pax-size-guard.sh --mode preserving` check matched the baseline:
    11 buckets, 11 pinfiles, 2015 root inputs, no per-repo deltas.
  - Broad PAX generated drift remains consistent with typed reachability/render-plan ordering
    effects rather than Maven bucket growth.
  - Audit recommendation: do not treat size guard alone as enough; keep the APK and unit-test
    Bazel gates as blockers before committing Item 9.
