# Item 29 - Declared Metadata Aggregation Modes (Design)

> **Status:** Proposed 2026-06-29.
> **Executor:** Codex.
> **Behaviour change:** none - mode parity and generated EMPTY-diff.
> **Global Constraints + Verification Playbook + Code-quality stance:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`.
> **Depends on:** Item 10 baseline. Prefer after Item 26 if both are in scope. Must run before
> Item 28 source-shape remediation touches this area.

---

## Why This Exists

`CollectDeclaredDependencyMetadataTask` currently makes a Gradle-model snapshot look cacheable by
watching broad build-file globs:

```kotlin
include("**/*.gradle")
include("**/*.gradle.kts")
include("gradle/**/*.toml")
```

That is the wrong cache boundary. It over-invalidates on comments and unrelated build edits, but
still misses convention plugins, `buildSrc`, included builds, settings/plugin management,
environment/project properties, dependency substitution, generated configuration, and other inputs
that affect the evaluated Gradle model.

The semantic artifact Grazel needs is not "files that may have influenced dependencies." It is the
evaluated, serializable declared-dependency metadata used by the workspace resolver: variants,
configuration roles, declared external/project dependencies, excludes, compile-only facts, KSP
facts, and project type.

## Goal

Remove build-file tracing from declared metadata collection and add an experiment-controlled
aggregation mode switch:

```kotlin
grazel {
    experiments {
        declaredDependencyMetadataAggregationMode.set(SINGLE_TASK)
        // or PROJECT_TASK_FANOUT
    }
}
```

Both modes must produce the same aggregate `build/grazel/declared-dependency-metadata.json` for the
same evaluated project model. Downstream tasks must not care which mode produced the file.

## Non-Goals

- Do not reintroduce old per-variant dependency resolution.
- Do not use build-script/TOML file trees as cache inputs.
- Do not use `--force-version`, closure dropping, or dependency-value shortcuts.
- Do not move bucket ownership or resolved-value policy into this item.
- Do not make PAX-specific behavior or paths.
- Do not reuse the deprecated `limitDependencyResolutionParallelism` flag. It remains a
  compatibility no-op.

## Experiment DSL

Add a new enum and property under `ExperimentsExtension`:

```kotlin
enum class DeclaredDependencyMetadataAggregationMode {
    SINGLE_TASK,
    PROJECT_TASK_FANOUT,
}
```

Default:

```kotlin
declaredDependencyMetadataAggregationMode.convention(SINGLE_TASK)
```

The default may be flipped only by Item 31 after PAX parity, performance, and cache behavior are
proven. This item must make switching modes easy and deterministic, and must leave enough timing
evidence for Item 31 to decide whether fanout should become the default.

## Mode 1: `SINGLE_TASK`

This is the compatibility/control path.

Task shape:

- Use a dedicated single-root task class, or reshape `CollectDeclaredDependencyMetadataTask` so the
  current cacheable `Property<String>` JSON input is gone. Keeping the current class shape with
  `declaredDependencyMetadataJson: Property<String>` is not allowed.
- Mark the task explicitly untracked/uncacheable with a durable reason: it reads evaluated
  Gradle/AGP model objects directly.
- Remove `dependencyDeclarationFiles`, `declaredDependencyMetadataJson`, and
  `dependencyDeclarationFileTree`.
- Write the same aggregate JSON output path used today.

Performance:

- Use bounded coroutine fanout over project-level metadata collection.
- Parallel work must be scoped to "read finalized model -> immediately produce serializable DTO".
- No `Project`, `Configuration`, `Variant`, `Dependency`, AGP variant, or mutable Gradle object may
  escape a coroutine worker.
- No unbounded `parallelStream` or unbounded dispatcher usage. Bound concurrency with an explicit
  mechanism such as `Semaphore(gradle.startParameter.maxWorkerCount)` or
  `Dispatchers.Default.limitedParallelism(n)`. Record the chosen bound in the execution log.

Safety guard:

- Before parallelizing a code path, audit for Gradle model mutation such as `maybeCreate`, `create`,
  `setExtendsFrom`, attribute mutation, or resolution-strategy mutation.
- Any mutating or configuration-creating step must remain sequential, move earlier into the variant
  layer, or be removed before that section is parallelized.

## Mode 2: `PROJECT_TASK_FANOUT`

This is the preferred architecture path, but PAX validation proved that the shard task itself must
not be cacheable while it reads live Gradle/AGP model objects. The fanout win for this item is
parallel task execution plus deterministic file merging, not cache reuse of per-project model
snapshots. A future cacheability item may add a separate stable snapshot producer if it can prove
the snapshot boundary is late and complete.

Task shape:

- Add an explicitly untracked per-project task, for example
  `CollectProjectDeclaredDependencyMetadataTask`.
- Each task owns one migratable project and writes one shard JSON through a deterministic
  `RegularFileProperty` output configured from `layout.buildDirectory.file(...)`, for example:

```text
build/grazel/declared-dependency-metadata/<safe-project-path>.json
```

- Each task keeps its assigned project/variant source as task-private implementation state and
  snapshots it inside `@TaskAction`, matching `SINGLE_TASK`'s late model-read boundary.
- Project shard tasks must not expose `Project`, `Configuration`, `Variant`, `Dependency`, AGP
  variant objects, or other live Gradle model objects as task inputs.
- Do not pass per-project metadata as `Property<String>`, `Provider<String>`, or any JSON payload
  string. JSON crosses this boundary only as the shard output file.
- A cacheable shard path is explicitly out of scope for this item unless a separate producer task
  writes a stable snapshot file and PAX proves byte-for-byte parity under full `migrateToBazel`.
- Add a cacheable root merge task that reads shard JSON files, sorts by project path, and writes the
  existing aggregate JSON output.
- The aggregate output file remains the only input consumed by `ResolveWorkspaceDependenciesTask`.

Cache model:

- Project shard tasks are `@UntrackedTask` because they read evaluated Gradle/AGP model objects in
  the action. They must not claim cacheability through provider-mapped live-model inputs.
- The merge task may be `@CacheableTask` because its inputs are shard files and stable scalar
  properties.
- If a needed Gradle fact cannot be represented as stable serialized metadata, add that fact to the
  metadata model rather than adding file-tree proxies.

Fanout granularity:

- Use one task per migratable project, not one task per variant.
- Prefer `VariantBuilder.onVariants` and the variant layer's typed APIs where possible.
- Do not duplicate AGP configuration-name knowledge in the task registrar.

## Shared Abstractions

Extract a shared collector boundary so both modes use the same semantic implementation:

```text
DeclaredProjectMetadataSnapshotter
    input: project + variants + typed variant facts
    output: ProjectDeclaredDependencyMetadata DTO

