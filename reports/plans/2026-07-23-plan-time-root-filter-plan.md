# Plan-Time Root Filtering — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop paying execution-time Gradle resolution for MAIN_HIERARCHY roots that
`shouldResolveMainHierarchyRoot` refuses to walk — by filtering them at plan time
(configuration phase) — via three evidence-gated phases: measure, parity-shadow, switch.

**Architecture:** Phase 0 instruments (uncommitted) four probes and runs one PAX migrate;
GATE 0 escalates the worth-it call to the user (with a Fable advisory on the evidence).
Phase 1 stamps the planner's live-variant verdict into root metadata and `check`s it against
the resolver's JSON verdict — behavior-neutral, continuously proven. Phase 2 filters in
`WorkspaceDependencyRootInputPlanner.plan` (single source → all three consumers narrow
consistently, zip-by-index safe) and degrades the resolver filter to an assertion. Spec:
`reports/specs/2026-07-23-plan-time-root-filter-design.md`.

**Tech Stack:** Kotlin, Gradle plugin (configuration-phase planning in `projectsEvaluated`),
JUnit4, PAX composite build.

## Global Constraints

- **Byte-identity:** generated output must never move, in ANY phase. Gates per committed task:
  `./gradlew :grazel-gradle-plugin:test --console=plain` →
  `./gradlew verifyGrazelGoldenBaseline --console=plain` (`...generated-file diff are clean.`;
  only the documented appcompat/constraintlayout waiver) → `bazelisk build --nobuild //...`.
  Phases 1 and 2 additionally require the **full PAX sweep**
  (`reports/specs/VERIFICATION-GATES.md` §PAX 1–6). ANY output drift = stop, revert, report.
- **Phase-0 instrumentation is NEVER committed** — before any commit,
  `git grep -n "GRAZEL-ITEM4" -- grazel-gradle-plugin/src` must be empty.
- **Configuration-phase budget:** the phase-2 predicate must not measurably grow `plan()`
  duration vs the phase-0 baseline (spec §Phase-2 budget check). Regression = stop-and-investigate.
- One Gradle build at a time; bazelisk never concurrent with Gradle. PAX repo git-read-only
  (migrate overwriting generated files in place allowed).
- Stage explicit paths; never `git add -A`; never stage `codedb.snapshot`.
- Do not touch `bucket/`, the zip-by-index mechanism itself (backlog 10), LINT planning,
  `CollectTargetMavenRepoReferencesTask`.
- **Model policy (execution):** Sonnet/Haiku workers per task weight; Fable advisory at
  GATE 0; Opus adversarial final review.

## Decision authority

GATE 0 (after Task 1) is the **user's**: worth-it threshold from measured numbers, informed by
a Fable advisory the controller commissions on the evidence doc. GATE 1 (after Task 2) is
mechanical (controller): PAX sweep green + zero parity-check failures. Pin-consumer shape for
Task 3 is selected by Task 1's pin-probe evidence (empty intersection → drop-outright;
non-empty → mark-not-drop, and the controller re-confirms scope with the user before Task 3).

---

## File map

| File | Phase | Change |
|---|---|---|
| `.../gradle/dependencies/WorkspaceDependencyRootInputPlanner.kt:116-138` | 0: parity log · 1: verdict stamp · 2: live filter | the single filtering source |
| `.../tasks/internal/WorkspaceDependencyInputsRegistrar.kt:88-135` | 0 only: plan-timer + materialization timestamps + pin log (all reverted) | instrumentation |
| `.../gradle/dependencies/AggregatedDependencyRoot.kt:31-45` | 1: `plannedMainLeafBuildType: Boolean? = null` on `AggregatedDependencyRootMetadata` | stamp field |
| `.../gradle/dependencies/AggregatedDependencyResolver.kt:118-123` | 0: drop log · 1: parity `check` · 2: filter → assertion | consumer side |
| `.../gradle/dependencies/resolution/MainReachabilityTracker.kt:166-173` | 2: `shouldResolveMainHierarchyRoot` retained for the assertion | unchanged logic |
| `reports/review/item4-plan-time-filter-evidence.md` | 0 | **create** — committed evidence |
| `.../src/test/kotlin/.../dependencies/WorkspaceDependencyRootInputPlannerTest.kt` (or existing planner test file — locate first) | 1, 2 | verdict + filter unit tests |

