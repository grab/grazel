# Item 22 — Set-Math Ownership Reduction Experiment (Design)

> **Status:** Completed as Outcome B 2026-06-28: proven problem-essential; no Phase 2 reshape.
> **Executor:** Codex.
> **Behaviour change:** investigation-first; outcome is either an output-preserving reshape
> (golden empty-diff) **or** a documented "proven problem-essential" finding. Not an
> open-ended rewrite.
> **Global Constraints & Verification Playbook + Code-quality stance:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`. **Depends on:** Items 12 (planner extracted),
> 13 (delta ownership landed), 17 (set-math consolidated into `BucketSetMath.kt`).

> **⚠️ Execution note — delegate to subagents; protect the main context.** Spike, measurement,
> and PAX runs go to focused subagents returning distilled results.

---

## Why this item exists

The roadmap already anticipates it: *"Main undeclared-transitive placement keeps the current
set-math unless a separate future experiment proves a reduction."* This is that experiment,
written down. It is also the first concrete application of the Item 1 **code-quality stance**:
the set-subtraction ownership math is the single largest complexity source in the dependency
layer, and it is **model-essential** — irreducible *only* while ownership stays "resolve every
leaf classpath, then assign by set intersection/subtraction." The maintainer's instruction is
to be ambitious about removing complexity where the PAX baseline protects correctness. So we
test, with evidence, whether this complexity can go — rather than enshrine it on a "leave
alone" list by assertion.

## The set-math under test

In `DependencyBucketPlacementEngine` / `BucketSetMath` (post-Item-17 home):
- `intersectByBucketOwner` — "common across all selected leaves ⇒ default bucket."
- `withoutDependenciesCoveredBy` / `withoutDependenciesOwnedByNonDefaultHierarchy` — "already
  owned by an ancestor/default ⇒ don't repeat in the descendant."
- the `BucketPlacementGraph` ancestor/descendant coverage math that drives the above.

## The hypothesis — and the known counter-evidence

**Hypothesis (to falsify, not assume):** declaration-driven ownership — placing each dependency
in the bucket where it is *declared*, using the resolved graph only for versions/closure —
collapses most of the set-subtraction, because "which bucket owns it" comes from the
declaration, not from set membership.

**Known counter-evidence (from the prior grounding — do not ignore):** the roadmap's amendment
#3 already found "declarations drive structure" is **incomplete**. The set-math exists to handle
cases declarations cannot:
- **undeclared transitives common to multiple leaves** — no declared owner exists; "common ⇒
  default" is precisely what assigns them;
- **`compileOnly` / api-of-consumed-libs / conflict-resolved deps** — appear in the resolved
  closure but are declared elsewhere or nowhere on this path.

So the honest prior is that *some* of this math is problem-essential. The experiment's job is to
**quantify how much** is model-essential (removable via declarations) vs problem-essential
(must stay for undeclared/transitive/conflict cases).

## Phase 1 — Investigate & measure (no production change)

1. **Instrument the current placement** on PAX + sample: for every dependency the set-math
   places, record *why* it landed where it did — declared-in-this-bucket, common-across-leaves
   (undeclared), inherited-coverage-subtracted, conflict-resolved, compileOnly, etc. Produce a
   distribution: what fraction of placements are explainable by declaration alone vs require the
   set-math's "common ⇒ default" / "covered ⇒ subtract" reasoning.
2. **Count the special cases** the set-math carries (the complexity metric from the code-quality
   stance: branches, coverage predicates, fallbacks) and which of them fire on PAX at all. A
   predicate that never fires on PAX or sample is a reduction candidate regardless of the
   hypothesis.
3. **Shadow-plan parity check.** If the first two measurements suggest the model may reduce,
   build a non-production/shadow ownership planner that applies the proposed declaration-driven
   rule plus the candidate residual hybrid rule. It must emit a diff against the current
   planner for PAX + sample without changing production outputs.
4. **Decision gate (explicit, recorded):** use the rubric below. The default is to stop unless
   the evidence is strong enough to justify the reshape.

## Phase 1 exit rubric

Proceed to Phase 2 **only if every condition is true**:

1. **Classification is complete.** Every PAX/sample placement touched by the set-math has a
   known reason. No "unknown / could not classify" bucket remains.
2. **The simpler model has a named shape.** The candidate replacement can be described as
   "declaration-driven ownership + one named hybrid residual rule" without scattering new
   special cases across the resolver, planner, engine, or renderer.
3. **Shadow parity is exact or the non-parity is deliberately reclassified.** The shadow planner
   matches current PAX/sample placement exactly. If it does not, Phase 2 under this item is
   forbidden; either stop and document or open a new output-changing item with explicit diff
   classification.
4. **Complexity actually drops by the Item 1 yardstick.** The proposal removes at least one
   re-derivation round-trip, fallback, coverage predicate family, or multi-file reasoning path.
   A net-neutral or larger LOC change is acceptable only if it removes special-case/fan-out
   complexity.
5. **The residual is problem-essential and tested.** Any remaining undeclared-transitive,
   conflict-resolved, compileOnly, or api-of-consumed-libs behavior is represented by one
   named rule with focused tests and PAX examples.

Stop and go to Phase 3 if any condition fails. That is not a failure of the goal; it is a
valid proof that the current set-math is problem-essential for this verified PAX shape.

## Phase 2 — Reshape (only if Phase 1 green), output-preserving

If Phase 1 justifies it, replace the set-subtraction placement with the simpler model:
- declaration-driven ownership for declared deps;
- a single, named, tested rule for the residual undeclared/transitive/conflict cases (the
  "hybrid reconciliation" the roadmap requires be a NAMED, TESTED Layer-3 responsibility — not
  scattered predicates).
- **Prefer output-preserving:** the goal is the same PAX buckets with fewer special cases. Gate
  on **golden empty-diff + size-guard no-increase** — byte-for-byte proof per the code-quality
  stance (maximal ambition where the net is strongest). Add focused unit tests on the new
  placement rule (the golden does not cover internal-correctness regressions that render
  identically).
- If the simpler model **cannot** reproduce PAX output exactly, it is either wrong or it is an
  intended-diff change — in which case STOP, re-classify as an output-changing item with
  adversarial review + diff-by-diff classification, and do **not** proceed under the empty-diff
  contract.

Phase 2 exit criteria:

- sample golden empty-diff;
- PAX generated diff/status hashes stable against the frozen baseline;
- size guard no-increase;
- focused unit tests cover declaration-driven ownership and the named hybrid residual rule;
- removed predicates/special cases are enumerated in the execution log and final commit;
- no new renderer/target-builder feedback path is introduced.

## Phase 3 — Document as proven-essential (if Phase 1 says stop)

Promote the set-math from "leave alone (asserted)" to **"leave alone (proven problem-essential)"**:
- record the measured distribution showing what fraction requires the set-math and why;
- give the concrete PAX examples (the undeclared-transitive / conflict-resolved cases) that no
  declaration-driven rule can place;
- write the finding into `DO-NOT-REVISIT.md` so the question is closed with evidence, not
  re-litigated.

## Safety mechanism

- **Phase 1 is read-only / instrumentation** — no production behaviour change; remove any
  temporary instrumentation before completion.
- **Phase 2 (if reached):** sample golden empty-diff + PAX generated diff stable vs the frozen
  Item 10 baseline; size-guard no-increase; new unit tests on the reshaped rule; existing
  engine/planner tests green. Any output diff = stop-and-reclassify.
- **No silent scope creep:** this item does not change variant compression, rendering, or the
  test/androidTest delta model (Item 13).

## Acceptance criteria (either outcome completes the item)

- **Outcome A (reduced):** placement reshaped to declaration-driven + one named hybrid rule;
  golden empty-diff; PAX stable; size guard no-increase; focused tests added; the removed
  special cases enumerated in the commit.
- **Outcome B (proven essential):** measured evidence recorded; the irreducible cases documented
  with PAX examples in `DO-NOT-REVISIT.md`; the set-math left intact and re-labelled
  problem-essential. No code change beyond removing instrumentation.
- In both outcomes: the decision gate (Phase 1 step 3) and its rationale are written down.

## Out of scope / Non-goal

- The test/androidTest delta-ownership model (Item 13) — settled.
- Target-reference model / cacheability (Items 19–20).
- Dead-code/duplication/indirection (Item 21) and set-math *consolidation* (Item 17) — this item
  assumes those are done and operates on the consolidated `BucketSetMath`.
- Any "simplify by deleting a predicate that actually fires on PAX" without the Phase 1 evidence
  and the empty-diff gate.
