# Item 39 — RJE Lockfile Reconstructor Shape & Renderer Hygiene (Design)

> **Status:** Proposed 2026-07-02.
> **Executor:** Codex. **Behaviour:** **preserving / empty generated diff**.
> **Depends on:** Item 38 (local Maven proxy pin integration).
> **Global Constraints + Verification Playbook + Code-quality stance:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`.

> **Execution note — use subagents deliberately.** This item is mostly source-shape and
> invariants. Use focused subagents for RJE-source/hash checks, changed-file source-shape
> review, PAX force-pinning audit, and final adversarial review. The parent agent owns
> reconciliation and must spot-check claims.

---

## Goal

Refactor `MavenInstallLockfileReconstructor.kt` so the code reads as a small orchestration layer
over named rules_jvm_external lockfile concepts instead of a single file that appears to manually
wrangle JSON.

The outcome must make the distinction obvious:

- **JSON parsing and domain transforms** use structured JSON/model APIs.
- **Manual-looking string rendering and Starlark repr/hash code** exists only because
  rules_jvm_external v3 lockfiles require exact byte shape and signature compatibility.
- That compatibility code is isolated, named, tested, and documented as RJE-specific, not general
  Grazel JSON style.

This is not an algorithm change. It is an altitude/source-shape cleanup over already verified
Item 38 behavior.

## Problem

Current `MavenInstallLockfileReconstructor` combines too many responsibilities:

1. Parses lockfile JSON.
2. Rewrites proxy URLs back to canonical repository URLs.
3. Merges baseline facts and skipped artifacts.
4. Applies POM-packaging skip policy.
5. Recomputes rules_jvm_external `__INPUT_ARTIFACTS_HASH`.
6. Recomputes rules_jvm_external `__RESOLVED_ARTIFACTS_HASH`.
7. Implements Starlark-like `repr`.
8. Implements custom JSON indentation and string escaping.
9. Renders the final lockfile in RJE's expected field order.

That makes the file look like arbitrary manual JSON manipulation even though the load-bearing reason
is narrower: RJE lockfiles are JSON, but the committed file must keep RJE's exact render shape and
hash semantics. A generic `Json.encodeToString(...)` rewrite is risky because:

- RJE validates signature hashes computed over Starlark-like values.
- Key ordering and field ordering are significant for byte-identical output.
- Null shasum fields and other JSON values must not be dropped by Grazel's shared
  `Json { explicitNulls = false }` configuration.
- The force-pinning path has already proven correctness through PAX; this item must not weaken that.

## Target Shape

Keep `MavenInstallLockfileReconstructor` as the public entry point, but make it orchestration only:

```text
MavenInstallLockfileReconstructor
  -> RulesJvmExternalLockfileParser
  -> MavenLockfileRepositoryUrlRewriter
  -> BaselineLockfileFactsMerger
  -> PomPackagingSkipNormalizer
  -> RulesJvmExternalLockfileHasher
       -> StarlarkRepr
  -> RulesJvmExternalLockfileRenderer
