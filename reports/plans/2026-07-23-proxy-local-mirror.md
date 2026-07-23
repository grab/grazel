# Proxy Local-Mirror-With-Self-Fallthrough (A″) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the lockfile-derived origin allowance so the local Maven proxy serves Gradle-resolved bytes locally and falls through to origin (via its existing fetch machinery) for everything else — never failing a build, never reading committed lockfiles.

**Architecture:** All changes are inside the existing proxy/pin path: `LocalMavenProxyServer.serve()`/`servePom()` lose their hard-failure and allowance branches; `LocalMavenProxyService.configure()` and `PinMavenArtifactsTask` lose the allowance plumbing; `MavenInstallLockfileFallbackIndex.kt` is deleted. `knownComponentGavs`/`metadataOnlyGavs` remain observability-only. Stats gain a `knownComponentFallthroughs` counter and the pin summary line reports served-vs-fell-through clearly.

**Tech Stack:** Kotlin Gradle plugin, ktor embedded server (existing), JUnit4 + `com.sun.net.httpserver` test origins (existing harness in `LocalMavenProxyServerTest`).

**Spec:** `reports/specs/2026-07-23-proxy-local-mirror-design.md` — read it first; its "Decision" and "Deletions" sections are the requirements.

## Global Constraints

- The proxy must NEVER return 500 except from the generic exception handler in the routing block — every deliberate branch either serves bytes or falls through to `serveFromCacheOrOrigin` (or 404s for alternate-classifier probes).
- No production code may read any `*_install.json` for proxy configuration. `MavenInstallLockfileFallbackIndex.kt` and its test are deleted, not deprecated.
- Generated output must stay byte-identical: `./gradlew verifyGrazelGoldenBaseline --console=plain` must pass with a clean diff after every task.
- One Gradle build at a time. Never stage `codedb.snapshot`. Stage explicit paths only (no `git add -A`). Do not push.
- `dependencies.overrideArtifactVersions` must keep flowing into `additionalGavs` (only the lockfile contribution is removed).

---

### Task 1: Server semantics, stats, wiring, and lockfile-index deletion

One coherent compile unit: the server's serve/servePom branches, the stats model, the service/task wiring, the summary log, and the lockfile-index file all change together (the module will not compile with only part of these applied).

**Files:**
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/proxy/LocalMavenProxyServer.kt`
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/proxy/LocalMavenProxyService.kt:37-60`
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/PinMavenArtifactsTask.kt:114-146`
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/maven/LocalMavenResolutionStats.kt`
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/dependencies/ArtifactPinner.kt:395-416` (summary log)
- Delete: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/proxy/MavenInstallLockfileFallbackIndex.kt`
- Delete: `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/proxy/MavenInstallLockfileFallbackIndexTest.kt`
- Test: `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/proxy/LocalMavenProxyServerTest.kt`

**Interfaces:**
- Consumes: existing `serveFromCacheOrOrigin(repoIndex, path, countContentHit)` and `serveArtifactWithFallbackCounter(repoIndex, path, countContentHit, fallbackCounter)` — unchanged.
- Produces: `LocalMavenProxyServer.configure(artifactIndex, knownComponentGavs, metadataOnlyGavs, pomFileResolver)` (allowance parameter GONE); `LocalMavenProxyService.configure(facts, canonicalRepositoryUrls)`; `LocalMavenResolutionStats` with `knownComponentFallthroughs: Long` and WITHOUT `lockfileArtifactFallbacks`, `artifactMisses`, `knownPomFailures`.

- [ ] **Step 1: Convert the server tests to the new contract (failing first)**

In `LocalMavenProxyServerTest.kt`:

1. Remove the `allowedOriginArtifactPaths` parameter from the `newProxy(...)` helper (find it near the bottom of the file) and from every call site.
2. Delete these two tests outright (they test the allowance, which no longer exists):
   - `` `does not hard fail alternate artifact probes for active lockfile aar artifacts` ``
   - `` `does not hard fail alternate artifact probes for active lockfile pom artifacts` ``
3. Replace the three hard-fail tests with fallthrough tests. Use the file's existing helpers (`newProxy`, `newOriginServer`/HttpServer setup, `get`, `sha1`) exactly as neighboring origin-fallback tests do (`falls back unknown poms to origin with auth and caches the response` is the pattern to copy origin-server setup from):

