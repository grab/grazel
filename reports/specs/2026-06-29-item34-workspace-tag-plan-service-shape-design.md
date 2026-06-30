# Item 34 — Workspace Tag Plan: Service Shape & Packaging Cleanup

> **Status:** Approved 2026-07-01 for execution. Codex must still validate the grounded current
> state before implementation and stop on any generated-output drift.
> **Executor:** Codex (after review). **Intended behaviour:** preserving — generated output
> (BUILD/WORKSPACE, including emitted `tags`) should be byte-identical. Golden EMPTY-diff is the
> target; flag anything that can't meet it.
> **Global Constraints + Verification Playbook + Code-quality stance:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`. **Depends on:** none hard; touches Layer-4 plan
> service + the tag-plan task wiring. Likely independent of Item 33.

> **⚠️ Execution note — delegate to subagents; protect the main context.**

---

## Why this exists (problem statement)

The "workspace tag plan" subsystem is altitude-clean in its *output* (it's a Layer-4 plan consumed
read-only by extractors; no feedback from generated targets), and its collector is essential
(cross-variant transitive Maven closure must be computed once, upstream of per-project parallel
generation). An Opus investigation confirmed that and flagged the residual issues below. This item
proposes to clean the **packaging and service shape** around the tag plan **without** changing the
collector's algorithm or the emitted tags.

**Explicitly accepted, NOT in scope (recorded so it isn't re-litigated):** `CollectWorkspaceTargetTagPlanTask`
is `@UntrackedTask` and the collector reads the **live** variant/dependency-graph model at
execution (`rootProject.subprojects`, `variantBuilder.build(project)` at
`WorkspaceTargetTagPlanCollector.kt:250`, `dependencyGraphsService`). The maintainer **accepts**
this live-model/untracked coupling for now. The resolved Maven *values* already arrive via a clean
file boundary (`workspaceDependencies: @InputFile RegularFileProperty`,
`CollectWorkspaceTargetTagPlanTask.kt:53-55` → `dependencyResolutionService.init(file)` `:69`), so
no `ResolvedComponentResult` is threaded or inlined into this task. Do **not** attempt to make the
task cacheable or to serialize the variant topology in this item.

## Grounded current state (Codex: verify these line refs still hold)

- `model/WorkspacePlan.kt:40-51` — `WorkspacePlan.tagPlan: List<TargetTagPlan>`;
  `TargetTagPlan { key: TargetTagKey, tags: List<String> }`; `TargetTagKey(variantId: String,
  variantType: String, targetKind: String)`.
- `CollectWorkspaceTargetTagPlanTask.kt` — `@UntrackedTask`; writes `target-tag-plan.json`
  (`@OutputFile`, ~`:61-62`); runs the collector over `rootProject` (`:72`).
- `TasksManager.kt:~88` — feeds `targetTagPlan` into `ComputeWorkspacePlanTask` as an input.
- `WorkspacePlanBuilder.kt:~63-66` — `build(...)` assigns `tagPlan = targetTagPlan` **unchanged**
  (pure pass-through; the builder does no work on tags).
- `WorkspacePlanService.kt:31-33` — three mutable nullable fields: `workspacePlan`,
  `workspaceRenderPlan`, `targetTagsByKey`; `tagsByKey()` lazily builds + memoizes the index
  (`:119-126`), invalidated on repopulate (`:38`, `:52`); `tagsFor(...)` read path (`:67-79`).
- Render-plan back-edge (the contrast): `CollectTargetMavenRepoReferencesTask.kt:~183`
  `populateRenderPlan(accumulated.asRenderPlan())` mutates the render plan **mid-fold**
  (consumer-first). This mutation is accepted/intentional; this item only proposes to *isolate*
  it, not remove it.
- Consumers of `tagsFor`: Android library, Android unit test, Android instrumentation, Kotlin
  library, and Kotlin unit test extractors (Codex: grep for the full set before editing).

## Approved work (three parts — Codex to validate before implementation)

### Part 1 — De-passenger the tag plan (code smell; likely empty-diff, S)
Today the tag plan is **double-materialized**: written to its own `target-tag-plan.json` AND
threaded through `ComputeWorkspacePlanTask` → `WorkspacePlanBuilder` (which only copies it) → embedded
in the plan file. Proposal:
- Have the plan service (or its replacement from Part 2) load the tag index **directly from
  `target-tag-plan.json`**, the file the collect task already produces.
- Remove `tagPlan` from `WorkspacePlan` and the `tagPlan = targetTagPlan` copy in
  `WorkspacePlanBuilder.build`, and drop the `targetTagPlan` input on `ComputeWorkspacePlanTask`.
- **Open question for Codex:** does anything other than `tagsByKey()` read `workspacePlan.tagPlan`?
  (Investigation said no — confirm by grep before removing the field.) Confirm both hydration
  points (`populatePlan` / `initPlan` in `GenerateBazelScriptsTask.kt:~108-111`) still get the tag
  index from the right place after the field is gone.
- Expected: byte-identical generated Bazel output (same tags, just sourced from the collector file
  once). Removing `tagPlan` changes the internal ignored `build/grazel/workspace-plan.json` shape;
  that is acceptable only if committed/generated `BUILD.bazel`, `WORKSPACE`, pin JSONs, and PAX
  generated baseline stay unchanged. If any committed/generated output changes, stop and classify
  it.

### Part 2 — Split `WorkspacePlanService` (THE altitude win; intended empty-diff, M)
The service mixes a **read-only** tag index with the **self-mutating** render plan under one lock,
hiding the one genuine back-edge among innocent read-only state. Proposal:
- Separate into (a) a read-only tag index (e.g. `TagPlanIndex` / `WorkspaceTagPlanService`) owning
  `targetTagsByKey` + `tagsFor`, and (b) the render-plan service retaining the consumer-first
  `populateRenderPlan` mutation — now **explicit and isolated**, with a short doc comment naming it
  as the intentional consumer-first back-edge.
- The plan (`workspacePlan`) field: Codex to decide whether it stays with the render-plan service or
  gets its own holder — pick whatever leaves each service with one reason to change.
- Update injection sites and every task that can need tag-derived facts:
  `AnalyzeVariantCompressionTask`, `CollectTargetMavenRepoReferencesTask`, and
  `GenerateBazelScriptsTask` must initialize the tag service directly from `target-tag-plan.json`
  before invoking extractors/builders that call `tagsFor`. `GenerateRootBazelScriptsTask` reads
  plans directly and does not need the tag service.
- **Open question for Codex:** is `workspacePlan` itself read-only post-load, or also mutated? That
  determines whether it groups with the tag index (read-only) or the render plan (mutable).
- Intended empty-diff: pure relocation of state ownership; no behaviour change.

### Part 3 — Type the key (optional polish; empty-diff if serialization handled, S–M)
`TargetTagKey` carries `variantType` as a stringified enum and `targetKind` as string constants,
re-`.toString()`'d at the producer (`WorkspaceTargetTagPlanCollector.kt:~201`) and all consumers.
Proposal: use `VariantType` directly and promote `TargetTagKinds` to an enum.
- **Caveat / open question:** this changes `target-tag-plan.json`'s serialized shape (enum name vs
  raw string). Usually identical text → empty-diff, but Codex must golden-test it. If it's not
  clean empty-diff, defer Part 3 to its own slice rather than risk Parts 1–2.
- Current decision for this goal: defer Part 3 unless Parts 1-2 finish green and a focused proof
  shows enum serialization is byte-identical. Plain enum `targetKind` is likely not byte-identical
  because current values are lowercase strings.

## What to LEAVE ALONE (essential — do not touch)
- The collector's cross-variant transitive Maven closure walk + `transitiveMavenDepsCache` + global
  sort (`WorkspaceTargetTagPlanCollector.kt`).
- `calculateMavenDependencyTags` rewrite/sort.
- The `tagsFor` memoized read path itself (only its *home* may move in Part 2).
- The accepted `@UntrackedTask` / live-variant-model coupling (recorded above).
- The emitted `tags` output — must stay byte-identical.

## Safety mechanism (proposed)
- Sample golden EMPTY-diff + PAX generated diff stable vs the frozen Item 10 baseline. Any emitted
  tag change = stop-and-investigate.
- No parity flag expected (compile-time relocation); compiler + tests + golden are the net.
- Add/keep a focused test asserting `tagsFor` returns the same set after the service split.
- Size guard (Item 10): no change expected.

## Suggested sequencing
Part 1 (declutter) → Part 2 (split) — they compose; removing the pass-through first makes the split
cleaner. Part 3 only if it proves clean empty-diff, else its own slice.

## Open questions for Codex (resolve during review)
1. Confirm `workspacePlan.tagPlan` has no reader besides `tagsByKey()` before deleting the field.
2. Decide `workspacePlan`'s grouping in the split (read-only vs mutable side).
3. Confirm Part 3's enum serialization is byte-identical JSON, or split it out.
4. Confirm there's no hidden consumer that depends on the tag plan being embedded in the plan file
   (vs its standalone `target-tag-plan.json`).

## Acceptance criteria (draft)
- Tag plan no longer routed through `WorkspacePlanBuilder`/`ComputeWorkspacePlanTask`; single
  on-disk home (`target-tag-plan.json`).
- `WorkspacePlanService` split so the read-only tag index and the mutable render plan are separate;
  the consumer-first render-plan mutation is isolated and documented.
- (If Part 3 done) `TargetTagKey` typed; JSON proven byte-identical.
- Sample golden empty-diff; PAX generated diff stable; PAX migrate + both APKs green; size guard
  no-increase; `tagsFor` parity test green.
- Accepted untracked/live-model coupling untouched and still documented.

## Out of scope / Non-goal
- Making the tag-plan task cacheable / serializing variant topology (accepted untracked coupling).
- Changing the collector algorithm or emitted tags.
- Removing the render-plan consumer-first mutation (only isolating it).
