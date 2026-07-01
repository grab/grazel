# Item 36 — Local Maven Resolution: Gradle-Model Facts (Design)

> **Status:** Approved 2026-07-01 (brainstormed; grounded by Opus probes of rje 6.10 + the pinning map).
> **Executor:** Codex. **Behaviour change:** none — additive Gradle-model facts wired to nothing that
> renders. Golden EMPTY-diff; PAX size guard no-change.
> **Global Constraints + Verification Playbook + Code-quality stance:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`. **Depends on:** none.
> **Feature context:** first of three items for the `experiments { localMavenResolution }` feature —
> an in-process, migration-time Maven proxy that serves Gradle's already-resolved artifacts to
> rules_jvm_external's pin step so rje never re-resolves from the network. Item 36 = the facts;
> Item 37 = the proxy server; Item 38 = pin integration + lockfile hash reconstruction. Reference
> map: `reports/DEPENDENCY-PINNING-MAP.md`.

> **⚠️ Execution note — delegate to subagents; protect the main context.**

---

## Goal

Extract, from the Gradle model, the two fact-sets the local Maven proxy (Item 37) needs to serve
artifacts, plus the auth needed for origin fallback — **without changing which repositories are
"supported" or rendered**, so generated output stays byte-identical. This is pure addition at the
Gradle-model layer (Layer 0/2), alongside `RepositoryDataSource`.

## Altitude / execution anchor

This item is the only slice allowed to touch live Gradle resolution facts. The intended boundary is:

```
gradle/dependencies
  root resolved configurations / ResolvedComponentResult
    -> LocalMavenResolvedFacts
       artifactIndex: maven relative path -> File
       componentIndex: group:name:version -> ComponentIdentifier
       PomFileResolver: resolve(gav) -> File?

migrate/dependencies proxy/pinner
  consumes artifactIndex + PomFileResolver only
  must not receive Project, Configuration, ComponentIdentifier, ResolvedArtifactResult,
  ArtifactResolutionQuery, or ArtifactView
