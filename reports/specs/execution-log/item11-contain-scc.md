# Item 11 Execution Log — SCC Diagnostic Containment

## Current Slice

- Active item: `reports/specs/2026-06-27-item11-contain-scc-design.md`
- Goal: eliminate false SCC handling from the normal path and keep SCC only as a typed-node
  diagnostic fallback for proven genuine cycles.
- Baseline before this slice: Item 9 committed at `d84f3db`
  (`Add typed dependency reachability graph`).
- Behavior expectation: preserving against the post-Item-9 generated baseline unless the
  typed-SCC audit proves a genuine previously hidden correctness issue.

## Decisions

- Start from Item 9's typed reachability model: `DependencyGraphNode(project, sourceSet)` and
  `DependencyGraphEdge` values.
- Treat the known PAX `deliveries-model-food:test -> food-testkit:main ->
  deliveries-model-food:main` shape as false until typed projection proves otherwise.
- Bucket ownership and Maven materialization remain out of scope for this item.

## Initial Verification Carried Forward

- Item 9 local and PAX gates are recorded in
  `reports/specs/execution-log/item9-typed-reachability.md`.
- PAX size guard at Item 9 baseline remains unchanged: 11 buckets, 11 pinfiles, 2015 total
  artifact roots.
- Resource note: free data-volume disk was about `21GiB` after the Item 9 PAX gates. Do not
  start new heavy PAX verification without deliberate cleanup.

## Next Steps

1. Add focused typed-SCC diagnostics/tests around the known false cycle shape.
2. Inspect current SCC/fixpoint call sites and determine whether fallback can be removed or
   must fail closed with typed diagnostics.
3. Keep generated output empty-diff locally before any PAX rerun.
