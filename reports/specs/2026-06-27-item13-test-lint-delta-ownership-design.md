# Item 13 — Test/androidTest Delta-Ownership (Design)

> **Status:** Approved 2026-06-27 (adversarial-gated; deliberately narrowed).
> **Executor:** Codex. **Behaviour change: YES — the single intended output change.**
> Output-changing, dual-run diff-classified, then monotonically re-baselined only after an
> accepted reduction.
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`. **Depends on:** Item 12 (planner extracted),
> Item 10 (size guard).

> **⚠️ Execution note — delegate to subagents; protect the main context.**

---

## Goal

Reduce pin bloat by making **test/androidTest buckets own only their resolved-identity
deltas** — the deps declared (or transitively required) for test/androidTest that the
inherited **main** bucket does not already provide. This targets the verified bloat source
in `android_test_maven`/`test_maven`. It is the **one intended output change** of the
altitude-layering pass.

Lint delta ownership is out of scope. Lint repos must exact-match the baseline in this item.
A future lint item needs its own tested main-inheritance model.

## Scope (adversarial-gate result — deliberately narrowed)

The adversarial gate established two things that scope this item:
1. **Declared-dep placement is ALREADY declaration-driven** (status quo:
   `collectDeclaredMainDependenciesByProjectBucket` buckets by `declaration.bucketName`;
   resolution always wins version). No change there.
2. **Follow-the-roots for MAIN undeclared transitives is wash-to-RISK** — it can scatter a
   shared transitive that today's set-math consolidates into `default` across several
   narrower buckets, *increasing* total roots and tripping the never-increase guard. So
   **MAIN undeclared-transitive placement KEEPS the current set-math** (`intersectByBucketOwner`
   consolidate-to-default) — fewer roots, correctness-neutral (deps-lists gate usage; closure
   stays complete for version-forcing).

**In scope:** test/androidTest delta-ownership.
**Out of scope:** lint delta ownership; any change to main undeclared-transitive placement
(kept as set-math).

## Mechanism

Extend the existing test/main subtraction already present in the planner:
- `withoutTestDependenciesCoveredBy` (`AggregatedDependencyResolver.kt:1310`) and
  `canCoverInheritedTestRoot` (`:1378`) already drop test deps that main covers. Generalize
  this so a test/androidTest bucket carries **only** deps not covered by the main
  bucket(s) it inherits from (per `BucketHierarchyGraph.canExtendFrom`, Test/AndroidTest
  extend AndroidBuild/JvmBuild), with shared deps referencing the main repo's label.
- Inheritance source (which main bucket a test bucket inherits) is well-defined by the
  variant graph (`testBucketExtendsFrom` adds `baseBucketName`).

## The load-bearing correctness guard (do NOT regress)

**A test dep that genuinely resolves to a DIFFERENT version than main must keep its own
copy.** The existing version-equality guard (`canCoverInheritedTestRoot` checks
`this.dependency.version == dependency.version`) is what prevents under-collection. Delta
subtraction must drop a test dep ONLY when main provides the **same resolved identity**
(version + excludes + jetifier/target-label metadata). Dropping a version-divergent dep
would make the test target reference main's version → missing/incompatible class → build
break. Preserve this guard when generalizing; add a regression test for it.

## Transition safety (dual-run classification, multi-flavor)

- **Flag-gated dual-run diff classification** (`-Pgrazel.internal.parity=delta`): keep the
  pre-Item-13 placement; run both and diff. Classify every difference as
  intended-test/androidTest-reduction or regression. Lint differences are regressions in
  this item.
- **Run dual-run classification + the size guard on a representative MULTI-FLAVOR
  application** (PAX), not just the sample modules — single-variant samples hide placement
  effects.
- Remove the old path once diffs are all classified as intended reductions, the size guard
  passes, and PAX builds pass.
- If using any common-ancestor query for test placement, pin selection to
  `closestCommonAncestorsOf(...).first()` under the deterministic `bucketHierarchyNodeComparator`
  (set-valued; never assume a singular LCA).

## Validation (oracle is NOT empty-diff — this is the intended change)

- **PAX builds** (migrate + both APKs) — primary correctness proof; test/androidTest APK
  especially, since this changes test buckets.
- **Size guard (Item 10):** total roots / pinfile count / bucket count must **not increase**;
  the expectation is a **reduction** in test/androidTest pin size. If there is no reduction,
  stop and decide whether the item is a no-op rather than rebasing noise. An *increase*
  fails Item 13 with no internal waiver; a correctness-required increase means this delta
  approach is wrong and the item is abandoned or redesigned.
- **Scope-aware diff classification:** non-test/non-androidTest repos exact-match. Lint repos
  exact-match. Every changed scoped repo is documented as a delta reduction and must be
  non-increasing.
- **Regression tests:** (a) test bucket carries only deltas, not re-owning a main dep;
  (b) a test dep that resolves to a different version than main KEEPS its own copy (the
  version-equality guard).
- **Monotonic baseline update:** after an accepted reduction, lower the machine-readable PAX
  size baseline to the new verified counts. Do not loosen it. The re-baseline commit records
  old -> new counts and the classified diff justifying each reduction.

## Acceptance criteria

- Test/androidTest buckets own only their deltas; main placement unchanged (set-math). Lint
  exact-matches baseline.
- Version-equality guard preserved + regression-tested (no under-collection).
- PAX builds green; size guard shows no increase (expected reduction); diffs classified;
  dual-run classification green on a multi-flavor app; delta parity mode + old path removed
  after confirmation.

## Out of scope / Non-goal

- **Main undeclared-transitive follow-the-roots** (kept as set-math; the gate showed it's
  wash-to-risk). If ever revisited, it is a separate dual-run classified experiment kept
  ONLY on measured reduction.
- Slimming `ComputeWorkspaceDependencies` (Item 14); variant compression; `--force-version`;
  dropping closure.