DeclaredDependencyMetadataMerger
    input: sorted project metadata shards
    output: DeclaredDependencyMetadata
```

Names are illustrative, not mandatory. The important boundary is that Gradle/AGP objects are read
only inside the snapshotter and converted immediately into serializable DTOs. Merge and downstream
code operate only on DTOs/JSON.

Any JSON encode/decode call site introduced by this item must be added to the Item 30 JSON-phase
inventory. JSON crossing a task boundary must use Gradle file inputs/outputs, never JSON string
task properties.

## Required Parity

Both modes must produce byte-identical aggregate JSON on focused samples unless the spec records a
deterministic ordering normalization fix. If ordering normalization changes bytes, generated output
must still be empty-diff and the JSON diff must be classified once.

PAX must be able to switch modes without generated-output regression:

1. Run `migrateToBazel` in `SINGLE_TASK` mode.
2. Capture/compare aggregate declared metadata and generated output.
3. Run `migrateToBazel` in `PROJECT_TASK_FANOUT` mode.
4. Compare aggregate declared metadata and generated output.
5. If generated output is identical, one APK/build verification pass is enough. If output differs,
   stop and classify the diff before building.

## Instrumentation

Add concise timing/counter logs for both modes:

- migratable project count;
- variant enumeration time;
- per-project snapshot time;
- JSON encoding time;
- shard merge time;
- aggregate JSON size;
- mode used.

Record PAX timings in `reports/specs/EXECUTION-LOG.md` or an item-specific execution log. Do not
leave noisy per-project logs enabled by default.

## Tests

Add focused tests before broad PAX validation:

- experiment extension default and mode override;
- no `dependencyDeclarationFileTree`, `dependencyDeclarationFiles`, or
  `declaredDependencyMetadataJson: Property<String>` path remains;
- single-mode and fanout-mode sample aggregate JSON parity;
- fanout merge stores the merged `projects` map deterministically, for example with
  `toSortedMap()`;
- fanout merge produces byte-identical aggregate JSON when shard inputs are supplied in shuffled
  order;
- downstream resolver consumes the same aggregate file path in both modes;
- fanout shard tasks do not use `Property<String>` / `Provider<String>` JSON payload inputs;
- fanout shard tasks are explicitly untracked and do not expose live Gradle/AGP objects as task
  inputs;
- switching modes does not change generated sample BUILD/WORKSPACE files.

Where Gradle functional coverage is expensive, add unit tests around the snapshotter/merger and one
functional sample test proving task wiring.

## Verification

Minimum Grazel gates:

```text
./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon
./gradlew migrateToBazel --console=plain --no-daemon
reports/scripts/verify-default-task-graph.sh
reports/scripts/verify-sample-bucket-labels.sh
git diff --check
```

PAX mode parity gate:

```text
cd /Users/arun.sampathkumar/work/pax-android
./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks
# repeat with the alternate experiment mode
git diff --check
```

If the two modes produce identical generated output, run the usual PAX build/test gate once against
the selected target mode. If they differ, stop and investigate before any build is treated as
evidence.

## Hard Exit Gates

This item is not complete unless all are true:

- `dependencyDeclarationFileTree`, `dependencyDeclarationFiles`, and
  `declaredDependencyMetadataJson` are deleted or replaced by non-JSON-string task properties.
- The experiment mode exists and is covered by tests.
- `SINGLE_TASK` is explicitly untracked/uncacheable and uses bounded coroutine fanout only within
  the approved DTO snapshot boundary.
- `PROJECT_TASK_FANOUT` uses untracked per-project shard tasks plus a cacheable deterministic merge
  task, without JSON payload string inputs.
- fanout shard tasks snapshot live Gradle/AGP model objects only inside task actions and expose no
  live Gradle/AGP model object task inputs.
- merged `DeclaredDependencyMetadata.projects` ordering is deterministic and tested with shuffled
  shard input order.
- Both modes write or feed the same aggregate metadata contract to downstream tasks.
- Focused sample tests prove mode parity.
- PAX can switch modes without generated-output regression, or any difference is stopped and
  classified before proceeding.
- Execution logs record the chosen default, PAX mode-parity result, timing counters, and remaining
  risk.
