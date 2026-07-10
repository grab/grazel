# Grazel Dependency Subsystem — Architecture Overview

*Branch: `arun/dependencies-refactor`. This document orients a new maintainer to the refactored
dependency migration subsystem: how a Gradle dependency graph becomes a set of Bazel `maven_install`
repositories with pinned `maven_install.json` lockfiles, and where the experimental local Maven
proxy fits in.*

---

## 1. The one-paragraph model

Grazel resolves the whole Gradle dependency graph, groups every external artifact into named
**buckets** (each bucket becomes one Bazel `maven_install` repository), decides which bucket *owns*
each artifact so narrower scopes never re-list what a broader scope already provides, renders the
resulting plan into a `WORKSPACE`, and finally **pins** each repository into a reproducible
`maven_install.json`. Pinning normally hits real remote repositories; when the experimental
`experiments.localMavenResolution` flag is on, pinning is routed through an in-process Maven proxy
that serves rules_jvm_external the exact artifacts Gradle already resolved, and the resulting
lockfile is rewritten back to canonical URLs.

The pipeline is a linear, file-serialized sequence of Gradle tasks (each stage writes JSON under
`build/grazel/` for the next), orchestrated by `TaskManager` (`tasks/internal/TasksManager.kt`).

---

## 2. End-to-end data flow

```
 Gradle graph          declared metadata        bucket ownership          workspace / render        maven pinning              local proxy (optional)
 -----------           -----------------         ----------------          ------------------        -------------              ----------------------
 ResolveWorkspace  ->  DeclaredDependency    ->  BucketOwnership       ->  WorkspacePlanBuilder  ->  PinMavenArtifactsTask  ->  LocalMavenProxyServer
 DependenciesTask      Metadata (snapshot)       Planner                   + WorkspaceRenderPlan     + ArtifactPinner           + LocalMavenResolvedFacts
 (Aggregated-          folded into closures      (set-math reduction)      Builder                   (rje pin scripts)          + lockfile reconstruction
  DependencyResolver)
```

1. **Resolution.** `WorkspaceDependencyInputsRegistrar` prepares one *aggregated dependency root*
   per bucket/leaf (from app and standalone `com.android.test` binary modules).
   `AggregatedDependencyResolver` walks each root's resolved Gradle graph
   (`ResolvedComponentsVisitor`), drops BOM/platform (pom-only) components rje rejects, and produces
   per-bucket **closures** — `shortId (group:artifact) -> ResolvedDependency` maps of roots plus
   their transitive descendants.
2. **Declared metadata.** A per-project snapshot (`DeclaredDependencyMetadata`) of what the user
   *declared* (variant topology, `extendsFrom`, exclude rules, compileOnly, project edges, override
   targets) is captured before resolution and folded into the closures. This covers dependencies a
   binary root never surfaced (e.g. `compileOnly`, or declarations on unreached modules) and carries
   user intent as *declared-metadata* placeholders.
3. **Bucket ownership.** `BucketOwnershipPlanner` runs one planning pass over all projects, deciding
   which bucket owns each artifact and dropping copies a broader bucket already covers (set-math /
   reduction). Output: one `ResolveDependenciesResult` per non-empty bucket, in stable order
   (default → main hierarchy → per-leaf → unit-test → instrumentation-test → lint).
4. **Compute + arbitrate.** `ComputeWorkspaceDependencies` collapses those results into a single
   `WorkspaceDependencies` snapshot (versions reconciled to highest compatible, closures flattened,
   override targets computed).
5. **Plan.** `WorkspacePlanBuilder` turns dependencies into a `WorkspacePlan`: candidate
   `maven_install` repos, their pin inputs (root artifacts), and override-target labels.
   `CollectTargetMavenRepoReferences` walks the target model to learn which repos are actually
   referenced, and `FinalizeWorkspacePlan` narrows the plan into a `WorkspaceRenderPlan` (exactly
   what to materialize).
6. **Generate.** `GenerateRootBazelScriptsTask` renders the root `WORKSPACE` (via
   `WorkspaceBuilder`) and each subproject's `BUILD.bazel`; output is buildifier-formatted.