```

The proxy/pinner/reconstructor layers must not re-derive these facts from render models,
`WorkspacePlan`, generated `WORKSPACE`, or lockfiles. Hydration happens after the root/app/test
classpath roots have been selected/resolved and can run beside downstream bucket/workspace planning,
but it is not part of bucket ownership or set-math.

## Grounded current state (Codex: re-confirm line refs)

- `gradle/Repository.kt:64-69` — `Repository(name, url, username?, password?)` (basic-auth only,
  `Serializable`).
- `gradle/Repository.kt:31-62` — `RepositoryDataSource` interface; `:71-137` `DefaultRepositoryDataSource`.
- `:111-125` `allRepositoriesLazy` reads `repo.credentials?.username/password` (`PasswordCredentials`).
- `:131-136` `isSupported()` — supported iff no credentials OR `PasswordCredentials`; **`HttpHeaderCredentials`
  → unsupported → excluded from rendering today.**
- POM-from-Gradle precedent (external, confirmed working): `ArtifactResolutionQuery`
  `project.dependencies.createArtifactResolutionQuery().forComponents(id)
  .withArtifacts(MavenModule, MavenPomArtifact).execute()` → `MavenPomArtifact` `.file` (the cached
  POM). Memoize per component id (heavyweight API; cache-hit at migration time since the graph is
  already resolved).
- `gradle/dependencies/model/ResolveDependenciesResult.kt` — `ResolvedDependency(id=group:name:version,
  shortId, …)` is the resolved-graph value model already used by the branch.
- Existing `DefaultDependenciesDataSource.dependencyArtifactMap(...)` is not sufficient for this
  item: it keys by coordinates, drops classifier/extension/path identity, and walks a compile-only
  subset. Use it only as `ArtifactView` precedent, not as the implementation.

## Work

### Part 1 — Repository auth capture (additive, non-perturbing)
1. Add a typed auth to the repository model:
   ```kotlin
   sealed interface RepositoryAuth {
       object None : RepositoryAuth
       data class Basic(val username: String, val password: String) : RepositoryAuth
       data class Header(val name: String, val value: String) : RepositoryAuth
   }
   ```
   Add `auth: RepositoryAuth = RepositoryAuth.None` to `Repository` (keep existing `username`/`password`
   fields intact so current consumers — `UrlRewriter`, `MavenInstallArtifactsCalculator` — are byte-for-byte
   unaffected).
2. Populate `auth` in `allRepositoriesLazy` by probing the Gradle repo's credentials:
   `repo.getCredentials(PasswordCredentials::class.java)` → `Basic`; `repo.getCredentials(HttpHeaderCredentials::class.java)`
   → `Header(name, value)`; else `None`. Guard with try/catch (Gradle throws if the wrong credential
   type is requested).
3. **Do NOT touch `isSupported()` / `supportedRepositories` / the rendered repo set.** Header-auth-only
   repos remain unsupported and unrendered exactly as today. `auth` is consumed only by the Item 37
   proxy on origin fallback. This keeps the feature empty-diff (maintainer decision:
   origin-fallback replay only, not enabling header-auth repos end-to-end).

### Part 2 — Resolved-artifact index (path → File)
4. Add a builder (e.g. `ResolvedArtifactIndex` / `ResolvedArtifactIndexBuilder` in `gradle/dependencies/`)
   producing `Map<String /* maven relative path */, File>` from an `ArtifactView`/`ArtifactCollection`
   over the resolved root configurations already used by the branch. For each `ResolvedArtifactResult`:
   reconstruct the maven relative path from `.id` (a `ModuleComponentArtifactIdentifier`:
   `group.replace('.', '/')/name/version/name-version[-classifier].ext`) and map it to `.getFile()`.
   Reconstruction happens **once at build time** (maintainer decision: index keyed by reconstructed
   path so the proxy serve path is a trivial map lookup).
5. Index only the primary artifacts (the resolved jars/aars of the configurations already resolved);
   POMs/metadata are NOT in this index (Part 3 handles known-component POMs; Item 37 handles
   unknown parent/BOM metadata fallback).

### Part 3 — POM component-id map (for lazy POM resolution)
6. Add a `Map<String /* group:name:version */, ComponentIdentifier>` built cheaply from the
   **already-resolved** components (`resolutionResult.allComponents`, filtering out `project :` ids) —
   no new resolution pass, just reading what the branch already resolved.
7. Provide a tiny interface owned by this layer, e.g.:
   ```kotlin
   internal interface PomFileResolver {
       fun resolvePom(gav: String): File?
   }
   ```
   The implementation keeps the private `ComponentIdentifier` map and runs the
   `ArtifactResolutionQuery` (Part-1 grounded pattern) only on demand. Memoize per component id with
   thread-safe storage (`ConcurrentHashMap`/sentinel or equivalent), because Item 37 can receive
   concurrent HTTP requests. Parent/BOM POMs that are not resolved components are expected to miss
   here → Item 37 may fall back to origin. A known component id whose POM cannot be read is a bug and
   must be surfaced distinctly from an unknown component miss.

## Hard constraints
- `isSupported()` and the rendered repo/artifact set are **unchanged** — verify by golden empty-diff.
- The auth field is additive; existing consumers must compile and behave identically.
- The POM resolver must be lazy + memoized (never a whole-graph eager POM resolution) — it is a
  potentially heavyweight `ArtifactResolutionQuery`; at migration time it must hit the cache, not the
  network, for resolved components.
- No Gradle-API leak into any pure-JVM class introduced here; these facts live in the Gradle-model
  layer where Gradle types are expected.
- `ComponentIdentifier`, `ResolvedArtifactResult`, `ArtifactView`, `ArtifactResolutionQuery`,
  `Project`, and `Configuration` must not be added to serialized/cross-boundary models such as
  `ResolvedDependency`, `WorkspaceDependencies`, `WorkspacePlan`, `MavenInstallData`, proxy route
  models, or the lockfile reconstructor.
- Do not read Gradle configurations/artifact views from arbitrary coroutine or HTTP request threads.
  Gradle fact hydration is a controlled task/build-service setup operation. The only request-time
  Gradle-adjacent behavior is the `PomFileResolver` interface, with synchronization and counters.

## Safety mechanism
- Sample golden EMPTY-diff + PAX generated diff stable vs the frozen Item 10 baseline (nothing is
  wired to rendering; any diff = a bug). Size guard: no change.
- No parity flag (pure addition; compiler + tests + golden are the net).
- Record timings/counts for fact hydration in the execution log when running PAX: artifact index
  entries, component-index entries, first lazy POM lookup latency, memoized POM hits/misses.

## Testing
- `RepositoryAuth` capture: `None` / `Basic` / `Header` cases from fake Gradle repos.
- Artifact index: coordinates → files incl. classifier/extension path reconstruction.
- POM resolver: memoization (second call no re-query); hit for known component; distinct miss for a
  non-component id; failure surfaced for a known component whose POM cannot be loaded.

## Acceptance criteria
- `Repository` carries `auth: RepositoryAuth`; populated for basic + header + none; `isSupported()`
  untouched; existing consumers unchanged.
- Artifact index builder produces `Map<mavenPath, File>` from the resolved artifacts.
- POM component-id map + lazy memoized `ArtifactResolutionQuery` POM resolver exist behind a small
  interface; Gradle live objects remain private to the Gradle facts layer.
- Sample golden empty-diff; PAX generated diff stable; size guard no-increase; new unit tests green.

## Out of scope / Non-goal
- The proxy server (Item 37), pin integration or hash reconstruction (Item 38).
- Enabling header-auth repos end-to-end / any change to `isSupported()` or the rendered repo set.
- Eager whole-graph POM resolution.
