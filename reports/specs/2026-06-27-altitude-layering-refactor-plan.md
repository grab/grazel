# Altitude Layering Refactor Plan

> **SUPERSEDED 2026-06-27.** Architectural input only; **not** an execution contract.
> The source of truth is `reports/specs/ALTITUDE-LAYERING-ROADMAP.md` plus the item specs.
> The "Open Decisions" section below has been resolved or narrowed by those specs. Do not
> re-decide from this file alone.

## Purpose

This document captures the preferred architecture for the next foundational
dependency-refactor pass. The goal is to make bucketing, reachability, pinning,
and rendering feel like one layered system instead of a set of downstream fixes.

The current root-resolution architecture is the right performance direction, but
it lost provenance that old Grazel obtained naturally by resolving closer to each
module/variant. The next refactor should restore that provenance explicitly
through typed variant/dependency graph models.

## Core Altitude Rule

Variant topology is the spine. Other layers attach facts to that topology.

```text
Layer 0: Variant topology
Layer 1: Cheap declared facts
Layer 2: Resolved value graph
Layer 3: Bucket ownership plan
Layer 4: Workspace/render plans
Layer 5: Bazel file rendering
```

Each layer should have one job. Higher layers may consume lower-layer facts, but
lower layers should not reach upward into Bazel rendering, pin files, or previous
generated output.

## Layer 0: Variant Topology

Owns the shape of the world:

- Projects -> variants -> typed variant nodes.
- Main, test, and androidTest relationships.
- Flavor/build type hierarchy.
- Bucket DAG nodes such as `default`, `debug`, `free`, `freeDebug`,
  `androidTestDebug`, and test equivalents.
- `VariantGraphKey`.
- `BucketHierarchyGraph`.

This layer answers:

```text
What buckets exist, and how are they related?
```

It must not own:

- Maven versions.
- Resolved artifacts.
- Transitive closure.
- Bazel labels.
- Pinning behavior.
- BUILD/WORKSPACE rendering details.

Important modeling point: Android variants are a DAG, not a tree.

```text
        default
        /    \
     free    debug
        \    /
      freeDebug
```

Common ownership must therefore be graph-aware. A shared dependency may belong to
`debug`, `free`, `default`, or a typed test node depending on which leaves require
it.

### DAG-First Compression, Not SCC-Driven Bucketing

Variant and bucket compression should be DAG-first. The variant hierarchy is a
modeling graph, not a discovered runtime dependency cycle. If the variant/bucket
graph has a cycle, that is a bug in Layer 0.

```text
Bucket ownership:
  use bucket DAG ancestor/descendant math
  use topological ordering where useful
  do not use SCC as the primary model
```

SCC/fixpoint behavior became tempting because target references were discovered
too late, after rendering had already started. That is an altitude smell:

```text
render project A
  -> discover target refs from generated target output
  -> update accumulated repo refs
  -> render project B
  -> maybe B changes A
  -> repeat globally
```

The refactor should instead make all relevant graph edges explicit before
rendering. Then variant compression remains a DAG problem, and rendering becomes
a single pass over prepared plans.

SCC may still exist only as a defensive graph-service diagnostic for proven genuine typed
cycles. The known PAX `deliveries-model-food` / `food-testkit` project-level cycle is now
treated as a false SCC caused by collapsing `testImplementation` and `implementation` edges
into plain project nodes. Typed source-set/variant projections should eliminate that case.
SCC must not leak into bucket ownership or renderer workflow:

```text
Allowed:
  DependencyGraphsService reports typed-node cycles and keeps fallback only with proof.

Not allowed:
  Workspace/render tasks use global fixpoints as the normal way to discover
  dependency facts.
```

The special `com.android.test -> app` relationship should be represented as a
typed target-reference edge. It participates in reachability and test target
planning, but not in Maven ownership extraction as a fake configuration edge.

## Layer 1: Cheap Declared Facts

Collects metadata from Gradle without resolving configurations.

Owns:

- Direct declared Maven dependencies.
- Direct declared project dependencies.
- Excludes.
- KSP declarations.
- compileOnly/runtimeOnly/test/androidTest ownership hints.
- Non-configuration target references, such as `com.android.test` target project
  edges.

This layer answers:

```text
Why did this dependency or target relationship exist?
```

It does not decide selected versions. Declared versions are only ownership hints.
Resolved versions come from Layer 2.

### Typed Graph Edge Requirement

Use the existing graph investment, but make the edge model honest. Do not encode
non-configuration facts as fake Gradle `Configuration` instances.

Recommended shape:

```kotlin
sealed interface DependencyGraphEdge

data class ConfigurationEdge(
    val configuration: Configuration,
) : DependencyGraphEdge

data class AndroidTestTargetProjectEdge(
    val targetProjectPath: String,
) : DependencyGraphEdge

data class TargetReferenceEdge(
    val reason: TargetReferenceReason,
) : DependencyGraphEdge
```

