# Item 23 - Target Reference Model Hygiene (Design)

> **Status:** Proposed 2026-06-28.
> **Executor:** Codex.
> **Behaviour change:** none - golden EMPTY-diff.
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`. **Depends on:** Item 19.

> **Execution note - delegate when useful; protect the main context.** This is intentionally
> small, but a focused read-only grep/review subagent is useful before deletion because the
> branch diff is large.

---

## Goal

Close the remaining Item 19 cleanup debt by removing dead production compatibility code and
collapsing duplicate target-reference models, without changing generated output, workspace
planning behavior, Maven repo materialization, or target generation.

Item 19 achieved the important altitude fix:

```text
CollectTargetMavenRepoReferencesTask
  -> TargetReferenceFactsExtractor
  -> TargetReferenceFacts
```

instead of:

```text
CollectTargetMavenRepoReferencesTask
  -> ProjectBazelFileBuilder.targets()
  -> BazelTarget inspection
```

This item removes the compatibility scaffolding left after that cutover.

## Current Problem

Two small leftovers remain after Item 19:

1. `TargetMavenRepoReferencesCollector.fromTargets(...)` still lives under production source.
   It consumes `BazelTarget` models but has no production role after the Item 19 cutover. Keeping
   it makes the old altitude violation look supported even though the live task path no longer
   uses it.
2. `TargetReferenceFacts` and `TargetMavenRepoReferences` are structurally identical:

   ```kotlin
   val repoNames: Set<String>
   val projectPaths: Set<String>
   val projectTargets: Map<String, Set<String>>
   ```

   `toTargetMavenRepoReferences()` is currently just a copy between equivalent shapes. This adds
   naming noise without a real boundary.

## Correct Altitude

Reference facts should have one semantic model at the workspace-plan layer:

```text
TargetReferenceFactsExtractor
  -> TargetReferenceFacts
  -> CollectTargetMavenRepoReferencesTask writes JSON
  -> FinalizeWorkspacePlanTask reads JSON
  -> WorkspaceRenderPlan
```

No production code should keep a `BazelTarget`-based reference collector. The task output file
name remains `target-maven-repo-references.json` unless a verified empty-diff implementation proves
renaming it is harmless; the Kotlin model does not keep the old name.

## Scope

1. **Delete production `TargetMavenRepoReferencesCollector`.**
   - Grep-confirm `TargetMavenRepoReferencesCollector.fromTargets(...)` has zero non-test
     callers.
   - Remove `tasks/internal/TargetMavenRepoReferencesCollector.kt` from production source.
   - Update tests that used it to exercise `TargetReferenceFactsCollector` or task-level
     collection instead. Do not keep dead production code alive for its own test.
   - If a production caller exists, stop and reconcile the caller with the Item 19 architecture;
     do not preserve the old collector as a compatibility path.

2. **Collapse duplicate reference models.**
   - Keep `TargetReferenceFacts` as the semantic model unless implementation proves a stronger
     name; there must be exactly one model shape at the end.
   - Remove `TargetMavenRepoReferences`.
   - Remove `toTargetMavenRepoReferences()`.
   - Keep `TargetReferenceFacts.asRenderPlan()` and merge/normalization helpers if they still
     carry real behavior.

3. **Keep behavior unchanged.**
   - Do not change `TargetReferenceFactsExtractor`.
   - Do not change tag generation, bucket ownership, Maven repo materialization, pinner behavior,
     `WorkspaceRenderPlanBuilder`, or target builder behavior.
   - Do not replace `@maven//:` / `//path:target` string parsing in this item. That is a separate
     typed-label design and cannot be used to skip the required model-collapse work here.

## Out of Scope

- Fully typed Bazel label/reference modeling.
- Removing regex parsing from `TargetReferenceFactsCollector`.
- Removing `WorkspacePlanService.populateRenderPlan(...)` mutation.
- Making `CollectTargetMavenRepoReferencesTask` cacheable.
- Changing PAX or sample generated output.

## Required Coverage

- `TargetReferenceFactsCollectorTest` continues to prove:
  - structured `MavenDependency` repos are collected;
  - normalized `@maven//:` tags are collected;
  - bucket-prefixed Maven tags are ignored;
  - `ProjectDependency` and absolute `StringDependency("//path:target")` project refs are
    collected.
- Task-level tests continue to prove consumer-first reference activation and final
  `WorkspaceRenderPlan` population.
- Any old `TargetMavenRepoReferencesCollectorTest` coverage must either be deleted as obsolete
  or rewritten against live APIs.

## Verification

Focused:

```text
./gradlew :grazel-gradle-plugin:test --tests "*TargetReferenceFactsCollectorTest" --tests "*WorkspacePlanTasksTest" --console=plain --no-daemon
```

Grazel:

```text
./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon
./gradlew migrateToBazel --console=plain --no-daemon
reports/scripts/verify-pax-size-guard.sh --mode preserving
git diff --check
```

PAX final guard:

```text
cd /Users/arun.sampathkumar/work/pax-android
./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks
git diff --check
```

No PAX commits. No public push.

## Acceptance Criteria

- No production code consumes `BazelTarget` to collect target Maven repo references.
- `TargetMavenRepoReferencesCollector` is deleted from production source.
- Only one target-reference data model remains.
- Generated Grazel output is empty-diff.
- PAX size guard remains unchanged.
- PAX migrate leaves the accepted baseline unchanged.

## Risks / Traps

- **False cleanup:** Do not remove `TargetReferenceFactsCollector`; it is live and owns the shared
  reference-fact normalization rules.
- **Scope creep:** Regex/string-label cleanup is real debt, but it is not this item. Avoid turning
  this small preserving cleanup into a cross-layer label model rewrite.
- **Test-only dead surface:** A test that only proves deleted compatibility code works is not a
  reason to keep that compatibility code in production.
- **Early exit:** Do not finish after deleting the collector alone. The duplicate model collapse,
  test rewrite, Grazel verification, and PAX final guard are part of the same item.