7. **Pin.** When artifact pinning is enabled, `PinMavenArtifactsTask` + `ArtifactPinner` resolve the
   generated `WORKSPACE` against real (or locally proxied) Maven repos and write `maven_install.json`.

---

## 3. Subsystems and responsibilities

### 3.1 Android variant configuration roles & hierarchy
`gradle/variant/Variant.kt` (+ `VariantBuilder`, `VariantMatcher`, `BucketHierarchyGraph`,
`VariantCompressor`).

The foundation. AGP creates a configuration per build-type × flavor permutation but no typed API to
ask "which configurations belong to variant X?". `Variant<T>` reconstructs that mapping uniformly for
Android and pure-JVM projects, exposing **configuration roles** (compile, runtime, annotation
processor, KSP, Kotlin compiler plugin) and the workspace classpath configurations that feed pinning.

Key vocabulary defined here and reused everywhere downstream:
- **Variant** — a migratable unit (Android flavor+build-type leaf, or JVM build/test/lint).
- **`VariantType`** — `AndroidBuild`, `AndroidTest`, `Test`, `JvmBuild`, `Lint` (`isBuildGraph`
  distinguishes build graphs from test graphs to avoid artificial topo-sort cycles).
- **`extendsFrom` / hierarchy bucket** — a variant's declared parents (`freeDebug` extends `default`,
  `free`, `debug`; `debugUnitTest` also extends `test`). A non-leaf parent is a *hierarchy bucket*.
- **Leaf / leaf closure** — a concrete buildable variant and the full dependency set it inherits.
- **Default variant names** — `DEFAULT_VARIANT` (`default`), `TEST_VARIANT` (`test`),
  `ANDROID_TEST_VARIANT` (`androidTest`), `LINT_VARIANT` (`lint`).

### 3.2 Declared dependency metadata collection & resolution graph
`gradle/dependencies/AggregatedDependencyResolver.kt`.

Resolves every external dependency for the migratable project set and assigns each to a bucket. It
walks the resolved graph of each **aggregated dependency root**, folds in `DeclaredDependencyMetadata`,
and produces the per-project, per-bucket closures that seed ownership planning.

- **Root** (`AggregatedDependencyRoot`) — a resolvable Gradle configuration used as a traversal entry
  point, tagged by `AggregatedDependencyRootKind` (`MAIN_HIERARCHY`, `MAIN_LEAF`, `TEST_HIERARCHY`,
  `UNIT_TEST`, `ANDROID_TEST`, `LINT`). Only binary modules provide roots; absence of a binary root
  means "nothing migratable to resolve".
- **`ProjectDependencyBucket`** — a `(projectPath, bucketName)` pair; closures are always
  project-qualified so two projects declaring the same bucket name never collide before ownership.
- **Reachable main bucket** — a `(project, bucket)` actually reachable from a selected binary root via
  declared project edges; gates which declared-main dependencies apply and downstream
  "missing Maven dependency" checks.
- Exclude-rule handling mirrors Gradle semantics: `withoutDependenciesExcludedByEveryReachableRoot`
  keeps an artifact if *any* reaching root does not exclude it.
- `ResolutionSession` (inner class) holds all mutable traversal state so the resolver itself is
  stateless/reusable. `mergeDependencyMetadataByMaxVersion` (top-level) is the single merge rule:
  real resolved beats declared-metadata; otherwise higher version wins; exclude rules are *unioned*
  when exactly one side is declared metadata, *intersected* otherwise.

### 3.3 Dependency bucket ownership & placement planning
`gradle/dependencies/BucketOwnershipPlanner.kt` (+ `DependencyBucketPlacementEngine`, `BucketSetMath`,
`CoveredDependency`).

The step that turns "which artifacts exist where" into "which bucket is the single source of truth
for each artifact". Takes an `OwnershipPlannerInput` (leaf / hierarchy / test closures + declared
metadata + reachability) and emits one `ResolveDependenciesResult` per non-empty output bucket.

