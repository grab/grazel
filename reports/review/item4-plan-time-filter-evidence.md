# Item 4: Plan-time root-filter evidence (MAIN_HIERARCHY drop counts, parity, pin coupling, config baseline)

Instrumentation: temporary `GRAZEL-ITEM4` logging (Task 1), applied to three
files, run against the samples project and a full PAX migrate, then reverted
(Step 6 below):

- `AggregatedDependencyResolver.kt` (`resolve()`): `DROP kind=... project=...
  bucket=...` for every `MAIN_HIERARCHY` root the execution-time filter
  (`MainReachabilityTracker.shouldResolveMainHierarchyRoot`) rejects, plus a
  `TOTALS planned=... kept=...` summary per resolve call.
- `WorkspaceDependencyRootInputPlanner.kt` (`planBinaryProjectRoots`): a
  parity probe, log-only (no filtering applied), computing the same
  "keep/drop" verdict from live variant data (`variant.isBase` /
  `mainLeafBuildTypeNames`) available at plan time — `PLAN keep|drop
  project=... bucket=...`.
- `WorkspaceDependencyInputsRegistrar.kt` (`register`): `PLAN-TIME <ms>ms
  roots=<n>` wrapping the `WorkspaceDependencyRootInputPlanner.plan(...)`
  call (configuration-phase cost baseline); `MATERIALIZED <configName>
  t=<nanoTime>` wrapping each root's `resolutionResult.rootComponent`
  provider (resolution-materialization timestamps); `PIN config=...
  meta=<projectPath>:<kind>:<bucketName>` for every root registered into
  `pinMavenArtifactsTask`.

## Samples

Command: `./gradlew migrateToBazel --console=plain --continue` (see
"Environment note" below for why `--continue` was needed) against this
repo's own sample modules (`sample-android`, `sample-android-tests`,
`sample-android-library`, `sample-kotlin-library`, `flavors/*`). Full log:
`item4-samples.log` (16 KB, scratchpad).

- `GRAZEL-ITEM4 PLAN`: 4 lines, all `keep` — `:sample-android` (`debug`,
  `default`), `:sample-android-tests` (`debug`, `default`). Zero drops.
- `GRAZEL-ITEM4 PLAN-TIME`: `6ms roots=56` (also observed `8ms` and `15ms`
  on separate runs of the same graph — configuration-phase noise, not
  resolution work).
- `GRAZEL-ITEM4 TOTALS`: `planned=56 kept=56`. Zero `DROP` lines.
- `GRAZEL-ITEM4 PIN`: 61 lines (one per root wired into
  `pinMavenArtifactsTask`, spanning `MAIN_HIERARCHY`/`TEST_HIERARCHY`/
  `MAIN_LEAF`/`UNIT_TEST`/`ANDROID_TEST`/`LINT` roots across all
  sample/flavor projects), consistent with `PLAN-TIME roots=56` plus 5 extra
  `LINT` roots not covered by `planBinaryProjectRoots`'s bucket-name
  reporting.
- `GRAZEL-ITEM4 MATERIALIZED`: fired for every resolved configuration.

Sanity result: on this small local project the resolver-side filter never
rejects anything (`kept == planned`), and the plan-time parity probe agrees
(`PLAN drop` count = 0 = `DROP` count = 0). `git status --porcelain` after
this run showed only the three instrumented files (plus the untracked
`scratchpad/`), confirming no generated output was touched by the probes.

## PAX

Command: `./gradlew migrateToBazel --no-daemon --console=plain --continue
--rerun-tasks` in `/Users/arun.sampathkumar/work/pax-android` (composite-
builds this repo's `grazel-gradle-plugin` via `grazelLocalEnv=true` in
`local.properties`, so the Task-1 instrumentation was live). Full log:
`item4-pax.log` (644 KB, scratchpad). PAX daemon status was verified idle
before starting (`./gradlew --status` → "No Gradle daemons are running"),
and no git write operations were performed against the PAX checkout at any
point — `git -C pax-android status --porcelain` was empty before and
remained empty after the run.

**Result:** `BUILD FAILED in 9m 38s` at `:generateBuildifierScript` ("Bazel
command failed" / `ERROR: Failed to initialize sandbox: getconf failed`,
log line 4397). **This failure is unrelated to the probes and downstream of
all evidence collection** — see "Environment note" below. All four probes
fired completely across the whole task graph (`git -C pax-android
status --porcelain` stayed empty; `2379 actionable tasks: 2379 executed`
before the failure was reported).

