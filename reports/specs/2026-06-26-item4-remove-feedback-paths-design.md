# Item 4 — Remove Generated-Output Feedback Paths (Design)

> **Status:** Approved 2026-06-26. Fourth spec in the dependency-refactor spec set.
> **Executor:** Codex. **Behaviour change:** none (deletes unreachable code + a vestigial
> task edge; golden-checked per deletion).
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Depends on:** Item 3 (all consumers read from `WorkspacePlan`; old paths now dead).

> **⚠️ Execution note — delegate to subagents; protect the main context.** Dead-code
> reachability checks and PAX runs go to focused subagents returning distilled results.

---

## Goal

Delete the derivation code Item 3 made dead, and complete the structural decoupling that
finishes killing feedback-edge-2. Dead **tests** are removed alongside their dead code.
Each deletion is golden-checked (empty `git diff` on committed sample outputs).

## Deletion inventory (same isolated order as the cutover; each its own commit)

### 1. Manifest path + task-graph decouple
- Delete `tasks/internal/GeneratedBuildMavenRepos.kt` (the object and its `tagLabelPattern`
  regex, `GeneratedBuildMavenRepos.kt:26`).
- Delete `GenerateBazelScriptsTask.referencedMavenRepos` output
  (`GenerateBazelScriptsTask.kt:77-79,98-105`).
- Delete `tasks/internal/GeneratedBuildMavenReposTest.kt`.
- In `TasksManager.kt`: remove the `generatedProjectMavenRepoManifests` input wiring **and**
  the `dependsOn(projectGenerateBazelScriptsTasks)` edge on root gen
  (`TasksManager.kt:118-125`). Root gen retains `workspaceDependencies` + the plan +
  `dependsOn(finalizeWorkspacePlan)`.
- **Update `reports/scripts/verify-default-task-graph.sh`** to the new decoupled graph
  shape. This is an **intentional oracle update**, not a regression: root gen no longer
  depends on project gen.

### 2. Pinner WORKSPACE-regex discovery
- Delete `ArtificatPinner.materializedMavenInstallRepos()` (`ArtificatPinner.kt:316-320`)
  and its call sites; repo discovery now comes from the plan (Item 3).
- **Keep** the `maven_install_json` pin/unpin toggle (`:83-99`) and the `shouldRunPinning`
  `#maven_install_json` scan (`:136-139`) — legitimate Bazel pin mechanics, not feedback.
- Trim any `DefaultArtifactPinnerTest` cases that targeted the deleted regex method.

### 3. Extractor-side tag derivation
- Delete `collectTransitiveMavenDepsForTags` (`:186-201`), `bestVariantKeyForTagClosure`
  (`:203-221`), `MavenTagClosureKey` (`:227-231`), `transitiveMavenDepsForTagsCache`
  (`:74-76`), and the walking tag-build block (`:146-162`); the extractor now reads only
  `plan.tagPlan`.
- Drop any `variantBuilder` / `dependencyGraphsService` wiring in the extractor that
  existed solely for the walk.
- This also removes the Item 1 baseline's `AndroidExtractor` tag-closure cache naturally
  (it cached the now-deleted walk).
- Delete local transitive-tag calculation in every other tag-producing extractor switched
  in Item 3:
  `AndroidUnitTestDataExtractor`, `AndroidInstrumentationBinaryDataExtractor`,
  `KotlinProjectDataExtractor`, and `KotlinUnitTestDataExtractor`. After deletion, tags
  come from `WorkspacePlan.tagPlan` only.

### 4. Parity-assert code — LAST
- Remove the `-Pgrazel.internal.planParity` flag and all per-consumer compute-both-and-
  assert code introduced in Item 3. Removed **after** deletions 1–3 are golden-confirmed,
  since it was the safety net for them.

## Testing

- **Per deletion:** sample golden empty-diff (`git diff --exit-code` on committed sample
  outputs); focused dependency tests.
- **Task graph:** the **updated** `verify-default-task-graph.sh` passes against the
  decoupled graph; no legacy `*ResolveDependencies` and no root-gen↔project-gen edge.
- **Cacheability spot-check:** second `migrateToBazel` run shows the decoupled root/project
  tasks UP-TO-DATE (decoupling should not regress incrementality).
- **PAX acceptance:** migrate + `//app:app-gps-pax-debug.apk` +
  `//app:app-gps-pax-debug-android-test.apk` (parity flag is gone — normal gate);
  bounded count/content audit and strict reachability audit stable (documented waivers only).

## Acceptance criteria

- `GeneratedBuildMavenRepos`, the manifest output, the WORKSPACE-regex discovery method,
  extractor-side tag derivation/walks, and the parity code are all gone (with their dead
  tests).
- No generated-output feedback path remains: WORKSPACE repo set comes from the plan;
  pinning repo discovery comes from the plan; tags come from the plan.
- Root gen no longer depends on project gen; `verify-default-task-graph.sh` reflects and
  asserts the new shape.
- Sample golden empty-diff throughout; PAX acceptance green.

## Out of scope (explicit)

- **Provenance / exclude correctness** — global collapse stays the behaviour (Item 5).
- **`resolve()` extraction / simplify / reports cleanup** — Item 6.

## Non-goal

Variant compression untouched (see Item 2 non-goal).
