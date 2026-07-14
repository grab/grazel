# Pre-Merge Code-Quality Pass — `arun/dependencies-refactor` Design

**Status:** Approved (design). Awaiting spec review before plan.
**Date:** 2026-07-14
**Branch:** `arun/dependencies-refactor` (grazel), verified against PAX `arun/grazel-refactor`.
**MR:** !165

## Goal

Close every open code-quality item on the `arun/dependencies-refactor` branch —
inline documentation, packaging/structure, and altitude (over-specialised
algorithm code) — with generated output kept byte-identical to the accepted
baseline, verified through `reports/specs/VERIFICATION-GATES.md` on both the
grazel repo and the PAX consumer.

## Context

The branch is mechanically merge-ready: 167 commits ahead of `master`, 0 behind,
clean tree, pushed, gates green on `be28f62`. What remains is quality, not
correctness:

- `BucketOwnershipPlanner.kt` is 879 lines with **zero** KDoc — the original
  complaint that motivated the doc effort. The doc-workflow output that would
  have fixed this was lost in an earlier git-wipe incident and never restored.
- Six packaging/structure findings sit report-only in
  `reports/specs/dependency-refactor-structure-review.md` (none applied).
- Two altitude findings from the bucket-algorithm `/simplify` pass were deferred:
  main-vs-test planning-pipeline unification, and the `canCover*` predicate merge.

## Hard Constraints (apply to every task)

- **Byte-identity gate.** Generated Bazel output must stay identical to the
  accepted baseline. The golden baseline (`verifyGrazelGoldenBaseline`, proxy
  enabled) is the local oracle; PAX is the consumer oracle. Any change that moves
  generated output is reverted unless explicitly intended (none here are).
- **Build serialization.** Only one Gradle build at a time (local or PAX). PAX
  composite-builds the working-tree plugin via `includeBuild`, so no
  `publishToMavenLocal` is needed.
- **PAX non-destructive rule.** Never run `git stash/checkout/reset/commit/add/
  clean/restore/switch/branch -D/push` in the PAX repo. `migrateToBazel
  --rerun-tasks` overwriting generated files in place is fine; all verification
  is read-only.
- **No git access for doc-workflow agents.** Workflow subagents receive only
  file-path lists — never git commands. This is the direct lesson from the
  git-wipe incident (a rogue agent ran a bulk `git checkout`/`restore`).

## Gate Adaptation — PAX HEAD is now clean (CORRECTED SEMANTICS)

`VERIFICATION-GATES.md` step 2 previously expected the PAX diff to show the
baseline shape **`1854 files changed, 68 insertions, 775167 deletions`**, because
PAX carried the generated output as *uncommitted* working-tree modifications.

The PAX latest generated output is now **committed** and HEAD is **clean**. The
gate semantics therefore flip:

- After `migrateToBazel --rerun-tasks`, a **byte-identical** run leaves the PAX
  tree **clean** — `git diff` is empty and there are no untracked generated
  files. This is the new pass condition.
- **Any** modified or untracked generated file after migrate is a **regression**.
- The **size guard** (step 3) is unaffected — it reads generated files directly,
  not the diff. Expect `bucketCount=11`, `pinfileCount=11`,
  `totalArtifactRoots=1945`, no per-repo deltas.
- A **pre-flight** PAX migrate on current grazel HEAD must yield a clean tree
  before any change — this validates the committed baseline as the true oracle.

`VERIFICATION-GATES.md` step 2 is updated as part of this work to:
*"byte-identical run leaves the tree clean; any modified/untracked generated file
is a regression"*, with the historical uncommitted-baseline shape recorded as
superseded. The non-destructive rule is unchanged.

## Architecture — Three Tiers, Three Commits

Work is partitioned by regression risk so PAX sweeps run only where output could
actually move.

### Tier 1 — Documentation (Commit 1)

Comment-only. Cannot alter generated output. Local gate only.

- Re-run the by-feature **documentation workflow** on **Sonnet** agents (never
  Haiku), git-locked: agents receive only file-path lists; no git, no bash-git,
  no writes outside their assigned files.
- **Scope = the diff.** Document only declarations added/materially changed in
  `origin/master...HEAD`; unchanged neighbours are out of scope.
- **Complexity stack-ranker.** A ranking phase scores each changed unit; only
  units above the bar get KDoc. Trivial/self-evident code (getters, data
  holders, one-line delegators) is left undocumented. Docs must add intent,
  invariants, and coupling the code cannot state for itself — **regurgitating
  the signature in prose is a defect**, and the adversarial verifier deletes it.
- Partition the branch's changed main + test sources into cohesive feature
  clusters:
  1. Bucket algorithm (`BucketOwnershipPlanner`, `DependencyBucketPlacementEngine`,
     `BucketSetMath`, `BucketReduction`, `ProjectDependencyBucket`, and sibling
     `Bucket*`/placement files).
  2. Local-Maven proxy (`proxy/` package).
  3. rje lockfile & maven-install rendering (`migrate/dependencies/` rje files).
  4. Variant / configuration facts (`gradle/variant/`, resolved-facts).
  5. Workspace / render plan (`WorkspacePlanBuilder`,
     `WorkspaceRenderPlanBuilder`, and collaborators).
  6. Artifact pinning (`ArtifactPinner`, `PinMavenArtifactsTask`, pinning
     workspace).
- One Sonnet writer per cluster → inline KDoc, on the ranker's document-worthy
  targets only, oriented to a reader with zero prior context. Primary,
  non-negotiable target: the complex parts of `BucketOwnershipPlanner.kt`.
- **Adversarial verification pass**: read-only Sonnet verifiers flag both
  factual drift (KDoc misdescribing the code) and regurgitation/trivial docs
  (KDoc that merely restates the code). Both are corrected/deleted before commit.