```

Suggested files, keeping the scope local to Maven pinning:

- `migrate/dependencies/RulesJvmExternalLockfile.kt`
  - Typed wrapper/model for the RJE v3 lockfile sections Grazel reads/writes.
  - It may hold section payloads as `JsonObject`/`JsonElement` where RJE owns the schema.
  - Do not over-model sentinel top-level fields. Keys such as
    `__INPUT_ARTIFACTS_HASH` and `__RESOLVED_ARTIFACTS_HASH` can remain string
    constants gathered in one place; the abstraction is about named sections and
    ownership, not replacing every JSON key with a class.
  - It must name fields (`artifacts`, `dependencies`, `repositories`, `services`, `skipped`,
    `inputArtifactsHash`, `resolvedArtifactsHash`) instead of passing anonymous `JsonObject`s
    through all layers.
- `migrate/dependencies/RulesJvmExternalLockfileRenderer.kt`
  - The only production file allowed to render final lockfile JSON manually.
  - Owns RJE v3 top-level field order, indentation, optional-section omission, and string escaping.
  - If generic kotlinx JSON encoding can replace any helper, it must be proven byte-identical first.
- `migrate/dependencies/RulesJvmExternalLockfileHasher.kt`
  - Owns RJE 6.10 `__INPUT_ARTIFACTS_HASH` and `__RESOLVED_ARTIFACTS_HASH` calculation.
  - Pure JVM, no Gradle/Bazel imports.
- `migrate/dependencies/StarlarkRepr.kt`
  - Owns Starlark-like repr and Java `String.hashCode()` hashing.
  - Pure JVM and directly unit-tested.
- `migrate/dependencies/MavenLockfileRepositoryUrlRewriter.kt`
  - Owns proxy-prefix to canonical URL rewriting over parsed JSON/model sections.
- `migrate/dependencies/BaselineLockfileFactsMerger.kt`
  - Owns baseline shasum preservation and skipped-artifact merge rules.
- `migrate/dependencies/PomPackagingSkipNormalizer.kt`
  - Owns the POM-packaging artifact skip policy and first-pin guard.

Names can change during implementation if a better local convention exists, but the responsibilities
must remain separated and visible.

## Non-Goals

- Do not change Item 38 behavior.
- Do not change bucket placement, dependency ownership, proxy serving, or artifact/POM resolution.
- Do not introduce a new JSON library.
- Do not replace the RJE renderer with generic JSON rendering unless tests prove byte-for-byte
  equality on current lockfiles and force-pinning.
- Do not make PAX-specific exceptions.
- Do not commit or push PAX changes.

## Hard Constraints

- **Generated output stays byte-identical** for Grazel and PAX.
- **Flag off stays inert.** The existing non-proxy pin path must not move.
- **Flag on force-pinning stays verified.** PAX proxy repin must still produce canonical lockfiles:
  no `localhost`/`127.0.0.1` survives in `WORKSPACE` or `*_maven_install.json`.
- **RJE hash semantics stay unchanged.** Keep Java `String.hashCode()`, Starlark-like repr, resolved
  hash topo fold, and canonical repository input hash behavior unless re-grounded against the
  RJE source and proven by tests.
- **String keys are fine at the JSON boundary.** Use constants for RJE top-level keys and section
  names. Do not create needless wrappers for `__INPUT_ARTIFACTS_HASH` or
  `__RESOLVED_ARTIFACTS_HASH`; the problem to solve is scattered responsibility, not the existence
  of JSON object keys.
- **Manual JSON string construction is quarantined.** Outside the dedicated RJE renderer/Starlark
  repr files, code should use JSON/model APIs and named data structures.
- **No hidden receivers for policy-heavy transforms.** Avoid hard-to-read extensions like
  `Map<...>.withSomething(...)` when the receiver name carries meaning; prefer named parameters and
  small named collaborators.
- **Kotlin type system remains the guardrail.** No reflection, unchecked dynamic access, or
  stringly shortcuts when a typed wrapper/model is viable.

## Work Plan

### Phase 1 — Lock current behavior

1. Add or tighten focused tests before refactoring:
   - parsing + rendering a checked-in RJE lockfile with no URL rewrite is byte-identical;
   - null shasum values are preserved;
   - optional empty sections are omitted exactly as today;
   - baseline skipped-artifact behavior remains unchanged;
   - POM-packaging first-pin guard remains fail-closed.
2. Keep the existing PAX force-repin command in the execution log as the baseline symptom to
   preserve.

### Phase 2 — Extract named RJE model/parser

1. Introduce the RJE lockfile wrapper/model.
2. Parse once at the boundary, then pass named lockfile objects through collaborators.
3. Keep raw `JsonElement` payloads where RJE owns nested schema; do not over-model every artifact
   field unless it removes real complexity.

### Phase 3 — Extract policy collaborators

1. Move URL rewrite logic to `MavenLockfileRepositoryUrlRewriter`.
2. Move baseline merge logic to `BaselineLockfileFactsMerger`.
3. Move POM-packaging skip logic to `PomPackagingSkipNormalizer`.
4. `MavenInstallLockfileReconstructor.reconstruct(...)` should read as a straight pipeline.

### Phase 4 — Extract hasher + renderer

1. Move RJE hash code to `RulesJvmExternalLockfileHasher`.
2. Move Starlark repr to `StarlarkRepr`.
3. Move `renderLockfile`, JSON indentation, and JSON string rendering to
   `RulesJvmExternalLockfileRenderer`.
4. Attempt to replace hand-rolled JSON string escaping with a standard JSON primitive encoder only
   if the checked-in-lockfile byte-identity tests remain green. If not, keep the helper isolated in
   the renderer and document the RJE compatibility reason.

### Phase 5 — Review and final verification

1. Run a local source-shape review over the changed files:
   - no policy-heavy anonymous map extensions;
   - helper data classes live near their domain, not buried mid-file;
   - names explain RJE compatibility instead of generic JSON behavior;
   - no migration-diary comments.
2. Run simplify-pass and an adversarial review over this item’s diff. Fix confirmed findings or
   reject with concrete code evidence.

## Required Verification

Focused Grazel checks:

```bash
./gradlew :grazel-gradle-plugin:test \
  --tests "com.grab.grazel.migrate.dependencies.MavenInstallLockfileReconstructorTest" \
  --console=plain --no-daemon
```

Then broader local checks:

```bash
./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon
./gradlew migrateToBazel --console=plain --no-daemon
reports/scripts/verify-default-task-graph.sh
reports/scripts/verify-pax-size-guard.sh --mode preserving
git diff --check
git diff --check master...HEAD
```

PAX force-pinning guard after meaningful non-doc changes:

```bash
cd /Users/arun.sampathkumar/work/pax-android
./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks
git diff --check
```

The PAX run must exercise `experiments.localMavenResolution=true` and a forced/changed pin path.
Record proxy stats and timing in `reports/specs/EXECUTION-LOG.md`. After the run:

- `WORKSPACE` and `*_maven_install.json` contain no `localhost` or `127.0.0.1`;
- PAX generated files are unchanged against the committed PAX baseline, except intentional local
  `build.gradle` hook lines if they are still present;
- `reports/scripts/verify-pax-size-guard.sh --mode preserving` passes unchanged.

If lockfile reconstruction changes are non-trivial or any generated output moves during debugging,
also run the accepted PAX build/test gate before claiming the item is complete:

```bash
./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk
./bazel.sh test --test_output=errors \
  //app-utils:app-utils-gps-pax-debug-test \
  //app-test:app-test-gps-pax-debug-test \
  //application-initializer:application-initializer-gps-pax-debug-test
```

## Acceptance Criteria

- `MavenInstallLockfileReconstructor.kt` is small orchestration over named collaborators.
- Manual JSON rendering is isolated in the RJE renderer and covered by byte-identity tests.
- RJE hash/Starlark repr logic is isolated and directly tested.
- Baseline merge and POM-packaging skip behavior are isolated and covered by focused tests.
- Existing checked-in lockfiles remain byte-identical when reconstructed without proxy rewrites.
- PAX forced proxy repin passes and remains canonical/no-diff.
- Execution log records decisions, commands, timing/proxy stats, and any rejected review findings.
- No PAX commit/push. Grazel may be committed locally only at a clean green checkpoint.
