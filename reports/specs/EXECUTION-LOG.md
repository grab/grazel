# Dependency Refactor Execution Log

This log is the compact continuity source for the long-running dependency-refactor goal.
Update it after major decisions, failures, commits, and verification gates so context
compaction does not force re-reading legacy reports.

## 2026-06-26 02:43 +08 - Goal Start

- Active item: Item 1 - Baseline, Knowledge Consolidation & Hygiene.
- Current commit: `d8d8f72f0216e3d91e7abec612097f16a65209e2`.
- Initial `git status --short`:
  - Modified production files:
    - `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`
    - `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/android/AndroidExtractor.kt`
  - Modified docs/specs:
    - `reports/dependencies-refactor-current-status.md`
    - all six `reports/specs/2026-06-26-item*-*.md`
  - Untracked:
    - `reports/dependencies-refactor-pending-tasks.md`
- Spec contract verified present:
  - `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`
  - `reports/specs/2026-06-26-item2-structured-planning-seam-design.md`
  - `reports/specs/2026-06-26-item3-consumer-cutover-design.md`
  - `reports/specs/2026-06-26-item4-remove-feedback-paths-design.md`
  - `reports/specs/2026-06-26-item5-provenance-and-exclude-correctness-design.md`
  - `reports/specs/2026-06-26-item6-simplify-review-verification-design.md`
- Resource check:
  - `df -h` showed about 49 GiB available on `/System/Volumes/Data`.
  - A broad `du -sh /private/var/tmp/_bazel_*` scan was too slow and was interrupted;
    use narrower cache checks before expensive Gradle/Bazel commands.
- Decisions:
  - PAX uses local composite included build wiring, so running `migrateToBazel` in
    `/Users/arun.sampathkumar/work/pax-android` should pick up this Grazel checkout
    without publishing.
  - Do not commit PAX-side changes. PAX is verification-only; record PAX audit artifacts
    from the Grazel repo if needed.
  - Current PAX checkout is expected to be on `master` with no local changes. If PAX
    `build-logic` needs minor task-ordering compatibility edits for this Grazel branch,
    make them deliberately for verification only and leave them uncommitted.
  - Stay in this current branch/worktree as requested by the goal. Use subagents for
    bounded read-heavy audits and final reviews, not uncontrolled parallel writes.
  - Context management is itself a goal: do not spawn subagents reflexively; use them for
    clear slices where they reduce main-context load or allow safe parallel auditing.
- Commands run:
  - `cat /Users/arun.sampathkumar/.codex/attachments/e55a8070-c6fe-4e4f-982a-17d9dd482344/pasted-text-1.txt`
  - `git status --short`
  - `git rev-parse HEAD`
  - `df -h / /Users/arun.sampathkumar/work/grazel /Users/arun.sampathkumar/work/pax-android`
  - `ls reports/specs`
  - `ps -axo pid,ppid,pcpu,pmem,rss,comm | sort -k3 -nr | head -20`
- Remaining risks:
  - Item 1 still needs baseline commits, verification scaffolding/audit record, local
    checks, and PAX verification.

## 2026-06-26 02:50 +08 - Item 1 Focused Test Gate

- Active item: Item 1 - production cleanup baseline.
- Commands run:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --tests "com.grab.grazel.gradle.dependencies.DependencyBucketPlacementEngineTest" --tests "com.grab.grazel.gradle.variant.BucketHierarchyGraphTest" --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --tests "com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependenciesTest" --tests "com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionServiceTest" --tests "com.grab.grazel.gradle.dependencies.ResolvedComponentsVisitorTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallArtifactsCalculatorTest" --tests "com.grab.grazel.migrate.android.DefaultAndroidLibraryDataExtractorTest" --tests "com.grab.grazel.gradle.DefaultDependenciesDataSourceTest"`
- Result:
  - Gradle printed `BUILD SUCCESSFUL in 28s`, `17 actionable tasks: 3 executed, 14 up-to-date`.
  - The PTY session did not exit after success and was interrupted; treat the printed
    Gradle result as the evidence for this focused gate.
- Subagent Item 1 inventory result:
  - Existing: Item 1 spec, the two existing verification scripts, tracked generated
    baseline surface, all six specs.
  - Missing: named golden verification script/task, PAX bounded-audit script/record,
    `reports/specs/DO-NOT-REVISIT.md`, and old report cleanup.

## 2026-06-26 03:02 +08 - Item 1 Local Baseline Generation

- Commands run:
  - `./gradlew migrateToBazel --console=plain`
  - `reports/scripts/verify-default-task-graph.sh`
  - `reports/scripts/verify-sample-bucket-labels.sh`
- Results:
  - `migrateToBazel` printed `BUILD SUCCESSFUL in 39s`, `46 actionable tasks: 35 executed, 11 up-to-date`.
  - `verify-default-task-graph.sh` passed.
  - `verify-sample-bucket-labels.sh` failed with
    `Unexpected dependency buckets: androidTest,debug,debugAndroidTest,debugUnitTest,default,lint,test`.
- Root cause:
  - The verifier oracle was stale. Typed hierarchy buckets such as `debugUnitTest` are
    expected in the current branch and are already documented in prior current-status
    notes. The current generated `dependencies.json` consistently includes
    `debugAndroidTest` and `debugUnitTest`.
- Fix:
  - Updated `reports/scripts/verify-sample-bucket-labels.sh` to expect the typed buckets
    and assert representative direct ownership in `debugUnitTest` and `debugAndroidTest`.

## 2026-06-26 03:06 +08 - Named Golden Task Portability Failure

- Command run:
  - `./gradlew verifyGrazelGoldenBaseline --console=plain`
- Result:
  - `migrateToBazel` portion completed and pinning was up-to-date.
  - Task failed in `reports/scripts/verify-grazel-golden-baseline.sh` with
    `mapfile: command not found`.
- Root cause:
  - Gradle invokes `/bin/bash` on macOS, which is Bash 3.2 and does not include
    `mapfile`. The script used a Bash 4 builtin.
- Fix:
  - Replaced `mapfile` with a Bash 3-compatible `while read` loop.
