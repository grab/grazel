# Item 38 — Local Maven Resolution: Pin Integration + Lockfile Reconstruction (Design)

> **Status:** Approved 2026-07-01 (brainstormed; grounded by two Opus probes of rules_jvm_external 6.10).
> **Executor:** Codex. **Behaviour:** **OUTPUT-PATH, hard-gated.** Flag OFF ⇒ pin runs exactly as
> today (byte-identical). Flag ON ⇒ the committed lockfiles MUST be byte-identical to a vanilla
> network pin (the gate). This is the sole output-affecting slice of the feature, gated like Item 13.
> **Global Constraints + Verification Playbook + Code-quality stance:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`. **Depends on:** Item 36 (facts), Item 37 (proxy service).
> **Feature context:** third of three. Reference: `reports/DEPENDENCY-PINNING-MAP.md`. The two Opus
> probes' full findings are the authoritative source for the rje 6.10 hashing algorithm summarized
> below — Codex MUST verify against the rje 6.10 source (`private/rules/coursier.bzl`,
> `private/rules/v3_lock_file.bzl`) before locking the implementation.

> **⚠️ Execution note — delegate to subagents; protect the main context.** PAX pin runs go to
> focused subagents.

---

## Goal

Wire the Item 37 proxy into the pin flow behind `experiments { localMavenResolution }`: swap the
generated `maven_install` repositories to the proxy's localhost URLs for the pin, let coursier
resolve/fetch entirely from local (Gradle-resolved) bytes, then reconstruct each generated lockfile
back to canonical — rewriting URLs **and recomputing rules_jvm_external 6.10's two signature hashes**
in pure Kotlin — so the committed lockfile is byte-identical to what a vanilla network pin produces.

The broader branch has mostly verified the up-to-date happy path where pin JSONs already exist and
`shouldRunPinning` skips expensive repinning. This item is specifically about the cold/changed pin
path: when lockfiles are missing or out of date, rje currently delegates the work to Coursier. With
`localMavenResolution` enabled during the goal run, that repin must use the local Gradle-backed
proxy and still produce canonical lockfiles.

**Why the hashes must be recomputed (probe result):** repository URLs are part of BOTH
`__INPUT_ARTIFACTS_HASH` and `__RESOLVED_ARTIFACTS_HASH`. Declaring localhost repos for the pin
produces localhost-based URLs and localhost-based hashes; rewriting URLs → canonical therefore
invalidates both hashes, which must be recomputed over the canonical values.

## Grounded current state (Codex: re-confirm)

- `migrate/dependencies/ArtificatPinner.kt` — the pin flow to extend: `pinArtifacts(...)` `:218-294`;
  reversible WORKSPACE edits `pin()`/`unpin()` `:97-113`; `failWhenOutOfDate` `:115-122`;
  `determinePinningTarget` `:197-204`; `shouldRunPinning` `:133-194`; `collectPinnableMavenInstallRepos`
  `:328-338`; `PinningWorkAction` (`WorkerExecutor.noIsolation`) `:345-376`.
- `bazel/rules/MavenRules.kt:107-111` — `maven_install(repositories = …)` emission (the swap target);
  `WorkspaceBuilder.kt:127-153` drives per-repo rules.
- `bazel/exec/Bazel.kt:50-76` `bazelCommand`; `BazelLogParsingOutputStream.kt:58-71` (rje error strings
  incl. "invalid input signature … must be regenerated") + `:83-86` `isOutOfDate` — reuse for in-flow
  validation.
- `extension/ExperimentsExtension.kt` — flag pattern (`Property<Boolean>` `.convention(false)`);
  `GrazelExtension.kt:64,222`.
- rje 6.10 pinned at `WORKSPACE:91`. Lockfile is `version "3"`.

### The rje 6.10 signature algorithm (grounded; verify against source)

**`hash(x)` == Java `String.hashCode()`** over `repr(x)` — in Kotlin literally `str.hashCode()`
(UTF-16, signed 32-bit, overflow wraps). No crypto. The hard part is faithful Starlark `repr`:
- dict → `{"k": v, ...}` in **insertion order** (NOT sorted); list → `["a", "b"]`; string →
  double-quoted with escapes (`\"  \\  \r  \n  \t`, `\xNN` for other control chars); int → decimal;
  bool → `True`/`False`; None → `null`. One space after `:` and `,`.

**`__INPUT_ARTIFACTS_HASH`** (a DICT `{"group:artifact": int, …, "repositories": int}`) via
`compute_dependency_inputs_signature(boms, artifacts, repositories, excluded_artifacts)`
(`coursier.bzl:371-411`):
- per artifact: value `hash(_stable_artifact(artifact) + salt)`, salt ∈ {`"bom"`,`"artifact"`,`"excluded_artifact"`};
  `_stable_artifact` = decode the artifact JSON spec, sort keys, join `"%s=%s" % (k, v)` with `:`.
- collapse per key: `v[0]` if one entry else `hash(repr(sorted(v)))`.
- `all_hashes["repositories"] = hash(repr(sorted(repositories)))`.
- The `artifacts`/`boms`/`excluded_artifacts`/`repositories` here are the **declared rule attributes**
  (canonical) — which Grazel authors, so it can compute this dict directly.

