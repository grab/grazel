# Correctness-Hardening Pass — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Kill the zip-by-index pairing between resolved root components and root metadata
(item 10, keyed carrier), and prove-or-refute the collapsibility of the triple-pass test
subtraction (item 6) — acting on whichever the proof supports.

**Architecture:** Item 10: a `RootKey` (uniqueness-verified) is captured into a
`KeyedRootComponent` carrier at wiring time in `WorkspaceDependencyInputsRegistrar`; the
resolve task joins components to metadata by key with loud mismatch failure; `zip` + size
check die. Fingerprinting gated on samples before PAX; explicit fallback shape. Item 6: a
truth-table proof over `Coverage.canCover` vs `CoveredDependency.canCoverTest` decides
collapse vs `[RESOLVED-KEEP]` documentation. Spec:
`reports/specs/2026-07-23-correctness-hardening-design.md`.

**Tech Stack:** Kotlin, Gradle plugin (Provider.map wiring, @Input fingerprinting), JUnit4.

## Global Constraints

- **Byte-identity:** per task: `./gradlew :grazel-gradle-plugin:test --console=plain` →
  `./gradlew verifyGrazelGoldenBaseline --console=plain` (`...generated-file diff are clean.`;
  documented appcompat/constraintlayout waiver only) → `bazelisk build --nobuild //...`.
  **One full PAX sweep after both items land** (§PAX 1-6 of
  `reports/specs/VERIFICATION-GATES.md`). ANY output drift = stop, revert, report.
- **Resolution ORDER must not change** (item 10): the keyed join must consume components in
  today's wired order — order feeds deterministic merge tie-breaks
  (`AggregatedDependencyResolver` KDoc). Keys ARE the pairing; order stays the iteration.
- **Item 6 collapses ONLY on proof of identity**; refutation ships documentation + pinning
  tests instead. No behavior change to bucket contents either way.
- One Gradle build at a time; bazelisk never concurrent with Gradle. PAX git-read-only.
- Stage explicit paths; never `git add -A`; never stage `codedb.snapshot`.
- Do not touch: `TopologicalSorter`, `resolution/` beyond the resolve-task join site,
  pin-task consumption shape, planner emission logic (only the key-uniqueness assertion).
- **Models:** Sonnet implementers + reviewers; Opus adversarial final; Fable advisory only if
  item 6's proof is ambiguous.

## Decision authority

Task 2 (item 6) ends with a PROOF VERDICT (provable/refuted). Controller applies it
mechanically (collapse vs document); if the reading is genuinely ambiguous (predicates
incomparable on a reachable input), controller commissions a Fable advisory and escalates to
the user rather than guessing.

---

## File map

| File | Item | Change |
|---|---|---|
| `.../gradle/dependencies/AggregatedDependencyRoot.kt` | 10 | `RootKey` + `metadata.rootKey()` + `KeyedRootComponent` |
| `.../gradle/dependencies/WorkspaceDependencyRootInputPlanner.kt` | 10 | key-uniqueness assertion at end of `plan()` |
| `.../tasks/internal/WorkspaceDependencyInputsRegistrar.kt:113-119` | 10 | carrier wiring (`.map` capture) |
| `.../tasks/internal/ResolveWorkspaceDependenciesTask.kt:56,73-97` | 10 | `ListProperty<KeyedRootComponent>`; keyed join replaces zip+size-check |
| `.../gradle/dependencies/bucket/TestBucketPlanner.kt:450-476` | 6 | collapse OR KDoc, per proof |
| `.../gradle/dependencies/bucket/Coverage.kt:41-44,65+` | 6 | read-only (proof source) |
| Tests: planner/resolve-task test files (locate existing; extend) + new `TestBucketCoverageTruthTableTest.kt` | both | uniqueness, join-failure, truth tables |

---

### Task 1 (item 10): RootKey + keyed carrier + join

**Files:** per file map rows 1-4 + tests.

**Interfaces:**
- Produces: `internal data class RootKey(val projectPath: String, val configurationName: String, val kind: AggregatedDependencyRootKind)`;
  `internal fun AggregatedDependencyRootMetadata.rootKey(): RootKey`;
  `internal data class KeyedRootComponent(val key: RootKey, val component: ResolvedComponentResult)`.

- [ ] **Step 1: Key types + uniqueness assertion (test-first).** Add `RootKey`/`rootKey()`/
  `KeyedRootComponent` to `AggregatedDependencyRoot.kt`. At the end of
  `WorkspaceDependencyRootInputPlanner.plan()`, before returning:

```kotlin
        val plannedRootInputs = rootInputs.filter { rootInput -> rootInput.configuration.isCanBeResolved }
        val duplicateKeys = plannedRootInputs
            .map { rootInput -> rootInput.toMetadata().rootKey() }
            .groupingBy { key -> key }
            .eachCount()
            .filterValues { count -> count > 1 }
        check(duplicateKeys.isEmpty()) {
            "Workspace dependency root keys are not unique: $duplicateKeys — " +
                "extend RootKey (e.g. with bucketName) before keyed pairing can be trusted"
        }
        return plannedRootInputs
```

  Unit test: drive `plan()` with a fixture producing multiple roots; assert no throw and that
  a hand-constructed duplicate-key scenario (call `rootKey()` on two metadata literals) is
  detected by the same grouping logic. **If the samples/PAX runs in later steps trip this
  check: STOP, report the colliding keys — the key gets extended per spec, not weakened.**

- [ ] **Step 2: Carrier wiring.** In the registrar (`:113-119`):

```kotlin
            resolveWorkspaceDependenciesTask.configure {
                rootInputs.forEach { rootInput ->
                    val key = rootInput.toMetadata().rootKey()
                    workspaceDependencyRootComponents.add(
                        rootInput.configuration.incoming.resolutionResult.rootComponent
                            .map { component -> KeyedRootComponent(key = key, component = component) }
                    )
                }
            }
```

  (`key` computed eagerly at configuration time — cheap metadata fields — so the lambda
  captures a value, not the `rootInput`.)

- [ ] **Step 3: Keyed join in the resolve task.** Property becomes
  `abstract val workspaceDependencyRootComponents: ListProperty<KeyedRootComponent>`; replace
  lines 73-97's pairing:

```kotlin
        val keyedComponents = workspaceDependencyRootComponents.get()
        val rootMetadata = fromJson<List<AggregatedDependencyRootMetadata>>(
            workspaceDependencyRootMetadata.get().asFile
        )
        val metadataByKey = rootMetadata.associateBy { metadata -> metadata.rootKey() }
        check(metadataByKey.size == rootMetadata.size) {
            "Duplicate root keys in workspace dependency root metadata — keyed pairing unsafe"
        }
        ...
                workspaceDependencyRoots = keyedComponents.map { keyed ->
                    val metadata = checkNotNull(metadataByKey[keyed.key]) {
                        "No root metadata for resolved component key ${keyed.key}; " +
                            "metadata keys: ${metadataByKey.keys.take(5)}..."
                    }
                    AggregatedDependencyRoot(keyed.component, metadata)
                }
```

  Delete the old size `check` (`:77-79`). Also `check(keyedComponents.size == rootMetadata.size)`
  stays as a totality guard (every metadata consumed — cheap and catches a component list that
  silently lost an element). Iteration order = `keyedComponents` order = today's wiring order
  (resolution-order constraint honored). Update the `rootMetadata.size` usage in the log line
  (`:104`) if needed.

- [ ] **Step 4: Join-failure unit test** — construct a keyed component whose key is absent
  from metadata; assert the `checkNotNull` fires with the key in the message. (Test the join
  logic via a small extracted internal function if the task action isn't directly drivable —
  extract `internal fun pairRootsByKey(keyedComponents, rootMetadata): List<AggregatedDependencyRoot>`
  and test that.)

- [ ] **Step 5: Fingerprinting gate (BEFORE any PAX):**
  `./gradlew :grazel-gradle-plugin:test --console=plain` → BUILD SUCCESSFUL, then
  `./gradlew migrateToBazel --console=plain` on samples → BUILD SUCCESSFUL (this exercises the
  `@Input ListProperty<KeyedRootComponent>` end-to-end), then
  `./gradlew verifyGrazelGoldenBaseline --console=plain` → clean, then
  `bazelisk build --nobuild //...`. **If Gradle rejects the carrier (serialization/fingerprint
  error): STOP, report BLOCKED — the fallback (zip + per-element identity check) is a design
  decision the controller re-confirms, not an implementer improvisation.**

- [ ] **Step 6: Commit**

```bash
git add grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyRoot.kt \
        grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/WorkspaceDependencyRootInputPlanner.kt \
        grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/WorkspaceDependencyInputsRegistrar.kt \
        grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/ResolveWorkspaceDependenciesTask.kt \
        <test files>
git commit -m "refactor(resolver): keyed root-component pairing replaces zip-by-index

RootKey(projectPath, configurationName, kind) — uniqueness asserted at plan
time — travels with each resolved component via a KeyedRootComponent carrier;
the resolve task joins by key with loud mismatch failure. The positional
contract across three configure blocks is gone. Byte-identical (golden +
bazel analysis verified)."
```

