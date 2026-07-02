# Item 42 - Extractor-Owned Transitive Tags

> **Status:** Proposed 2026-07-03.
> **Executor:** Codex. **Behaviour:** output-changing, PAX-gated tag correction.
> **Depends on:** Current dependency-refactor baseline after Items 34-41.
> **Supersedes part of Item 34:** Item 34 cleaned up the workspace tag-plan service shape while
> accepting the collector as essential. This item revises that architectural premise: the target tag
> prepass is accidental complexity if extractors can derive tags from direct dependencies plus the
> root-computed transitive store.
> **Global Constraints + Verification Playbook + Code-quality stance:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`.

---

## First Principle

We inverted dependency resolution, but we did not lose the data model.

Old master resolved module/variant classpaths and populated transitive dependency facts near each
module. The branch now resolves from root app / `com.android.test` classpaths, but it still produces
the same useful value: a Gradle-resolved Maven short-id to transitive-closure mapping. Once that
store is populated, extractors should continue to own tag construction the way they did before:

```text
root/binary classpath resolution
  -> WorkspaceDependencies
  -> DependencyResolutionService / TransitiveDependenciesStore

generateBazelScripts / analyzeVariantCompression
  -> target builder
    -> extractor
      -> direct project deps from Gradle/extractor model
      -> direct Maven deps from Gradle/extractor model
      -> tags = direct Maven labels + transitiveStore[direct Maven shortId]
      -> typed target data

target builder
  -> BazelTarget
```

The inversion changes where the resolved closure is computed. It should not move tag ownership out
of extractors into a separate target-tag plan that re-walks projects and variants.

## Problem

The current branch has a workspace target-tag prepass:

- `CollectWorkspaceTargetTagPlanTask`
- `WorkspaceTargetTagPlanCollector`
- `WorkspaceTargetTagPlanService`
- `target-tag-plan.json`
- extractor calls to `workspaceTargetTagPlanService.tagsFor(...)`

This path duplicates extractor responsibilities:

- it walks projects and variants separately from target generation;
- it re-derives which direct Maven and direct project dependencies a target has;
- it requires `AnalyzeVariantCompressionTask`, `CollectTargetMavenRepoReferencesTask`, and
  `GenerateBazelScriptsTask` to hydrate a tag-plan service before extractors run;
- it is an extra PAX stage (`collectWorkspaceTargetTagPlan`) that processed 17,090 targets in about
  17 seconds during the 2026-07-03 PAX timing run;
- it makes tags look like a Layer-4 workspace plan concern, when tags are part of each extracted
  target's dependency view.

This is the same altitude smell the branch has been removing elsewhere: downstream target shape is
being precomputed by a side pipeline instead of being produced by the extractor model that already
has the direct-dependency context.

## Goal

Remove the workspace target-tag prepass and restore extractor-owned Maven tag calculation. This is a
targeted output-changing correction: generated `tags` should shrink where the current prepass
included Maven deps that came only through direct project dependencies.

The desired rule is:

```text
tags for a generated target =
  normalized @maven labels for the target's direct Maven deps
  union
  normalized @maven labels for the Gradle-resolved transitive closure of those direct Maven deps
```

Direct project dependencies do **not** contribute Maven tags to the consuming target.

Example:

```text
:a declares implementation("x:y:z") and implementation(project(":b"))
:b declares implementation("p:q:r")

Generated :a tags include:
  @maven//:x_y_z
  transitive closure of x:y:z

Generated :a tags do NOT include:
  @maven//:p_q_r
  transitive closure of p:q:r