**`__RESOLVED_ARTIFACTS_HASH`** via `_compute_lock_file_hash_v3(lockfile)` (`v3_lock_file.bzl:134-159`)
→ `_compute_final_hash` (`:92-132`), computed **from the lockfile JSON itself**:
- per artifact node build `type_info` with keys inserted **in this order**: `standard`
  (= the artifact's fields minus `shasums`), `sha`, then `repository` (the URL key from the
  `repositories` map), then `dependencies` (sorted), then `dependency_hashes`.
- topo (dependency-first) fold: `dependency_hashes = {dep: final.get(dep, backup.get(dep, 0))}`;
  `final[curr] = hash(repr(node))`; `backup[k] = hash(repr(node_prefold))` (for cycles).

**Validation read-back** (`coursier.bzl:596-698`): input hash compared to
`compute_dependency_inputs_signature(<declared attrs>)`; resolved hash compared to
`_compute_lock_file_hash_v3(<lockfile>)`; mismatch → `fail("… invalid input signature … must be
regenerated")` / `"… invalid signature … may be corrupted"` (gated by `fail_if_repin_required`).

## Work

### Part 1 — Feature flag
1. Add `localMavenResolution: Property<Boolean>` (`.convention(false)`) to `ExperimentsExtension`.
   Read it where the pin flow is wired. Flag off ⇒ zero behaviour change (the entire Item 38 path is
   skipped). Wire the Item 37 `BuildService` via `usesService` only when the flag is on.
   The default stays `false` after this slice; the goal execution must explicitly enable it for
   sample/PAX flag-on verification.

### Part 2 — Repo swap around the pin (reversible)
2. Establish a stable canonical-repo registry: distinct canonical repo URL ↔ index `n` (shared with
   the Item 37 serve route `/r/{n}/`). Start the Item 37 service, read its ephemeral base URL.
3. Before pinning, **swap** each rendered `maven_install(repositories=[…])` in the generated WORKSPACE
   from canonical → `http://<baseUrl>/r/{n}/`, reversibly (reuse/mirror the `ArtificatPinner.pin()/unpin()`
   text-edit pattern). Localhost must be present for **both** `bazel run …:pin` (script generation)
   and the `PinningWorkAction` script execution. Apply per rule (default `maven`, per-variant,
   aggregated `ksp_maven`, …).
4. Run the existing pin flow unchanged (coursier now fetches plain-HTTP from the proxy).
   The proxy must be configured before the `bazel run ...:pin --script_path=...` step, and the
   localhost WORKSPACE state must remain active through the generated `PinningWorkAction` script
   executions, because those scripts perform the actual JSON write.

### Part 3 — Lockfile reconstruction (pure-JVM)
5. Add `MavenInstallLockfileReconstructor` — pure Kotlin, **zero Gradle/bazel imports** (JSON +
   `String.hashCode`). For each produced `<repo>_install.json`:
   - **URL rewrite:** localhost → canonical in `repositories` (the top-level map keys), per-artifact
     `url`, and `mirror_urls` (1:1 prefix rewrite via the `/r/{n}/` → canonical registry).
   - **Recompute `__RESOLVED_ARTIFACTS_HASH`** from the rewritten lockfile via a faithful Kotlin port
     of `_compute_lock_file_hash_v3` + `_compute_final_hash` (insertion-order dicts, Starlark `repr`,
     topo fold, cycle backup-hash).
   - **Recompute `__INPUT_ARTIFACTS_HASH`** via a faithful port of `compute_dependency_inputs_signature`
     over the **canonical declared** `artifacts`/`boms`/`excluded_artifacts`/`repositories` (which
     Grazel authored for that rule).
   - Write both back into the lockfile, preserving rje's exact JSON formatting/key order (so the file
     is byte-identical to a vanilla pin, not merely semantically equal).
6. Implement a shared Starlark-`repr` + `hash()` helper (pure Kotlin) as the reconstruction primitive;
   unit-test it directly against known `repr`/hash pairs from rje.

### Part 4 — In-flow validation + hard-fail; unswap
7. After reconstruction, **validate in-flow**: reuse the existing `bazel build <probe> --nobuild` +
   `BazelLogParsingOutputStream` invalid-signature detection to confirm rje accepts the reconstructed
   lockfile. On rejection → **hard-fail** with a clear message naming the rje-6.10 hash-reconstruction
   mismatch and pointing at disabling the flag. (Maintainer decision: hard-fail; implementers iterate
   until green. No silent fallback to network pin.)
8. **Unswap** the WORKSPACE repos → canonical, coordinated with `ArtificatPinner.pin()`'s marker
   uncommenting, so the committed WORKSPACE + lockfiles are fully canonical.
9. Emit a final `logger.quiet` summary from the pin path when the flag is on, using Item 37 stats:
   e.g. `Local Maven resolution served X artifacts from Gradle index, Y POMs from Gradle cache,
   Z unknown metadata POMs from origin, 0 artifact misses, in Nms`. This is a debugging and trust
   gate; do not rely only on success/failure.

