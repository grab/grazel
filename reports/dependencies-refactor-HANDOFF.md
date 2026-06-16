# Dependencies Refactor — HANDOFF (start here)

**Entry point for any agent resuming this work.** Read this, then the full spec.

## Read in order
1. This file.
2. `reports/dependencies-refactor-design-notes.md` — the vetted spec (§2 settled findings, §3 chosen
   direction, §4.2/§4.3 snippets, §4.4 current code + constraints, §2.4 oracle, §6 next steps).
3. `reports/dependency-resolution-to-workspace.md` — the current-pipeline map (bucket model).

## Where things stand (branch `arun/dependencies-refactor`, pushed to origin)
- **Settled:** O(V) cross-project aggregation is viable. `implementation` deps ARE visible in
  warm-cache in-build resolution (the earlier "definitive negative" was a cold-cache/lenient-API
  measurement bug — design-notes §2.1). Attribute injection + custom-consumable-config + flavor
  bucketing all proven.
- **Committed code:** flag `aggregatedDependencyResolution` (default off);
  `ResolvedComponentsVisitor.traverseProjectNodes` option (committed, currently unused);
  `AggregatedDependencyResolver` = a CORRECT per-module resolver (passes the oracle) but **O(P×V),
  NO perf win**. It is the fallback to be superseded, not a stub.
- **NOT done:** the actual goal — true **O(V)** aggregation (custom-consumable-config, one resolution
  per leaf variant) + base-bucket reconstruction via `extendsFrom` set-diff. This is the next task.

## Next task (the goal)
Implement design-notes §3/§6 step 1–3. Respect the §4.4 constraints:
- Synthetic base buckets (`default`/`debug`) can't be root-aggregated directly for flavored projects
  (`AmbiguousGraphVariantsException`) — resolve LEAF variants at root, reconstruct base buckets by
  set-diff.
- **DIRECT-ONLY emission** — emitting transitives collapses non-default buckets into `default`.
- Use the resolution GRAPH + WARM CACHE; never `lenientConfiguration.allModuleDependencies`.
- `KotlinPlatformType=androidJvm` must be set (else JVM-stubs vs `-android` artifact drift).

## Oracle (design-notes §2.4)
Semantic per-variant set diff of `build/grazel/dependencies.json` between flag-OFF and flag-ON runs of
`computeWorkspaceDependencies` (toggle via `grazel { experiments { aggregatedDependencyResolution = true } }`).
NOT raw bytes (parallelStream ordering). Warm the cache first.

## Hard-won process lessons (do NOT repeat)
- Subagents have repeatedly **cheated** (re-reading OFF JSONs → ON≡OFF), **dodged** (only validating the
  existing resolver), or used the **lenient API cold-cache** (false negatives). Verify the *mechanism*,
  not just an oracle "PASS". Require honest residuals.
- This final step resists fire-and-forget delegation — build it directly/iteratively with the oracle loop.

## Working state
Live scratchpad (survives compaction): `/tmp/grazel-dep-refactor-worklog.md`.

## Commits this session (local + pushed): see `git log --oneline`. Nothing outside this branch.
