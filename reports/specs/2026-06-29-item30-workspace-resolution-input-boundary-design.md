# Item 30 - Workspace Resolution Input Boundary (Design)

> **Status:** Proposed 2026-06-29.
> **Executor:** Codex.
> **Behaviour change:** none - generated EMPTY-diff.
> **Global Constraints + Verification Playbook + Code-quality stance:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`.
> **Depends on:** Item 26 preferred. Must run before broad Item 24/28 source-shape cleanup touches
> workspace dependency task wiring.

---

## Why This Exists

`WorkspaceDependencyInputsRegistrar` currently serializes root metadata while wiring
`ResolveWorkspaceDependenciesTask`:

```kotlin
private fun ResolveWorkspaceDependenciesTask.addRoot(rootInput: WorkspaceDependencyRootInput) {
    workspaceDependencyRootComponents.add(
        rootInput.configuration.incoming.resolutionResult.rootComponent
    )
    workspaceDependencyRootMetadataJsons.add(Json.encodeToString(rootInput.toMetadata()))
}
```

That JSON encode happens during task configuration / `projectsEvaluated` task wiring. This is a
strict boundary violation. Task configuration may wire task providers, file providers, and stable
properties, but it must not eagerly serialize, parse, or otherwise compute model payloads that
belong to task execution.

The resolver itself is allowed to keep Gradle resolution roots:

```kotlin
ListProperty<ResolvedComponentResult>
```

This is intentional, master-like, and cacheable by Gradle design. This item must not reinterpret
`ResolvedComponentResult` as unsafe merely because it is a live Gradle object. The bug is eager
metadata work and weak metadata/root pairing, not the existence of Gradle-resolved root components
as task inputs.

## Goal

Move workspace dependency root metadata serialization out of configuration-time task wiring and out
of JSON string task inputs while preserving the existing resolved-root cacheability contract.
At the same time, produce a hard inventory of all production JSON encode/decode sites touched by
the workspace dependency pipeline and verify that model JSON crosses task boundaries as Gradle file
inputs/outputs, not as parsed/encoded provider payloads.

The target shape is file-backed metadata:

```text
CollectWorkspaceDependencyRootMetadataTask
    output: RegularFileProperty workspaceDependencyRootMetadata

ResolveWorkspaceDependenciesTask
    input:  @InputFile RegularFileProperty workspaceDependencyRootMetadata
    input:  ListProperty<ResolvedComponentResult> workspaceDependencyRootComponents
