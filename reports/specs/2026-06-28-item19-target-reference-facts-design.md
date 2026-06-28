# Item 19 - Target Reference Facts; Remove TargetBuilder Execution From Reference Collection (Design)

> **Status:** Draft for final review 2026-06-28.
> **Executor:** Codex.
> **Behaviour change:** none - reference collection must be byte-identical. Golden EMPTY-diff.
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`. **Depends on:** Items 17 and 18.
> **Supersedes** Candidate Future Item 19 in
> `2026-06-28-next-slices-scc-and-target-references-draft.md`.

> **Execution note - delegate to subagents; protect the main context.** Wide target-builder
> audits, PAX diff audits, and final parity review should be done in focused agents that
> return distilled findings. The parent reconciles and spot-checks.

---

## Goal

Remove the remaining high-cost altitude violation in workspace planning:
`CollectTargetMavenRepoReferencesTask` currently executes `TargetBuilder.build(project)` via
`ProjectBazelFileBuilder.targets()` only to inspect the produced `BazelTarget` models, and
`GenerateBazelScriptsTask` executes the same builders again to render `BUILD.bazel`.

This item replaces reference discovery from final target models with structured
`TargetReferenceFacts` produced before `BazelTarget` creation. The existing consumer-first
incremental algorithm stays; `WorkspacePlanService.populateRenderPlan(...)` mutation stays; the
task may remain untracked. The unacceptable part is running target builders during reference
collection.

## Current Problem

Current flow:

```text
CollectTargetMavenRepoReferencesTask
  -> ProjectBazelFileBuilder.create(project).targets()
     -> TargetBuilder.build(project)
     -> Android/Kotlin data extraction
     -> BazelTarget deps/tags/plugins/associates/instruments
  -> TargetMavenRepoReferencesCollector.fromTargets(BazelTarget)
     -> inspect typed deps
     -> regex render-shaped tags and StringDependency labels
  -> WorkspaceRenderPlan

GenerateBazelScriptsTask
  -> ProjectBazelFileBuilder.create(project).targets()
     -> TargetBuilder.build(project) again
  -> render BUILD.bazel
```

This is not generated-file parsing, but it is still backwards layering:

```text
final target view -> workspace planning input -> final target view again
```

The cost is also real. Target builders can perform variant matching, data extraction,
dependency mapping, tag calculation, compression lookups, and reachability checks. Doing that
once for planning and once for rendering amortizes away part of the root-resolution win.

## Correct Altitude

Target reference discovery belongs between extracted facts and target generation:

```text
Layer 0  Variant topology      VariantBuilder / VariantGraphKey / typed graph nodes
Layer 1  Cheap declared facts   declared deps, project edges, excludes, KSP/test ownership
Layer 2  Resolved values        root app/androidTest resolved artifacts and closures
Layer 3  Planning               BucketOwnershipPlanner + WorkspacePlan + WorkspaceRenderPlan
                                 + TargetReferenceFacts
Layer 4  Generation             TargetBuilder / ProjectBazelFileBuilder consume plans once
Layer 5  Rendering              BUILD.bazel / WORKSPACE / maven_install json formatting
```

Desired flow:

```text
CollectTargetMavenRepoReferencesTask
  accumulated references = empty

  for project in consumers-first order:
    WorkspacePlanService.populateRenderPlan(accumulated.asRenderPlan())
    TargetReferenceFactsExtractor.collect(project)
      -> uses variant/compression/extracted dependency facts and naming policy
      -> does NOT call TargetBuilder.build(project)
      -> does NOT create BazelTarget
    accumulated += facts

  write target-maven-repo-references.json

GenerateBazelScriptsTask
  read final WorkspaceRenderPlan
  TargetBuilder.build(project) once
  render BUILD.bazel
