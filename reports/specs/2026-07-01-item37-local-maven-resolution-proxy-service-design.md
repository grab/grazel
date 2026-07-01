# Item 37 — Local Maven Resolution: Proxy BuildService + Ktor Server (Design)

> **Status:** Approved 2026-07-01 (brainstormed; grounded by Opus probes of rje 6.10 + the pinning map).
> **Executor:** Codex. **Behaviour change:** none — the service is dormant (not wired into the pin
> flow until Item 38). Golden EMPTY-diff; PAX size guard no-change.
> **Global Constraints + Verification Playbook + Code-quality stance:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`. **Depends on:** Item 36 (the Gradle-model facts).
> **Feature context:** second of three — the in-process, migration-time Maven server that serves
> Gradle's resolved artifacts. Item 36 = facts; Item 37 = this server; Item 38 = pin integration +
> hash reconstruction. Reference: `reports/DEPENDENCY-PINNING-MAP.md`.

> **⚠️ Execution note — delegate to subagents; protect the main context.**

---

## Goal

Build the in-process local Maven repository server — a Gradle `BuildService` hosting an embedded
Ktor engine — that serves artifacts from the Item 36 facts, computes checksums on the fly, resolves
POMs lazily, and falls back to the origin repository (fail-slow) with auth replay, persisting misses.
In this item the service is **dormant**: constructible and fully testable over HTTP, but **nothing
in the migration flow calls it** — that wiring is Item 38. This keeps Item 37 empty-diff.

## Altitude / execution anchor

The proxy is **HTTP over already-hydrated Gradle facts**, not "Gradle API over HTTP".

```
Item 36 Gradle facts
  artifactIndex: maven relative path -> File
  PomFileResolver: gav -> File?
  canonical repositories + RepositoryAuth

Item 37 LocalMavenProxyService
  accepts those facts at configure/start time
  serves files/checksums/origin fallback
  records stats
  never reads Project, Configuration, ComponentIdentifier, ResolvedArtifactResult,
  ArtifactResolutionQuery, ArtifactView, WorkspacePlan, generated WORKSPACE, or lockfiles