If using Guava `ValueGraph`, account for the fact that a `ValueGraph` has one
edge value per node pair. If multiple reasons can exist for the same project
pair, store an edge-value collection, for example:

```kotlin
data class DependencyGraphEdges(
    val values: Set<DependencyGraphEdge>,
)
```

Alternatively, keep separate typed graphs for configuration dependencies and
reachability ordering, but expose them through one cohesive graph service.

Configuration consumers must filter to `ConfigurationEdge`. Reachability and
ordering consumers may use all ordering-relevant edge types.

The edge filter matters for preserving DAG semantics. Configuration-only
consumers should see the per-variant Gradle dependency graph. Reachability
consumers may also see test-target and target-reference edges. Bucket compression
should operate on the Layer 0 bucket DAG plus ownership facts, not on a flattened
union of every edge type.

## Layer 2: Resolved Value Graph

Owns root/app and `com.android.test` binary classpath resolution.

Owns:

- Gradle-selected versions.
- Selected artifacts.
- Component graph traversal with `ResolvedComponentsVisitor`.
- Full transitive closure.
- Maven coordinate identity after Gradle conflict resolution.

This layer answers:

```text
What exact artifacts did Gradle choose?
```

It must not decide bucket ownership. It provides values and closure; ownership is
computed by Layer 3.

Hard invariant:

```text
Gradle-resolved identity is the source of truth.
Declared identity is not allowed to override selected versions.
```

Example:

```text
implementation "x:y:1.0"
debugImplementation "x:y:2.0"
```

If Gradle resolves debug to `x:y:2.0`, bucket placement uses `2.0`. If Gradle
upgrades a declared `1.0` to `2.0`, the planner places the resolved `2.0`
artifact, not the declared literal.

## Layer 3: Bucket Ownership Plan

This is the main missing altitude. It should become a first-class model instead
of living implicitly inside workspace computation or rendering.

Inputs:

- Variant topology from Layer 0.
- Declared direct facts from Layer 1.
- Resolved artifact identities from Layer 2.

Owns:

- Which bucket owns each direct resolved Maven root.
- Main/test/androidTest inheritance.
- Flavor/build type common ownership.
- Deduplication placement.
- Direct-root optimization before transitive expansion.
- Distinguishing candidate repos from materialized repos.

This layer answers:

```text
Which repo should own this direct resolved root?
```

It must not:

- Render Bazel files.
- Parse generated files.
- Decide Coursier conflict policy.
- Do task wiring.

Expected examples:

```text
implementation x:y:1.0
  -> @maven owns x:y:1.0

debugImplementation x:y:2.0
  -> @debug_maven owns x:y:2.0

androidTestImplementation t:u:1.0
  -> @android_test_maven owns t:u:1.0
```

Test inheritance rule:

```text
main/default owns inherited main deps.
test/androidTest owns only direct typed deltas.
```

The planner should optimize direct roots. Transitive closure expansion happens
after ownership is decided.

## Layer 4: Workspace And Render Plans

Owns serializable plans consumed by pinner, root generation, and module
generation.

Owns:

- Materialized Maven repositories.
- Final `maven_install.artifacts`.
- Override targets.
- Repo label mapping.
- Target Maven tag closure facts.
- Strictly reachable project set.
- Active vs ignored/unreachable BUILD output decisions.

This layer answers:

```text
What should exist in generated Bazel world?
```

It must not derive state by reading generated `BUILD.bazel`, `WORKSPACE`, or JSON
pin files. Generated files are outputs, not inputs.

Coursier invariant:

```text
maven_install.artifacts includes the final Gradle-resolved direct roots plus the
resolved transitive closure needed to constrain Coursier to Gradle's selected
versions.
```

Do not use `--force-version` or conflict-masking options as a shortcut.

## Layer 5: Rendering

Pure output.

Owns:

- `BUILD.bazel`.
- `WORKSPACE`.
- Maven install JSON files.

Rendering should be boring. It consumes plans and prints files.

It must not:

- Infer ownership.
- Discover repos from generated output.
- Compute reachability.
- Mutate the dependency model.

## Why Grazel Master Felt Better

Old Grazel resolved closer to each module/variant. That was slower, but Gradle
gave it local provenance naturally:

```text
module + variant configuration
  -> Gradle resolves exact local classpath
  -> Grazel sees resolved values in that local context
  -> aggregation/root generation merges those facts
```

The new root-resolution architecture is faster:

```text
root app/androidTest binary classpath
  -> Gradle resolves big final graph once
  -> Grazel fans that result down into buckets/modules
```

But this loses local "why" information unless Layers 0 and 1 explicitly preserve
it. The foundational refactor should recreate master-like provenance without
returning to expensive per-module resolution.

## Current Item 7 Lesson