---

### Task 2 (item 6): prove, then collapse-or-document

**Files:** `Coverage.kt` (read-only), `TestBucketPlanner.kt:432-476`, new
`grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/bucket/TestBucketCoverageTruthTableTest.kt`.

- [ ] **Step 1: The proof.** Read `Coverage.canCover` (`Coverage.kt:41-44`) and
  `CoveredDependency.canCoverTest` (`Coverage.kt:65+`) completely, then answer in writing
  (your report) with code citations:
  (a) For a NON-direct candidate: does `canCoverTest(c, ...) == true` imply `canCover(c) == true`
  on every reachable input? Equivalently: exhibit or rule out a dep that pass 1 drops
  (generically covered) which a single-`canCoverTest` filter would KEEP (not test-coverable) —
  and vice versa: a dep pass 1 keeps that... trace ALL four quadrants for both direct and
  non-direct candidates through the actual three-pass flow (`TestBucketPlanner.kt:450-476`)
  vs the hypothetical single-pass `testDependencies.filterNot { canCoverTest }` form.
  (b) Where does the `overrideTarget` annotation attach during `Coverage.ofGrouped(...).subtract`
  (read `Coverage.subtract`), and does the single-pass form attach it to the same deps with
  the same values?
- [ ] **Step 2: Truth-table tests** — encode every quadrant from step 1 as unit tests against
  the REAL predicates (construct `CoveredDependency`/`ResolvedDependency` fixtures mirroring
  existing bucket test style — locate existing `TestBucketPlanner`/`Coverage` tests first and
  reuse their fixture helpers). These tests pin the semantics regardless of verdict.
- [ ] **Step 3a (PROVABLE — all quadrants agree + annotation identical):** collapse
  `withoutTestDependenciesCoveredBy` to the single-pass form; the truth-table tests plus an
  equivalence test (old form as a test-local reference implementation, property-checked over
  the fixtures) guard it. Gates: unit + golden + bazel analysis.
- [ ] **Step 3b (REFUTED — any quadrant diverges):** leave the three passes; write the
  counterexample into the function's KDoc (replacing the current "why three passes" prose with
  the concrete quadrant that forces them); tests from step 2 pin it. Gates: unit + golden.
- [ ] **Step 4: Commit** (message per branch):

```bash
# 3a:
git commit -m "refactor(bucket): collapse triple-pass test subtraction to single canCoverTest filter

Proven equivalent by quadrant truth-table tests (see TestBucketCoverageTruthTableTest);
overrideTarget annotation attachment preserved. Byte-identical (golden verified)."
# 3b:
git commit -m "docs(bucket): pin why test subtraction needs three passes (item 6 refuted)

Truth-table tests exhibit the quadrant where canCover and canCoverTest diverge
for non-direct deps; single-pass collapse would change bucket contents. KDoc
now states the counterexample. No behavior change."
```

**PROOF VERDICT reported to controller; ambiguity → Fable advisory + user escalation.**

---

### Task 3: Full PAX sweep (controller-run, after both items)

§PAX 1-6 verbatim (migrate `--rerun-tasks` → clean-tree → size guard 11/11/1945 → APK →
3 focused tests → CI-set graph analysis `Analyzed 1442 targets`). Item 10 changed task-input
shape; expect identical outputs and a re-fingerprinted (not-up-to-date) first run — that is
expected, not drift. ANY generated-file/output deviation: stop, revert, report.

### Task 4: `/simplify` pass (controller-run)

Over the effort's code diff, byte-identity gated, skip-with-reason per spec semantics.

## Final verification

Opus adversarial whole-effort review: key-uniqueness reasoning (is the tuple actually unique
under flavors/standalone-test remapping?), join totality (leftover components AND leftover
metadata both caught?), fingerprinting behavior change (`@Input` on a wrapper — does it
weaken up-to-date checking vs raw ResolvedComponentResult?), item-6 proof soundness
(quadrants exhaustive? annotation timing verified against `Coverage.subtract`'s actual code?),
resolution-order preservation.

## Out of scope

Items 5/7/8/9; metadata-merge consolidation; pin-task shape; any bucket-content change
without proof; deleting the totality size-check (kept deliberately).
