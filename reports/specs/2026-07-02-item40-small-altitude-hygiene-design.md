# Item 40 — Small Altitude Hygiene: Typed Facts, KSP Roles & Service Wiring (Design)

> **Status:** Proposed 2026-07-02.
> **Executor:** Codex. **Behaviour:** **preserving / empty generated diff**.
> **Depends on:** Item 38. Independent of Item 39 unless both touch the same files.
> **Global Constraints + Verification Playbook + Code-quality stance:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`.

> **Execution note — use subagents deliberately.** Use small read-only subagents for branch-diff
> altitude scans, BuildService usage inventory, KSP/variant-role review, and final adversarial review.
> The parent agent owns reconciliation and must spot-check claims.

---

## Goal

Resolve the small branch-introduced altitude hygiene findings from
`reports/ALTITUDE-REVIEW-branch.md` while explicitly **excluding** the larger legacy
`Dependencies.kt` per-project extractor/render path.

This item is a preserving cleanup. It should make the already-landed architecture harder to regress:

- bucket ownership consumes typed variant facts instead of re-inferring test/main shape from rendered
  bucket-name suffixes;
- KSP processor root discovery has one variant-owned role seam instead of scattered string
  heuristics;
- tasks that consume Gradle `BuildService` providers declare `usesService(...)`;
- deterministic merge/order assumptions are structural, not convention-only;
- any retained task-action re-derivation is either removed when trivial or documented as intentional.

## Non-Goals

- Do **not** touch `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/Dependencies.kt`.
  The legacy per-project dependency extractor path is the next large frontier, not this item.
- Do not unify the divergent `Dependencies.kt` declaration classifier.
- Do not change Maven bucket ownership semantics, generated BUILD/WORKSPACE shape, local Maven proxy
  behavior, lockfile reconstruction, or PAX generated output.
- Do not add PAX-specific hacks.
- Do not commit or push PAX changes.

If any finding appears to require `Dependencies.kt` edits or output-changing behavior, stop and record
it as a follow-up instead of smuggling it into this preserving item.

## Problems To Fix

### 1. Bucket ownership still re-infers typed test shape from bucket-name suffixes

`BucketOwnershipPlanner` has typed `VariantType` information at planning time, but some helper paths
still use rendered-name logic such as `endsWith(Test.testSuffix)` /
`endsWith(AndroidTest.testSuffix)` to recover main/test relationships.

Target shape:

- `DependencyBucketPlacementPlan` or a nearby planner model carries the test bucket's `VariantType`
  and any derived main-bucket relationship needed by test placement.
- Helper names should speak in typed concepts, e.g. `testBucketProjection`,
  `mainBucketNameFor(testBucket)`, or `visibleMainBucketsFor(testBucket, testType)`.
- `BucketOwnershipPlanner` may still use `VariantType.testSuffix` when constructing final bucket
  names, but must not use suffix checks as a classifier when a typed value is available.

Required tests:

- focused unit test proving unit-test and android-test bucket handling does not depend on a string
  suffix classifier;
- existing bucket placement tests remain green and generated output stays empty-diff.

### 2. KSP processor root input discovery needs a clear variant-owned role seam

`CollectKspProcessorDependenciesTask` currently delegates part of the work to
`createWorkspaceKspProcessorClasspath(project)`, but the overall shape still reads as task-level KSP
configuration discovery. KSP role knowledge should live in `gradle.variant`; the task should wire
inputs and perform task-action collection only.

Target shape:

- Add or tighten a `KspProcessorRootInputPlanner` / `WorkspaceKspProcessorClasspathPlanner` under
  `gradle.variant`.
- The planner owns KSP declaration-bucket classification, processor classpath creation, and the
  mapping from a Gradle project to the small set of root inputs the task needs.
- `CollectKspProcessorDependenciesTask.register(...)` consumes those planned inputs and wires
  `ResolvedComponentResult`, direct short IDs, artifact files, and classpath files.
- Avoid new eager resolution in configuration phase. Keep existing intentional
  `ResolvedComponentResult` task inputs.
- Preserve `@CacheableTask` behavior and current `@PathSensitive(PathSensitivity.NONE)` artifact
  file semantics unless a test proves a different annotation is required.

Required tests:

- unit tests for the variant-layer KSP role planner with main/test KSP declarations;
- focused `CollectKspProcessorDependenciesTaskTest` coverage or existing task tests updated to prove
  the task consumes planner output rather than classifying names itself.

### 3. BuildService consumers must declare `usesService(...)`

Only the local Maven proxy task path currently models service usage consistently. Branch-new services
such as dependency resolution, workspace plan, render plan, tag plan, graph, and compression services
are passed through Gradle providers/properties in multiple tasks. Gradle should see those usages.

Target shape:

- Inventory every task in `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal`
  that receives a `BuildService` provider or a `Property<...Service>`.
- For each service consumed by a task, add the corresponding `usesService(provider)` in task
  registration/configuration.
- Prefer a small helper if it removes repetition without hiding which service is used.
- Do not convert task inputs to string paths or other cache-hostile shortcuts.

Expected task families to inspect include, but are not limited to:

- `ComputeWorkspaceDependenciesTask`
- `ComputeWorkspacePlanTask`
- `FinalizeWorkspacePlanTask`
- `CollectWorkspaceTargetTagPlanTask`
- `CollectTargetMavenRepoReferencesTask`
- `AnalyzeVariantCompressionTask`
- `GenerateRootBazelScriptsTask`
- `GenerateBazelScriptsTask`
- `PinMavenArtifactsTask` (already a reference implementation; keep it correct)

Required tests / checks:

- task registration tests or task-graph checks that fail if the service wiring disappears are ideal;
- at minimum, focused source inventory plus `verify-default-task-graph.sh`.

### 4. Deterministic merge ordering should be structural

Where merge code currently relies on `sortedBy(...).associate { ... }` insertion order, prefer an
explicit sorted map/list model so later edits cannot silently bypass determinism.

Target shape:

- Harden declaration-metadata merge output ordering with `toSortedMap()` / explicit sorted output
  types where appropriate.
- Add a shuffle/order test if one does not already cover the exact merge path.
- Preserve byte-identical generated output.

### 5. Re-derivation scan, fix only trivial local cases

The altitude review flagged some task-action re-derivations. This item should not become a broad
rewrite, but it should not ignore easy cleanup.

Target shape:

- Inventory task-action re-derivations in files already touched by this item.
- If the fix is a local move to a named service method or planner method with no behavior change, do
  it and test it.
- If the fix requires output-changing semantics or the `Dependencies.kt` path, record it in
  `reports/specs/KNOWN-LIMITATIONS.md` or a future item note instead.

## Work Plan

### Phase 0 — Ground and log

1. Record current Grazel commit, current PAX branch/SHA/status, and active item in
   `reports/specs/EXECUTION-LOG.md`.
2. Create/update `reports/specs/execution-log/item40-small-altitude-hygiene.md`.
3. Confirm `reports/ALTITUDE-REVIEW-branch.md` findings against the current code before editing.
4. Confirm `Dependencies.kt` is excluded from the edit set.

### Phase 1 — Typed bucket test projection

1. Add failing tests around unit-test/android-test bucket ownership that would catch suffix-based
   misclassification.
2. Thread typed test information through the planner model.
3. Remove classifier-style suffix checks from `BucketOwnershipPlanner`; keep suffix use only for
   final bucket-name construction.
4. Run focused bucket ownership tests.

### Phase 2 — KSP variant-role seam

1. Add tests for a variant-layer KSP processor classpath planner.
2. Move/centralize KSP declaration-bucket and processor-classpath input planning into
   `gradle.variant`.
3. Keep `CollectKspProcessorDependenciesTask` responsible for task inputs and task action only.
4. Run focused KSP task/planner tests.

### Phase 3 — BuildService usage wiring

1. Inventory service-consuming tasks.
2. Add `usesService(...)` where missing.
3. Run task registration/task graph checks.

### Phase 4 — Determinism and trivial re-derivation cleanup

1. Harden merge ordering with explicit sorted structures.
2. Add/keep shuffle tests.
3. Fix only local re-derivations that do not broaden this item.

### Phase 5 — Review and verification

1. Run a scoped source-shape review over this item’s diff.
2. Run simplify-pass if the implementation touches more than a few files.
3. Run adversarial review focused on typed-vs-string regressions, BuildService lifecycle wiring,
   cacheability annotations, KSP task boundaries, and accidental `Dependencies.kt` edits.
4. Fix confirmed findings or reject them with concrete code evidence in the execution log.

## Required Verification

Focused checks:

```bash
./gradlew :grazel-gradle-plugin:test \
  --tests "*BucketOwnershipPlanner*" \
  --tests "*Ksp*" \
  --tests "*CollectKspProcessorDependenciesTask*" \
  --console=plain --no-daemon