The current direct-root optimization is correctness-faithful but did not fully
meet the original pin-size metric.

Observed PAX shape:

- Materialized pinfiles improved from 17 to 12.
- `maven_install` repos improved from 28 to 24.
- Total `maven_install.artifacts` roots increased from 1784 to 2094 (+17.4%).

This is acceptable only as a correctness baseline, not as the final pin-size
architecture. Full closure retention is required for correctness, so future size
wins must come from better direct-root ownership and fewer materialized repos,
not from dropping transitive closure.

Follow-up requirements:

- Add an automated size guard against the accepted PAX baseline.
- Amend specs if the accepted threshold is "correctness first, near master size,
  and no unexplained bloat" instead of strict `<= master`.
- Production-wire or remove unused DAG helper APIs such as
  `commonAncestorsOf`/`closestCommonAncestorsOf`.

## Recommended Component Boundaries

Candidate components after refactor:

```text
VariantTopology
  Builds typed variant/bucket DAGs.

DeclaredDependencyFacts
  Captures direct declarations, excludes, project edges, and target-reference
  edges without resolution.

DependencyGraphsService
  Owns typed graph construction and exposes configuration-only and
  reachability/order projections.

ResolvedDependencyGraph
  Holds Gradle-resolved artifacts, selected versions, and component closure.

BucketOwnershipPlanner
  Combines topology + declared facts + resolved values into direct-root bucket
  ownership.

WorkspacePlan
  Describes materialized repos, artifact roots, closure, override targets, and
  repo mappings.

WorkspaceRenderPlan
  Describes exactly what renderers need for BUILD/WORKSPACE output.
```

The naming does not need to be final, but these responsibilities should stay
separate.

## Suggested Refactor Phases

### Phase 1: Typed Graph Edge Foundation

- Introduce `DependencyGraphEdge`.
- Preserve configuration dependency behavior through `ConfigurationEdge`.
- Add target-reference edge support for cases such as `com.android.test`
  `targetProjectPath`.
- Ensure dependency extraction filters configuration edges only.
- Ensure reachability/order uses all relevant typed edges.

### Phase 2: Bucket Ownership Model

- Create a first-class bucket ownership model for direct resolved roots.
- Use variant DAG and declared ownership facts to place roots.
- Keep resolved identity from Gradle.
- Add tests for:
  - debug override version.
  - flavor/buildType common ownership.
  - test inherits main but owns direct test deltas.
  - excludes diverging bucket placement.

### Phase 3: Workspace Plan Cleanup

- Make workspace computation consume the ownership model.
- Keep candidate repos and materialized repos separate.
- Keep closure expansion after direct-root ownership.
- Preserve Coursier version-forcing through full resolved closure in artifacts.

### Phase 4: Rendering Purity

- Ensure renderers only print plans.
- Remove any remaining generated-output feedback paths.
- Remove dead/speculative helpers not used in production.

### Phase 5: Verification And Size Guard

- Add automated PAX size guard for:
  - materialized repo count.
  - pinfile count.
  - total artifact roots.
  - per-key generated shape where meaningful.
- Keep correctness gates primary:
  - PAX `migrateToBazel`.
  - PAX debug APK.
  - PAX android-test APK.
  - focused unit tests from PAX-derived failures.
  - Grazel task graph and bucket-label scripts.

## Non-Negotiable Invariants

- Root/app and `com.android.test` Gradle-resolved classpaths remain the value
  source.
- No return to old expensive per-module resolution as the default path.
- Declared metadata is cheap provenance, not version truth.
- Gradle-resolved versions/artifacts are source of truth.
- Full transitive closure is retained in `maven_install.artifacts` where needed
  to constrain Coursier.
- Maven compile-filter tags use `@maven//:` labels.
- Active generated targets must be strictly reachable from configured roots.
- No PAX-only hacks.
- No parsing generated BUILD/WORKSPACE as upstream model input.

## Open Decisions To Merge With Claude's Spec

- Whether typed graph edges replace the existing `ValueGraph<Project,
  Configuration>` shape directly or sit beside it as a reachability graph.
- Whether `BucketHierarchyGraph` should own all common-ancestor selection policy
  or expose primitive graph facts to `BucketOwnershipPlanner`.
- Whether the size guard should fail at strict master parity, accepted baseline
  parity, or "master + documented tolerance" while correctness is proven.
- Exact component names.
- Exact serialization boundary for cacheable task outputs.

## Desired End State

The final architecture should read as:

```text
Variant layer defines possible structure.
Declared layer defines ownership intent.
Resolved layer defines artifact truth.
Planner combines them.
Renderer prints them.
```

If a future bug appears, it should be obvious which layer owns the fix. A missing
test target edge should be a graph-layer issue. A wrong version should be a
resolved-value issue. A bloated bucket should be a bucket-ownership issue. A bad
BUILD line should be a renderer issue.