- Documentation is descriptive only — no code statements change, so the golden
  hash cannot move. If it does, a doc introduced a non-comment edit and is reverted.

### Tier 2 — Structure / Packaging (Commit 2)

All findings resolve as `internal` moves or same-package/same-FQ-name file
operations. Cannot alter generated output (symbols do not appear in generated
Bazel files). Local gate only.

- **#1 Layering inversion.** Move the maven-repo naming family
  (`toMavenRepoName`, `toMaterializedMavenRepoName`, `BASE_MAVEN_REPO`,
  `MAVEN_COMPILE_FILTER_TAG_PREFIX`) from `migrate/dependencies/Repository.kt`
  into `gradle.dependencies` (new `gradle/dependencies/MavenRepoNaming.kt`), and
  mark them `internal` — they are pipeline implementation details, not plugin
  API. Update ~10 import sites (7 in `gradle.dependencies`, 3 in
  `migrate.dependencies`). This flips the dependency direction so
  `migrate.dependencies` depends on `gradle.dependencies` (correct).
- **#2 File/type name mismatch.** Split `maven/MavenRepositoryPath.kt` into
  `maven/MavenCoordinates.kt` + `maven/MavenPath.kt` (reuse the parked split in
  `scratchpad/lost-work/`). Same package — no import changes. All `internal`.
- **#3 Misfiled fact builders.** Move the Gradle fact-collection machinery out of
  `proxy/LocalMavenResolvedFacts.kt` (`LocalMavenResolvedFactsBuilder`,
  `ResolvedArtifactIndexBuilder`, `ResolvedComponentIndexBuilder`,
  `GradleModuleCacheFileResolver`/`...IndexBuilder`, `GradlePomFileResolver`,
  and the `PomFileResolver`/`PomArtifactQuery`/`PomCacheLookup` interfaces) into
  `gradle/dependencies/`. Keep the `LocalMavenResolvedFacts` DTO + `PomFileResolver`
  interface near the proxy. Split the 417-line file into focused files. Highest
  effort; introduces an acceptable `proxy -> gradle.dependencies` dependency.
- **#4 Misplaced package KDoc.** Relocate the rje package-overview block off
  `RulesJvmExternalLockfile.kt` (which holds only the model + parser) onto the
  subsystem anchor `MavenInstallLockfileReconstructor`. This is done *after*
  Tier 1 restores the docs, and supersedes the earlier "item #4 is stale" concern.
- **#5 File/class name mismatch.** Rename `tasks/internal/TasksManager.kt` →
  `TaskManager.kt` to match the `internal class TaskManager`. Same package, file
  rename only.
- **#6 Contract + implementation mixed.** Split `JvmVariant`/`JvmVariantData`
  (+ the `JvmVariant(...)` factory) out of `gradle/variant/Variant.kt` into
  `gradle/variant/JvmVariant.kt`, leaving `Variant.kt` as the interface + shared
  vocabulary. Same package — API-safe, no import changes.

### Tier 3 — Altitude (Commit 3)

Touches live algorithm code. Behavior-preserving in intent; highest regression
risk. Local gate + **full PAX sweep**.

- **Unify main vs Test/AndroidTest planning pipeline** in `BucketOwnershipPlanner`
  — collapse the special-cased branches onto shared infrastructure instead of
  parallel per-source-set code paths.
- **Merge the `canCover*` predicate family** in `BucketSetMath` (`canCover`,
  `canCoverDeclaredPlaceholder`, `rootsSupersetClosureOf`) where they share
  structure, without changing coverage decisions.
- **Oracle-driven method.** Each generalisation is applied in isolation as a pure
  refactor. After each, run the golden baseline; if the diff moves, that specific
  transform is reverted. Only transforms that leave the golden byte-identical
  survive into the commit.

## Verification Plan

Uses `reports/specs/VERIFICATION-GATES.md` (with the corrected step 2). Local
gate = `:grazel-gradle-plugin:test` + `verifyGrazelGoldenBaseline`.

| Step | Gate |
|------|------|
| Pre-flight | PAX migrate on current HEAD → **clean tree** (validates oracle) |
| After Commit 1 (docs) | Local gate |
| After Commit 2 (structure) | Local gate |
| After Commit 3 (altitude) | Local gate + **full PAX sweep** |
| Final sign-off | **full PAX sweep** |

Full PAX sweep = migrate `--rerun-tasks` → clean-tree check → size guard
(11/11/1945, no deltas) → APK build (`//app:app-gps-pax-debug.apk` +
`...-android-test.apk`) → focused tests (3 pass). Long builds run in the
background and chain on completion; only one Gradle build at a time.

Known local waiver: `verify-sample-bucket-labels.sh` may fail only on the
pre-existing appcompat/constraintlayout one-sided-exclude assertion. Any other
failure is real.

## Out of Scope

- Any change intended to move generated output.
- Further algorithm rewrites beyond the two named altitude findings.
- Splitting `BucketOwnershipPlanner` into multiple files (deliberately not
  flagged — cohesive algorithm; set-math already extracted to `BucketSetMath`).

## Risks & Mitigations

- **Doc workflow agent goes rogue (git-wipe repeat).** Mitigation: agents get
  only file-path lists; no git/bash-git tools; writes confined to assigned files.
- **A structure move perturbs the golden hash.** Mitigation: local golden gate
  after Tier 2; revert the offending move. All Tier 2 symbols are `internal` and
  absent from generated files, so this is not expected.
- **Altitude generalisation changes coverage decisions.** Mitigation: isolated
  application + golden-as-oracle after each transform; full PAX sweep before the
  commit is accepted.
- **PAX oracle drift.** Mitigation: pre-flight clean-tree check establishes the
  committed baseline as ground truth before any change.
