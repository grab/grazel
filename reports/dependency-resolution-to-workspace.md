# Grazel: Gradle Dependencies → Bazel WORKSPACE Pipeline

> Research report mapping how Grazel transforms resolved Gradle dependencies into
> Bazel `WORKSPACE` content, centered on `ResolveVariantDependenciesTask` and the
> downstream dependency pipeline.
>
> Scope: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/`
> Branch: `arun/dependencies-refactor`

## Executive Summary

Grazel converts a Gradle project's dependency graph into a pinned, reproducible
Bazel `maven_install` setup in **four stages**, each driven by a dedicated Gradle
task and backed by shared build services for caching:

```
Gradle variants/configurations
        │
        ▼
[Stage 1] ResolveVariantDependenciesTask          (per variant)
        │   resolves + flattens graph → build/grazel/{variant}/dependencies.json
        ▼
[Stage 2] ComputeWorkspaceDependenciesTask         (whole project)
        │   dedup + version-arbitrate + transitive closure → WorkspaceDependencies
        ▼
[Stage 3] MavenInstallArtifactsCalculator           (during root script gen)
        │   WorkspaceDependencies → Set<MavenInstallData>
        │   ...then PinMavenArtifactsTask → *_install.json (pinning)
        ▼
[Stage 4] WorkspaceBuilder / RootBazelFileBuilder    (Starlark emission)
            maven_install(...) rules written into WORKSPACE
```

Key cross-cutting ideas:
- **Per-variant resolution then global merge.** Each Android variant resolves
  independently; a later stage deduplicates against the `default` variant.
- **Highest-version-wins arbitration** using Gradle's `DefaultVersionComparator`.
- **Build services** (`DependencyResolutionService`, `DependencyGraphsService`)
  cache results across tasks and serve fast lookups during target generation.
- **Deferred pinning**: WORKSPACE is generated first (unpinned), then a separate
  task runs `bazel run @<repo>//:pin` to produce `*_install.json` lockfiles.

---

## Stage 1: Dependency Resolution

### Entry Point
**`ResolveVariantDependenciesTask`** (`tasks/internal/ResolveVariantDependenciesTask.kt:69`)
- A Gradle `DefaultTask` that resolves Gradle configurations into a serialized dependency graph.
- Registered **per variant** via the `register()` companion method, which also creates a `resolveDependencies` lifecycle task at root + project level (`:243`).
- **Inputs**: `variantName`, `base` flag, `compileConfiguration` (`List<ResolvedComponentResult>`), `compileDirectDependencies` (map), `compileExcludeRules` (map), KSP configuration lists + artifact mappings.
- **Outputs**: `resolvedDependencies` (`RegularFileProperty` → `build/grazel/{variant}/dependencies.json`).

### Key Classes
| Class | Responsibility |
|-------|----------------|
| `ResolveVariantDependenciesTask` | Resolves variant configs, writes the JSON output. |
| `ResolvedComponentsVisitor` | DFS-flattens the transitive graph; collects jetifier requirements + repository metadata. |
| `ResolveDependenciesResult` (`model/`) | Holds resolved deps keyed by scope (`COMPILE`, `KSP`). |
| `ResolvedDependency` (`model/`) | Immutable Maven artifact: id, version, transitives, exclude rules, repo, jetifier flags, `processorClass`. |
| `DependencyResolutionService` | Build service caching/serving resolved deps to downstream tasks via variant/group/name lookups. |
| `DependencyGraphsBuilder` | Builds variant-keyed **project** dependency graphs (Guava `ValueGraph`). |
| `DependencyGraphsService` | Build service that lazily builds + caches those graphs on first access. |

### Data Flow
1. **Variant setup** (`processVariant`, `:419`): for each variant, check `hasUniqueDependencies()` (`:307`); extract direct external deps via Gradle `incoming.dependencies`; collect exclude rules (`extractExcludeRules`, `:284`); for KSP variants collect artifacts + processor classes (`collectKspDependencyInfo`, `:354`).
2. **Graph flatten** (`action`, `:158`): `ResolvedComponentsVisitor.visit()` DFS over the root `ResolvedComponentResult`, skipping project deps + ignored artifacts; each visit yields a `VisitResult` (component, repo name, transitives, jetifier flag) → mapped to `ResolvedDependency`.
3. **Variant inheritance/dedup** (`:174`): non-base variants read the parent variant's JSON (`baseDependenciesJsons`) and filter out already-inherited deps; transitives are stripped from output (`removeTransitives`) so only direct deps are kept.
4. **KSP processor extraction** (`:207`): processor class names pulled from KSP processor JARs via `KspProcessorClassExtractor`, stored on `ResolvedDependency.processorClass`.