**Counts** (verified directly against `item4-pax.log`):

- `GRAZEL-ITEM4 TOTALS`: **`planned=2451 kept=2451`** (line 8312) — zero
  resolver-side drops.
- `GRAZEL-ITEM4 DROP`: **0** occurrences.
- `GRAZEL-ITEM4 PLAN drop`: **0** occurrences.
- `GRAZEL-ITEM4 PLAN keep`: **27** occurrences (27 `MAIN_HIERARCHY`
  candidate buckets across every migratable app/`com.android.test` project
  in PAX, all kept).
- `GRAZEL-ITEM4 PLAN-TIME`: **`1584ms roots=2451`** (line 1010).
- `GRAZEL-ITEM4 MATERIALIZED`: **2451** occurrences (one per resolved root
  configuration, matching `roots=2451`).
- `GRAZEL-ITEM4 PIN`: **2451** occurrences (one per root registered into
  `pinMavenArtifactsTask`, matching `roots=2451`).

### Environment note: the buildifier failure

`:generateBuildifierScript` invokes `bazelisk run
@grab_bazel_common//:buildifier` (`GenerateBuildifierScriptTask`), which
fails in this execution environment with `ERROR: Failed to initialize
sandbox: getconf failed` — reproduced identically running `bazelisk run
@grab_bazel_common//:buildifier` directly against this repo (unrelated to
PAX, unrelated to any Task-1 change) and unaffected by disabling the agent's
own bash sandbox. This is a pre-existing, local sandboxing incompatibility
between Bazel's own sandbox-exec use and this execution host, not a
regression introduced by the instrumentation or by the underlying resolver
code. `generateBuildifierScript` has no dependency relationship forcing it
to run after `resolveWorkspaceDependenciesTask`/`computeWorkspaceDependencies`
(they are independent branches of the `migrateToBazel` task graph feeding a
common downstream consumer), so `--continue` was added to both the samples
and PAX commands (deviating from the brief's exact invocation) purely so
that the independent branch containing every `GRAZEL-ITEM4` probe still
executes to completion despite the unrelated buildifier failure. Without
`--continue`, Gradle's default scheduler happened to run
`generateBuildifierScript` before the resolve branch and aborted the whole
graph on failure, producing **zero** probe output — this was confirmed
empirically on the samples project (see Step 2 log history) before
`--continue` was added for both runs.

## Parity diff

Every `PLAN drop` must pair 1:1 with a `DROP` for the same project+bucket,
and vice versa.

- Samples: `PLAN drop` = 0, `DROP` = 0. Trivial pairing, no mismatches.
- PAX: `PLAN drop` = 0, `DROP` = 0. Trivial pairing, no mismatches.

**`parityMismatches`: empty list `[]`** in both runs — the plan-time live-
variant verdict (`variant.isBase || variant.name in mainLeafBuildTypeNames`)
and the resolve-time JSON-metadata verdict
(`MainReachabilityTracker.shouldResolveMainHierarchyRoot`) agree on every
one of the 27 (PAX) + 4 (samples) `MAIN_HIERARCHY` candidate buckets
observed. Caveat: because the dropped set is empty in both runs, this is a
**parity-on-agreement** result only — the "both sides drop the *same* root"
half of the claim is exercised over zero cases in this data. The "both
sides never disagree about keeping" half (which is what's actually tested,
27+4 = 31 keep verdicts, 0 disagreements) does hold.

## Pin coupling

`GRAZEL-ITEM4 PIN` lines: 61 (samples), 2451 (PAX) — one per root wired into
`pinMavenArtifactsTask.localMavenResolutionRootConfigurations`, covering
every `WorkspaceDependencyRootInput` the planner produced (`MAIN_HIERARCHY`,
`TEST_HIERARCHY`, `MAIN_LEAF`, `UNIT_TEST`, `ANDROID_TEST`, `LINT`), i.e.
the **full, unfiltered** planner output, not the resolver-filtered subset.

`PIN` set ∩ `DROP` set: since `DROP` is empty in both runs, the intersection
is **trivially empty** — no dropped root's configuration was ever also
consumed by `pinMavenArtifactsTask`. This is an *empirical* result of the
filter never firing here, not a structural guarantee: `pinMavenArtifactsTask`
is wired directly off the planner's full `rootInputs` list (registrar code,
before any resolver-side filtering), so if the resolver-side filter ever
does drop a `MAIN_HIERARCHY` root in some other repo, that root's
configuration would still be registered with `pinMavenArtifactsTask` today
— the coupling is real, just unobserved in this data because nothing was
dropped to couple against.

