# Item 26 - Variant-Owned Workspace Dependency Root Inputs (Design)

> **Status:** Completed 2026-06-28 (`468dd5f`), with follow-up source-shape review delegated to
> Items 30, 29, and 28.
> **Executor:** Codex.
> **Behaviour change:** none - golden EMPTY-diff.
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`. **Depends on:** Items 9 and 10.

> **Execution note - protect altitude.** This item exists because task wiring started
> reconstructing variant/configuration semantics. The fix should move that knowledge back to the
> variant/dependency-planning layers, not hide it behind new helper functions in the task package.
> Use scoped subagents aggressively: every Kotlin file changed by this branch must be visited for
> similar altitude violations before the item exits.

---

## Goal

Remove ad hoc Gradle configuration-name lookups from `WorkspaceDependencyInputsRegistrar`.
Workspace dependency root inputs should be driven by `Variant`/`VariantBuilder` and a small typed
root planner, not by task-layer string construction.

This is a preserving architecture cleanup. Generated output, PAX generated baseline, Maven repo
materialization, and dependency resolution semantics must remain unchanged.

## Current Problem

`WorkspaceDependencyInputsRegistrar` currently owns too much:

- enumerates variants eagerly through `VariantBuilder.build(project)`;
- imports AGP model types (`BaseVariant`, `BuildType`) to classify variants;
- synthesizes unit-test configuration names such as
  `${variant.name}UnitTestRuntimeClasspath`;
- synthesizes android-test configuration names such as
  `${variant.name}AndroidTestRuntimeClasspath`;
- looks up `lintChecks` directly;
- decides which configurations become `MAIN_HIERARCHY`, `MAIN_LEAF`, `TEST_HIERARCHY`,
  `UNIT_TEST`, `ANDROID_TEST`, or `LINT` roots.

This violates the intended layering:

```text
Variant API owns Gradle/AGP variant and configuration shape.
Dependency root planner owns dependency-root intent.
Task registrar wires task providers and task inputs.
```

The current code works, but it duplicates configuration parsing already present in
`ConfigurationParsingVariant` and makes future AGP/configuration-shape fixes land in task wiring.

This known violation is also a signal to look for nearby versions of the same mistake across the
branch diff: task-layer code rebuilding variant semantics, renderers re-deriving planner facts,
dependency code reaching into target-generation details, tests keeping production seams alive, or
helpers hardcoding names that an existing typed model should own.

## Correct Altitude

Target shape:

```text
VariantBuilder.onVariants(project)
  -> Variant exposes typed compile/runtime/lint/test classpath configuration sets
  -> WorkspaceDependencyRootInputPlanner maps variant facts to dependency root descriptors
  -> WorkspaceDependencyInputsRegistrar adds root components + metadata to ResolveWorkspaceDependenciesTask