```

Those `:b` Maven requirements belong to `:b`'s generated target tags/deps, not `:a`'s compile
filter tags.

The source of versions and transitives remains Gradle-resolved workspace dependencies populated by
root app / `com.android.test` resolution. Declared dependencies identify target ownership/context;
declared versions do not select values.

## Non-Goals

- Do not return to old per-module full dependency resolution.
- Do not compute tags by parsing generated `BUILD.bazel` or by executing target builders outside
  generation.
- Do not change bucket ownership, Maven repo materialization, Coursier artifact forcing, or local
  Maven proxy behavior.
- Do not optimize or redesign `Dependencies.kt` broadly unless needed to expose a typed tag helper.
- Do not change generated output except for the explicit tag correction above. Any generated diff
  outside removing Maven tags that originate only from direct project dependencies is
  stop-and-classify.
- Do not remove `TargetReferenceFacts` / render-plan reference collection unless a separate proof
  shows it is the same accidental complexity. This item is about Maven compile-filter tags.

## Required Grounding Before Code

Before implementation, Codex must inspect and record current behavior in
`reports/specs/EXECUTION-LOG.md`:

1. How `DependencyResolutionService.populateCache(...)` fills `TransitiveDependenciesStore`.
2. How `DependenciesDataSource.collectMavenDeps(...)` and
   `collectTransitiveMavenDeps(...)` already use direct dependencies and the store.
3. Every extractor currently calling `WorkspaceTargetTagPlanService.tagsFor(...)`.
4. Every task currently wiring `targetTagPlan`.
5. The exact current semantics in `WorkspaceTargetTagPlanCollector`, especially:
   - direct Maven dependency tags;
   - transitive tags for direct Maven dependencies;
   - Android library/project-dependency contribution that must be removed;
   - variant-specific direct dependency selection versus shortId-only transitive expansion;
   - compressed variant behavior.

Master behavior checkpoint: `master` generated tags from extractor data. It selected direct Maven
dependencies through the target's `VariantGraphKey`, but transitive expansion was keyed only by
Maven `shortId` (`DependencyResolutionService.getTransitiveDependencies(dep.shortId)`). No variant
name, variant hierarchy, repository, or `VariantGraphKey` participated in the transitive lookup.
Item 42 should not introduce variant-aware transitive expansion. Its only intended output change is
removing tags that were contributed solely by direct project dependencies.

This grounding must produce a short parity matrix before edits begin:

```text
target kind | current tag-plan inputs | extractor-owned replacement | test coverage
```

## Implementation Plan

### Step 1 - Add parity tests around current semantics

Add focused tests that pin current tag behavior before deleting the prepass. Prefer tests around the
replacement helper and extractor outputs, not tests that preserve the old collector API.

Minimum cases:

- direct Maven dependency produces its own `@maven//:` tag;
- Gradle-resolved transitive closure from `TransitiveDependenciesStore` is included;
- variant-specific direct dependency selection is preserved, while transitive expansion remains
  shortId-only to match master/current generated output;
- direct project dependencies do not contribute their Maven closure to the consuming target's tags;
- a fixture proves `:a` with direct Maven `x:y:z` and direct project `:b` only tags `x:y:z` and
  `x:y:z`'s transitive closure, not `:b`'s Maven deps;
- unit-test and android-test targets preserve their existing main/test tag split;
- compressed variant target data carries the same tags as uncompressed extractor output.

### Step 2 - Introduce an extractor-owned tag helper

Create a small typed helper in the dependency/extractor boundary, for example
`TransitiveMavenTagCalculator` or a better name discovered during implementation.

The helper should accept explicit role-named inputs, not hidden collection receivers:

```text
project
variant key / preferred variant names
target kind or variant type when needed
direct Maven dependencies
direct project dependencies only for project-label dependency modeling, not Maven tag donation
```

It should read transitive closure from `DependencyResolutionService` /
`TransitiveDependenciesStore`, normalize labels through the existing Maven label utilities, and
return sorted stable `@maven//:` labels.

Do not introduce a new global "target plan" model. The helper is a local extraction utility over
already-populated resolved facts.

### Step 3 - Move extractor consumers off the tag-plan service

Update Android and Kotlin extractors currently calling `workspaceTargetTagPlanService.tagsFor(...)`
to call the extractor-owned helper.

Keep target builders as transformers from typed extracted data to Bazel targets. Target builders
must not become dependency analyzers.

`AnalyzeVariantCompressionTask` must keep producing compressed target data through extractors; it
should not need a tag-plan input after this item.

### Step 4 - Remove the prepass

After extractor parity is proven:

- delete `CollectWorkspaceTargetTagPlanTask`;
- delete `WorkspaceTargetTagPlanCollector`;
- delete `WorkspaceTargetTagPlanService`;
- delete `target-tag-plan.json` wiring from `TasksManager`, compression, reference collection, and
  generation tasks;
- remove Dagger bindings and tests that only preserved the old service/collector surface;
- update task-graph verification so `collectWorkspaceTargetTagPlan` is absent.

Do not keep dead compatibility APIs for tests. Rewrite tests to the live extractor/helper APIs.

### Step 5 - Verify generated output and timing

Run the normal gates plus output-diff classification. Also record PAX migrate timing before/after,
specifically whether the prior `collectWorkspaceTargetTagPlan` stage disappears and total migrate
time improves or stays flat.

