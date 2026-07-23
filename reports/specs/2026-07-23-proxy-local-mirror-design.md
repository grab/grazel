# Local Maven Proxy: Local-Mirror-With-Self-Fallthrough Design

**Status:** APPROVED (brainstormed 2026-07-23; shape A″ chosen over allowlist A and
coursier-fallthrough A′)

## Problem

The proxy's origin-fetch permission (`allowedOriginArtifactPaths` +
`additionalComponentGavs`) is seeded by re-reading previously **committed**
`*_install.json` lockfiles (`MavenInstallLockfileFallbackIndex.kt`, wired at
`PinMavenArtifactsTask.kt:120-133`). This is an altitude violation: the plugin
authors the maven_install artifact universe (full Gradle-resolved transitive
closure, by design, so coursier's versions align with Gradle's), then rediscovers
its own scope by parsing its own outputs. Consequences, both paid on 2026-07-23:

- **Discontinuity failures.** Any event that invalidates the committed lockfiles
  (a monorepo rebase resetting them to another plugin's bucket names/universe, a
  bucket rename, a grab-bazel-common bump changing `GRAB_BAZEL_COMMON_ARTIFACTS`)
  makes legitimate pin fetches hard-fail with HTTP 500, requiring a manual
  two-phase bootstrap (`localMavenResolution=false` → pin → `true` → pin).
- **Cold start is a distinct, worse mode.** On a tree with no lockfiles the
  allowance is empty by construction — the mechanism never helps a first run.

Exploration (three read-only agents, 2026-07-23) established:

1. Three of the proxy's four trust sets (`artifactIndex`, `knownComponentGavs`,
   `metadataOnlyGavs`) are already computed upstream, in-memory, from live Gradle
   resolution at pin time. Only the allowance reads generated files.
2. The allowance's actual consumers fall into three categories, all deviations
   from the authored closure: out-of-scope declared deps (espresso-web),
   version/variant skews coursier derives (guava-android, checker-qual), and
   grab-bazel-common's Starlark-side artifact list (invisible to the Gradle
   process — it is a `.bzl` constant expanded by Bazel).
3. The conflict-loser `requested` selectors are visited and discarded in
   `ResolvedComponentsVisitor` — capturable, but unnecessary under this design.

## Decision

The proxy becomes a **best-effort local mirror that can never fail a build**:

1. **Gradle-resolved artifact/POM/checksum** → served from Gradle's local files
   (unchanged; the feature's core value — pin bytes align with what Gradle
   resolved, no network).
2. **Everything else** → served via the proxy's **existing**
   `serveFromCacheOrOrigin` machinery (per-repo auth, ktor client with 10s
   connect / 60s socket timeouts, write-through cache, per-path request
   coalescing). No enumeration of the unknown universe, no permission lists.
3. **No hard failures.** Both `hardFailure(...)` branches in `serve()` and the
   known-component hard-fail branches in `servePom(...)` are removed. The only
   remaining 500 is the generic exception handler.

Rationale for self-fallthrough (A″) over 404-plus-coursier-fallthrough (A′):
minimal diff (widen an existing exercised path vs rewire pin-time repository
lists), byte-identical committed output and untouched pin configuration, and all
network stays behind ktor's timeouts — coursier's timeout-less sockets (the
2026-07-23 77-minute pin hang) are never in the unknown-artifact path.

## Deletions

- `proxy/MavenInstallLockfileFallbackIndex.kt` — entire file and its tests.
- `allowedOriginArtifactPaths` parameter and field through
  `PinMavenArtifactsTask` → `LocalMavenProxyService` → `LocalMavenProxyServer`.
- `additionalComponentGavs` lockfile plumbing (the `additionalGavs` parameter
  itself stays — it still carries `dependencies.overrideArtifactVersions` and
  the pinnable-repo closure GAVs).
- `hardFailure(...)` and every branch that calls it.
- The two-phase-bootstrap gotcha in `reports/specs/VERIFICATION-GATES.md`,
  replaced by the cold-start gate below.

## What stays, and why

- `knownComponentGavs` / `metadataOnlyGavs`: retained purely for
  **observability** — a fallthrough for a known component is unexpected (Gradle
  had it; the local index didn't) and logs a WARN plus a dedicated
  `knownComponentFallthroughs` counter. It no longer blocks anything.
- The alternate-classifier probe short-circuit
  (`isKnownAlternateArtifactProbe`): perf guard against origin round-trips for
  nonexistent classifiers. Its input map now derives from `artifactIndex` keys
  only (the allowance keys disappear from `knownMainArtifactExtensionsByGav`).
- Checksum derivation (`serveChecksum`): unchanged — hashes whatever the proxy
  actually serves, including origin-fetched bytes.
- The write-through cache and fetch coalescing: unchanged.

## Stats

- Remove `lockfileArtifactFallbacks`.
- Add `knownComponentFallthroughs` (WARN-logged per GAV at first occurrence).
- Keep `originFallbacks`, `originFailures`, `metadataOnlyArtifactFallbacks`,
  and the rest. The pin-summary stats line is unchanged in shape.

## Verification gates

1. **Unit truth table** for `serve()`: resolved artifact → local file; known
   component with missing artifact → origin fallthrough + counter (not 500);
   metadata-only GAV → origin fallthrough; unknown GAV → origin fallthrough;
   alternate-classifier probe → 404 short-circuit; checksum of origin-served
   artifact → hash of served bytes. Plus: `servePom` known-component miss →
   origin fallthrough (not 500).
2. **Local**: `:grazel-gradle-plugin:test`, `verifyGrazelGoldenBaseline`
   (byte-identical — this change must not move generated output), sample
   analysis `bazelisk build --nobuild //...`.
3. **Cold-start gate (the definitive test)**: in the samples, delete every
   `*_install.json` (WORKSPACE and BUILD files intact — see trap below), run a
   single `migrateToBazel`: it must pin green from nothing, no bootstrap, and
   the regenerated lockfiles must match the committed ones. Cold start = warm
   start.
4. **PAX sweep** per VERIFICATION-GATES.md, including the same
   lockfiles-deleted cold-start migrate in PAX (deleting generated files that
   the migrate immediately regenerates is within the non-destructive rule; the
   clean-tree gate then proves byte-stability of the from-nothing pin).

### Known trap (out of scope, documented)

Deleting **all** generated Bazel files including WORKSPACE creates a separate,
pre-existing chicken-and-egg: bazel-invoking tasks (`generateBuildifierScript`
bootstrapping buildifier) need a valid WORKSPACE before regeneration completes.
The lockfile-absent case, by contrast, is a designed path: generation renders
`#maven_install_json` (commented) when the lockfile is missing
(`MavenInstallArtifactsCalculator.kt:142,175`) and `determinePinningTarget`
switches to `@<repo>//:pin` (`ArtifactPinner.kt:208-215`). The cold-start gate
therefore scopes deletion to lockfiles only. Full-wipe bootstrap ordering is a
separate backlog item, not this effort.

## Out of scope

- Full-wipe (WORKSPACE-deleted) bootstrap ordering (above).
- A strict mode / fallthrough budget that fails the build past a threshold —
  add only if the counters ever show creeping proxy bypass.
- Capturing conflict-loser `requested` selectors in `ResolvedComponentsVisitor`
  — unnecessary once nothing needs to enumerate the unknown universe.