Key API facts (verified): `Variant<*>.isWorkspaceMainHierarchyRoot = isBase || backingVariant is
BuildType`; `Variant<*>.isWorkspaceAndroidLeaf = backingVariant is BaseVariant`;
`Variant<*>.workspaceBuildTypeName = (backingVariant as? BaseVariant)?.buildType?.name`
(`gradle/variant/Variant.kt:125-134`). The resolver's JSON-side filter keeps a MAIN_HIERARCHY/
AndroidBuild root iff `bucket == DEFAULT_VARIANT || bucket in mainBuildTypeNamesByProject`
where that set = leaf (androidLeafVariant) AndroidBuild variants' `buildType` names
(`MainReachabilityTracker.kt:49-56,166-173`).

---

### Task 1 (Phase 0): Instrument, measure on PAX, evidence doc — NO code commit

**Files:**
- Modify (temporarily, ALL reverted): planner, registrar, resolver (see file map)
- Create + commit: `reports/review/item4-plan-time-filter-evidence.md`

**Interfaces:**
- Produces: the evidence doc with a `## Verdict inputs` section Task 2/3 and GATE 0 key off:
  `droppedRootCount`, `estimatedSecondsSaved`, `parityMismatches` (list, expect empty),
  `pinIntersectionEmpty` (bool), `planDurationMsBaseline`.

- [ ] **Step 1: Apply the four probes** (grep-able prefix `GRAZEL-ITEM4`; adapt mechanically to
  exact signatures — this patch is throwaway):

(a) Resolver drop-counter — in `AggregatedDependencyResolver.resolve()` where
`rootsToResolve` is computed:

```kotlin
            val rootsToResolve = workspaceDependencyRoots.filter { root ->
                val keep = mainReachabilityTracker.shouldResolveMainHierarchyRoot(root.metadata)
                if (!keep) {
                    logger.warn(
                        "GRAZEL-ITEM4 DROP kind=${root.metadata.kind} " +
                            "project=${root.metadata.projectPath} bucket=${root.metadata.bucketName}"
                    )
                }
                keep
            }
            logger.warn(
                "GRAZEL-ITEM4 TOTALS planned=${workspaceDependencyRoots.size} kept=${rootsToResolve.size}"
            )
```

(b) Planner parity log — in `planBinaryProjectRoots`, before the MAIN_HIERARCHY block
(NO filtering, log only):

```kotlin
        val mainLeafBuildTypeNames = sortedVariants
            .filter { variant ->
                variant.variantType == VariantType.AndroidBuild && variant.isWorkspaceAndroidLeaf
            }
            .mapNotNullTo(mutableSetOf()) { variant -> variant.workspaceBuildTypeName }
        sortedVariants
            .filter { variant ->
                variant.variantType == VariantType.AndroidBuild && variant.isWorkspaceMainHierarchyRoot
            }
            .forEach { variant ->
                val keep = variant.isBase || variant.name in mainLeafBuildTypeNames
                project.logger.warn(
                    "GRAZEL-ITEM4 PLAN ${if (keep) "keep" else "drop"} " +
                        "project=${project.path} bucket=${variant.name}"
                )
            }
```

(c) Registrar probes — around the `plan(...)` call:

```kotlin
            val planStartNs = System.nanoTime()
            val rootInputs = WorkspaceDependencyRootInputPlanner.plan(...)
            rootProject.logger.warn(
                "GRAZEL-ITEM4 PLAN-TIME ${(System.nanoTime() - planStartNs) / 1_000_000}ms roots=${rootInputs.size}"
            )
```

materialization timestamps (wrap the provider):

```kotlin
                    workspaceDependencyRootComponents.add(
                        rootInput.configuration.incoming.resolutionResult.rootComponent.map { component ->
                            rootProject.logger.warn(
                                "GRAZEL-ITEM4 MATERIALIZED ${rootInput.configuration.name} t=${System.nanoTime()}"
                            )
                            component
                        }
                    )
```

pin probe (in the `pinMavenArtifactsTask?.configure` block):

```kotlin
                    rootProject.logger.warn(
                        "GRAZEL-ITEM4 PIN config=${rootInput.configuration.name} meta=${rootInput.toMetadata().projectPath}:${rootInput.toMetadata().kind}:${rootInput.toMetadata().bucketName}"
                    )
```

- [ ] **Step 2: Compile + samples sanity** — `./gradlew :grazel-gradle-plugin:compileKotlin
  --console=plain` → BUILD SUCCESSFUL; then `./gradlew migrateToBazel --console=plain 2>&1 |
  tee <scratchpad>/item4-samples.log`; grep `GRAZEL-ITEM4`; verify `git status --porcelain`
  shows ONLY the three instrumented files (generated output untouched).

