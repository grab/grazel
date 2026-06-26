# Item 7 — Pin-Size Reduction via Bucket Ownership (Design)

> **Status:** Approved 2026-06-26 (post adversarial feasibility review). Follow-up spec
> beyond the original 6-item set.
> **Executor:** Codex. **Behaviour change: YES — output-changing; re-baselines goldens.**
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Depends on:** Items 1–6 (planning seam, provenance, verified baseline in place).

> **⚠️ Execution note — delegate to subagents; protect the main context.** Size-guard
> measurement, diff classification, and PAX runs go to focused subagents returning
> distilled results.

---

## Goal

Reduce `maven_install` pin-file bloat by tightening bucket **ownership** in the existing,
already-variant-driven placement layer. The main bloat source is test/lint pinfiles and
shared deps duplicated across leaf buckets.

## Altitude note (read before scoping)

Bucket placement is **already driven by the Variant API** — `DependencyBucketPlacementEngine`
consumes `DependencyBucketVariant` (derived from `Variant.extendsFrom` / buildType / flavors)
and `BucketHierarchyGraph`. It is correctly variant-graph-driven; it simply **runs in
Layer 0** (inside `AggregatedDependencyResolver`, during `ResolveWorkspaceDependenciesTask`),
not in the Layer-1 planning layer.

An adversarial feasibility review established that **relocating placement to Layer 1 is NOT
behaviour-preserving**: bucketing is Layer 0's output contract (`ResolveDependenciesResult.variantName`
*is* the bucket name; `ComputeWorkspaceDependencies` hard-assumes a `default` bucket at
`ComputeWorkspaceDependencies.kt:62` and runs a default-subtraction pass; override-target
derivation and `reachableMainBucketsByProject` provenance are co-located with placement).
Relocation would be a ground-up reshape of `WorkspaceDependencies` + `ComputeWorkspaceDependencies`
with high silent-diff risk.

**Decision: do NOT relocate. Tighten in place.** Item 7 modifies the existing
`DependencyBucketPlacementEngine` (which is pure and unit-testable). The Layer-0 location is
recorded as **documented altitude debt**: placement is variant-API-driven but lives in
resolution; a Layer-0→Layer-1 relocation is a separate large reshape, explicitly deferred,
not part of this item. (Because we do not relocate, the override-target and provenance
co-location are left intact — no desync risk.)

## Oracle

- **Primary correctness:** DAG/declaration-consistent placement + PAX builds (migrate +
  `//app:app-gps-pax-debug.apk` + `//app:app-gps-pax-debug-android-test.apk`).
  **Master is NOT the correctness oracle — it over-allows.**
- **Size guard (master = upper bound, GLOBAL):**
  - total artifacts across all buckets ≤ master total; AND
  - bucket count ≤ master; AND
  - per-bucket ≤ master counterpart **only for ownership-unchanged buckets**.
  - (Per-bucket-≤-master globally was rejected: it is mathematically incompatible with the
    closure-completeness invariant under de-duplication — moving a shared dep + its closure
    into a common-owner bucket can grow that bucket past master even as the total shrinks.)

## Invariants

- Selected **versions** always from Gradle resolution; declaration is an **ownership key
  only** (`DeclaredExternalDependency.bucketName`: `implementation→default`,
  `debugImplementation→debug`, `androidTestImplementation→androidTest` —
  `DeclaredDependencyMetadataCollector.kt:314-533`).
- Every materialized repo keeps its **full Gradle-resolved closure** in
  `maven_install.artifacts`. Reduction comes from fewer direct **roots** per bucket
  (de-dup / deltas), never from dropping closure or adding `--force-version`.

## Sub-steps

### 7a — Focused tighten (output-changing; re-baselined)
In `DependencyBucketPlacementEngine`:
- **Test/lint buckets inherit main ownership and own only direct deltas** — a test bucket
  carries only deps declared in test scope that main does not already provide; shared deps
  reference the main repo's label.
- **Closest-common-owner de-duplication** — a shared direct dep lands once at its common
  ancestor in the variant hierarchy instead of being duplicated across leaf buckets.
- **Only repos with real direct-owned roots materialize** (candidate repos with no
  direct-owned root do not produce a `maven_install`).

### 7b — Full declaration-driven re-ownership (escalation; measured decision after 7a)
Only if 7a's reduction is judged insufficient against the size target: re-derive all
placement uniformly from declared configuration scope. Re-baselined separately.

## Required API amendment (from the adversarial review)

`BucketHierarchyGraph` is a **multi-parent DAG** (a leaf extends `{flavor, buildType}`), so a
single-node `closestCommonAncestor` is ambiguous or collapses to `default`. Implement it as
**set-valued** `commonAncestors(nodes): Set<BucketHierarchyNode>` plus a **documented
deterministic selection policy** (e.g. maximum depth, then a stable comparator tie-break),
reusing the existing `coversDescendantLeaves` / `descendantLeafNames` machinery
(`DependencyBucketPlacementEngine.kt:408-431`) rather than inventing a tree LCA. The
tie-break must be deterministic — it decides which bucket a shared dep lands in, hence pin
sizes and override targets.

## Validation

- **PAX builds** (migrate + both APKs) — primary correctness proof.
- **Automated GLOBAL size-guard check vs master** — total ≤ master total, bucket count ≤
  master, per-bucket ≤ master for unchanged buckets. A violation fails the item.
- **Diff-by-diff classification** — every change documented as a reduction/redistribution;
  an unexplained diff is stop-and-investigate.
- **Regression tests:**
  - shared dep owned at a common ancestor, not duplicated across leaves;
  - a test bucket carrying only deltas, not re-owning a main dep;
  - a materialized repo retaining its full closure in `artifacts`;
  - `commonAncestors` set-valued result + deterministic selection on a multi-flavor ×
    build-type DAG fixture.
- **Re-baseline goldens** (sample committed outputs + PAX bounded-audit record) after each
  sub-step; commit with documented diff rationale.

## Out of scope

- Layer-0→Layer-1 placement relocation (documented altitude debt; separate large reshape).
- Reachability single-pass (Item 8).
- Library/JVM-only roots; cacheability.

## Non-goal

Variant compression; `--force-version`; closure dropping.

## Review provenance

This spec was revised after a fresh adversarial Opus review against the codebase, which
BLOCKED the original "relocate first, empty-diff" framing and the per-bucket size guard, and
flagged the single-node LCA. All three amendments (no relocation / tighten in place; global
size guard; set-valued `commonAncestors`) are incorporated above.