```

Task wiring must pass Gradle file providers, for example:

```kotlin
resolveWorkspaceDependenciesTask.configure {
    workspaceDependencyRootMetadata.set(
        collectWorkspaceDependencyRootMetadataTask.flatMap { it.workspaceDependencyRootMetadata }
    )
}
```

Do not pass file paths as strings. `ResolveWorkspaceDependenciesTask` parses the
`RegularFileProperty` inside `@TaskAction`. JSON payloads must not be encoded, decoded, or carried
as `Provider<String>` / `ListProperty<String>` values during task configuration.

## Non-Goals

- Do not mark `ResolveWorkspaceDependenciesTask` untracked because it uses
  `ResolvedComponentResult`.
- Do not mark `CollectKspProcessorDependenciesTask` untracked because it uses
  `ResolvedComponentResult`.
- Do not replace Gradle root-component inputs with bespoke serialized resolution graphs in this
  item.
- Do not move root dependency resolution back to old per-module/per-variant full resolution.
- Do not change bucket ownership, dependency values, exclusions, or generated Bazel output.
- Do not add file-glob cache proxies.
- Do not pass JSON file paths as `String` task inputs. Use `RegularFileProperty`,
  `ConfigurableFileCollection`, `Provider<RegularFile>`, or Gradle file collections.

## Scope

Primary scope:

- `WorkspaceDependencyInputsRegistrar`
- `WorkspaceDependencyRootInputPlanner`
- `ResolveWorkspaceDependenciesTask`
- focused tests around task wiring and Gradle file-property boundaries

Audit scope:

- all production Kotlin JSON encode/decode call sites reachable from `grazel-gradle-plugin`;
- production `tasks/internal` task registration/configuration blocks;
- `Json.encodeToString`, `Json.decodeFromString`, `fromJson`, `writeJson`, `decodeFromStream`,
  `encodeToStream`, `readText`/`writeText` paired with JSON, and equivalent wrappers;
- JSON strings/maps/lists used as task inputs where a file boundary is the correct Gradle model;
- KSP task wiring only to verify no eager JSON/parse issue exists there.

This item should record any discovered adjacent issue, but only fix issues that can be resolved
without changing dependency semantics or generated output.

## Required Inventory

This item must leave a durable JSON phase inventory in one of:

```text
reports/specs/execution-log/item30-json-phase-inventory.tsv
reports/specs/json-phase-inventory.tsv
```

Minimum columns:

```text
file
line
call
owner_task_or_service
phase
transport
verdict
action_taken
retained_rationale
```

Allowed `phase` values:

```text
task_action
helper_called_from_task_action
task_configuration
provider_calculation
service_called_from_task_action
unknown
```

Allowed `transport` values:

```text
file_input_output
json_string_task_property
in_memory_service_state
local_helper_only
none
```

Allowed `verdict` values:

```text
ok_file_boundary
ok_action_local
fixed_to_file_boundary
fixed_to_task_action
retained_with_rationale
blocked_needs_maintainer_decision
```

`unknown`, blank, or unreviewed rows fail the item. Every retained non-file JSON transport must
explain why it is not a task-boundary model payload.

## Required Semantics

### 1. No JSON payload work during task wiring

Forbidden outside `@TaskAction`:

- `Json.encodeToString(...)`
- `Json.decodeFromString(...)`
- `fromJson(...)`
- `writeJson(...)`
- reading JSON files;
- constructing large serialized payloads from Gradle model objects.

Allowed during task configuration:

- adding task output file providers to task input file properties;
- adding typed/scalar task input providers that are not JSON payloads;
- adding `Provider<ResolvedComponentResult>` root components;
- wiring task dependencies and output file locations;
- cheap scalar conventions.

Do not encode JSON in a provider as a workaround. Gradle already has the correct transport for JSON:
files. A producer task writes the JSON file in its action; the consumer task reads and parses that
file in its action.

Task-boundary rule:

- JSON model payloads crossing between tasks must use `RegularFileProperty`, `ConfigurableFileCollection`,
  `@InputFile`, `@InputFiles`, `@OutputFile`, or `@OutputFiles`.
- JSON model payload files must be wired as Gradle file objects/properties, never as string paths.
- A task may hold typed/scalar non-JSON inputs, but it must not hold model JSON as
  `Property<String>` or `ListProperty<String>`.
- Helpers such as `fromJson`/`writeJson` are acceptable only when called from a task action or from
  a helper whose only production callers are task actions.
- Services may parse JSON only when initialized from a task action; the inventory must record the
  task-action call chain.

### 2. Preserve Gradle-resolved root component inputs

`ResolveWorkspaceDependenciesTask` may remain `@CacheableTask` with
`ListProperty<ResolvedComponentResult>` inputs. This is an explicit invariant inherited from master:
Gradle owns resolution result identity and cacheability.

Do not "simplify" this into an untracked task or a bespoke resolution-result serializer unless a
separate future item proves a concrete Gradle cache failure.

### 3. Keep root metadata paired deterministically

The current task action zips root components and metadata by list position. That may remain only if
wiring preserves deterministic ordering and tests prove it.

Preferred within this item:

- introduce a stable root key/id in metadata and write the metadata list/file in the same
  deterministic order used to add root component providers;
- sort roots deterministically before adding them;
- add a test that mismatched counts still fail clearly and stable ordering is preserved.

Do not introduce generated-output changes for this pairing cleanup.

### 4. Keep task altitude clean

Responsibilities:

- `gradle.variant`: exposes typed configuration roles and variant facts.
- `gradle.dependencies`: plans workspace dependency roots and metadata.
- `tasks/internal`: wires task and file providers only.
- `CollectWorkspaceDependencyRootMetadataTask`: serializes planned root metadata to a JSON file
  during task execution.
- `ResolveWorkspaceDependenciesTask`: reads and decodes metadata file, resolves roots, writes
  `workspace-dependency-results.json`.
- `ComputeWorkspaceDependenciesTask`: remains file-in/file-out and does not regain root/variant
  knowledge.

## Implementation Shape

Suggested preserving stages:

1. Add a focused regression test that fails if `WorkspaceDependencyRootInput.toMetadata()` is called
   during task configuration.
2. Add `CollectWorkspaceDependencyRootMetadataTask` or equivalent, with a deterministic
   `RegularFileProperty` output:

```text
build/grazel/workspace-dependency-root-metadata.json
```

3. Replace `workspaceDependencyRootMetadataJsons: ListProperty<String>` with an
   `@InputFile RegularFileProperty` on `ResolveWorkspaceDependenciesTask`, for example:

```text
workspaceDependencyRootMetadata: RegularFileProperty
```

4. Keep `workspaceDependencyRootComponents: ListProperty<ResolvedComponentResult>` unchanged.
5. Ensure task wiring connects:

```text
CollectWorkspaceDependencyRootMetadataTask.output -> ResolveWorkspaceDependenciesTask.input file
```

6. Generate the JSON phase inventory before edits, covering every production JSON encode/decode
   call site.
7. Fix every task-boundary JSON payload that is not file-backed, unless a row records a concrete
   maintainer-quality rationale.
8. Regenerate the JSON phase inventory after edits. No row may remain `unknown`, blank, or
   unreviewed.
9. Record the audit in the execution log with file:line findings.

## Tests

Add or update focused tests for:

- root metadata serialization happens in the metadata producer task action, not during task
  configuration;
- `ResolveWorkspaceDependenciesTask` receives root metadata through an input file, not a JSON string
  list property or string path;
- `ResolveWorkspaceDependenciesTask` still decodes metadata inside `@TaskAction`;
- root component and metadata counts still fail with a clear message if mismatched;
- root ordering is deterministic for repeated planner output;
- no production task registration/configuration path eagerly calls JSON encode/decode after the
  fix, using a source scan or focused unit test where practical;
- JSON phase inventory exists, covers all production encode/decode call sites, and has no unknown
  rows;
- KSP resolver wiring keeps its current cacheable `ResolvedComponentResult` behavior and has no
  eager JSON work.

Tests must not use reflection or source-string assertions when a typed seam is practical. A narrow
source scan is acceptable only for the "no eager JSON in task wiring" guard, because the property
being guarded is source-shape/phase discipline.

## Verification

Minimum Grazel gates:

```text
./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon
./gradlew migrateToBazel --console=plain --no-daemon
reports/scripts/verify-default-task-graph.sh
reports/scripts/verify-sample-bucket-labels.sh
git diff --check
```

PAX gate after implementation:

```text
cd /Users/arun.sampathkumar/work/pax-android
./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks
git diff --check
```

If PAX generated output differs from the accepted baseline, stop and classify the diff before
running APK builds. For this item, the expected generated diff is empty. If migrate output is
unchanged, run the normal PAX build/test gate when this item is part of a larger goal that already
requires it.

## Hard Exit Gates

This item is not complete unless all are true:

- no `Json.encodeToString`, `Json.decodeFromString`, `fromJson`, `writeJson`, or equivalent JSON
  payload work runs eagerly from task registration/configuration for workspace dependency roots;
- every production JSON encode/decode call site is inventoried with phase, transport, verdict, and
  action/rationale;
- every task-boundary JSON model payload is transported through Gradle file inputs/outputs, matching
  the master-like file boundary style;
- workspace dependency root metadata is transported as a Gradle file input/output, not as
  `Provider<String>` / `ListProperty<String>` JSON payloads;
- workspace dependency root metadata file locations are wired as `RegularFileProperty` /
  `Provider<RegularFile>` or Gradle file collections, not as string paths;
- `WorkspaceDependencyInputsRegistrar.addRoot` or its replacement wires root component providers
  and metadata file providers only;
- `ResolvedComponentResult` cacheable inputs are preserved and not reframed as a problem;
- root component/metadata pairing remains deterministic and has focused test coverage;
- KSP task wiring is audited and either unchanged with rationale or fixed if an eager metadata
  issue is found;
- generated Grazel sample output is empty-diff;
- PAX `migrateToBazel` leaves the accepted baseline unchanged;
- execution logs record the audit findings, chosen fix shape, commands, results, and remaining
  risks.