```kotlin
@Test
fun `known component poms missing locally fall through to origin`() {
    val origin = newOriginServer(mapOf("/com/example/library/1.0/library-1.0.pom" to "<origin-pom/>"))
    val artifact = temporaryFolder.newFile("library-1.0.jar").apply { writeText("jar-bytes") }
    newProxy(
        artifactIndex = mapOf("com/example/library/1.0/library-1.0.jar" to artifact),
        knownComponentGavs = setOf("com.example:library:1.0"),
        origins = listOf(origin.asOrigin()),
        pomResolver = { PomFileResolution.Unavailable("no pom captured") }
    ).use { proxy ->
        val response = get("${proxy.baseUrl()}/r/0/com/example/library/1.0/library-1.0.pom")
        assertEquals(200, response.code)
        assertEquals("<origin-pom/>", response.body)
        assertEquals(1, proxy.stats().knownComponentFallthroughs)
        assertEquals(0, proxy.stats().requestFailures)
    }
}

@Test
fun `known component artifacts missing locally fall through to origin`() {
    val origin = newOriginServer(mapOf("/com/example/library/1.0/library-1.0.aar" to "aar-bytes"))
    val jar = temporaryFolder.newFile("library-1.0.jar").apply { writeText("jar-bytes") }
    newProxy(
        artifactIndex = mapOf("com/example/library/1.0/library-1.0.jar" to jar),
        knownComponentGavs = setOf("com.example:other:1.0"),
        origins = listOf(origin.asOrigin())
    ).use { proxy ->
        // GAV known to Gradle (com.example:other:1.0) but its artifact never indexed
        val response = get("${proxy.baseUrl()}/r/0/com/example/other/1.0/other-1.0.jar")
        assertEquals(200, response.code)
        assertEquals(1, proxy.stats().knownComponentFallthroughs)
    }
}

@Test
fun `unknown concrete artifacts fall through to origin`() {
    val origin = newOriginServer(mapOf("/org/foreign/dep/2.0/dep-2.0.jar" to "foreign-bytes"))
    newProxy(origins = listOf(origin.asOrigin())).use { proxy ->
        val response = get("${proxy.baseUrl()}/r/0/org/foreign/dep/2.0/dep-2.0.jar")
        assertEquals(200, response.code)
        assertEquals("foreign-bytes", response.body)
        assertEquals(1, proxy.stats().originFallbacks)
        assertEquals(0, proxy.stats().knownComponentFallthroughs)
    }
}
```

If the file's origin-server helper has different names (e.g. inline `HttpServer.create` blocks), inline the same setup those neighboring tests use instead of `newOriginServer(...)`/`asOrigin()` — match the file's existing idiom, do not invent a new harness.

4. Also update the two remaining renamed expectations: the test `` `metadata-only known artifacts fall back to origin and cache the response` `` keeps its behavior (assert counter `metadataOnlyArtifactFallbacks`, unchanged); the alternate-probe tests keep 404 behavior unchanged.
5. Anywhere a deleted stats field (`lockfileArtifactFallbacks`, `artifactMisses`, `knownPomFailures`) is asserted, remove or replace the assertion per the new contract.

- [ ] **Step 2: Run the test class to verify it fails to compile / fails**

Run: `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.proxy.LocalMavenProxyServerTest" --console=plain`
Expected: FAIL (unresolved `knownComponentFallthroughs`, removed `newProxy` parameter, etc.)

- [ ] **Step 3: Rewrite `LocalMavenProxyServer` serve semantics**

In `LocalMavenProxyServer.kt`:

1. Remove the field `allowedOriginArtifactPaths` and its `configure(...)` parameter; `knownMainArtifactExtensionsByGav` now derives from `artifactIndex.keys` alone:

```kotlin
this.knownMainArtifactExtensionsByGav = artifactIndex.keys
    .asSequence()
    .mapNotNull(::mainArtifactExtensionByGav)
    .groupBy(
        keySelector = { (gav, _) -> gav },
        valueTransform = { (_, extension) -> extension }
    )
    .mapValues { (_, extensions) -> extensions.toSet() }
```

2. Replace the dispatch tail of `serve()` (everything after the `isKnownAlternateArtifactProbe` branch) with:

```kotlin
if (concreteGav in metadataOnlyGavs) {
    return serveArtifactWithFallbackCounter(
        repoIndex, path, countContentHit, counters.metadataOnlyArtifactFallbacks
    )
}
if (concreteGav != null && concreteGav in knownComponentGavs) {
    warnKnownComponentFallthrough(concreteGav, path)
    return serveArtifactWithFallbackCounter(
        repoIndex, path, countContentHit, counters.knownComponentFallthroughs
    )
}
return serveFromCacheOrOrigin(repoIndex, path, countContentHit)
```

and rewrite the `serve()` KDoc to describe the new contract: local-first, origin fallthrough for everything else, 404 only for alternate-classifier probes, no failure branches. Do not narrate the old design in the KDoc.

3. In `servePom()`, delete both `hardFailure` branches; the `Unavailable` case becomes `Unit`, and the post-`when` known-component check becomes a counted fallthrough:

```kotlin
if (canServeGradleBackedPom && gav in knownComponentGavs) {
    warnKnownComponentFallthrough(gav, path)
    return serveArtifactWithFallbackCounter(
        repoIndex, path, countContentHit, counters.knownComponentFallthroughs
    )
}
return serveFromCacheOrOrigin(repoIndex, path, countContentHit)
```

4. Delete `private fun hardFailure(...)` entirely.
5. Add the once-per-GAV warn helper and logger:

```kotlin
private val warnedFallthroughGavs = ConcurrentHashMap.newKeySet<String>()

private fun warnKnownComponentFallthrough(gav: String, path: String) {
    if (warnedFallthroughGavs.add(gav)) {
        logger.warn(
            "Local Maven proxy: Gradle knows component {} but has no local artifact for {} — " +
                "falling through to origin", gav, path
        )
    }
}

companion object {
    private val logger = org.gradle.api.logging.Logging.getLogger(LocalMavenProxyServer::class.java)
}
```

6. In `LocalMavenProxyCounters`: delete `artifactMisses` and `knownPomFailures`, delete `lockfileArtifactFallbacks`, add `val knownComponentFallthroughs = AtomicLong()`, and mirror all three changes in `snapshot()`.

- [ ] **Step 4: Update the stats model and summary log**

`LocalMavenResolutionStats.kt` — remove `artifactMisses`, `lockfileArtifactFallbacks`, `knownPomFailures`; add `knownComponentFallthroughs: Long = 0` (keep field ordering grouped with the other fallback counters).

`ArtifactPinner.logLocalMavenResolutionSummary` — replace the body with a served-vs-fell-through headline (user-requested observability):

```kotlin
private fun logLocalMavenResolutionSummary(
    logger: Logger,
    stats: LocalMavenResolutionStats,
    elapsedNanos: Long,
) {
    val servedLocally = stats.artifactHits + stats.gradlePomHits
    logger.quiet(
        ("Local Maven resolution: $servedLocally served locally " +
            "(artifacts=${stats.artifactHits}, poms=${stats.gradlePomHits}, " +
            "checksums=${stats.checksumHits}), " +
            "${stats.originFallbacks} fell through to origin " +
            "(known-component=${stats.knownComponentFallthroughs}, " +
            "metadata-only=${stats.metadataOnlyArtifactFallbacks}, " +
            "origin-failures=${stats.originFailures}), " +
            "${stats.alternateArtifactMisses} alternate probes skipped, " +
            "${stats.writeThroughCacheHits} cache hits, " +
            "${stats.requestFailures} request failures, " +
            "${stats.bytesServed} bytes served, in " +
            "${TimeUnit.NANOSECONDS.toMillis(elapsedNanos)}ms").ansiGreen
    )
}
```

- [ ] **Step 5: Remove the allowance plumbing and the lockfile index**

1. `LocalMavenProxyService.configure` — drop the `allowedOriginArtifactPaths` parameter and its pass-through to `activeServer.configure(...)`.
2. `PinMavenArtifactsTask.localMavenResolutionContextFactory()` — delete the `activeMavenInstallLockfileFallbackIndex` call, the `rootDirectory` val, and the import `com.grab.grazel.proxy.activeMavenInstallLockfileFallbackIndex`; the factory body becomes:

```kotlin
return LocalMavenResolutionPinContextFactory { pinnableRepos, repositoryInputs ->
    val facts = LocalMavenResolvedFactsBuilder(project).build(
        configurations = localMavenResolutionRootConfigurations.get(),
        additionalGavs = pinnableRepoResolutionGavs(
            pinnableRepos = pinnableRepos,
            additionalGavs = configuredAdditionalGavs
        )
    )
    val repositoryMappings = service.configure(
        facts = facts,
        canonicalRepositoryUrls = repositoryUrls(repositoryInputs)
    )
    LocalMavenResolutionPinContext(
        repositoryRewrite = MavenInstallRepositoryRewrite(
            proxyToCanonicalUrl = repositoryMappings.proxyToCanonicalUrl,
            canonicalToProxyUrl = repositoryMappings.canonicalToProxyUrl
        ),
        metadataOnlyShortIds = facts.metadataOnlyGavs
            .mapTo(sortedSetOf()) { gav -> MavenCoordinates.parse(gav).shortId },
        stats = { service.stats() }
    )
}
```

3. `git rm grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/proxy/MavenInstallLockfileFallbackIndex.kt grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/proxy/MavenInstallLockfileFallbackIndexTest.kt`
4. Grep-verify zero survivors: `grep -rn "allowedOriginArtifactPaths\|LockfileFallback\|lockfileArtifactFallbacks" grazel-gradle-plugin/src` must return nothing.

- [ ] **Step 6: Run the full unit suite**

Run: `./gradlew :grazel-gradle-plugin:test --console=plain`
Expected: BUILD SUCCESSFUL. If other test classes referenced deleted stats fields or the service signature, fix them to the new contract (behavior conversions only — no test deletions beyond the two named in Step 1).

- [ ] **Step 7: Commit**

```bash
git add grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/proxy \
        grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/PinMavenArtifactsTask.kt \
        grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/maven/LocalMavenResolutionStats.kt \
        grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/dependencies/ArtifactPinner.kt \
        grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/proxy
git commit -m "refactor(proxy): local mirror with self-fallthrough; delete lockfile allowance

The proxy serves Gradle-resolved bytes locally and falls through to origin
(existing fetch path: auth, timeouts, write-through cache) for everything
else. No branch can fail a build. The lockfile-derived allowance and its
bootstrap requirement are deleted; known-component fallthroughs are counted
and WARN-logged once per GAV."
```

---

### Task 2: Golden byte-identity + sample cold-start gate

**Files:**
- No source changes expected. Regenerated sample files must be byte-identical.

