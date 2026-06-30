# Item 32 - True Project Declared Metadata Fanout (Design)

> **Status:** Completed 2026-06-29; status reconfirmed 2026-07-01.
> **Executor:** Codex.
> **Behaviour change:** preserving generated output; task graph and performance shape may change.
> **Global Constraints + Verification Playbook + Code-quality stance:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`.
> **Depends on:** Items 29 and 31 complete. Prefer after Item 28 so source-shape cleanup does not
> polish the root-task fanout shape.

---

## Why This Exists

Item 29 added declared-metadata aggregation modes and Item 31 made
`PROJECT_TASK_FANOUT` the default. The current implementation improved the data boundary:

```text
source project model -> one shard JSON per project -> cacheable deterministic root merge
```

However, the current shard tasks are registered on the root project:

```kotlin
rootProject.tasks.register("collect${sourceProject.path.toPascalTaskName()}DeclaredDependencyMetadata")
```

This creates many root tasks such as:

```text
:collectGeoSavedPlacesDeclaredDependencyMetadata
:collectAppDeclaredDependencyMetadata
:mergeDeclaredDependencyMetadata
```

That is not true Gradle project fanout. Even with `org.gradle.parallel=true`, Gradle's parallel
project execution model is not being used as effectively as it would be if each shard task belonged
to its source project:

```text
:geo-saved-places:collectProjectDeclaredDependencyMetadata
:app:collectProjectDeclaredDependencyMetadata
:mergeDeclaredDependencyMetadata
```

The current shape is correct but leaves performance and task-graph altitude on the table.

## Completion Note

This item has landed. The implementation now registers each
`CollectProjectDeclaredDependencyMetadataTask` on `metadataSource.project.tasks` with the short
task name `collectProjectDeclaredDependencyMetadata`, writes the shard to the source project's
`build/grazel/declared-dependency-metadata/project.json`, and keeps the root-owned
`mergeDeclaredDependencyMetadata` task as the cacheable file-based merge. The default task-graph
verifier expects representative source-project shard paths such as
`:sample-android:collectProjectDeclaredDependencyMetadata` and rejects the old root-flat
`:collectSampleAndroidDeclaredDependencyMetadata` shape.

Future work should not re-open Item32 as pending implementation unless that task shape regresses.
Remaining related work is only normal follow-up hygiene/performance measurement, not the source
project fanout conversion itself.

## Goal

Move `PROJECT_TASK_FANOUT` from root-task fanout to true source-project task fanout while preserving
the aggregate declared metadata contract and all generated Bazel output.

Target shape:

```text
Layer 1/2: variant/project model produces DeclaredProjectMetadataSource per migratable project
Layer 6: each source project owns one untracked shard task
Layer 6: root owns one cacheable merge task

:projectA:collectProjectDeclaredDependencyMetadata
    -> projectA/build/grazel/declared-dependency-metadata/project.json

:projectB:collectProjectDeclaredDependencyMetadata
    -> projectB/build/grazel/declared-dependency-metadata/project.json

:mergeDeclaredDependencyMetadata
    inputs: all shard RegularFileProperty providers
    output: root build/grazel/declared-dependency-metadata.json
