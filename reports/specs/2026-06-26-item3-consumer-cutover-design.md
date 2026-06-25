# Item 3 — Consumer Cutover onto the `WorkspacePlan` (Design)

> **Status:** Approved 2026-06-26. Third spec in the dependency-refactor spec set.
> **Executor:** Codex. **Behaviour change:** none (each consumer switch is a no-op on
> output; Item 2 proved the plan equals current derivations).
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Depends on:** Item 2 (`WorkspacePlan` computed and differential-tested).

> **⚠️ Execution note — delegate to subagents; protect the main context.** Consumer
> call-site mapping and PAX parity runs go to focused subagents that return distilled
> results. Keep the orchestrating context for sequencing and verification.

---

## Goal

Switch the consumer groups to read their rendering *decisions* from `WorkspacePlan` /
`WorkspaceRenderPlan` instead of deriving them ad-hoc — **incrementally, isolated-first,
golden-checked after each step.** Old derivation code is left physically in place (dead)
for Item 4 to delete.

## Reaffirmed correctness principle (from Item 2)

Each switch must be a **no-op on output**. A non-empty golden diff — or a parity-assert
failure (below) — after any step means the plan's value for that consumer was **not**
truly equal to the old derivation, i.e. an Item 2 plan gap. The fix is always to **lift
the missing decision into the plan, never to scrape rendered output.**

## Cutover steps (each its own commit, each followed by the golden empty-diff check)

Order is isolated-first, so root/pinner are decoupled from the manifest **before** the
most-entangled extractor is touched.

### Step 1 — Pinner (`ArtificatPinner`)
Replace repo **discovery** with the plan:
- `materializedMavenInstallRepos()` (WORKSPACE regex, `ArtificatPinner.kt:316-320`) →
  `workspaceRenderPlan.materializedRepoNames`.
- repo selection inside `pinnableMavenInstallRepos` (`:322-345`) →
  `workspacePlan.repoPlan` filtered by `workspaceRenderPlan.materializedRepoNames`.

**Keep** the in-place `maven_install_json` pin/unpin toggle (`:83-99`) and
`shouldRunPinning`'s `#maven_install_json` scan (`:136-139`) — these mutate WORKSPACE to
activate pinning and are a legitimate Bazel mechanic, **not** a feedback edge.

### Step 2 — Root generation (`WorkspaceBuilder` / `MavenInstallArtifactsCalculator`)
Read `workspaceRenderPlan.materializedRepoNames` instead of the `referencedMavenRepos` set
assembled from per-project manifests (`MavenInstallArtifactsCalculator.kt:99,204-229`;
`WorkspaceBuilder.kt:129-135`). Candidate repo definitions and pin inputs still come from
`workspacePlan.repoPlan`. After this step, project gen still *writes* the manifest
(`GenerateBazelScriptsTask.kt:98-101`) but root gen no longer consumes it — the manifest is
now dead, deleted in Item 4.

### Step 3 — tag-producing extractors
Every extractor that emits compile-filter tags reads
`workspacePlan.tagPlan[TargetTagKey]` instead of calling `collectTransitiveMavenDeps` /
`calculateDirectDependencyTags` locally:
- `AndroidExtractor` (`AndroidExtractor.kt:146-162`) replaces its own Maven tag build and
  the direct-project walk (`collectTransitiveMavenDepsForTags` `:186-201`,
  `bestVariantKeyForTagClosure` `:203-221`).
- `AndroidUnitTestDataExtractor` (`AndroidUnitTestDataExtractor.kt:122-128`).
- `AndroidInstrumentationBinaryDataExtractor`
  (`AndroidInstrumentationBinaryDataExtractor.kt:140-149`).
- `KotlinProjectDataExtractor` and `KotlinUnitTestDataExtractor`.

These consumers run pre-compression or during generated target extraction, so the
`VariantGraphKey` + `targetKind` alignment defined in Item 2 is mandatory. The old tag
derivation code stays in place (dead/parity-only) for Item 4.

## Safety mechanism — flag-gated parallel assertion

An off-by-default Gradle property `-Pgrazel.internal.planParity=true` (matches Codex's CLI
flow). When enabled, each switched consumer computes **both** the plan value and the old
value and asserts exact equality, failing with a diff on mismatch.
- **Off by default:** normal runs pay nothing (no double computation).
- **Codex enables it for PAX verification runs** — an exact content check where no content
  golden exists (PAX acceptance is APK build + bounded count/content audit).
- **All parity code is removed in Item 4** along with the old derivations.

## Testing

- **Per step:** sample golden empty-diff (`git diff --exit-code` on committed sample
  outputs); focused dependency tests; full local loop (`verify-*.sh`).
- **Per phase end:** PAX acceptance loop (migrate + `//app:app-gps-pax-debug.apk` +
  `//app:app-gps-pax-debug-android-test.apk`) **with `-Pgrazel.internal.planParity=true`**,
  plus the bounded count/content audit and strict reachability audit from Item 1.

## Acceptance criteria

- Pinner, root generation, and all tag-producing extractors read decisions from
  `WorkspacePlan` / `WorkspaceRenderPlan`; no consumer derives repo set / tags / pin inputs
  ad-hoc as its source of truth.
- Sample golden empty-diff after every step.
- PAX acceptance loop green with parity flag on (no parity-assert failures); bounded
  count/content audit and strict reachability audit stable (documented waivers only).
- The `maven_install_json` toggle and `shouldRunPinning` WORKSPACE scan are retained
  (pin mechanics, not feedback).

## Out of scope (explicit)

- **Deleting old derivation code** — the project-gen manifest write, the WORKSPACE-regex
  repo discovery method, extractor-side tag derivation/walks, and the parity-assert code
  all remain until **Item 4**.
- **Provenance selection flip** — global collapse stays the behaviour; variant-scoped
  selection is **Item 5**.

## Non-goal

Variant compression is not refactored (see Item 2 non-goal). Tag-producing extractors read
`tagPlan` pre-compression where needed; compression's representative-pick carries the
selected variant's tags through unchanged, and tags are excluded from
`VariantEquivalenceChecker`, so no compression decision is perturbed.