## Layering Contract

End shape after this item:

```text
Layer 0  Variant topology
         VariantBuilder / Variant<*> / BucketHierarchyGraph

Layer 1  Cheap declared facts
         extractors/direct dependency APIs identify direct Maven/project deps

Layer 2  Resolved value graph
         root app + com.android.test resolution populate WorkspaceDependencies and
         TransitiveDependenciesStore

Layer 3  Bucket/workspace ownership
         unchanged by this item

Layer 4  Extraction and target data
         extractors combine direct deps + transitive store into typed target data, including tags

Layer 5  Rendering
         target builders/renderers serialize data; no tag inference
```

Allowed service state:

- `DependencyResolutionService` / `TransitiveDependenciesStore` as the resolved value store.
- Existing render-plan reference services if still required for workspace repo rendering.

Disallowed after this item:

- a separate target tag plan service;
- a task that precomputes all target Maven tags;
- tag computation by generated-output parsing;
- target-builder execution solely to discover tags.

## Verification

Required Grazel checks as changes mature:

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
./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test
git diff --check
```

PAX generated diff must be classified against the committed PAX baseline. Accepted diffs are limited
to removal of `@maven//:` tags that came only from direct project dependencies, plus any downstream
deterministic formatting/order changes caused by those removals. Any `WORKSPACE`, Maven install
JSON, deps, or non-tag `BUILD.bazel` diff is stop-and-investigate unless separately justified by the
maintainer.

## Performance Evidence

Record in `reports/specs/EXECUTION-LOG.md`:

- previous PAX `collectWorkspaceTargetTagPlan` time (`17,090` targets in about `17.1s` from the
  2026-07-03 timing run);
- new PAX migrate total time;
- whether variant compression, target reference collection, or generation time changed materially;
- memory/disk/process checks before expensive PAX commands.

Performance improvement is expected but not allowed to justify unclassified output drift.

## Risks And Guardrails

### Service initialization order

Extractors must only call the helper after `DependencyResolutionService` has loaded
`workspace-dependencies.json`. Verify this for:

- `GenerateBazelScriptsTask`;
- `AnalyzeVariantCompressionTask`;
- `CollectTargetMavenRepoReferencesTask` if it still invokes extractors for reference facts.

### Direct project dependency over-collection

The current collector includes transitive Maven tags for direct project dependencies in Android
library paths. That is the behavior this item intentionally corrects. A generated target's Maven
compile-filter tags must be derived only from the target's direct Maven deps and those direct Maven
deps' Gradle-resolved transitive closure. Direct project dependencies contribute project labels, not
their Maven closure.

### Variant-specific direct deps, shortId-only transitive expansion

Do not accidentally flatten away the variant-specific selection of **direct** dependencies: a debug
target must still collect debug direct Maven deps, a release target must still collect release direct
Maven deps, and test/androidTest targets must keep their current direct-dependency split.

For **transitive** Maven expansion, preserve current/master behavior: expand by resolved Maven
`shortId` from `TransitiveDependenciesStore`. A future variant-aware transitive store may be a real
correctness improvement, but it is not part of this item because master did not model that dimension
and PAX has been relying on the shortId-only tradeoff.

### Compression

Compressed targets must carry tags from extractor-produced target data. If compression currently
expects precomputed tag-plan data, reshape compression to consume extractor output rather than
adding another side channel.

## Acceptance Criteria

- No production dependency on `WorkspaceTargetTagPlanService`, `WorkspaceTargetTagPlanCollector`,
  `CollectWorkspaceTargetTagPlanTask`, or `target-tag-plan.json`.
- Extractors own tag calculation through direct Maven deps plus
  `DependencyResolutionService` / `TransitiveDependenciesStore`.
- Target builders remain pure target construction from typed extractor data.
- Generated Grazel/PAX diffs are limited to classified removal of project-dependency-originated
  Maven tags.
- PAX migrate leaves no unclassified diff against the accepted PAX baseline.
- PAX debug APK, android-test APK, and focused test targets pass.
- Task graph verification confirms the target-tag prepass is gone.
- Focused tests cover direct Maven tags, shortId-only transitive store tags, variant-specific direct
  dependency selection, direct project deps not contributing Maven tags, test/androidTest behavior,
  and compressed variants.
- Execution log records commands, timing, output-diff status, and any retained complexity with
  concrete evidence.
