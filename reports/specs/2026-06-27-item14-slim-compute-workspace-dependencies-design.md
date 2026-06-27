# Item 14 — Slim `ComputeWorkspaceDependencies` to a Value-Holder (Design)

> **Status:** Approved 2026-06-27. **Executor:** Codex.
> **Behaviour change:** none — relocation of ownership/override logic out of CWD. Golden
> empty-diff + flag-gated parity.
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`. **Depends on:** Item 12 (planner), Item 13.

> **⚠️ Execution note — delegate to subagents; protect the main context.**

---

## Goal

Reduce `ComputeWorkspaceDependencies` (CWD) to its Layer-2 job — a **value-holder** — by
moving its ownership/override-synthesis responsibilities to the planning layer (Layer 3/4)
established in Items 12–13. CWD is a 328-line post-processor today; this makes "which layer
owns a bug" obvious without changing output.

## Decomposition (from grounding)

**STAYS in CWD (Layer-2 values):**
- `classPaths` group + `maxVersionReducer` (`ComputeWorkspaceDependencies.kt:39-57,224-232`) — version arbitration.
- `flattenClasspath` + `flattenedClasspathReducer` (`:82-105,234-262`) — transitive-as-direct flattening for `maven_install`.
- `transitiveClasspath` / `globalTransitiveClasspath` / `variantTransitiveClasspath` / `reachableMainBucketsByProject` (`:153-217`) — closure + reachability indices.
- KSP aggregation (`:162-170`).

**MOVES out of CWD:**
- `reducedClasspath` dedup-vs-default (`containsDefaultOwnerEquivalent`, `:62-78,264-274`) →
  **Layer 3 `BucketOwnershipPlanner`** (it's an ownership decision: "is this already owned by default?").
- `reducedFinalClasspath` + `OverrideTarget` synthesis (`:114-150,276-312`) →
  **Layer 4 `WorkspaceRenderPlan` builder** (override targets are a plan/render concern; the
  render plan already does override-target closure).
- The `hasSame*Owner*` / `isDeclared*` predicates (`:300-327`) → move with their consumers
  (Layer 3).

## Constraint

This is a **pure relocation** — same computations, moved to the layer that owns them. No
algorithm change. The override-target *values* emitted must be byte-identical (override
labels are output-affecting). If a move would change an override label or a dedup decision,
that's a bug in the move, not an intended change.

Care point: `variantTransitiveClasspath` is computed from the reduced classpath after
default-bucket duplicate collapse. Moving duplicate collapse out of CWD is allowed only if
the new owner emits the exact same reduced shape back to CWD or to the downstream plan. If
target transitive-tag facts change, this item is no longer preserving and must stop.

## Safety mechanism

- **Sample golden EMPTY-diff.**
- **Flag-gated parity** (`-Pgrazel.internal.parity=cwd`): keep the old CWD path; run both and
  assert identical `WorkspaceDependencies` (and identical override targets / materialized
  repos / variant transitive classpath facts downstream); remove this parity mode and the old
  path after parity green on PAX + sample.
- **Size guard (Item 10):** no increase (expected: no change).

Item 14's empty-diff and size guard are measured against the post-Item-13 re-baselined PAX
state, not the original frozen baseline. Re-read the baseline numbers after Item 13.

## Acceptance criteria

- CWD retains only value-holder responsibilities (flatten / max-version / transitive /
  reachability / KSP); dedup-vs-default and override-synthesis live in Layer 3/4.
- Sample golden empty-diff; PAX parity green; size guard no-increase; PAX builds green.
- Old CWD path + CWD parity mode removed after parity confirmed.

## Out of scope / Non-goal

- Any output change (Item 13 was the only one); changing override-target semantics; variant
  compression.