```

## Grounded Current State

Primary production files:

- `tasks/internal/CollectTargetMavenRepoReferencesTask.kt`
  - calls `ProjectBazelFileBuilder.Factory.create(project).targets()`;
  - incrementally mutates `WorkspacePlanService` before each project;
  - writes `target-maven-repo-references.json`;
  - is currently `@UntrackedTask`;
  - currently declares `compressionResults` as an input even though it does not read it. Do not
    remove that input before this item decides whether reference facts need compression data.
- `tasks/internal/TargetMavenRepoReferencesCollector.kt`
  - consumes `Iterable<BazelTarget>`;
  - extracts Maven repo names from `MavenDependency.repo` and build target `tags`;
  - extracts project paths/target names from `ProjectDependency` and regex-parsed
    `StringDependency`.
- `tasks/internal/GenerateBazelScriptsTask.kt`
  - initializes final `WorkspaceRenderPlan`;
  - calls `ProjectBazelFileBuilder.targets()` again and renders.
- `migrate/internal/ProjectBazelFileBuilder.kt`
  - owns target builder execution and sorting.
- Target builders/data extractors under `migrate/target`, `migrate/android`, and
  `migrate/kotlin` contain the facts that currently flow into `BazelTarget`.

Existing tests that must remain meaningful:

- `WorkspacePlanTasksTest.collect target references reaches targets activated by prior
  references`
- `WorkspacePlanTasksTest.collect target references uses consumer first single pass`
- `TargetMavenRepoReferencesCollectorTest`

## Work

1. **Add a structured reference model.**

   Create a serializable or plain internal model near the workspace-plan model layer:

   ```kotlin
   internal data class TargetReferenceFacts(
       val repoNames: Set<String> = emptySet(),
       val projectPaths: Set<String> = emptySet(),
       val projectTargets: Map<String, Set<String>> = emptyMap()
   )
   ```

   It may alias or convert to `TargetMavenRepoReferences`, but it should name the semantic
   layer clearly. It is not a render target.

2. **Add a target naming/reference policy.**

   Add a small shared policy for target names that are currently reconstructed from
   `ProjectDependency(prefix, suffix)` or string labels. The policy must cover:

   - Android library target names and `_lib` variants;
   - Android binary target names;
   - Android unit test targets;
   - Android instrumentation/com.android.test targets;
   - Kotlin library and unit-test targets;
   - compressed variant suffixes from `VariantCompressionResult`;
   - generated helper references currently represented as `StringDependency`.

   Do not duplicate naming formulas silently between facts and target builders. If an existing
   helper already owns a formula, reuse it or move it to the shared policy with tests.

3. **Build reference facts from reference-specific facts, not `BazelTarget`.**

   Introduce a `TargetReferenceFactsExtractor` path used only by
   `CollectTargetMavenRepoReferencesTask`. It should use cheap dependency/variant/compression
   inputs and shared tag/naming helpers, not full source/resource/manifest target extraction.
   Reusing a full Android/Kotlin target data extractor is allowed only as a temporary,
   explicitly logged bridge when needed for parity; it must be tracked as performance debt in
   the execution log. The new path must not call:

   - `ProjectBazelFileBuilder.targets()`;
   - `TargetBuilder.build(project)`;
   - `TargetMavenRepoReferencesCollector.fromTargets(...)`;
   - `BazelTarget.statements(...)`.

   The required win for this item is zero target-builder execution during reference
   collection. The stronger preferred win is avoiding full target data extraction too; if that
   cannot be done safely in one slice, document the remaining duplicated extractor calls and
   keep generated output empty-diff.

4. **Preserve the incremental WorkspaceRenderPlan loop.**

   Keep this algorithm:

   ```text
   accumulated facts -> populate WorkspaceRenderPlan -> collect next project facts
   ```

   `WorkspacePlanService` mutation is allowed in this item. `@UntrackedTask` may remain.
   Cacheability is deferred to Item 20. Do not try to global-precompute all references unless a
   separate preserving proof is written.

5. **Dual-run parity before cutover.**

   First add a temporary comparison path:

   ```text
   old = TargetMavenRepoReferencesCollector.fromTargets(ProjectBazelFileBuilder.targets())
   new = TargetReferenceFactsExtractor.collect(project)
   assert/record old == new
   ```

   Run it on focused tests and PAX before removing the old production path. After parity is
   proven, cut over the task to the facts path and delete the old production dependency on
   `ProjectBazelFileBuilder.Factory`.

6. **Delete or quarantine render-model parsing.**

   After cutover, `TargetMavenRepoReferencesCollector.fromTargets(...)` should either be
   removed or remain test-only as a parity fixture. Production reference collection must not
   inspect `BazelTarget` or parse render-shaped labels/tags to infer workspace repos.

## Execution Phases

Execute this item in preserving phases so any mismatch is attributable:

1. **Additive facts + parity.** Add `TargetReferenceFacts` and the facts extractor without
   changing production output. Run old target-model collection and new facts collection side by
   side in focused tests and, if useful, behind a temporary internal parity path.
2. **Cutover.** Switch `CollectTargetMavenRepoReferencesTask` to the facts path while keeping
   the consumer-first `WorkspacePlanService` mutation. The task may still be untracked.
3. **Remove production target-model collection.** Delete the task dependency on
   `ProjectBazelFileBuilder.Factory`; remove or quarantine `TargetMavenRepoReferencesCollector`
   as test-only parity support.
4. **Performance accounting.** Record whether full data extractors remain in the facts path,
   and log target-builder invocation count during collection as zero.

## Required Coverage

The facts path must cover every reference source currently inspected from `BazelTarget`:

- build target `deps`;
- build target `tags` that contain Maven repo labels;
- plugin targets;
- lint checks;
- Android test `associates`;
- Android test `instruments`;
- Kotlin unit-test associates;
- `StringDependency` project labels used for generated helper targets;
- `ProjectDependency` prefix/suffix target names;
- compressed Android target suffixes.

Maven compile-filter tags must remain normalized `@maven//:` labels in generated output; no
bucket-prefixed Maven tags may be introduced.