```

Broader Grazel checks:

```bash
./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon
./gradlew migrateToBazel --console=plain --no-daemon
reports/scripts/verify-default-task-graph.sh
reports/scripts/verify-sample-bucket-labels.sh
reports/scripts/verify-pax-size-guard.sh --mode preserving
git diff --check
git diff --check master...HEAD
```

Required PAX loop before completion:

```bash
cd /Users/arun.sampathkumar/work/pax-android
./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks
./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk
./bazel.sh test --test_output=errors \
  //app-utils:app-utils-gps-pax-debug-test \
  //app-test:app-test-gps-pax-debug-test \
  //application-initializer:application-initializer-gps-pax-debug-test
git diff --check
```

Operational constraints from the current goal still apply: check disk, memory, process pressure, and
Bazel private output roots before expensive PAX runs; preserve caches unless genuinely low on space;
do not add aggressive `--jobs`; do not commit PAX.

## Acceptance Criteria

- No edits to `Dependencies.kt`.
- `BucketOwnershipPlanner` no longer classifies test buckets by rendered-name suffix where typed
  variant facts are available.
- KSP processor classpath/root input planning is visibly variant-layer-owned; the KSP task wires
  planned inputs and performs collection.
- All tasks consuming branch services declare `usesService(...)` or have a logged reason why Gradle
  cannot/should not model that service.
- Merge determinism is explicit and tested.
- Generated Grazel and PAX output remain unchanged against the accepted baseline.
- Required Grazel and PAX gates pass or have documented pre-existing waivers.
- Execution logs record decisions, commands, results, timing where relevant, and remaining risks.