- **Ownership by hierarchy.** A broader bucket *covers* an artifact; a `CoveredDependency` already
  owned by an ancestor is dropped (or downgraded to an override carrier) from narrower buckets.
  Hierarchy buckets may be *explicit* (user-declared, present in closures) or *inferred*
  (synthesised by `DependencyBucketPlacementEngine` when all descendant leaves share an artifact).
- **Ordered passes.** Main graph is planned first (`planMainBuckets`); its coverage is handed to the
  unit-test pass; both are handed to the instrumentation-test pass — so a test bucket only declares
  what its enclosing scopes do not already provide.
- **Test coverage is stricter than main.** `canCoverTestDependency` dispatches to
  `canCoverDeclaredTestMetadata` / `canCoverInheritedTestRoot` / `canCoverDeclaredTestRoot`, requiring
  matching version/repository/jetifier/closure and compatible exclude rules; the *scoped sibling
  closure* trick lets a same-bucket sibling vouch for transitive edges the owner lacks.
- **Declared metadata folding.** `applyDeclaredMetadata` merges user intent (exclude rules, override
  targets) onto the resolved artifact by max version, then placeholders covered by `default` are
  scrubbed (`withoutDeclaredPlaceholdersCoveredByDefault`).

### 3.4 Workspace plan, render plan & root dependency inputs
`gradle/dependencies/WorkspacePlanBuilder.kt` (+ `WorkspaceDependencyRootInputPlanner`,
`ComputeWorkspaceDependencies`, `WorkspaceRenderPlanBuilder`).

Turns resolved workspace dependencies into a `WorkspacePlan`: the candidate `maven_install`
repositories, their pin inputs, and override-target labels.

- **Bucket / variant repo** — a variant name maps 1:1 to a repo via `toMavenRepoName`
  (`debug` → `debug_maven`, `default` → `maven`).
- **Aggregated repo** — not tied to a variant (e.g. `ksp_maven`); collects cross-variant artifacts
  (annotation processors) and bypasses per-variant reduction.
- **Root artifact / pin input** — what a repo must pin. `default` and `lint` pin every resolved
  artifact; other variant repos pin their direct declarations plus transitively reachable artifacts
  rehydrated from the owning variant (`mavenInstallRootArtifactsByVariant`).
- **Override target** — a Bazel label redirecting one artifact's coordinates to a target in a
  *different* repo, so a variant repo borrows an artifact already pinned by `default` instead of
  re-pinning it. Sourced from artifacts themselves + user-configured overrides; self-references
  dropped (`calculateMavenInstallOverrideTargets`).

`WorkspaceRenderPlanBuilder` prunes to repos actually reachable from migrated targets
(`materializedMavenRepos`), yielding the `WorkspaceRenderPlan`.

### 3.5 Android/Kotlin target migration & Bazel generation
`migrate/internal/WorkspaceBuilder.kt` (+ `ProjectBazelFileBuilder`, target builders,
`TargetReferenceFactsExtractor`, `TargetVariantReachability`).

Owns the last two stages of the flow. `WorkspaceBuilder` emits the root `WORKSPACE`: rule
repositories (Kotlin, grab-bazel-common, Dagger, tools_android), Android SDK/NDK, and one
`maven_install` rule per materialized repo (via `MavenInstallArtifactsCalculator` →
`MavenInstallData`), honouring artifact pinning. `ProjectBazelFileBuilder` emits each module's
`BUILD.bazel` from the `BazelTarget`s the target builders produce.

- **Reference facts** (`TargetReferenceFacts`) — the project targets and Maven repo names a project's
  generated targets refer to; drive reachability pruning and repo materialization.
- **Load strategy** — `WORKSPACE` uses `LoadStrategy.Inline()` (loads at point of use), the
  convention Bazel expects for `WORKSPACE`.

### 3.6 rules_jvm_external lockfile & maven-install artifact rendering
`migrate/dependencies/RulesJvmExternalLockfile.kt` (+ `RulesJvmExternalLockfileParser` / `Renderer`
/ `Hasher`, `MavenInstallArtifactsCalculator`, `MavenInstallLockfileArtifactKey`, `StarlarkRepr`).

The tail end: turns the plan into the two artefacts rje needs — `maven_install(...)` blocks in
`WORKSPACE`, and a pinned `maven_install.json` per repository.