### Output Artifact
A JSON-serialized `ResolveDependenciesResult` at `build/grazel/{variant}/dependencies.json` — `variantName` + a per-scope `Set<ResolvedDependency>`. Parent-variant JSONs feed child variants via the `baseDependenciesJsons` input. Downstream, `DependencyResolutionService` (`:109`) loads these into a `WorkspaceDependencies` object and caches variant→dep and shortId→transitive maps.

### Notable Details
- **Two-pass variant registration** (`:263`): create tasks first, then wire inter-variant `extendsFrom` relationships.
- **Memory management**: heavy `ResolvedComponentResult` lists explicitly cleared after serialization (`releaseResolvedGraphs`, `:228`) to avoid OOM on large multi-variant projects.
- **Build services** use coroutine `Mutex` for thread-safe init.
- **`limitDependencyResolutionParallelism`** flag (`:538`) allows sequential resolution to reduce artifact-download contention.

---

## Stage 2: Compute Workspace Dependencies

### Entry Point
**`ComputeWorkspaceDependenciesTask`** (`tasks/internal/ComputeWorkspaceDependenciesTask.kt:52`)
- **Inputs**: the per-variant JSON files from Stage 1.
- **Outputs**: a single `WorkspaceDependencies` JSON at `build/grazel/dependencies.json`, plus in-memory cache population via `DefaultDependencyResolutionService`.

