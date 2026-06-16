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

## STATUS (after 3 O(V) iterations — all fail; STOP blind iteration, INSTRUMENT next)
Three iterations of the custom-consumable O(V) approach all fail the oracle, and iteration 3
**regressed** (default bucket 19→0). Blind edit→oracle cycles are thrashing — guessing at Gradle
attribute-matching without seeing the actual resolution. **The next session must add instrumentation
FIRST** (log per variant: root config requested attrs; selected consumable variant per project dep
or the selection failure; resolved-graph node/child counts; whether grazelExportCompile<V> was
created + its outgoing deps) to learn WHY buckets come back empty — before any more code changes.
Full iteration log + hypotheses: `/tmp/grazel-dep-refactor-worklog.md`.

**KNOWN-GOOD FALLBACK:** the per-module resolver at commit `9c714fe` PASSES the oracle (correct
output, but O(P×V) — no perf win). If O(V) proves too costly to land, that is the shippable option
(behind the flag, honestly documented). The two failing leads: export-attr-only → shallow transitive
resolution; export+standard-attrs → variant selection breaks. Likely a real Android+JVM single-config
tension (may need per-ecosystem root configs). HEAD currently has iter2 (regressed) committed as WIP.

## (historical) Current attempt state (O(V) v1 — committed, FAILS oracle)
An O(V) custom-consumable-config aggregation is committed in `AggregatedDependencyResolver.kt`
(supersedes the per-module attempt-3). It **compiles and runs cleanly but FAILS the oracle**:
- ON yields only a `default` bucket (19 deps, JVM-project-only); OFF has default:163, debug:29,
  androidTest:5, lint:2. **Android projects' deps are not surfaced through the aggregation.**
- 4 version mismatches (ON lower) — `resolutionStrategy { force }` not applied to the root configs.

**Top next step (likely fix):** the consumable currently `extendsFrom grazel<V>CompileClasspath`
(a resolvable config); that indirection appears not to propagate Android modules' deps as outgoing.
Investigation B (design §4.3) instead extended the **declared scopes directly**
(`implementation`/`api`/`<buildType>Implementation`/`<flavor>Implementation`) and DID expose impl
deps — switch the consumable to that. Then apply force/substitution `resolutionStrategy` to the root
configs. Add instrumentation (per-variant participating-project count + selected consumable variant)
to pinpoint where Android projects drop out. Full diagnosis: `/tmp/grazel-dep-refactor-worklog.md`.

**To restore a WORKING (O(P×V)) ON path:** revert the resolver to commit `9c714fe`
(`git show 9c714fe:grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`).

## Working state
Live scratchpad (survives compaction): `/tmp/grazel-dep-refactor-worklog.md`.

## Commits this session (local + pushed): see `git log --oneline`. Nothing outside this branch.