**`pinIntersectionEmpty`: true** (trivially, on the observed data — see
caveat above).

## Config-phase baseline

`GRAZEL-ITEM4 PLAN-TIME`:

- Samples: `6ms roots=56` (range 6–15ms across repeated runs).
- PAX: **`1584ms roots=2451`** — the full `WorkspaceDependencyRootInputPlanner.plan(...)`
  call across all ~490 PAX modules, measured with `System.nanoTime()`
  immediately around the call inside the `projectsEvaluated` callback.

**`planDurationMsBaseline`: 1584** (PAX). This is a pure configuration-phase
cost (variant/root enumeration), not resolution — it does not include any
Gradle dependency resolution work, which happens later in
`resolveWorkspaceDependenciesTask`'s task action.

## Estimated seconds saved

The resolver-level filter (`MainReachabilityTracker.shouldResolveMainHierarchyRoot`)
rejected **zero** `MAIN_HIERARCHY` roots in both the samples run and the
full PAX migrate (`TOTALS kept == planned` in both cases; 2451/2451 on
PAX). With no dropped roots to attribute a `MATERIALIZED` timestamp delta
to, there is **no resolution work being skipped by this filter on either
dataset measured** — the estimate is not "small" but **zero**, backed
directly by the `DROP`/`TOTALS` counts rather than inferred from ambiguous
timestamp attribution.

**`estimatedSecondsSaved`: 0** (exact, not a range — derived from
`droppedRootCount = 0`, not from timestamp-delta inference, since there are
no dropped roots for any timestamp delta to attach to).

## Verdict inputs

- `droppedRootCount`: **0** (PAX: `planned=2451 kept=2451`; samples:
  `planned=56 kept=56`)
- `estimatedSecondsSaved`: **0** (no dropped roots in either dataset; see
  "Estimated seconds saved" above)
- `parityMismatches`: **`[]`** (empty — 0 `PLAN drop` vs 0 `DROP` in both
  runs; 31 total keep-verdicts across both runs, 0 disagreements)
- `pinIntersectionEmpty`: **true** (trivially, on the observed data — see
  "Pin coupling" caveat: the coupling is structurally real, just unobserved
  here because nothing was dropped)
- `planDurationMsBaseline`: **1584** (PAX; `WorkspaceDependencyRootInputPlanner.plan(...)`
  wall time for ~2451 roots across ~490 modules)

## Step 6: instrumentation reverted

All three instrumented files (`AggregatedDependencyResolver.kt`,
`WorkspaceDependencyRootInputPlanner.kt`,
`WorkspaceDependencyInputsRegistrar.kt`) were reverted via `git checkout --`
after this evidence was extracted; `git grep -n "GRAZEL-ITEM4" --
grazel-gradle-plugin/src` returns empty.

## GATE 0 advisory (Fable) and ruling

**Why zero drops was near-structural, not a corpus accident:** the planner's MAIN_HIERARCHY
emission and the filter's allow-set derive from the *same* filtered variant model in the same
run (`VariantBuilder.onVariants` constructs `AndroidBuildType` variants only from surviving
migratable variants; the metadata JSON is produced from the same `variantsByProject` map).
In the common case every BuildType-backed hierarchy root's name is, by construction, some
surviving leaf's buildType — the filter cannot fire.

**The filter is NOT dead code.** A firing shape is constructible: a *flavored* project plus a
grazel-DSL `variantFilter` that ignores a build type's application variants but not its test
variants (e.g. exact-name matches like `freeRelease` that miss `freeReleaseUnitTest`). The
surviving unit-test variant manufactures an AndroidBuild-typed `AndroidBuildType` for that
build type (`VariantBuilder.kt:184-198` emits across variant types), the planner emits a
MAIN_HIERARCHY root for it, and the JSON leaf set contains no such leaf — the filter fires,
correctly. Our corpora simply lack this shape. Secondary hazard noted: any live-variant vs
JSON divergence would make the filter drop silently (under-resolution) — a drop is always a
signal a human should see.

**Ceiling note:** even where the filter fires, `pinMavenArtifactsTask` receives the dropped
root's configuration regardless, so the hoist could never have eliminated those resolutions
for the pin path.

**Ruling (user, GATE 0):** CLOSED-NOT-WORTH-IT. Phases 1-2 not built. Filter retained as-is;
no code annotations (implementation-temporal); this document is the durable record.
