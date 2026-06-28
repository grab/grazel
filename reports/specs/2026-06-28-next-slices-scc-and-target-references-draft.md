# Superseded Draft: SCC Removal And Target Reference Model

> **Status:** Superseded/renumbered 2026-06-28. Discussion input only.
> Slice 17 was replaced by
> `reports/specs/2026-06-28-item18-retire-scc-typed-dag-ordering-design.md`.
> The target-reference idea below is superseded by
> `2026-06-28-item19-target-reference-facts-design.md`. The cacheability idea remains a
> non-final future discussion note and should be treated as candidate Item 20 only after
> maintainers approve an explicit spec.
> **Reconciliation (2026-06-28):** Slice 17 (SCC removal) is replaced by
> `2026-06-28-item18-retire-scc-typed-dag-ordering-design.md` (roadmap Item 18) — use that
> spec, not this slice. A new cleanup not in this draft — set-math de-dup + dead-code removal —
> is captured as `2026-06-28-item17-consolidate-bucket-setmath-design.md` (roadmap Item 17),
> and runs first once approved. The target-reference model has since been promoted to
> `2026-06-28-item19-target-reference-facts-design.md`; use that spec, not this candidate
> section. Cacheability remains candidate future Item 20 and is **draft-only**.
> **Context:** Items 10-16 of the altitude-layering pass are complete through
> local commit `afbdaa3` (`Finalize dependency refactor review`). PAX baseline is
> the intentionally dirty generated state on
> `/Users/arun.sampathkumar/work/pax-android`, branch `arun/grazel-refactor`,
> commit `05d2b4801530726ab722133c2ba32cbba9afeb67`.

## Goal

Continue the altitude cleanup without changing generated output:

```text
Variant/dependency graph owns ordering facts.
Extracted data models own target reference facts.
Target builders/renderers remain the final view.
```

The next work should be split into preserving slices. Each slice must be PAX
baseline-checked and should produce an empty generated diff unless explicitly
approved.

## Superseded Slice: Remove SCC From Reachability Ordering

### Problem

Items 9 and 11 added typed graph nodes/edges and removed the old task-local
cyclic-group fixpoint. The known PAX false cycle is now modeled as an acyclic
typed graph:

```text
deliveries-model-food:test -> food-testkit:main
food-testkit:main -> deliveries-model-food:main
deliveries-model-food:test -> deliveries-model-food:main
```

However, `ProjectReachabilityOrder` still calls
`stronglyConnectedComponents(...)` internally and then rejects non-trivial typed
SCCs. This is safe but conceptually stale: the normal model is a typed DAG, so
ordering should be a DAG topo sort with cycle diagnostics, not SCC condensation.

### Desired Change

- Replace SCC-based ordering in `ProjectReachabilityOrder` with direct typed DAG
  topological ordering.
- Reuse or adapt the existing `TopologicalSorter` graph semantics where possible;
  the old sorter already has clear DAG behavior and mostly lacked typed edge
  handling.
- Keep fail-closed typed cycle diagnostics:
  - typed node identity: project path + source set/type;
  - edge labels: `ConfigurationEdge`, `AndroidTestTargetProjectEdge`, etc.
- Remove now-dead cyclic-group plumbing:
  - `ProjectReachabilityGroup.cyclic` if it becomes unused;
  - collector checks that only existed for synthetic cyclic groups;
  - SCC helper functions if no production caller remains.

### Expected Output

No generated output change. Any PAX diff is a stop-and-investigate signal.

### Main Files

- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/TopologicalSorter.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/DependencyGraphs.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/CollectTargetMavenRepoReferencesTask.kt`
- focused tests under
  `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/`
  and
  `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/tasks/internal/`

### Required Tests

- PAX-shaped false cycle remains acyclic under typed ordering.
- Genuine typed cycle fails with typed diagnostic edge labels.
- `com.android.test -> app` ordering remains consumer-first.
- `CollectTargetMavenRepoReferencesTask` still processes each acyclic project
  once.

## Superseded Candidate Item 19: Move Target References Before BazelTarget String Parsing

> Superseded by `2026-06-28-item19-target-reference-facts-design.md`, which adds the stronger
> requirement to remove `TargetBuilder.build(project)` execution from reference collection while
> preserving the current consumer-first `WorkspacePlanService` mutation.

### Problem

`TargetMavenRepoReferencesCollector` currently scans in-memory `BazelTarget`
models. This is better than parsing generated `BUILD.bazel`, but it still uses
the final render view as an upstream model:

```text
ProjectBazelFileBuilder -> List<BazelTarget>
TargetMavenRepoReferencesCollector parses deps/tags/labels
FinalizeWorkspacePlan -> WorkspaceRenderPlan
```

Some fields are typed (`MavenDependency`, `ProjectDependency`), but others are
already render-shaped (`StringDependency`, `tags: List<String>`). This forces
regex parsing of labels such as:

```text
@debug_maven//:artifact -> debug_maven
//feature/path:target -> :feature:path + target
```

The altitude issue is not generated-file feedback; it is that `BazelTarget` is
the final render view. Extractors should provide the semantic target data model.

### Desired Change

Introduce a structured reference model produced from extracted target data and
shared target naming policy:

```kotlin
internal data class TargetReferenceFacts(
    val mavenRepoNames: Set<String> = emptySet(),
    val projectPaths: Set<String> = emptySet(),
    val projectTargets: Map<String, Set<String>> = emptyMap()
)
```

The exact type name is open, but the ownership is not:

- Extracted data models own semantic facts: deps, plugins, associates,
  instruments, lint checks, tags, target kind.
- A small target-naming policy owns final target names and suffixes.
- Render targets consume the same model but do not become the source of truth.
- Do not store target builders in services.
- Do not store live Gradle services or `ProjectBazelFileBuilder` instances.
- Persist only serializable reference facts where task boundaries need them.

### Data To Cover

- Android library/binary deps, plugins, lint checks, tags.
- Kotlin library/unit-test deps, plugins, associates, tags.
- Android unit test deps, associates, tags.
- Android instrumentation/com.android.test deps, associates, instruments, tags.
- Compressed target suffixes from `VariantCompressionResult`.
- Generated helper target names currently represented as `StringDependency`.

### Expected Output

No generated output change. This is a model-transfer cleanup, not a behavior
change.

### Main Files

- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/TargetMavenRepoReferencesCollector.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/CollectTargetMavenRepoReferencesTask.kt`
- target data classes under
  `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/android/`
  and
  `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/kotlin/`
- target builders under
  `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/`
- possible new model file under
  `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/model/`

### Required Tests

- Existing collector behavior is reproduced from structured facts.
- No bucket-prefixed Maven tags are introduced.
- Project target references are identical before/after for representative:
  - Android library;
  - Kotlin library;
  - Android unit test;
  - Android instrumentation target;
  - lint checks;
  - compressed Android library target.

## Candidate Future Item 20: Cacheability Follow-Up For Target Reference Collection

### Problem

`CollectTargetMavenRepoReferencesTask` is currently untracked because build target
model inputs are not fully declared. Candidate Item 19 should make the model boundary more
serializable, which creates an opportunity to make this task cacheable or at
least reduce the untracked surface.

### Desired Change

- After Candidate Item 19, reassess task inputs and outputs.
- If target reference facts can be collected from declared inputs plus
  serialized workspace/compression data, make the task tracked/cacheable.
- If not, document the exact remaining live input that blocks cacheability.

### Expected Output

No generated output change.

## Verification For Every Slice

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

PAX generated baseline must remain stable unless a future approved behavior
change explicitly re-baselines it:

```text
diff hash   5f05c2380375f16b0c04c6fa5f14d3a1666cf94d6b36a5ce1e0814a1b6e43566
status hash b9b38774443602baa0adf251daeb236e68cd181e1f4ccdf74ee412a30822c6d6
dirty count 2231
```

## Open Decisions Before Execution

- Whether Candidate Item 19 should add reference facts to every extracted data class or
  use adapters beside the data classes to avoid widening model constructors.
- Whether `StringDependency` should be narrowed into typed label variants now or
  only adapted at the target-reference boundary.
- Whether compressed target naming should be centralized before or during Slice
  Candidate Item 19.