**Interfaces:**
- Consumes: Task 1's plugin behavior.
- Produces: proof that cold start = warm start (the spec's definitive gate).

- [ ] **Step 1: Golden baseline**

Run: `./gradlew verifyGrazelGoldenBaseline --console=plain`
Expected: `Grazel golden baseline verified: migrateToBazel, task graph, bucket labels, and generated-file diff are clean.` A dirty diff means Task 1 changed generated output — a defect; stop and fix before proceeding.

- [ ] **Step 2: Cold-start pin (lockfiles deleted, WORKSPACE intact)**

```bash
rm maven_install.json
./gradlew migrateToBazel --console=plain
```
Expected: BUILD SUCCESSFUL with NO manual bootstrap step; the pin summary line ("Local Maven resolution: ... fell through to origin ...") appears; `git status --porcelain maven_install.json` shows the file regenerated. Then:

```bash
git diff --exit-code maven_install.json
```
Expected: exit 0 (regenerated lockfile byte-identical to committed — cold start = warm start). If it differs, capture the diff in the task report and STOP for review (deterministic pinning is a spec assumption).

CAUTION: delete ONLY `maven_install.json`. Never delete WORKSPACE or BUILD files — bazel-invoking tasks (buildifier bootstrap) need a valid WORKSPACE (documented trap in the spec).

- [ ] **Step 3: Sample graph analysis**

Run: `bazelisk build --nobuild //...`
Expected: `Build completed successfully` (~259 targets).

- [ ] **Step 4: Commit (only if anything moved)**

Expected: nothing to commit. If the working tree is dirty here, that contradicts Step 1/2 expectations — report, do not commit generated drift.

---

### Task 2a: Cold-start pre-flight — unpin WORKSPACE when referenced lockfiles are missing

Added after Task 2's cold-start gate BLOCKED: with a lockfile deleted, the
committed WORKSPACE's `maven_install_json = "//:<repo>_install.json"` attribute
makes the FIRST bazel invocation of the migrate (`generateBuildifierScript`,
which `rootGenerateBazelScripts` depends on for formatting) fail inside
`@maven`'s `pinned_coursier_fetch` before any Grazel logic runs.

**Files:**
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/dependencies/ArtifactPinner.kt`
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/GenerateBuildifierScriptTask.kt`
- Test: `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/migrate/dependencies/` (new or existing pinner test class)

**Interfaces:**
- Consumes: the existing `private fun unpin(workspaceFile: File)` and
  `ACTIVE_MAVEN_INSTALL_JSON_REGEX` in `ArtifactPinner.kt:117-124`.
- Produces: `internal fun unpinWorkspaceIfLockfilesMissing(workspaceFile: File): Boolean`
  in ArtifactPinner (companion or top-level, matching file idiom) — returns true
  when it unpinned. Called at the start of `GenerateBuildifierScriptTask`'s
  action, before the bazel exec.

- [ ] **Step 1: Failing tests** — workspace text referencing two lockfiles, one
  missing on disk → function unpins (all `maven_install_json` lines commented,
  pinned load/call lines commented) and returns true; all lockfiles present →
  text byte-identical, returns false; workspace with no active
  `maven_install_json` lines → untouched, returns false.
- [ ] **Step 2: Implement** — extract referenced lockfile names via the active
  maven_install_json regex, resolve them against the workspace file's parent
  directory, call the existing `unpin` when any is missing, log one quiet line
  naming the missing files ("unpinning WORKSPACE: lockfile(s) X missing —
  pinning regenerates them").
- [ ] **Step 3: Wire** into `GenerateBuildifierScriptTask.action()` before the
  bazel invocation (the task already knows the workspace root). No-op when all
  lockfiles exist — golden byte-identity must hold.
- [ ] **Step 4: Full unit suite green** (known 3 DefaultArtifactPinnerTest
  Bazel-8.5.1 failures tracked separately — must not grow).
- [ ] **Step 5: Commit** `fix(pin): unpin WORKSPACE pre-flight when referenced lockfiles are missing` (explicit paths).
- [ ] **Step 6: Re-run Task 2's three gates** — golden clean; cold start
  (`rm maven_install.json` → single migrate → `git diff --exit-code
  maven_install.json`) green; `bazelisk build --nobuild //...` green.

---

### Task 2b: Baseline-free lockfile reconstruction (pom-packaging classification from the authored universe)

Added after the PAX cold-start sweep failed in `pinMavenArtifacts`:
"Local Maven reconstruction requires a baseline lockfile before it can safely
classify POM-packaging artifacts: androidx.compose:compose-bom:pom,
org.jetbrains.kotlin:kotlin-stdlib-common:pom". Third outputs-as-inputs
instance: `PomPackagingSkipNormalizer` uses the previous lockfile to decide
whether a pom-packaging artifact is resolved (explicitly requested) or skipped
(transitive parent/BOM pom), and hard-errors without one.

**Decision (user-approved):** classify from the authored `maven_install`
artifact universe — a pom-packaging artifact key explicitly present in the
repo's authored artifacts is resolved; one absent is skipped. Baseline checks
in `BaselineLockfileFactsMerger` (shasum cross-check, skipped-regression check)
remain as warm-run assertions, gated on baseline presence; with no baseline,
RJE's own signature validation is the correctness oracle and the cold-start
byte-identity gate proves equivalence.

**Files:**
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/dependencies/PomPackagingSkipNormalizer.kt`
- Modify: its call sites in the reconstruction flow (ArtifactPinner / reconstruction pipeline)
- Test: `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/migrate/dependencies/`

- [ ] **Step 0 (validation before code): empirically confirm the classification
  rule** against PAX's committed direct-pin lockfiles (read-only): for every
  `:pom`-keyed entry in each committed `*_install.json`'s `artifacts` map,
  confirm the coordinate appears in the authored artifact list for that repo
  (the workspacePlan pinInputs / generated maven_install artifacts); for every
  pom entry in `skipped`, confirm it does NOT. Any counterexample → STOP and
  report; the rule is wrong.
- [ ] **Step 1: Failing tests** — normalizer given (lockfile with pom-packaging
  entries, authoredArtifactKeys set): requested pom key stays resolved; unrequested
  pom key folds into skipped; no baseline required anywhere.
- [ ] **Step 2: Implement** — replace the baseline parameter of the
  normalization path with the authored artifact-key set; delete
  `requireNoPomPackagingArtifactsWithoutBaseline`; make BaselineLockfileFactsMerger
  invoked only when a baseline lockfile exists.
- [ ] **Step 3: Gates** — unit suite 0 failures; golden clean; sample cold
  start (`rm maven_install.json` → migrate → `git status --porcelain` empty).
- [ ] **Step 4: Commit** `fix(pin): classify pom-packaging artifacts from the authored universe, not baseline lockfiles`

---

### Task 3: Replace the bootstrap gotcha in the gates doc

**Files:**
- Modify: `reports/specs/VERIFICATION-GATES.md` (the gotcha bullet beginning `**\`pinMavenArtifacts\` HTTP 500s from \`127.0.0.1:<port>\` after any lockfile discontinuity`)

**Interfaces:**
- Consumes: Tasks 1-2 landed.
- Produces: docs matching the shipped behavior.

- [ ] **Step 1: Replace the gotcha bullet**

Replace the entire two-phase-bootstrap bullet with:

```markdown
- **The local-maven proxy (`experiments.localMavenResolution`) needs no bootstrap.**
  It serves Gradle-resolved bytes locally and falls through to origin (with
  repository auth, timeouts, and a write-through cache) for anything else —
  committed lockfiles are never read to configure it, and deleting every
  `*_install.json` before a migrate is a supported cold start (the pin
  regenerates them in one run). Deleting the WORKSPACE itself is NOT supported:
  bazel-invoking tasks (buildifier bootstrap) need a valid WORKSPACE before
  regeneration completes. The pin summary line reports served-locally vs
  fell-through-to-origin counts; a rising `known-component` fallthrough count
  means the local index is eroding and deserves a look.
```

- [ ] **Step 2: Commit**

```bash
git add reports/specs/VERIFICATION-GATES.md
git commit -m "docs(gates): proxy cold start replaces the two-phase bootstrap gotcha"
```

---

### Task 4: `/simplify` pass over the proxy changes

**Files:**
- Scope: the diff of Tasks 1-3 (`git diff <pre-task-1-base>...HEAD`), centered on
  `proxy/LocalMavenProxyServer.kt`, `proxy/LocalMavenProxyService.kt`,
  `tasks/internal/PinMavenArtifactsTask.kt`, `maven/LocalMavenResolutionStats.kt`,
  `migrate/dependencies/ArtifactPinner.kt` and the proxy tests.

- [ ] **Step 1: Run the `/simplify` skill** (4 cleanup angles — reuse,
  simplification, efficiency, altitude) against that diff. Prime candidates the
  deletion may have exposed: `serveArtifactWithFallbackCounter` may now be the
  universal tail (fold?), `metadataOnlyGavs` vs `knownComponentGavs` branch
  near-duplication, dead KDoc references to removed branches, counters that no
  longer earn their keep, and whether `LocalMavenProxyService.configure`'s
  signature can shed further weight.
- [ ] **Step 2: Apply fixes strictly byte-identity gated** — after each fix:
  `./gradlew :grazel-gradle-plugin:test --console=plain` and
  `./gradlew verifyGrazelGoldenBaseline --console=plain` must stay clean; revert
  anything that moves generated output. Record skips with reasons.
- [ ] **Step 3: Commit** — `git commit -m "refactor(proxy): simplify pass over local-mirror changes"` (explicit paths).

---

## Whole-effort verification (controller-run, after all tasks)

1. **Adversarial whole-branch review** — dispatch on the most capable
   authorized model (Opus, per standing user authorization for adversarial
   reviews) with the review package for the full effort diff
   (`<pre-task-1-base>..HEAD`). The reviewer's brief: hunt correctness bugs in
   the new serve semantics (races on `warnedFallthroughGavs`, counter
   double-counting via `serveChecksum`'s countContentHit=false recursion,
   fallthrough behavior under concurrent identical requests, POM-vs-artifact
   asymmetries), verify the never-500 contract exhaustively against every
   `serve()` branch, and attack the "cold start = warm start" determinism claim.
   Fix Critical/Important findings via a fix subagent + re-review before the PAX
   sweep.
2. PAX sweep per VERIFICATION-GATES.md — with the cold-start variant: in PAX, `rm *_install.json`, single `./gradlew migrateToBazel --no-daemon --console=plain` (background, stall watchdog), expect green + clean tree (`git status --porcelain` empty — regenerated lockfiles byte-identical to committed). Then gates 3-6 (size guard, APK, focused tests, CI-set analysis).
3. Confirm the new summary line in the PAX migrate log and record the fallthrough counts in the task report.