```

The task registrar may still run after project evaluation if Gradle providers require it, but it
must not construct configuration names or inspect AGP backing types to infer hierarchy.

## Post-Execution Clarification

The implemented preserving slice moved workspace dependency root planning out of
`WorkspaceDependencyInputsRegistrar`. The registrar must remain a task-wiring component only.

The variant layer is the accepted home for workspace classpath role accessors. `Variant.kt` may
expose common role-shaped APIs such as `workspaceUnitTestClasspathConfigurations`,
`workspaceAndroidTestClasspathConfigurations`, and `workspaceLintClasspathConfigurations`, because
callers need one typed variant contract. If those accessors contain Android-specific name
construction, that is a variant-layer source-shape concern, not a task-layer altitude violation.
Items 30/29/28 may still improve that placement by moving Android-only defaults into concrete
Android variant implementations or `ConfigurationParsingVariant`, but they must not move the
knowledge back into task wiring.

## Design Principles

### 1. Prefer `onVariants` for configuration-phase wiring

`VariantBuilder.onVariants(project)` is the lazy/configuration-phase API. Workspace dependency
input registration must use it or a planner built on top of it.

`VariantBuilder.build(project)` is the eager API. It may remain for callers that genuinely need a
full materialized set, but this item must not add new eager usage and must remove eager registrar
usage unless parity work proves that doing so is impossible under the preserving contract. If
switching registrar logic from `build()` to `onVariants`, first add a parity test or audit showing
both APIs expose the same relevant variant identity for this path. If parity fails, fix the variant
API shape in this slice before continuing; do not fall back to eager task-layer enumeration as the
final state.

### 2. Keep configuration parsing in the variant layer

Hardcoded AGP configuration names belong in `gradle.variant`, currently through
`ConfigurationParsingVariant`, concrete Android variant implementations, or explicit role accessors
on `Variant`. If new classpath roles are needed, extend `Variant` or add variant-layer helpers
rather than calling `project.configurations.findByName(...)` from the task registrar.

Allowed variant-layer extensions include typed accessors such as:

```text
Variant.workspaceMainClasspathConfigurations
Variant.workspaceUnitTestClasspathConfigurations
Variant.workspaceAndroidTestClasspathConfigurations
Variant.workspaceLintClasspathConfigurations
```

Final names may vary, but each API name must make the classpath role explicit and keep the
configuration-name knowledge in `gradle.variant`.

### 3. Keep dependency-root intent outside the raw variant API

Do not make `gradle.variant` depend on `AggregatedDependencyRootKind` unless there is already a
clean dependency direction. Prefer a planner in `gradle.dependencies` or a narrow adjacent package
that consumes variant facts and emits root descriptors:

```kotlin
data class WorkspaceDependencyRootInput(
    val projectPath: String,
    val kind: AggregatedDependencyRootKind,
    val bucketName: String?,
    val metadataVariant: Variant<*>?,
    val configuration: Configuration,
    val traverseProjectNodes: Boolean,
    val targetBuckets: Set<String>,
)
```

The descriptor shape can change during implementation, but the final shape must preserve the distinction
between:

- the variant/configuration being resolved;
- the variant metadata used for bucket/build-type/flavor fields;
- the output bucket(s) receiving the resolved values.

### 4. Preserve test-root semantics exactly

Current unit/android-test roots sometimes resolve test classpaths while using the main leaf
variant as metadata and bucket owner. Preserve this unless a failing test proves the old behavior
was wrong.

Example semantic shape:

```text
configuration owner: freeDebugUnitTest / freeDebugAndroidTest classpath
metadata owner:      freeDebug main leaf
bucketName:          freeDebug
kind:                UNIT_TEST or ANDROID_TEST
```

This distinction is the reason the task registrar should consume planned descriptors rather than
assembling names locally.

## Scope

1. Add focused tests documenting current registrar/root-input behavior before moving code.
2. Add or extend variant-layer APIs so callers can ask for typed workspace classpath
   configurations without hardcoding configuration names outside `gradle.variant`.
3. Add a small root-input planner that converts variants into `WorkspaceDependencyRootInput`
   descriptors.
4. Refactor `WorkspaceDependencyInputsRegistrar` to:
   - wire tasks;
   - call the planner;
   - add root components/metadata from descriptors;
   - stop constructing configuration names;
   - stop importing AGP backing model types for root classification.
5. Add a regression test that fails if `WorkspaceDependencyInputsRegistrar` contains direct
   `findByName("lintChecks")`, `UnitTestRuntimeClasspath`, `AndroidTestRuntimeClasspath`, or
   AGP `BaseVariant`/`BuildType` imports.
6. Run a broad altitude scan over every Kotlin file changed by this branch diff:
   - build a deterministic changed-file inventory;
   - assign scoped subagents to file clusters;
   - ask them to identify task/variant/dependency/rendering boundary violations similar to this
     registrar issue;
   - parent agent reconciles findings and fixes confirmed altitude violations in this item.
7. Repeat the scan after fixes on the changed-file inventory and record that no confirmed
   in-scope altitude violations remain.

The item must not exit after fixing only `WorkspaceDependencyInputsRegistrar`. It exits only after
the scan has visited every changed Kotlin file and confirmed altitude violations in the current
slice are fixed. A finding may remain only if the parent proves it is not an altitude violation or
it would require an output-changing behavior redesign outside this preserving contract; such a
case must stop for maintainer direction, not silently become a follow-up.

## Out of Scope

- Changing dependency bucket ownership.
- Changing resolved dependency values or Coursier artifact roots.
- Changing KSP sidecar collection, except to avoid introducing new task-layer configuration-name
  parsing.
- Making `ResolveWorkspaceDependenciesTask` or `ComputeWorkspaceDependenciesTask` cacheability
  changes.
- Removing all string parsing from `ConfigurationParsingVariant`; that package is the current
  accepted location for AGP configuration-name knowledge.

## Verification

Focused:

```text
./gradlew :grazel-gradle-plugin:test --tests "*Variant*" --tests "*WorkspaceDependency*" --console=plain --no-daemon
```

Grazel:

```text
./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon
./gradlew migrateToBazel --console=plain --no-daemon
reports/scripts/verify-pax-size-guard.sh --mode preserving
git diff --check
git diff --check master...HEAD
```

PAX after production changes:

```text
cd /Users/arun.sampathkumar/work/pax-android
./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks
git diff --check
```

No PAX commits. No public push.

## Acceptance Criteria

- `WorkspaceDependencyInputsRegistrar` no longer hardcodes unit-test/android-test/lint
  configuration names.
- `WorkspaceDependencyInputsRegistrar` no longer imports `BaseVariant` or `BuildType`.
- Variant-owned APIs expose the configuration roles needed by workspace dependency input
  planning.
- Root-input planner tests cover main hierarchy, main leaf, unit test, android test, standalone
  `com.android.test`, lint, and filtered variants.
- `onVariants` vs `build` parity for the relevant root-input variants is proven or any
  intentional difference is documented and tested.
- `onVariants` vs `build` parity includes a multi-flavor Android sample, not only no-flavor
  variants.
- Every Kotlin file changed by this branch has been visited by the altitude scan.
- Every similar altitude violation found by the scan is fixed in this slice, or explicitly rejected
  as not a violation with rationale in the execution log/inventory.
- A post-fix scan has run over the same changed-file inventory and found no remaining confirmed
  in-scope altitude violations.
- Generated Grazel output is empty-diff.
- PAX generated baseline remains unchanged.

## Risks / Traps

- **Moving strings without fixing altitude:** A helper in `tasks/internal` that still builds
  `${variant.name}UnitTestRuntimeClasspath` is not a fix. The knowledge must move to the variant
  layer.
- **Wrong dependency direction:** Do not make low-level variant classes depend on high-level
  resolver output models unless the package boundary is intentionally changed.
- **Test-root metadata drift:** Resolving a test classpath under the wrong metadata owner can move
  dependencies between buckets without obvious compile errors. Preserve and test this explicitly.
- **Eager enumeration regression:** Switching from `build()` to `onVariants` is desired, but only
  after parity is verified for this path.
- **Narrow fix tunnel vision:** The registrar issue is the anchor, not the whole item. Do not stop
  until the broad changed-file altitude scan is complete and reconciled.
