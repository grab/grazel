# Item 15 — Rendering Purity + Hygiene (Design)

> **Status:** Approved 2026-06-27. Final item of the altitude-layering pass.
> **Executor:** Codex. **Behaviour change:** none (cleanup + a test). Golden empty-diff.
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`. **Depends on:** Items 11–14 (run last).

> **⚠️ Execution note — delegate to subagents; protect the main context.**

---

## Goal

Close out the altitude-layering pass: confirm renderers do not parse generated files or
infer ownership, kill dead/speculative code, and add the one missing test. Pure cleanup —
golden empty-diff.

## Work

1. **`commonAncestorsOf` / `closestCommonAncestorsOf` — wire or remove.** Added in Item 7's
   implementation but had zero production callers (test-only). After Items 12–13:
   - If Item 13's test-placement uses `closestCommonAncestorsOf(...).first()`, ensure it's
     genuinely wired and tested through that path; **delete the unused twin** if one remains.
   - If neither is used in production, **delete both** (don't ship speculative helpers).
2. **Delete dead-code residues** (from the earlier audit):
   - the discarded `readText()` whose return value is thrown away in
     `CollectTargetMavenRepoReferencesTask` (the file is already an `@InputFile`);
   - the dead `materializedMavenRepos` fallback method in `MavenInstallArtifactsCalculator`
     that is unreachable in the live path (render plan supplies the repo set). Before
     deleting it, grep for zero non-test callers passing or omitting `materializedMavenRepos`;
     remove the `= null` defaults and the fallback together.
3. **Add the missing `WorkspaceRenderPlanBuilder` unit test.** It's altitude-critical (owns
   materialized-repo derivation + override-target closure) but currently has only indirect
   coverage. Add direct unit tests: materialization from `repoPlan`, override-target closure
   BFS, only-direct-owned-roots materialize.
4. **Confirm renderer/workspace purity** (verify, document): `ProjectBazelFileBuilder`,
   `RootBazelFileBuilder`, `WorkspaceBuilder`, pinner do no ownership inference and do not
   parse generated `BUILD.bazel`, `WORKSPACE`, or pin JSON as upstream model inputs. Existing
   target-model planning feedback through `ProjectBazelFileBuilder.targets()` and
   `WorkspacePlanService` is acknowledged as model feedback, not generated-file parsing. Do
   not promise to remove that feedback in this cleanup item.

## Not here (handled elsewhere)

- `PAX-BOUNDED-AUDIT-BASELINE.md` refresh is **Item 10's** job (frozen baseline) — not
  duplicated here.
- The `populateRenderPlan` cyclic-loop redundancy is **left as-is** (Item 11 decision —
  touching it risks the fixpoint).

## Safety mechanism

- **Sample golden EMPTY-diff** (deleting dead code + adding a test changes no output).
- Removed code must be provably unreachable (grep zero production callers before deleting).
- PAX builds + size guard unchanged.

## Acceptance criteria

- `commonAncestorsOf`/`closestCommonAncestorsOf` either wired+tested in production or deleted
  (no speculative dead helpers).
- Dead `readText()` and dead `materializedMavenRepos` fallback removed.
- `WorkspaceRenderPlanBuilder` has direct unit tests.
- Renderer purity confirmed/documented: `ProjectBazelFileBuilder`, `RootBazelFileBuilder`,
  `WorkspaceBuilder`, and pinner do not parse generated files or infer ownership. Note:
  `CollectTargetMavenRepoReferencesTask` re-evaluates target builders for repo references;
  that in-task model feedback is out of scope here and is not a generated-file parsing
  regression.
- Golden empty-diff; PAX builds green; size guard unchanged.

## Out of scope / Non-goal

- Variant compression; any output change; the `populateRenderPlan` tightening (deferred).