```

`SINGLE_TASK` remains the compatibility/control override unless a later item deletes it.

## Non-Goals

- Do not change dependency ownership, bucket placement, Maven repo materialization, tags, or
  workspace dependency values.
- Do not make shard tasks cacheable in this item. They still read evaluated Gradle/AGP model
  objects in `@TaskAction` and must remain explicitly untracked unless a separate stable snapshot
  producer is introduced and PAX proves it.
- Do not reintroduce build-script/TOML file tracing.
- Do not pass JSON through `Property<String>` / `Provider<String>`.
- Do not revert to old per-module dependency resolution.
- Do not add PAX-specific paths, filters, or task names.

## Required Architecture

### Task ownership

Register each `CollectProjectDeclaredDependencyMetadataTask` on `metadataSource.project.tasks`, not
on `rootProject.tasks`.

Recommended task name:

```text
collectProjectDeclaredDependencyMetadata
```

The project path provides uniqueness:

```text
:geo-saved-places:collectProjectDeclaredDependencyMetadata
:app:collectProjectDeclaredDependencyMetadata
```

This avoids colliding with the root compatibility/control task named `:collectDeclaredDependencyMetadata`
if the root project is ever part of the migratable source set. If a name collision still exists
inside a source project, choose a short Grazel-specific name, but do not fall back to root-task
names.

### Output ownership

Each shard task should write to its owning project's build directory:

```text
<source-project>/build/grazel/declared-dependency-metadata/project.json
```

Use `RegularFileProperty` and provider wiring. Do not use string file paths as task inputs.

The root merge task consumes shard file providers:

```text
mergeDeclaredDependencyMetadata.declaredDependencyMetadataShards.from(
    shardTask.flatMap { it.declaredDependencyMetadataShard }
)
```

### Merge determinism

The merge result must be deterministic regardless of Gradle scheduling order or file-system order.

Do not depend on absolute file paths for semantic ordering. Either:

- parse each shard and sort by its single project path; or
- keep the current merger's project-key sorting and add a test that shuffled shard files produce
  byte-identical aggregate JSON.

### Altitude

- `WorkspaceDependencyInputsRegistrar` may choose the selected aggregation mode and wire the
  producer file into `ResolveWorkspaceDependenciesTask`.
- `WorkspaceDependencyInputsRegistrar` must not know root-flat task-name conventions or source
  project task naming details.
- `DeclaredDependencyMetadataTasks` owns task registration shape.
- `DeclaredDependencyMetadataCollector` / snapshotter owns semantic collection.
- Gradle/AGP live objects must be converted to serializable DTOs inside the shard task action and
  must not escape into task inputs, merge code, or downstream dependency policy.

## Performance Evidence Required

Before the change, record the existing root-task fanout baseline from execution logs:

```text
PAX root-task fanout full migrate: about 12m06s-12m12s
PAX merge action: about 554-1044 ms
PAX aggregate JSON: 35247531 bytes
PAX shard count: 2327
```

After the change, record:

- full PAX `migrateToBazel --rerun-tasks` wall time;
- merge action time;
- shard count and aggregate JSON bytes;
- whether task output shows source-project task paths instead of root-flat task names;
- whether `--parallel`/`org.gradle.parallel=true` appears to schedule shard tasks concurrently
  enough to reduce or explain wall time;
- memory/disk observations if timing is noisy.

The item may still be accepted if whole PAX migrate is not faster, but only with a written reason.
The primary hard requirement is correct task ownership and generated-output parity.

## Tests

Add or update focused tests for:

- default `PROJECT_TASK_FANOUT` task graph uses source-project shard tasks, not root-flat
  `:collect<Project>DeclaredDependencyMetadata` tasks;
- root `:mergeDeclaredDependencyMetadata` depends on all source-project shard tasks and consumes
  their `RegularFileProperty` outputs;
- shard output path is owned by the source project build directory;
- shard tasks remain explicitly untracked and do not expose live Gradle/AGP objects as inputs;
- merge task remains cacheable and file-based;
- shuffled shard inputs still produce byte-identical aggregate JSON;
- generated sample BUILD/WORKSPACE output remains unchanged.

Update `reports/scripts/verify-default-task-graph.sh` so the default path:

- requires representative source-project tasks such as
  `:sample-android:collectProjectDeclaredDependencyMetadata`;
- requires `:mergeDeclaredDependencyMetadata`;
- rejects root-flat shard names such as `:collectSampleAndroidDeclaredDependencyMetadata`;
- still rejects the legacy single aggregate task on the default path.

## Verification

Minimum Grazel gates:

```text
./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon
./gradlew migrateToBazel --console=plain --no-daemon
reports/scripts/verify-default-task-graph.sh
reports/scripts/verify-sample-bucket-labels.sh
reports/scripts/verify-pax-size-guard.sh --mode preserving
git diff --check
git diff --check master...HEAD
```

PAX gate:

```text
cd /Users/arun.sampathkumar/work/pax-android
./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks
./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk
./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test
git diff --check
```

Generated output must stay at the accepted PAX baseline. Any generated diff is
stop-and-investigate unless a maintainer explicitly reclassifies this item as output-changing.

## Operational Constraints

Before expensive Gradle/Bazel commands, check disk, memory, and process pressure. Preserve the
usual PAX cache unless disk is genuinely low. Do not add aggressive `--jobs` or disable Bazel disk
cache just to make timing easier.

If timing is noisy, run the smallest useful comparison first:

```text
./gradlew mergeDeclaredDependencyMetadata --console=plain --no-daemon --rerun-tasks
```

Then validate with full `migrateToBazel`.

## Hard Exit Gates

This item is not complete unless all are true:

- `PROJECT_TASK_FANOUT` shard tasks are registered on source projects, not root.
- The root merge task consumes shard files through Gradle file providers.
- No JSON string task inputs or build-script file-tree proxies are introduced.
- Shard tasks remain explicitly untracked unless a separate spec proves a cacheable snapshot
  boundary.
- Aggregate declared metadata is byte-identical to the accepted baseline, or any byte diff is a
  classified deterministic ordering normalization with empty generated-output diff.
- Grazel generated output is empty-diff.
- PAX migrate leaves the accepted generated baseline unchanged.
- PAX APK build and focused test gates pass, or any failure is proven pre-existing with evidence.
- Timing evidence compares old root-task fanout against true source-project fanout.
- Execution logs record commands, results, timing, failures/root causes, remaining risk, and
  whether true project fanout improved wall time.