```

If implementation pressure makes the service want Gradle live objects, stop and move that work back
to Item 36's facts layer or to Item 38's pinner orchestration.

## Grounded current state (Codex: re-confirm)

- `di/GradleServices.kt` — the existing `GradleServices` bundle (`ExecOperations`, `ObjectFactory`,
  `ProjectLayout`, `FileSystemOperations`, `WorkerExecutor`, `ProgressLoggerFactory`); the pattern
  for handing Gradle services to tasks.
- The codebase already has `BuildService` patterns (`DependencyResolutionService`,
  `DependencyGraphsService`, `WorkspacePlanService`, `WorkspaceRenderPlanService`,
  `WorkspaceTargetTagPlanService`, `VariantCompressionService`). Follow those registration/lifecycle
  conventions instead of inventing a parallel service style.
- Item 36 provides: `Repository.auth: RepositoryAuth`, the `Map<mavenPath, File>` artifact index,
  and the lazy memoized `PomFileResolver` interface.
- The project already accepts non-config-cache-pure, live-model coupling at migration time
  (`@UntrackedTask` pin task; the accepted live-variant-model coupling in Item 34). The maintainer
  has confirmed service mutation/configuration is acceptable, but the service must cache files,
  digests, POM results, origin misses, and stats — **not** Gradle result objects.
- Ktor is not yet a dependency; add **Ktor CIO** (server + client) to the plugin's build — pure
  Kotlin, no Netty.

## Work

### Part 1 — The BuildService
1. Add a `BuildService<Params>` (e.g. `LocalMavenProxyService`) in a new `bazel`/`migrate` proxy
   package. `Params` holds only serializable data: the write-through cache dir (default
   `build/grazel/maven-proxy/`) and the ordered canonical-repo descriptors (URL + a stable index `n`
   + `auth`) captured from Item 36.
2. The heavy data — the `Map<mavenPath, File>` artifact index and the `PomFileResolver` — is
   **set imperatively** on the service at execution time (a `configure(index, pomResolver)`-style
   method), not via `Params`. This is the accepted mutation path; do not serialize the index and do
   not pass Gradle live objects through the service API.
3. Lifecycle: the embedded server is **lazily started** on first use (a `baseUrl()`/`port()` accessor
   starts it if not running), bound to an **ephemeral port** (`:0`, OS-assigned). Implement
   `AutoCloseable`/`close()` to stop the server at build end. Expose the bound port + base URL.

### Part 2 — The Ktor CIO server + serve chain
4. Embedded Ktor CIO server. Single route family: `GET /r/{n}/{path...}` where `{n}` is the canonical
   repo index and `{path...}` is the maven relative path. Serve resolution order:
   - **a. Artifact index** — `path` (with `/r/{n}/` stripped) → `File` → stream bytes. (Byte lookup is
     keyed by path alone; `{n}` is used only for origin fallback + Item 38 URL reconstruction — the
     same artifact bytes regardless of which repo served them.)
   - **b. POM** — if `path` ends `.pom`, map to coordinate → component id → lazy memoized POM resolver
     (Item 36) → stream the cached POM `File`.
   - **c. Checksums** — if `path` ends `.sha1`/`.md5` (or `.sha256`), compute the digest **on the fly**
     from the bytes tier (a)/(b) would serve for the base path; return the hex digest. No stored
     checksum files.
   - **d. Origin fail-slow** — on miss, fetch from canonical repo `{n}` (Ktor CIO client), replaying
     `RepositoryAuth` (`Basic` → `Authorization: Basic …`; `Header` → the captured header). Stream to
     the caller **and** write-through into the cache dir in maven layout, so a subsequent run is a
     plain file hit. Cache dir default `build/grazel/maven-proxy/`.
   - Return 404 only if origin also 404s.
5. **Miss policy (hard correctness contract):**
   - Resolved artifact requests (`.jar`, `.aar`, classifier jars, and other non-POM artifact files
     that should exist in the Item 36 artifact index) must be served from Gradle's artifact index.
     Missing resolved artifacts are a hard failure, not origin fallback.
   - POM requests for known Gradle-resolved components must be served by `PomFileResolver`. If a known
     component cannot yield a POM file, hard-fail and surface the GAV/path.
   - POM/metadata requests for unknown parent/BOM components may fall back to origin, but must be
     counted and reported.
   - Checksum requests are computed from the exact bytes that would serve the base path. A checksum
     for a hard-failed base path also hard-fails.
6. **Thread-safety:** the serve chain will be hit concurrently (per-repo pins run via
   `WorkerExecutor.noIsolation`). The index/POM cache/write-through must be safe under concurrent
   reads and concurrent origin-miss writes (write to a temp file + atomic move; guard the POM
   memo map).
7. Add proxy stats with timings/counts and expose them for Item 38 summaries:
   Gradle artifact hits, hard artifact misses, Gradle POM hits, known-component POM failures,
   unknown POM/metadata origin fallbacks, origin failures, checksum hits, write-through cache hits,
   bytes served, and elapsed time.

### Part 3 — Wiring (dormant)
8. Register the `BuildService` in the DI/task wiring so it is constructible, but **do not** attach it
   to any pin/migrate task in this item. A no-op `usesService` registration point may be prepared for
   Item 38 but must not change the pin flow.

## Hard constraints
- The service is **dormant**: no migration task's behaviour changes; golden empty-diff.
- No localhost/proxy URL enters any generated file in this item.
- Ephemeral port only (no fixed port). The base URL is discovered at runtime.
- Serve chain never mutates generated output; the write-through cache dir is under `build/` and is
  not a committed/tracked artifact.
- Ktor CIO (server + client) only; no Netty.
- No Gradle live objects in the proxy service API or route handlers. The service consumes files,
  strings, repository descriptors, `RepositoryAuth`, and the `PomFileResolver` interface only.
- Origin fallback is metadata-only by policy. It is not a quiet escape hatch for missing Gradle
  resolved artifacts.

## Safety mechanism
- Sample golden EMPTY-diff + PAX generated diff stable (service unused by the flow). Size guard: no
  change.
- No parity flag.

## Testing
- Boot the service on an ephemeral port; assert `GET /r/0/<known-artifact>` streams the index file
  bytes.
- `GET` a `.pom` → lazy resolver hit; a `.sha1`/`.md5` → digest matches the served bytes.
- A miss → origin fallback (point origin at a local fixture server), assert bytes returned AND
  written through to the cache dir; second request served from the cache dir.
- Missing resolved artifact fixture hard-fails without origin fallback.
- Known-component POM resolver failure hard-fails; unknown parent/BOM POM fixture falls back to
  origin and increments the unknown-POM fallback counter.
- Auth replay: `Basic` and `Header` fixtures assert the outgoing request carries the right header.
- Concurrency: parallel GETs incl. concurrent origin misses for the same path — no corruption.
- `close()` stops the server (port no longer accepts connections).

## Acceptance criteria
- A `BuildService`-hosted Ktor CIO server exists, ephemeral-port, lazy-start, `close()`-stop, exposing
  its base URL.
- Serve chain implements index → lazy POM → on-the-fly checksum → origin fail-slow (auth replay) →
  write-through, thread-safe.
- Serve stats/summary data is available and covers every hit/miss/fallback class listed above.
- The service is registered/constructible but **not wired into the pin flow**.
- Sample golden empty-diff; PAX generated diff stable; size guard no-increase; the HTTP + concurrency
  tests green.

## Out of scope / Non-goal
- Any pin-flow wiring, repo swapping, or lockfile reconstruction (Item 38).
- Forward-proxy / TLS-MITM behaviour (Approach 2 uses localhost declared repos; the server is a plain
  reverse proxy / local repo — no CONNECT, no TLS).
- Persisting the cache dir beyond `build/` (maintainer decision: `build/grazel/maven-proxy/`).