## Safety Mechanism

- **Golden EMPTY-diff.** This is a preserving architecture/performance slice. Any generated
  sample or PAX diff is a stop-and-investigate event.
- **PAX frozen baseline.** PAX dirty diff/status hashes and size guard from Item 10 remain the
  comparison source. Do not rebaseline for this item.
- **Parity tests.** New facts path must match the old target-model collector for representative
  target shapes before cutover.
- **Consumer-first activation tests.** Existing tests proving earlier references activate later
  targets must stay green and should be adapted to the facts path.
- **Performance hygiene.** Record in `reports/specs/EXECUTION-LOG.md` after each meaningful
  run:
  - target-builder invocation count during `collectTargetMavenRepoReferences` before/after;
  - any full target data extractor invocation count still used by the facts path;
  - `migrateToBazel` elapsed time for Grazel sample and PAX when measured;
  - PAX size guard counts: bucket count, pinfile count, total artifact roots;
  - whether `CollectTargetMavenRepoReferencesTask` still uses target builders.

## Required Tests

- Facts collector parity for:
  - Android library target with Maven deps, project deps, plugins, lint checks, and tags;
  - Kotlin library target with Maven/project deps, plugins, lint checks, and tags;
  - Android unit test with associates and Maven tags;
  - Kotlin unit test with associates and Maven tags;
  - Android instrumentation target with associates and instruments;
  - compressed Android target suffix references;
  - `StringDependency("//path:target")` compatibility cases that cannot yet be modeled
    structurally.
- Consumer-first single-pass test still proves:
  - project A facts reference project B target;
  - project B facts see the accumulated `WorkspaceRenderPlan`;
  - project B contributes its Maven repos without a second pass.
- A guard test or instrumentation proof that `CollectTargetMavenRepoReferencesTask` does not
  call `TargetBuilder.build(project)` after cutover.

## Verification

Grazel:

```text
./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon
./gradlew migrateToBazel --console=plain --no-daemon
reports/scripts/verify-default-task-graph.sh
reports/scripts/verify-sample-bucket-labels.sh
reports/scripts/verify-pax-size-guard.sh --mode preserving
git diff --check
git diff --check master...HEAD
```

PAX after meaningful non-doc changes:

```text
cd /Users/arun.sampathkumar/work/pax-android
./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks
./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk
./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test
git diff --check
```

No PAX commits. No public push.

## Acceptance Criteria

- `CollectTargetMavenRepoReferencesTask` no longer depends on
  `ProjectBazelFileBuilder.Factory` and no longer calls target builders.
- Production reference collection no longer consumes `BazelTarget` models.
- The consumer-first incremental `WorkspaceRenderPlan` behavior is preserved.
- `WorkspacePlanService` mutation remains allowed; `@UntrackedTask` may remain with a clear
  note that cacheability is Item 20.
- Generated sample output and PAX output are empty-diff against the accepted baseline.
- PAX migrate, debug APK build, android-test APK build, and focused PAX unit tests pass.
- Execution log records performance hygiene metrics and confirms target-builder invocation
  count during reference collection dropped to zero.

## Out of Scope / Non-goals

- Full task cacheability for `CollectTargetMavenRepoReferencesTask`.
- Removing `WorkspacePlanService.populateRenderPlan(...)` mutation.
- Global precomputation of all references without the incremental loop.
- Changing bucket ownership, resolved values, tags, or Maven repo materialization.
- Rebaselining PAX generated output.

## Risks / Traps

- **Naming drift:** facts and target builders must not grow divergent target-name formulas.
- **Compressed suffix drift:** compressed representative variants must match generated target
  names exactly.
- **StringDependency compatibility:** existing helper references may still be string-shaped; do
  not drop them while purifying the model.
- **Order sensitivity:** project processing order is part of behavior because accumulated
  references activate later targets.
- **Hidden behavior changes:** an empty PAX diff is required, but not sufficient; parity tests
  must prove the new facts path sees the same semantic references as the old target-model path.