## Hard constraints (output-affecting)
- **Flag off ⇒ byte-identical to today.** The whole path is gated.
- **Flag on ⇒ committed lockfiles byte-identical to a vanilla network pin** — this is THE gate. Not
  "semantically equivalent": byte-for-byte (URLs, key order, hashes, whitespace).
- The reconstructor is **pure-JVM** (no Gradle/bazel). The rje algorithm port must match rje **6.10**;
  the version coupling is documented and re-verified on any rje bump.
- No localhost URL survives in any committed file (WORKSPACE or any `*_install.json`).
- Reuse the existing pin machinery (`ArtificatPinner`, `Bazel.kt`, `BazelLogParsingOutputStream`); do
  not fork a parallel pin path. Bucket/placement set-math untouched.
- Miss policy is inherited from Item 37: missing Gradle-resolved artifacts and known-component POM
  failures are hard failures; only unknown parent/BOM metadata may fall back to origin and must be
  counted. Origin fallback is not a performance escape hatch for artifacts.
- The proxy must not read Gradle live objects directly. `PinMavenArtifactsTask`/`ArtifactPinner`
  configures it with Item 36 facts and then treats it as an HTTP server plus stats source.

## Safety mechanism
- **Primary gate:** on sample + PAX, a flag-on cold/changed pin produces `*_install.json` files
  **byte-identical** to the committed vanilla-produced baselines — i.e. the existing golden
  empty-diff test, run with the flag on after forcing the repin path in a local workspace. Any diff =
  a reconstruction bug (stop).
- PAX baseline: the maintainer has committed the current PAX generated state on the PAX branch. Use
  the clean PAX checkout as the regression baseline. Never commit PAX changes; after flag-on
  verification, `git diff` in PAX must either be empty or match explicitly classified transient
  local test artifacts that are then cleaned.
- **In-flow gate:** the `bazel build --nobuild` probe must report rje accepts the signatures (no
  "invalid signature"); otherwise hard-fail.
- **Flag-off gate:** golden empty-diff with the flag off proves the path is inert by default.
- Size guard (Item 10): no change.
- Verify both the already-pinned skip path and the forced-repin path. The optimization is not proven
  by skip-path-only `migrateToBazel`.

## Testing
- `MavenInstallLockfileReconstructor` unit tests: feed a localhost-based lockfile fixture + the
  canonical registry; assert the rewritten lockfile + both recomputed hashes are byte-identical to a
  committed vanilla lockfile fixture. Include a fixture with a dependency cycle (backup-hash path).
- Starlark-`repr`/`hash()` primitive: table of known (value → repr → int) cases from rje.
- Flag-off: golden empty-diff (path inert).
- Flag-on integration (sample project): force cold/changed pinning locally, run full
  `migrateToBazel`, assert lockfiles are byte-identical to vanilla baselines after reconstruction,
  assert no localhost survives, and run `bazelisk build //...`.
- Flag-on PAX integration: from the clean committed PAX baseline, force the repin path locally,
  run `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`, assert PAX
  generated diff returns to baseline after reconstruction/unswap, run the usual debug APK +
  android-test APK gates, and record proxy stats. Do not commit PAX.
- Validation hard-fail: a deliberately corrupted reconstruction triggers the probe rejection + a
  clear error (no network fallback).
- Proxy miss-policy integration: intentionally remove one indexed artifact mapping in a focused test
  and assert hard failure; simulate unknown parent/BOM POM request and assert counted origin fallback.

## Acceptance criteria
- `experiments { localMavenResolution }` flag exists (`false` default); off ⇒ byte-identical to today.
- Flag on: repos swapped to the proxy for the pin, lockfiles reconstructed to canonical with both
  hashes recomputed in pure Kotlin, validated in-flow, WORKSPACE unswapped.
- Committed `*_install.json` byte-identical to vanilla-pin baselines on sample + PAX.
- Cold/changed pinning path is explicitly exercised; skip-path-only verification is insufficient.
- Pin summary reports Gradle artifact hits, Gradle POM hits, unknown metadata origin fallbacks,
  artifact misses, and elapsed time. Artifact misses must be zero on sample/PAX.
- On reconstruction/version mismatch: hard-fail with a clear message; no silent network fallback.
- rje-6.10 coupling documented. Reconstructor is pure-JVM and unit-tested incl. the cycle case.
- Sample + PAX golden empty-diff (flag off AND flag on vs vanilla baseline); PAX `migrateToBazel` +
  both APK builds green; size guard no-increase.

## Out of scope / Non-goal
- Forward-proxy / TLS-MITM (Approach 2 uses localhost declared repos).
- Bazel-downloader (`--experimental_downloader_config`) rewriting — only affects the pinned build
  phase, which already works; explicitly out of scope.
- Enabling header-auth repos end-to-end / changing `isSupported()` (Item 36 keeps this preserving).
- Supporting rje versions other than 6.10 in the same slice (documented coupling; future bump = a
  follow-up).