- [ ] **Step 3: PAX instrumented migrate** (~11 min, background; no concurrent Gradle):
  `cd /Users/arun.sampathkumar/work/pax-android && ./gradlew migrateToBazel --no-daemon
  --console=plain --rerun-tasks 2>&1 | tee <scratchpad>/item4-pax.log` → exit 0; PAX tree must
  stay clean (`status --porcelain` empty).

- [ ] **Step 4: Extract evidence** — counts of DROP lines and TOTALS; parity diff: every
  `PLAN drop` must pair with a `DROP` for the same project+bucket and vice versa (list
  mismatches verbatim); MATERIALIZED timestamp deltas around dropped roots → estimated
  seconds saved; PIN lines ∩ DROP set → `pinIntersectionEmpty`; PLAN-TIME baseline.

- [ ] **Step 5: Write + commit the evidence doc** (`## Samples`, `## PAX`, `## Parity diff`,
  `## Pin coupling`, `## Config-phase baseline`, `## Verdict inputs`):

```bash
git add reports/review/item4-plan-time-filter-evidence.md
git commit -m "docs(review): item-4 plan-time filter evidence (drop counts, parity, pin coupling, config baseline)"
```

- [ ] **Step 6: Revert ALL instrumentation** — `git checkout --` the three source files;
  verify `git grep -n "GRAZEL-ITEM4" -- grazel-gradle-plugin/src` is empty.

**STOP — GATE 0.** Controller commissions a Fable advisory on the evidence doc (evidence
quality + recommendation), then escalates to the user with numbers + advisory. User rules:
proceed / close as `[CLOSED-NOT-WORTH-IT]`.

---

### Task 2 (Phase 1): Parity shadow — stamp + check, behavior-neutral

**Files:**
- Modify: `AggregatedDependencyRoot.kt` (metadata field), `WorkspaceDependencyRootInputPlanner.kt`
  (compute verdict + plumb into metadata), `AggregatedDependencyResolver.kt` (parity `check`)
- Test: planner unit test (locate the existing planner/resolver test file first; create
  `WorkspaceDependencyRootInputPlannerTest.kt` only if none exists)

**Interfaces:**
- Produces: `AggregatedDependencyRootMetadata.plannedMainLeafBuildType: Boolean? = null`
  (null = filter not applicable to this kind; non-null only for MAIN_HIERARCHY/AndroidBuild
  roots). Task 3 consumes this exact field.

- [ ] **Step 1: Add the stamp field** — `val plannedMainLeafBuildType: Boolean? = null` on
  `AggregatedDependencyRootMetadata` (default null keeps JSON round-trip backward-compatible;
  confirm the serializer used by `CollectWorkspaceDependencyRootMetadataTask` tolerates the
  new field on old files — default-value fields do).

- [ ] **Step 2: Planner computes + plumbs the verdict** — in `planBinaryProjectRoots`, compute
  `mainLeafBuildTypeNames` once (exact code from Task 1(b), minus the logging), and for the
  MAIN_HIERARCHY block pass
  `plannedMainLeafBuildType = variant.isBase || variant.name in mainLeafBuildTypeNames`
  through `addVariantRoots`/`addConfigurationRoots` into the metadata (new parameter,
  default null so TEST_HIERARCHY/MAIN_LEAF/UNIT_TEST/ANDROID_TEST/LINT call sites are
  untouched). NO filtering yet.

- [ ] **Step 3: Resolver parity check** — in `resolve()`, inside the existing filter:

```kotlin
            val rootsToResolve = workspaceDependencyRoots.filter { root ->
                val jsonVerdict = mainReachabilityTracker.shouldResolveMainHierarchyRoot(root.metadata)
                root.metadata.plannedMainLeafBuildType?.let { plannedVerdict ->
                    check(plannedVerdict == jsonVerdict) {
                        "Plan-time main-leaf verdict (${plannedVerdict}) disagrees with " +
                            "resolve-time verdict (${jsonVerdict}) for " +
                            "${root.metadata.projectPath} bucket=${root.metadata.bucketName} — " +
                            "live-variant vs declared-metadata divergence; see " +
                            "reports/review/item4-plan-time-filter-evidence.md"
                    }
                }
                jsonVerdict
            }
```