### Key Classes
| Class | Responsibility |
|-------|----------------|
| `ComputeWorkspaceDependencies` | Orchestrates a 5-stage parallel-stream pipeline to dedup/flatten/reduce variant deps into a unified set. |
| `WorkspaceDependencies` (`model/`) | Result container: per-variant deps, aggregated repos (KSP), transitive-closure map. |
| `ResolvedDependency` (`model/`) | Maven node with id, version, `shortId` (group:artifact), `direct` flag, transitives, exclude rules, `OverrideTarget`, jetifier metadata. |
| `TransitiveDependenciesStore` | Thread-safe `shortId → Set<transitive shortId>` cache for closure queries. |
| `TopologicalSorter` | Sorts Gradle projects by dependency order (Kahn's algorithm); cycle detection via DFS. |
| `GradleDependencyToBazelDependency` | Maps project-to-project deps to Bazel labels, handling Android variant suffixes. |
| `BazelDependency.MavenDependency` | Renders a Maven coord as a Bazel label `@repo//:group_name` (`.`/`-` → `_`). |

### Data Flow — the 5-stage pipeline (`ComputeWorkspaceDependencies.compute`)
1. **Parse & group by variant** (`:27`): parallel-read variant JSONs, group by `variantName` then `shortId`, dedup via `maxVersionReducer()` (highest version wins).
2. **Drop duplicate non-default variants** (`:48`): for non-`default` variants, drop any `shortId` already present in `default`.
3. **Flatten transitive closure** (`:68`): expand `allDependencies` as direct entries, regroup by `shortId`, re-apply `maxVersionReducer()`.
4. **Override transitive targets** (`:95`): after flattening, a transitive whose `(shortId, version)` is **identical** to the parent/`default` copy is marked with `OverrideTarget` (a Bazel label redirecting to the parent's copy). This is a **correctness/graph-fidelity mechanism, not a cost optimization** — see the [Design Rationale](#design-rationale-authors-clarifications) section. It only fires on exact version match, so divergent versions (the whole reason variants are split) keep their own dedicated path.
5. **Compute transitive closure map** (`:138`): for all `direct` deps build `shortId → sorted Set<transitive shortId>`.
- **KSP aggregation** (`:156`): aggregate all KSP processor deps across variants, dedup by max version, store under `ksp_maven` in `aggregatedRepos`. ⚠️ **Known tech debt (author-confirmed):** KSP collapses to a *single* tree rather than following the per-variant hierarchy. This is an accepted edge case — it assumes processors are effectively variant-invariant, which can break for projects with flavor-specific annotation processors.

### Gradle → Bazel Mapping
- Gradle `group:artifact:version` → Bazel `@maven//:group_artifact` (`.`/`-` → `_`), e.g. `androidx.appcompat:appcompat:1.6.0` → `@maven//:androidx_appcompat_appcompat`.
- Repo name defaults to `maven`; variant-specific repos use `toMavenRepoName(variantName)` downstream.
- Project deps use `BazelDependency.ProjectDependency` → `//path/to:target` with optional variant suffix (`_debug`, `_release`).

### Output Artifact
A `WorkspaceDependencies` JSON with three top-level sections: `result` (per-variant dep lists), `aggregatedRepos` (e.g. `ksp_maven`), and `transitiveClasspath` (`group:artifact → [transitive shortIds]`). Consumed by Stage 3/4.

### Notable Details
- **Version arbitration** uses Gradle's `DefaultVersionComparator` (semantic, not string).
- Output sorted by `ResolvedDependency::id` for reproducible builds.
- Exclude rules + jetifier metadata preserved through merges (`merge()`, `:109`).
- Intermediate maps explicitly cleared after use to enable GC.

---

## Stage 3: Maven Install Artifacts & Pinning

### Entry Points
- **Data producer**: `GenerateRootBazelScriptsTask` calls `MavenInstallArtifactsCalculator.get()` to compute artifacts and populate WORKSPACE with `maven_install()` rules.
- **Pinning task**: `PinMavenArtifactsTask` (`tasks/internal/PinMavenArtifactsTask.kt:38`) — runs only if `rules.mavenInstall.artifactPinning.enabled` is true; inputs the WORKSPACE + `WorkspaceDependencies`; invokes `ArtifactPinner.pinArtifacts()`.

### Key Classes
| Class | Responsibility |
|-------|----------------|
| `MavenInstallArtifactsCalculator` | `WorkspaceDependencies` → `Set<MavenInstallData>`: version overrides, jetifier config, repos, exclude rules. |
| `MavenInstallData` | Artifacts, repos, external artifacts/repos, jetifier config, pinning flags, `*_install.json` filename. |
| `DefaultArtifactPinner` | Decides if repin needed, runs `bazel run @<repo>//:pin`, executes pin scripts, toggles `maven_install_json` in WORKSPACE. |
| `MavenInstallStore` | Concurrent `(variant, group, name) → repo_name` index used at target-generation time. |
| `Repository` / `DefaultRepositoryDataSource` | Extract + cache Maven repos from Gradle, filtering to public or `PasswordCredentials`-auth repos. |
| `BazelLogParsingOutputStream` | Streams Bazel output; detects `fail_if_repin_required` to know when the lockfile is stale. |

### Data Flow
```
WorkspaceDependencies
  → MavenInstallArtifactsCalculator.get()    (per variant/repo → MavenInstallData)
       artifacts:    ResolvedDependency → MavenInstallArtifact (+ exclusions)
       repositories: repos → DefaultMavenRepository (url + optional credentials)
       jetifierConfig / overrideTargets / mavenInstallJson = "${repoName}_install.json"
  → WorkspaceBuilder.buildJvmRules()  → maven_install(name=<repo>, artifacts=[...], ...)
       maven_install_json initially commented (#); fail_if_repin_required=False
  → WORKSPACE written
  → PinMavenArtifactsTask (if enabled)  → uncomment maven_install_json, run pin scripts
       → {maven,debug_maven,ksp_maven,...}_install.json
  → MavenInstallStore populated during target generation (resolve @maven vs @debug_maven)
```

### Pinning
**Why pinning is load-bearing (not just speed):** Grazel computes the *exact* resolved version for every artifact itself (the "world version" — this is what `maxVersionReducer` is for) and feeds Bazel that pinned set. The intent is to **prevent `rules_jvm_external`'s own resolver from diverging** from Gradle's resolution. Without pinning, RJE would re-resolve and fail builds on artifact version conflicts that Gradle had already settled; pinning forces RJE to *honor* Grazel's computed versions rather than re-derive them. So pinning is a **correctness requirement** here, with reproducibility/speed as bonuses.

Locks resolved versions + checksums into `*_install.json` for reproducible, fast builds. Flow: generate unpinned WORKSPACE (`artifactPinning=true`, `isMavenInstallJsonEnabled=false`) → `MavenRules.kt` prefixes `maven_install_json` with `#` and sets `fail_if_repin_required=False` → `shouldRunPinning()` decides (commented json ⇒ always repin; else trial `bazel build --nobuild` with `fail_if_repin_required=True`) → `pinArtifacts()` uncomments the json, runs `bazel run @<repo>//:pin --script_path=...` per repo (parallel via `WorkerExecutor`), producing the lockfiles. Recovery: `ensureSafeToRun()` unpins + deletes corrupt `.json` and retries.

### Classpath Reduction (`ClasspathReduction.kt`)
`calculateDirectDependencyTags(self, deps)` computes a reduced dep list per Bazel target: project deps → `@direct${project}`, maven deps → `@maven//group:artifact`, plus `@self//$self`, sorted. Each target declares only directly-needed deps; Bazel's transitive closure handles the rest. Used by Android/Kotlin/test extractors.

### Repositories
1. `RepositoryDataSource` (`gradle/Repository.kt:31`) scans all projects' repos; keeps `DefaultMavenArtifactRepository` (no mavenLocal/`file://`), public or `PasswordCredentials` only; preserves Gradle order.
2. `calculateRepositories()` (`MavenInstallArtifactsCalculator.kt:218`) maps each artifact's repo name → `DefaultMavenRepository` (with credentials if `includeCredentials`).
3. **External repositories** (`:109`) only attached to the DEFAULT variant (`debug_maven`).
4. **Aggregated repos** (e.g. `ksp_maven`) include transitive deps' repos (`calculateRepositoriesIncludingTransitives`, `:234`).

### Notable Details
- **Version overrides** via `overrideVersionsMap` (`:56`), applied in `toMavenInstallArtifact()` (`:178`).
- **Exclude rules** → `SimpleExclusion` unless in `excludeArtifactsDenyList`; non-empty exclusions ⇒ `DetailedArtifact`, else `SimpleArtifact`.
- **Jetifier**: from `requiresJetifier` + `jetifyIncludeList` minus `jetifyExcludeList`/`DefaultJetifierExclusions` → `jetify=true, jetify_include_list=[...]`.
- **Version conflict policy** passed through `MavenInstallData` to the rule.

---

## Stage 4: Workspace File Generation & Orchestration

### Entry Point
**`GenerateRootBazelScriptsTask`** (`tasks/internal/GenerateRootBazelScriptsTask.kt:50`)
- **Inputs**: `workspaceDependencies` (from Stage 2, via `dependencyResolutionService.init()`), `projectsToMigrate` (filtered by `MigrationChecker.canMigrate()`).
- **Outputs**: root `WORKSPACE` (`:67`) and root `BUILD.bazel` at `build/grazel/BUILD.bazel.bazel-ignore` (`:72`).

### Key Classes
| Class | Responsibility |
|-------|----------------|
| `WorkspaceBuilder` | Generates WORKSPACE content: repository rules, `maven_install`, Kotlin/Android SDK setup. |
| `RootBazelFileBuilder` | Generates root BUILD.bazel: Kotlin setup, Dagger/Android extensions, KSP processors, toolchains. |
| `GenerateRootBazelScriptsTask` | Orchestrates both builders, writes outputs. |

### WorkspaceBuilder — Starlark Emission (`build()`, `:86`)
1. `workspace(name = rootProject.name)`.
2. `kotlinRules()` — Kotlin repo, compiler, KSP compiler, toolchain registration.
3. Pre-bazel_common archives (if configured).
4. `bazelCommon()` — grab-bazel-common imports.
5. `buildJvmRules()` (`:106`) — **core**: optionally load Dagger artifacts/repos, load grab-bazel-common artifacts, call `mavenInstallArtifactsCalculator.get()` with the workspace deps, then emit one `mavenInstall()` rule per `MavenInstallData` (artifacts, repos, jetify, exclusions, version policy).
6. Android SDK/NDK repos (`addAndroidSdkRepositories()`), Google Play Services repo if present.

### Task Orchestration (`TasksManager.configTasks()`, `:51`)
```
ComputeWorkspaceDependenciesTask         → workspaceDependencies file
   → AnalyzeVariantCompressionTask       (consumes workspaceDependencies)
   → GenerateRootBazelScriptsTask        (WORKSPACE + root BUILD.bazel)
   → GenerateBuildifierScriptTask        → buildifierScript
   → FormatBazelFileTask (root)          (formats WORKSPACE/BUILD.bazel)
   → PinMavenArtifactsTask               (if artifactPinning enabled)
   → GenerateDownloaderConfigTask
   → GenerateBazelScriptsTask (per project)  (consumes workspaceDependencies)
   → PostScriptGenerateTask              (collection point)
   → migrateToBazelTask
   → bazelBuildAllTask
```
`workspaceDependencies` fans out to **three consumers**: root script gen, pin task, and per-project script gen.

### Notable Details
- **Lazy init**: builders are created from factories at task-action time so extension config is fully resolved.
- **Immutable snapshot**: workspace deps computed once, then referenced consistently across root + per-project generation.
- **Pinning is opt-in**, gated on `rules.mavenInstall.artifactPinning.enabled`.
- **Variant compression gates** root script gen because compression affects per-project BUILD generation.

---

## Variant Tree Hierarchy & Bucket Mapping

*(Verified directly against source — `MavenInstallStore.kt`, `migrate/dependencies/Repository.kt`, `gradle/variant/`.)*

The single most important structural idea: **Bazel cannot represent per-variant classpaths in one external repo.** A `maven_install` repo is one coherent, single-version-per-artifact resolved graph; Gradle resolves *each configuration as its own independent graph* (debug and release may resolve the same artifact to different versions). To preserve that, Grazel emits **one `maven_install` repo per variant** and reconstructs Android's variant tree as a hierarchy of buckets.

### Naming is flat; the hierarchy is in lookup
Repo names are derived purely from the variant name — there is **no nesting in the name itself**:

```kotlin
// migrate/dependencies/Repository.kt:21
fun String.toMavenRepoName() = when (this) {
    DEFAULT_VARIANT -> "maven"
    else -> replace("([a-z])([A-Z]+)".toRegex(), "$1_$2").toLowerCase() + "_maven"
}
```
- `default` → `@maven`  ·  `debug` → `@debug_maven`  ·  `freeDebug` → `@free_debug_maven`  ·  `androidTest` → `@android_test_maven`

The **hierarchy is expressed two ways**, not via names:

1. **`extendsFrom` sets** on each `Variant` (`gradle/variant/AndroidVariants.kt:42`). For `freeDebug`:
   `extendsFrom = {default, free, debug}` (plus test variants where applicable). The interface doc (`Variant.kt:38`) states outright: *"Variants can have a hierarchy and `extendsFrom` denotes the parent variants."* There is **no explicit tree object** — the DAG is encoded as adjacency sets on each node.

2. **Resolution-time dedup against parents** (`ResolveVariantDependenciesTask`): a child variant's task receives its parents' resolved-dependency JSONs as inputs (`baseDependenciesJsons`) and strips anything already claimed by a parent. So each variant's JSON contains **only the deps it introduces** — a dep lands in the bucket of the *most-specific variant that first introduces it*.

### Bucket lookup walks specific → general
`DefaultMavenInstallStore` (`gradle/dependencies/MavenInstallStore.kt:49`) stores the raw variant name as the key and converts to a repo name only at query time:

```kotlin
override fun get(variants: Set<String>, group: String, name: String): MavenDependency {
    fun get(variant: String) =
        if (cache.containsKey(ArtifactKey(variant, group, name)))
            MavenDependency(variant.toMavenRepoName(), group, name) else null
    return variants.asSequence().mapNotNull(::get).firstOrNull()   // walk hierarchy, specific→general
        ?: get(DEFAULT_VARIANT)                                     // fallback 1: @maven bucket
        ?: MavenDependency(group = group, name = name)             // fallback 2: bare @maven ("could be incorrect but makes for easier testing")
}
```
A `freeDebug` target resolves a dependency by walking `[freeDebug, free, debug, default]` and taking the **first bucket that owns it** — so shared deps resolve up to `@debug_maven` or `@maven`, and only freeDebug-unique deps come from `@free_debug_maven`. **This walk *is* the hierarchy at consumption time.**

### Worked example (`default → debug → freeDebug`)
| Dependency | Declared in | Bucket it lands in | How `freeDebug` finds it |
|------------|-------------|--------------------|--------------------------|
| `androidx.core:core` | `implementation` (default) | `@maven` | walk hits `default` |
| `okhttp` | `debugImplementation` | `@debug_maven` | walk hits `debug` |
| `com.example:foo` | freeDebug-only | `@free_debug_maven` | walk hits `freeDebug` |

### Verification notes / corrections to the initial explorer pass
- ✅ `toMavenRepoName`, `extendsFrom` values, and the `MavenInstallStore` lookup are confirmed in source.
- ⚠️ `toMavenRepoName` lives in **`migrate/dependencies/Repository.kt`**, not `gradle/Repository.kt`.
- ⚠️ `get()` has a **two-level fallback** (`variants` → `DEFAULT_VARIANT` → bare `@maven`); the bare-`@maven` fallback is a **test convenience and an acknowledged "hairy gap" (author-confirmed)**. If it fires during a real migration it silently routes a dependency to `@maven` that may belong in a variant bucket — a candidate for an assertion/log to make the gap loud.
- ❓ Not re-verified end-to-end: the exact `extendsFrom`→task-input wiring function name, and whether intermediate flavor-only variants (`free` alone) ever get a *materialized* repo vs. existing only for the lookup hierarchy.

---

## Design Rationale (author's clarifications)

These points come directly from the plugin author and resolve the "why" behind the architecture:

1. **Per-variant trees are essential, not an optimization.** Bazel can't hold `-debug` and `-release` versions of the same artifact in one graph. Separate trees, each forced to resolve independently, are the *only* faithful encoding of Gradle's per-configuration graphs. "Each Gradle configuration is a graph" is the core insight.

2. **`OverrideTarget` is for correctness, not cost.** Flattening a variant's closure can duplicate a node that's identical to the parent's (e.g. `B 1.2` reachable in both `default` and `debug`). The redirect points such exact-match nodes back to the parent bucket to keep the logical graph consistent — it does **not** reduce Bazel-side fetch/resolution cost (each tree still pins independently). It's "fitting logical Gradle semantics into the Bazel graph."

3. **Forced pinning prevents resolver divergence.** Grazel computes final version numbers and passes them as pinned values *by design*, because Gradle's resolution handed to Bazel would otherwise let RJE re-resolve and fail on conflicts. Pinning is the fix for those failures. The pinned version is the **per-tree (per-variant) resolved version**, with `maxVersionReducer` applied within that tree's flattened closure as a tiebreak (author-confirmed; the cross-tree "world version" framing is not what happens in practice — each tree carries its own resolved versions, and the current implementation works empirically).

4. **Single KSP tree is acknowledged tech debt** (see Stage 2 note).

| Concern | Path |
|---------|------|
| Resolution task | `tasks/internal/ResolveVariantDependenciesTask.kt` |
| Graph flatten | `gradle/dependencies/ResolvedComponentsVisitor.kt` |
| Resolution service | `gradle/dependencies/DependencyResolutionService.kt` |
| Project graphs | `gradle/dependencies/DependenciesGraphsBuilder.kt`, `DependencyGraphsService.kt`, `DependencyGraphs.kt` |
| Compute workspace deps | `tasks/internal/ComputeWorkspaceDependenciesTask.kt`, `gradle/dependencies/ComputeWorkspaceDependencies.kt` |
| Transitive/topo | `gradle/dependencies/TransitiveDependenciesStore.kt`, `TopologicalSorter.kt` |
| Gradle→Bazel mapping | `gradle/dependencies/GradleDependencyToBazelDependency.kt` |
| Maven install calc | `migrate/dependencies/MavenInstallArtifactsCalculator.kt`, `MavenInstallData.kt` |
| Pinning | `migrate/dependencies/ArtificatPinner.kt`, `tasks/internal/PinMavenArtifactsTask.kt` |
| Classpath reduction | `migrate/dependencies/ClasspathReduction.kt` |
| Repositories | `migrate/dependencies/Repository.kt`, `gradle/Repository.kt` |
| Maven store | `gradle/dependencies/MavenInstallStore.kt` |
| Workspace emission | `migrate/internal/WorkspaceBuilder.kt`, `RootBazelFileBuilder.kt` |
| Root script task | `tasks/internal/GenerateRootBazelScriptsTask.kt` |
| Orchestration | `tasks/internal/TasksManager.kt` |

*Note: line numbers reflect the state of the branch at research time and may drift.*
