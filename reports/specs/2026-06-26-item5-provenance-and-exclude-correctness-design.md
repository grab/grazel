# Item 5 — Provenance + Exclude Correctness (Design)

> **Status:** Approved 2026-06-26. Fifth spec in the dependency-refactor spec set.
> **Executor:** Codex. **Behaviour change: YES — this is the single output-changing item.**
> It re-baselines the goldens.
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Depends on:** Item 4 (feedback paths gone; consumers read the plan; old derivation
> code and parity scaffolding removed).

> **⚠️ Execution note — delegate to subagents; protect the main context.** Diff
> enumeration/classification and PAX runs go to focused subagents returning distilled
> results. Keep the orchestrating context for sequencing, decisions, and verification.

---

## Goal

Replace `shortId`-only grouping / global collapse with **effective-identity grouping +
owning-variant provenance**, and replace the exclude **union** with **within-repo
intersection**. Fixes the two correctness gaps Codex flagged: (1) non-default
`maven_install` closures rehydrating via global `shortId` selection can take another
variant's version/exclude shape; (2) unioned excludes become over-restrictive (missing
transitive → compile failure).

## Semantic model (the corrected understanding)

- A Gradle exclude is per-edge; a transitive **survives if any path keeps it**, so the
  effective cross-path exclusion is an **intersection**, never a union. The current union
  is strictly more aggressive than Gradle → over-restriction.
- **Across repos (buckets):** excludes are part of identity/ownership — a coordinate may
  have different shapes in `@maven` vs `@debug_maven`, each from its owning variant. No
  merging across repos (provenance).
- **Within one repo, where a coordinate is unavoidably shared** by multiple owners:
  reconcile excludes by **intersection** (drop only what all exclude); version by Coursier.
  Intersection always yields a valid permissive set, so there is **no hard-fail/split**
  path. Residual risk (a permissively-included transitive causing a duplicate-class error)
  is caught by the PAX build.

## Changes (all flow from "group by effective identity, not shortId")

1. **Effective-identity grouping.** Group/dedup artifacts by
   `(shortId + version + excludeRules + jetifier)` per repo, not `shortId` alone.
   Consistent with the existing `hasSameBucketOwnerAs` / `hasSameEffectiveIdentityAs`
   predicates (`ResolveDependenciesResult.kt:121-138`).
2. **Provenance / variant-scoped selection.** Retire the global collapse:
   `selectedArtifactByShortId` + `mergeSelected` (`MavenInstallRootArtifacts.kt:148-176`)
   and the `shortId`-only `globalTransitiveClasspath` union
   (`ComputeWorkspaceDependencies.kt:212-219`). Non-default repo closures rehydrate from
   the **owning variant's** resolved identity — Item 2's `variantArtifacts` becomes the
   **active** source; the derived global-collapse view is removed.
3. **Within-repo exclude intersection.** Replace the union at
   `AggregatedDependencyResolver.kt:1003-1004`, `ResolveDependenciesResult.kt:116`, and
   `maxVersionReducer` (`ComputeWorkspaceDependencies.kt:226-234`) with intersection of the
   owners' exclude sets for a coordinate shared within one repo.

## Granularity — two sub-steps, each re-baselined + diff-explained

Split so each diff is attributable to one cause:

- **5a — exclude intersection.** Union → intersection at the three merge sites. Smaller,
  attributable diff: *strictly less over-exclusion*. Likely resolves the PAX paths the old
  union masked. Re-baseline + classify diffs + commit.
- **5b — provenance / variant-scoped selection.** Retire the global collapse; rehydrate
  from owning variant. Diff: buckets use the owning variant's version/shape instead of a
  global winner. Re-baseline + classify diffs + commit.

## Validation (the oracle is NOT empty-diff for this item)

- **PAX acceptance must pass** — `migrateToBazel` + `//app:app-gps-pax-debug.apk` +
  `//app:app-gps-pax-debug-android-test.apk`. This is the primary proof the new output is
  *correct*, not merely different (paths the old union over-excluded now build).
- **Diff-by-diff classification.** Enumerate every change from the prior baseline (sample
  committed outputs + PAX bounded audit) and classify each as a known-correct improvement
  (e.g. "`X` no longer over-excludes `Z`"; "`Y` in `@debug_maven` now uses the debug
  variant's version"). Per the Global-Constraint oracle: every diff documented, not
  eliminated. An *unexplained* diff is a stop-and-investigate, not an accept.
- **New regression tests:**
  - two variants declaring different excludes on a shared coordinate → assert the emitted
    excludes are the **intersection**, not the union;
  - a non-default bucket whose transitive must take the **owning variant's** version, not a
    global winner.
- **Re-record the goldens** (sample committed outputs + PAX bounded-audit record) as the
  *new* baseline; commit with the documented diff rationale. Item 6 resumes the empty-diff
  guardrail against this new baseline.

## Acceptance criteria

- Global `shortId` collapse and `shortId`-only transitive union are gone; selection is
  effective-identity + owning-variant scoped.
- Excludes are intersected (never unioned); regression tests pin both correctness fixes.
- PAX acceptance green; every diff from the prior baseline documented and classified.
- New sample + PAX goldens committed as the baseline for Item 6.

## Out of scope

- `resolve()` extraction, simplify-pass, reports cleanup, full broad verification — Item 6.

## Non-goal

Variant compression untouched (see Item 2 non-goal).
