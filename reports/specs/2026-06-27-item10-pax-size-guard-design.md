# Item 10 — Frozen PAX Baseline + Automated Size Guard (Design)

> **Status:** Approved 2026-06-27. First executable item of the altitude-layering pass.
> **Executor:** Codex. **Behaviour change:** none (adds tooling + records a baseline; no
> grazel production code or generated-output change).
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `reports/specs/ALTITUDE-LAYERING-ROADMAP.md`.

> **⚠️ Execution note — delegate to subagents; protect the main context.** PAX measurement
> goes to a focused subagent returning only the counts.

---

## Goal

Freeze the current verified PAX generated output as the accepted size baseline, and add an
automated guard so every later altitude-layering item (11–15) is regression-checked: the
size totals must **never increase** from this baseline; reductions are a win. This is the
net that lets Item 13's output-changing rewrite proceed safely.

## Why first

Items 11–15 each need a regression check. In particular Item 13 (declaration-driven
ownership) is the one *intended* output change — it should *reduce* buckets, and this guard
is how we prove it reduced rather than regressed.

## Metrics (TOTALS only — not per-bucket)

Guard three totals against the frozen baseline:
1. **Bucket count** — number of `maven_install(name = …)` repos in the PAX `WORKSPACE`.
2. **Pinfile count** — number of generated `*_install.json` pin files for PAX.
3. **Total artifact roots** — sum of `artifacts` entries across all materialized
   `maven_install` repos.

**Per-bucket counts are NOT gated** — only reported. Rationale: Item 13 deliberately
**redistributes** (a shared dep moves into a common-owner bucket, growing it, to reduce the
total). A per-bucket ceiling would block exactly that intended improvement — the
incompatibility the adversarial review flagged. Gate totals; report per-bucket deltas for
transparency.

## Mechanism

1. **Record the baseline.** Refresh `reports/specs/PAX-BOUNDED-AUDIT-BASELINE.md` (currently
   stale) with the CURRENT PAX working-tree counts: bucket count, pinfile count, total
   artifact roots (and a per-bucket breakdown as informational context). Measured from the
   current verified PAX migrated output at `~/work/pax-android`. This committed snapshot is
   the frozen baseline. (PAX generated files themselves are not committed to the grazel
   branch — the baseline records the numbers; the guard recomputes from a fresh PAX migrate.)
2. **Guard script.** Add `reports/scripts/verify-pax-size-guard.sh` (or formalize the
   existing audit script) that, after PAX `migrateToBazel`:
   - recomputes the three totals from the PAX generated `WORKSPACE` + `*_install.json`;
   - **hard-fails** if any total exceeds the baseline;
   - prints reductions explicitly as wins;
   - prints per-bucket deltas as informational (non-gating).
3. **Wiring.** Script lives in the grazel repo `reports/scripts/`, points at
   `~/work/pax-android`, and is run by Codex in the verification loop (consistent with the
   existing manual PAX gates). Not CI-wired (PAX is a separate repo; PAX builds are the
   primary correctness gate regardless).

## Validation

- The guard passes against the frozen baseline at creation (self-consistent: current == baseline).
- Grazel sample golden EMPTY-diff (Item 10 changes no grazel production code).
- PAX correctness gates (migrate + both APKs) remain primary and unchanged.

## Acceptance criteria

- `PAX-BOUNDED-AUDIT-BASELINE.md` refreshed with current bucket/pinfile/total-root counts +
  per-bucket breakdown, committed.
- `verify-pax-size-guard.sh` exists, recomputes the three totals, hard-fails on any increase,
  reports reductions and per-bucket deltas.
- Guard green on current PAX; sample golden empty-diff.

## Out of scope

- Per-bucket gating (reported only).
- Any grazel production code change.
- The reductions themselves (Item 13 produces them; Item 10 only measures/guards).

## Non-goal

Master as a target (it over-bucketed); `--force-version`; dropping closure.
