# Item 31 - Declared Metadata Fanout Default Decision (Design)

> **Status:** Proposed 2026-06-29.
> **Executor:** Codex.
> **Behaviour change:** preserving generated output; task graph/default mode may change.
> **Global Constraints + Verification Playbook + Code-quality stance:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`.
> **Depends on:** Item 29 complete.

---

## Why This Exists

Item 29 introduces two declared-metadata aggregation modes:

```text
SINGLE_TASK           untracked compatibility/control path
PROJECT_TASK_FANOUT   cacheable per-project shard + deterministic merge path
```

Leaving `SINGLE_TASK` as the default forever would preserve the old always-run behavior after the
fanout architecture exists. This item forces an explicit default-mode decision from evidence rather
than inertia.

## Goal

Use Item 29's PAX parity, timing, and cache evidence to decide the default aggregation mode.

Preferred result:

```text
declaredDependencyMetadataAggregationMode.convention(PROJECT_TASK_FANOUT)
```

Allowed alternative:

```text
declaredDependencyMetadataAggregationMode.convention(SINGLE_TASK)
```

only with a maintainer-approved blocker recorded in the execution log and known limitations.

## Required Evidence

Before changing or retaining the default, record:

- PAX generated-output parity for both modes;
- aggregate declared-metadata byte parity or classified deterministic ordering diff;
- timing for `SINGLE_TASK` and `PROJECT_TASK_FANOUT`;
- no-op rerun behavior for both modes;
- cache hit/miss observation for fanout shard and merge tasks, or a recorded reason cache
  observation could not be performed in the local environment;
- any correctness or task-graph reason fanout cannot be default yet.

## Rules

- Do not flip the default if Item 29 parity is incomplete.
- Do not keep `SINGLE_TASK` as default merely because it already works.
- Do not weaken Item 29's JSON/file-boundary constraints.
- Generated BUILD/WORKSPACE output must remain empty-diff.
- PAX must still pass the same migrate/build gate used by Item 29.

## Tests

Add or update tests for:

- extension default value;
- explicit override of both modes;
- generated sample output remains unchanged under the chosen default.

## Verification

Minimum Grazel gates:

```text
./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon
./gradlew migrateToBazel --console=plain --no-daemon
reports/scripts/verify-default-task-graph.sh
reports/scripts/verify-sample-bucket-labels.sh
git diff --check
```

PAX gate:

```text
cd /Users/arun.sampathkumar/work/pax-android
./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks
./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk
git diff --check
```

## Hard Exit Gates

This item is not complete unless all are true:

- default mode decision is made explicitly;
- if fanout is default, tests and PAX gates prove generated output did not move;
- if single-task remains default, a maintainer-approved blocker and re-evaluation trigger are
  documented in `KNOWN-LIMITATIONS.md` or the item execution log;
- execution log records mode timings, cache observations, commands, results, and remaining risk.