- **Lockfile model** — `RulesJvmExternalLockfile` maps 1:1 onto the on-disk JSON keys. Format is
  version-tied to rules_jvm_external 6.10.
- **Input vs resolved hashes** — rje stores `__INPUT_ARTIFACTS_HASH` (what was requested) and
  `__RESOLVED_ARTIFACTS_HASH` (each artifact's resolved facts), both using Starlark `hash()`
  semantics reproduced byte-for-byte by `RulesJvmExternalLockfileHasher` + `StarlarkRepr`, so a
  Grazel-reconstructed lockfile passes the same signature validation rje performs at load.
- **Artifact key** — `group:artifact[:extension]`.

### 3.7 Local Maven proxy & lockfile reconstruction
`proxy/LocalMavenProxyServer.kt` (+ `LocalMavenProxyService`, `LocalMavenResolvedFacts`,
`MavenInstallLockfileFallbackIndex`) and `migrate/dependencies/MavenInstallLockfileReconstructor.kt`.
**Gated by `experiments.localMavenResolution` (default off).**

An in-process HTTP Maven repository on `127.0.0.1` that lets rje (via coursier) pin against the exact
set Gradle already resolved, so the Bazel lockfile is a faithful projection of the Gradle resolution
and pinning never depends on network/mirror/snapshot drift. See §5 for the request-classification
detail.

### 3.8 Task orchestration & progress reporting
`tasks/internal/TasksManager.kt` (+ per-stage `*Task` classes, `di/` Dagger component,
`extension/ExperimentsExtension.kt`, `ProgressReporter`).

Registers the linear task graph and wires each stage's serialized output to the next
(`resolve → compute → plan → finalize → generate → pin`). `AnalyzeVariantCompressionTask` runs after
compute to collapse equivalent variants. `ExperimentsExtension` holds the feature flags, including
`localMavenResolution` and `declaredDependencyMetadataAggregationMode`
(`SINGLE_TASK` vs `PROJECT_TASK_FANOUT`, default fanout).

---

## 4. Key domain types (vocabulary cheat-sheet)

| Type / term | Where | Meaning |
|---|---|---|
| `Variant<T>` | `gradle/variant` | Migratable unit + its configuration roles |
| `VariantType` | `gradle/variant` | AndroidBuild / AndroidTest / Test / JvmBuild / Lint |
| hierarchy bucket / leaf | `gradle/variant` | Shared parent scope vs concrete buildable variant |
| `AggregatedDependencyRoot` (`Kind`) | `gradle/dependencies` | Synthetic resolution entry point |
| `ProjectDependencyBucket` | `gradle/dependencies` | `(projectPath, bucketName)` placement key |
| `ResolvedDependency` | `gradle/dependencies/model` | A resolved artifact (+ closure, exclude rules, jetifier, override); `isDeclaredMetadata()` marks a declaration-only placeholder |
| `CoveredDependency` | `gradle/dependencies` | An artifact already owned by some bucket |
| `DeclaredDependencyMetadata` | `gradle/dependencies` | Per-project snapshot of declared deps + variant topology |
| `ResolveDependenciesResult` | `gradle/dependencies/model` | One bucket's output (COMPILE + KSP scopes) |
| `WorkspaceDependencies` | `gradle/dependencies/model` | Arbitrated per-variant/aggregated snapshot |
| `WorkspacePlan` / `CandidateMavenRepo` | `gradle/dependencies/model` | Candidate repos + pin inputs + override targets |
| `WorkspaceRenderPlan` | `gradle/dependencies/model` | Pruned plan of what to materialize |
| `MavenInstallData` | `migrate/dependencies` | One rendered `maven_install` bucket |
| `RulesJvmExternalLockfile` | `migrate/dependencies` | In-memory `maven_install.json` |
| override target | `gradle/dependencies` / `migrate` | Label redirecting an artifact to a target in another repo |
| `LocalMavenResolvedFacts` | `proxy` | Gradle-resolution snapshot the proxy answers from |
| GAV / Maven path / artifact key | `proxy` / `migrate` | `group:artifact:version` / repo-relative path / `group:artifact[:ext]` |

---

## 5. Where the local Maven proxy plugs in

The proxy is a **pinning-time detour**, entirely behind `experiments.localMavenResolution`. It does
not change resolution, ownership, planning, or `WORKSPACE` generation — those run identically whether
the flag is on or off. It only changes *what rje resolves against* during `PinMavenArtifactsTask`.

**Wiring** (`PinMavenArtifactsTask.kt`):
- If the flag is off, `localMavenResolutionContextFactory()` returns `null` and `ArtifactPinner` pins
  against canonical remote repos as before.
- If on, the task builds a `LocalMavenResolutionPinContextFactory`. At pin time it:
  1. builds `LocalMavenResolvedFacts` from the captured root configurations
     (`LocalMavenResolvedFactsBuilder`) — an **artifact index** (Maven path → resolved file, backfilled
     from the Gradle module cache), the **known-component GAV** set, **metadata-only GAVs**, and a lazy
     **POM resolver**;
  2. loads any existing lockfile's concrete paths as an `activeMavenInstallLockfileFallbackIndex`
     (the only unknown paths allowed to reach origin);
  3. calls `service.configure(...)` on the `LocalMavenProxyService`, which returns proxy↔canonical URL
     mappings;
  4. hands back a `LocalMavenResolutionPinContext` with a `MavenInstallRepositoryRewrite`.

**Pin run** (`ArtifactPinner`): temporarily rewrites the `WORKSPACE` repository URLs to the proxy,
runs rje's pin scripts, then restores the file so committed sources stay canonical.

**Proxy request classification** (`LocalMavenProxyServer.serve`, `127.0.0.1/r/{repoIndex}/{path}`),
in strict priority order:
1. `.sha1/.md5/.sha256` — derived on the fly from the served bytes (never fetched).
2. `.pom` — served from the Gradle POM resolver when the GAV is index-backed; known-component miss
   fails closed; parent/BOM POMs fall back to origin.
3. Artifact index hit — authoritative, served directly.
4. `maven-metadata.xml` — cache-or-origin.
5. **Alternate artifact probe** (coursier asking a different extension than Gradle resolved) → 404.
6. **Metadata-only GAV** → origin fallback allowed.
7. **Known component GAV** with a missing artifact → **hard 500** (fail closed — rje can never pin
   something Gradle did not see).
8. Unknown GAV on an allow-listed lockfile path → origin fallback allowed.
9. Any other unknown concrete artifact → hard failure; non-concrete paths → cache-or-origin.

Origin fetches use a per-key write-through disk cache with a `Mutex` to collapse concurrent misses.
All responses feed `LocalMavenResolutionStats` for post-migration reporting.

**Lockfile reconstruction** (`MavenInstallLockfileReconstructor.reconstruct`) turns the lockfile rje
pinned *against the proxy* into the canonical committed one, in order:
1. URL rewrite (proxy → canonical, `MavenLockfileRepositoryUrlRewriter`);
2. baseline merge (carry over prior facts, validate shasums did not drift);
3. POM-packaging normalisation (mark BOM/aggregator artifacts skipped);
4. hash recomputation over the *canonical* repository inputs, so the output matches a real rje run.

---

## 6. Reading order for a new maintainer

1. `gradle/variant/Variant.kt` — vocabulary and the variant/bucket hierarchy model.
2. `gradle/dependencies/AggregatedDependencyResolver.kt` — resolution → closures.
3. `gradle/dependencies/BucketOwnershipPlanner.kt` — ownership reduction (the conceptual core).
4. `gradle/dependencies/WorkspacePlanBuilder.kt` — buckets → repos.
5. `migrate/internal/WorkspaceBuilder.kt` — plan → `WORKSPACE`.
6. `migrate/dependencies/RulesJvmExternalLockfile.kt` — lockfile model + hashing.
7. `proxy/LocalMavenProxyServer.kt` + `MavenInstallLockfileReconstructor.kt` — the optional pinning path.
8. `tasks/internal/TasksManager.kt` — how the stages are wired into Gradle.