- [ ] **Step 4: Unit test** — drive the planner with a fixture where one BuildType-backed
  hierarchy variant matches a leaf build-type and one does not; assert the stamped verdicts
  (true/false) and that non-MAIN kinds carry null. Mirror existing planner/variant test
  fixture style.

- [ ] **Step 5: Gates** — full local (unit + golden + `bazelisk build --nobuild //...`), then
  **full PAX sweep §1–6**; additionally grep the PAX migrate log for the check's message text
  (must be absent).

- [ ] **Step 6: Commit**

```bash
git add <the three source files + test>
git commit -m "feat(resolver): plan-time main-leaf verdict stamped and parity-checked (item 4 phase 1)

Planner computes the leaf-build-type verdict from live variants and stamps it
into root metadata; resolver checks it against the JSON-derived verdict on
every MAIN root. Behavior-neutral: filter unchanged, loud failure on any
divergence. Byte-identical (golden + PAX sweep verified)."
```

**GATE 1 (controller, mechanical):** sweep green + zero parity failures → Task 3.

---

### Task 3 (Phase 2): The switch

**Files:**
- Modify: `WorkspaceDependencyRootInputPlanner.kt` (filter live), `AggregatedDependencyResolver.kt`
  (filter → assertion), planner test (filtered cases)

Shape (selected at GATE 0 from pin evidence — default shown is drop-outright; if
`pinIntersectionEmpty=false`, STOP and re-confirm scope with the user first):

- [ ] **Step 1: Planner filters** — extend the MAIN_HIERARCHY block's filter:

```kotlin
            sortedVariants
                .filter { variant ->
                    variant.variantType == VariantType.AndroidBuild &&
                        variant.isWorkspaceMainHierarchyRoot &&
                        (variant.isBase || variant.name in mainLeafBuildTypeNames)
                }
```

  (stamp becomes always-true for emitted roots — keep stamping it; the assertion consumes it.)

- [ ] **Step 2: Resolver assertion** — replace the filter with:

```kotlin
            workspaceDependencyRoots.forEach { root ->
                check(mainReachabilityTracker.shouldResolveMainHierarchyRoot(root.metadata)) {
                    "Planner emitted a MAIN_HIERARCHY root the resolver would refuse to walk: " +
                        "${root.metadata.projectPath} bucket=${root.metadata.bucketName} — " +
                        "plan-time filter regression (item 4 phase 2)"
                }
            }
            val rootsToResolve = workspaceDependencyRoots
```

  (the parity `check` from phase 1 collapses into this; `shouldResolveMainHierarchyRoot`
  itself is retained unchanged in `MainReachabilityTracker`.)

- [ ] **Step 3: Unit tests** — planner: dropped candidate produces NO root inputs (and pin/
  metadata/resolve consumers all see the narrowed list — assert via `plan()` output);
  kept candidates unchanged.

- [ ] **Step 4: Measure** — temporarily re-apply ONLY the PLAN-TIME probe (uncommitted) and
  run the PAX migrate once with `--profile`; record: plan() duration vs phase-0 baseline
  (budget check — must be within noise), `resolveWorkspaceDependenciesTask` duration and
  total migrate time vs phase-0 run. Revert probe. Append numbers to the evidence doc
  (amend commit or follow-up docs commit).

- [ ] **Step 5: Gates** — full local + **full PAX sweep §1–6**. Byte-identity is the premise
  phase 1 proved: ANY output drift = revert + report (do not patch baselines).

- [ ] **Step 6: Commit**

```bash
git add <planner, resolver, tests> 
git commit -m "perf(resolver): filter non-leaf MAIN_HIERARCHY roots at plan time (item 4 phase 2)

Doomed roots are no longer wired as @Input providers, so Gradle never resolves
them at execution. Resolver filter degrades to an assertion tripwire. Measured:
<N> roots dropped, resolve task <before>-><after>, plan() delta within noise.
Byte-identical (golden + PAX sweep verified)."
```

---

### Task 4: `/simplify` pass (controller-run)

Over the effort's code diff, byte-identity gated; skip-with-reason per the spec's phases.

## Final verification

Opus adversarial whole-effort review (spec + plan + evidence doc as inputs), focused on:
the live-vs-JSON equivalence chain, the three-consumer narrowing consistency (zip-by-index),
the assertion's coverage vs the deleted filter, serialization compatibility of the stamp
field, and whether the measured numbers support the commit-message claims.

## Out of scope

Zip-by-index rekeying (backlog 10); S2 memoization; LINT planning; deleting the resolver
assertion (post-release follow-up); `bucket/`.
