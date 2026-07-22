# Dual Reachability Channels Investigation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Empirically decide whether the MAIN-root walk-reachability fold in
`AggregatedDependencyResolver` is redundant with the declared-edge DFS seed, then either delete
it (evidence + static argument) or document/pin the real semantic difference.

**Architecture:** A temporary uncommitted logging patch at the fold site measures, per root,
what the walk fold would add beyond already-seeded state. Local samples + one PAX migrate
produce the evidence; a static-argument investigation (Gradle features that can create resolved
project edges with no declared counterpart) decides generality. The decision matrix in the spec
(`reports/specs/2026-07-22-reachability-channels-design.md`) picks Task 3A (delete) or 3B
(document). Task 4 (asymmetry unification) runs on either branch if blanks never occur.

**Tech Stack:** Kotlin, Gradle plugin, PAX composite build (`grazelLocalEnv=true`), JUnit4.

## Global Constraints

- **Byte-identity:** generated output must not move. Gates:
  `./gradlew :grazel-gradle-plugin:test --console=plain` then
  `./gradlew verifyGrazelGoldenBaseline --console=plain` (must print `...generated-file diff
  are clean.`; the ONE documented appcompat/constraintlayout bucket-labels waiver is
  acceptable). Task 3A (delete) additionally requires `bazelisk build --nobuild //...` and the
  **full PAX sweep** (`reports/specs/VERIFICATION-GATES.md` §PAX 1–6).
- **On the delete branch, ANY golden drift disproves the subset claim** → abandon 3A, revert,
  execute 3B instead. Do not "fix" the golden.
- **One Gradle build at a time** (local OR PAX, never both; bazelisk never concurrent with
  Gradle).
- **PAX non-destructive rule**: no git write ops in `/Users/arun.sampathkumar/work/pax-android`;
  `migrateToBazel --rerun-tasks` overwriting generated files in place is allowed (tree returns
  clean because instrumentation only logs).
- **The instrumentation patch is NEVER committed.** Before any commit, `git diff` must show no
  `GRAZEL-ITEM2` string in tracked sources.
- Stage explicit paths only; never `git add -A`; never stage `codedb.snapshot`.
- Do not touch `bucket/`, `TopologicalSorter`, `CollectTargetMavenRepoReferencesTask`, or
  expand into the `RootContribution` protocol cleanup (critic-03 item 2) beyond what the fold
  gating itself needs.

## Decision authority

Task 2 ends at the spec's decision matrix. The controller (orchestrating session) adjudicates
which branch runs. **Escalate to the user instead of deciding** if the outcome hinges on
declaring a Gradle feature unsupported (e.g. "module→project `dependencySubstitution` /
composite builds are out of grazel's scope") — that is a product decision, not an
implementation one.

---

## File map

| File | Change |
|---|---|
| `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt:128-138` | Task 1: temporary instrumentation (reverted). Task 3A: gate the fold on `contribution.scope == null` |
| `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/resolution/RootContributionComputer.kt` | Task 3A: `shouldFoldWalkReachability` helper + KDoc updates |
| `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/resolution/MainReachabilityTracker.kt:263-277` | Task 3B: KDoc rewrite; Task 4: `recordReachable` asymmetry unification |
| `reports/review/item2-channel-evidence.md` | Task 2: **create** — the committed evidence |
| `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/resolution/` | Task 3A or 3B: pinning test (new file `WalkReachabilityFoldTest.kt`) |

---

### Task 1: Instrumentation patch + samples evidence (NO commit)

**Files:**
- Modify (temporarily): `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt:128-138`

**Interfaces:**
- Consumes: `RootContribution(scope, outcome, lintClosure, ...)` — `scope != null` ⟺
  MAIN_HIERARCHY/MAIN_LEAF root; `MainReachabilityTracker.reachableMainProjectPaths` /
  `reachableMainBucketNamesByProject` (public vals, accumulated state).
- Produces: `GRAZEL-ITEM2` log lines + a samples-evidence section (handed to Task 2, which
  writes the committed evidence doc).

- [ ] **Step 1: Apply the instrumentation patch**

In `AggregatedDependencyResolver.resolve()`, replace the existing fold block (currently):

```kotlin
                if (contribution.lintClosure == null) {
                    // MAIN_HIERARCHY/MAIN_LEAF roots already seed their reachability scope earlier,
                    // inside RootContributionComputer.compute (the classpath walk must observe the
                    // seeded scope) — so this fold only records what was *discovered* while walking,
                    // and intentionally does not also seed MAIN scope here (that would double-seed).
                    mainReachabilityTracker.recordReachable(
                        contribution.outcome.reachableProjectPaths,
                        contribution.outcome.reachableBucketNamesByProject
                    )
                }
```

with (comment block kept, instrumentation inserted before the fold):

```kotlin
                if (contribution.lintClosure == null) {
                    // GRAZEL-ITEM2 INSTRUMENTATION — temporary, never commit.
                    val walkPaths = contribution.outcome.reachableProjectPaths
                    val walkBuckets = contribution.outcome.reachableBucketNamesByProject
                    val walkOnlyPaths = walkPaths - mainReachabilityTracker.reachableMainProjectPaths
                    val walkOnlyBuckets = walkBuckets.mapNotNull { (path, names) ->
                        val novel = names -
                            mainReachabilityTracker.reachableMainBucketNamesByProject[path].orEmpty()
                        if (novel.isEmpty()) null else "$path=$novel"
                    }
                    val kindTag = if (contribution.scope != null) "MAIN" else "TEST"
                    val seedOnlyPaths = contribution.scope
                        ?.let { scope -> scope.reachableProjectPaths - walkPaths }
                        .orEmpty()
                    logger.warn(
                        "GRAZEL-ITEM2 $kindTag root=${metadata.projectPath} " +
                            "bucket=${metadata.bucketName} " +
                            "walkOnlyPaths=${walkOnlyPaths.size}" +
                            (if (walkOnlyPaths.isEmpty()) "" else ":$walkOnlyPaths") + " " +
                            "walkOnlyBuckets=${walkOnlyBuckets.size}" +
                            (if (walkOnlyBuckets.isEmpty()) "" else ":$walkOnlyBuckets") + " " +
                            "seedOnlyPaths=${seedOnlyPaths.size}"
                    )
                    val blankBuckets = walkBuckets.values.sumOf { names -> names.count(String::isBlank) }
                    if (blankBuckets > 0) {
                        logger.warn("GRAZEL-ITEM2 BLANK root=${metadata.projectPath} count=$blankBuckets")
                    }
                    // MAIN_HIERARCHY/MAIN_LEAF roots already seed their reachability scope earlier,
                    // inside RootContributionComputer.compute (the classpath walk must observe the
                    // seeded scope) — so this fold only records what was *discovered* while walking,
                    // and intentionally does not also seed MAIN scope here (that would double-seed).
                    mainReachabilityTracker.recordReachable(walkPaths, walkBuckets)
                }
```

Semantics of the measurement: `walkOnlyPaths`/`walkOnlyBuckets` are computed against the
tracker state at the instant BEFORE this root's fold executes (this root's own DFS seed and all
prior roots are already in) — i.e. exactly what `recordReachable` is about to add. For MAIN
roots, empty `walkOnly*` on every root ⟹ the fold is a no-op. For TEST roots the same numbers
show what the walk fold genuinely contributes (converse evidence, recorded only).

- [ ] **Step 2: Compile check**

Run: `./gradlew :grazel-gradle-plugin:compileKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Samples run**

Run: `./gradlew migrateToBazel --console=plain 2>&1 | tee /private/tmp/claude-503/-Users-arun-sampathkumar-work-grazel/c7f42733-8fef-4fff-b63a-1e7a9256539b/scratchpad/item2-samples.log`
Then: `grep "GRAZEL-ITEM2" /private/tmp/claude-503/-Users-arun-sampathkumar-work-grazel/c7f42733-8fef-4fff-b63a-1e7a9256539b/scratchpad/item2-samples.log`
Expected: one line per non-LINT root. Record for the report: per-MAIN-root
`walkOnlyPaths`/`walkOnlyBuckets` counts, per-TEST-root novelty counts, any BLANK lines.
Sanity: `git status --porcelain` must show ONLY the resolver file modified (generated files
byte-identical — the patch only logs).

- [ ] **Step 4: Report (no commit)**

Leave the patch in the working tree (Task 2 needs it for the PAX run). Report the samples
evidence table. DO NOT commit anything.

---

### Task 2: PAX instrumented migrate + static argument + evidence doc + revert patch

**Files:**
- Create: `reports/review/item2-channel-evidence.md`
- Revert: the Task 1 patch in `AggregatedDependencyResolver.kt`

**Interfaces:**
- Consumes: Task 1's working-tree patch and samples evidence.
- Produces: the committed evidence doc; the decision inputs for the matrix. Task 3A/3B key off
  its `## Verdict inputs` section.

- [ ] **Step 1: PAX instrumented migrate (~11 min, run in background; no other Gradle build
  may run concurrently)**

```bash
cd /Users/arun.sampathkumar/work/pax-android
./gradlew migrateToBazel --no-daemon --console=plain --rerun-tasks 2>&1 | \
  tee /private/tmp/claude-503/-Users-arun-sampathkumar-work-grazel/c7f42733-8fef-4fff-b63a-1e7a9256539b/scratchpad/item2-pax.log
```

Expected: exit 0. Then verify the PAX tree stayed clean (instrumentation logs only):
`git -C /Users/arun.sampathkumar/work/pax-android status --porcelain` → empty.

- [ ] **Step 2: Extract the evidence**

```bash
LOG=/private/tmp/claude-503/-Users-arun-sampathkumar-work-grazel/c7f42733-8fef-4fff-b63a-1e7a9256539b/scratchpad/item2-pax.log
grep -c "GRAZEL-ITEM2 MAIN" "$LOG"                                  # total MAIN roots
grep "GRAZEL-ITEM2 MAIN" "$LOG" | grep -v "walkOnlyPaths=0 "        # MAIN roots where fold adds paths
grep "GRAZEL-ITEM2 MAIN" "$LOG" | grep -v "walkOnlyBuckets=0 "      # MAIN roots where fold adds buckets
grep "GRAZEL-ITEM2 TEST" "$LOG" | grep -v "walkOnlyPaths=0 " | head # TEST-root novelty (converse, recorded only)
grep "GRAZEL-ITEM2 BLANK" "$LOG"                                    # blank-bucket occurrences
```

- [ ] **Step 3: Static-argument investigation**

Answer, with file citations, in the evidence doc:
1. Does PAX use module→project `dependencySubstitution`?
   `grep -rn "dependencySubstitution\|substitute(\|useTarget" /Users/arun.sampathkumar/work/pax-android --include="*.gradle" --include="*.gradle.kts" --include="*.kt" -l | grep -v build/` — classify every hit (plugin-classpath vs app-module dependency substitution).
2. Any `includeBuild` in PAX besides the grazel plugin composite?
   `grep -rn "includeBuild" /Users/arun.sampathkumar/work/pax-android/settings.gradle*`
3. The general argument: enumerate the Gradle mechanisms that could make the resolved graph
   contain a `project(...)` edge with no declared counterpart (dependencySubstitution
   module→project; composite-build substitution; anything else found reading
   `ResolvedComponentsVisitor`'s project-edge detection) versus mechanisms that only REMOVE or
   RE-VERSION edges (excludes, constraints, platform alignment, capability conflicts). State
   plainly whether "walk ⊆ DFS for MAIN roots" holds (a) for PAX, (b) universally, (c) only if
   substitution/composite shapes are declared out of scope — option (c) is the user-escalation
   case per §Decision-authority.

- [ ] **Step 4: Write and commit the evidence doc**

`reports/review/item2-channel-evidence.md` sections: `## Samples evidence` (Task 1 table),
`## PAX evidence` (Step 2 numbers, with the raw grep lines for any non-zero case),
`## Static argument` (Step 3), `## Verdict inputs` (three booleans: `mainWalkOnlyEmpty`,
`staticArgumentHolds` (or `holdsOnlyIfSubstitutionOutOfScope`), `blanksNeverOccur`).

- [ ] **Step 5: Revert the instrumentation patch**

`git checkout -- grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`
Then verify: `git grep -n "GRAZEL-ITEM2" -- grazel-gradle-plugin/src` → no hits.

- [ ] **Step 6: Commit (evidence doc ONLY)**

```bash
git add reports/review/item2-channel-evidence.md
git commit -m "docs(review): item-2 reachability-channel evidence (samples + PAX + static argument)"
```

**STOP.** Report the `## Verdict inputs` booleans. The controller applies the spec's decision
matrix and dispatches Task 3A or 3B (and Task 4 if `blanksNeverOccur`).

---

### Task 3A (CONDITIONAL — delete branch): gate the MAIN-root fold out

Run ONLY if the controller rules: `mainWalkOnlyEmpty && staticArgumentHolds`.

**Files:**
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/resolution/RootContributionComputer.kt` (helper + KDoc)
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt:101-138` (gating + doc shrink)
- Create: `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/resolution/WalkReachabilityFoldTest.kt`

**Interfaces:**
- Consumes: `RootContribution` (scope/lintClosure fields as-is).
- Produces: `internal fun RootContribution.shouldFoldWalkReachability(): Boolean`.

- [ ] **Step 1: Write the failing test**

```kotlin
// grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/resolution/WalkReachabilityFoldTest.kt
package com.grab.grazel.gradle.dependencies.resolution

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the item-2 contract (reports/review/item2-channel-evidence.md): MAIN roots' reachability
 * comes solely from the declared-edge DFS seed (computeScope/recordMainRoot); the walk outcome
 * is folded only for TEST-kind roots, where it is the sole reachability source. LINT roots
 * never fold.
 */
class WalkReachabilityFoldTest {

    @Test
    fun `main roots do not fold walk reachability`() {
        val contribution = RootContribution(
            scope = MainProjectEdgeScope(
                reachableProjectPaths = sortedSetOf(":app"),
                reachableBucketNamesByProject = sortedMapOf(),
                excludedShortIdsByTargetProject = sortedMapOf()
            ),
            outcome = RootVisitOutcome(
                dependencies = emptyMap(),
                reachableProjectPaths = emptySet(),
                reachableBucketNamesByProject = emptyMap()
            ),
            routing = emptyList(),
            lintClosure = null,
            seedsBinaryRoot = true
        )
        assertFalse(contribution.shouldFoldWalkReachability())
    }

    @Test
    fun `test roots fold walk reachability`() {
        val contribution = RootContribution(
            scope = null,
            outcome = RootVisitOutcome(
                dependencies = emptyMap(),
                reachableProjectPaths = setOf(":util"),
                reachableBucketNamesByProject = emptyMap()
            ),
            routing = emptyList(),
            lintClosure = null,
            seedsBinaryRoot = false
        )
        assertTrue(contribution.shouldFoldWalkReachability())
    }

    @Test
    fun `lint roots never fold walk reachability`() {
        val contribution = RootContribution(
            scope = null,
            outcome = RootVisitOutcome(
                dependencies = emptyMap(),
                reachableProjectPaths = emptySet(),
                reachableBucketNamesByProject = emptyMap()
            ),
            routing = emptyList(),
            lintClosure = emptyMap(),
            seedsBinaryRoot = false
        )
        assertFalse(contribution.shouldFoldWalkReachability())
    }
}
```

(If `RootVisitOutcome`/`MainProjectEdgeScope` constructor shapes differ from the above, adapt
the fixture construction to the real signatures — the three assertions are the contract.)

- [ ] **Step 2: Run to verify it fails to compile** (helper doesn't exist)

Run: `./gradlew :grazel-gradle-plugin:compileTestKotlin --console=plain`
Expected: FAIL — unresolved reference `shouldFoldWalkReachability`.

- [ ] **Step 3: Implement the helper** (in `RootContributionComputer.kt`, directly below the
  `RootContribution` data class)

```kotlin
/**
 * Whether the spine should fold this contribution's walk-discovered reachability
 * ([RootVisitOutcome.reachableProjectPaths]/[RootVisitOutcome.reachableBucketNamesByProject])
 * into the [MainReachabilityTracker].
 *
 * MAIN_HIERARCHY/MAIN_LEAF roots ([scope] != null) are excluded: their reachability is fully
 * determined by the declared-edge DFS seeded in [RootContributionComputer.compute] — verified
 * empirically (samples + PAX) and by static argument in
 * `reports/review/item2-channel-evidence.md`. LINT roots ([lintClosure] != null) never folded.
 * TEST_HIERARCHY/UNIT_TEST/ANDROID_TEST roots fold: the walk is their only reachability source.
 */
internal fun RootContribution.shouldFoldWalkReachability(): Boolean =
    lintClosure == null && scope == null
```

- [ ] **Step 4: Gate the fold in the spine**

In `AggregatedDependencyResolver.resolve()`, replace the fold block with:

```kotlin
                if (contribution.shouldFoldWalkReachability()) {
                    mainReachabilityTracker.recordReachable(
                        contribution.outcome.reachableProjectPaths,
                        contribution.outcome.reachableBucketNamesByProject
                    )
                }
```

(the double-seed comment is deleted — the helper's KDoc now owns that explanation) and shrink
the `resolve()` KDoc's ordering paragraph (lines 101-117) to:

```kotlin
        /**
         * Visits every eligible workspace dependency root and dispatches per [AggregatedDependencyRootKind]
         * (via [RootContributionComputer]) to resolve, exclude-filter and bucket its classpath.
         * Ordering matters: MAIN_HIERARCHY/MAIN_LEAF roots seed reachability (declared-edge DFS,
         * inside [RootContributionComputer.compute]) and must be processed in
         * [workspaceDependencyRoots] order before any TEST_HIERARCHY/UNIT_TEST/ANDROID_TEST root
         * that depends on the same project's main reachability facts. TEST-kind roots fold their
         * walk-discovered reachability via [MainReachabilityTracker.recordReachable] — the walk is
         * their only reachability source; see [RootContribution.shouldFoldWalkReachability] for
         * why MAIN and LINT roots do not fold.
         */
```

Add the import for `shouldFoldWalkReachability` if the package differs (same package as
`RootContributionComputer` — check the resolver's existing imports of that file's types).

- [ ] **Step 5: Run the new test + full suite**

Run: `./gradlew :grazel-gradle-plugin:test --console=plain`
Expected: `BUILD SUCCESSFUL` (new + existing).

- [ ] **Step 6: Golden + bazel analysis**

Run: `./gradlew verifyGrazelGoldenBaseline --console=plain` →
`...generated-file diff are clean.` **If ANY drift: STOP, `git checkout` the source changes,
report — the branch flips to 3B.**
Then: `bazelisk build --nobuild //...` → `Build completed successfully`, zero missing
package/target.

- [ ] **Step 7: Commit**

```bash
git add grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/resolution/RootContributionComputer.kt \
        grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt \
        grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/resolution/WalkReachabilityFoldTest.kt
git commit -m "refactor(resolver): MAIN roots no longer fold walk reachability (single channel)

Declared-edge DFS seeding is the sole MAIN-root reachability source; the walk
fold was a proven no-op (evidence: reports/review/item2-channel-evidence.md).
TEST roots keep the fold (their only source). Byte-identical (golden + bazel
analysis verified)."
```

- [ ] **Step 8: Full PAX sweep** (VERIFICATION-GATES.md §PAX 1–6, mandatory for this branch):
  migrate `--rerun-tasks` → clean-tree check → size guard `--mode preserving` (expect 11/11/1945,
  no deltas) → APK build → focused tests (3 pass) → graph analysis over the CI unit-test set
  (`list_unit_test_targets` → `build --nobuild --keep_going`, expect `Analyzed 1442 targets`,
  zero errors). ANY deviation: report before proceeding; do not push anything.

---

### Task 3B (CONDITIONAL — document branch): pin the channel semantics

Run ONLY if the controller rules the delete bar unmet (evidence non-empty, argument fails, or
user rules substitution shapes in-scope).

**Files:**
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/resolution/MainReachabilityTracker.kt:263-268` (KDoc)
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt:101-117` (KDoc)
- Create: `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/resolution/WalkReachabilityFoldTest.kt`

- [ ] **Step 1: Write the pinning test** (this passes immediately — it pins current behavior)

```kotlin
// grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/resolution/WalkReachabilityFoldTest.kt
package com.grab.grazel.gradle.dependencies.resolution

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the item-2 finding (reports/review/item2-channel-evidence.md): the walk-discovered
 * reachability fold is LOAD-BEARING for MAIN roots — a resolved-graph project edge can exist
 * with no declared counterpart (<insert the exact shape from the evidence doc>), so
 * [MainReachabilityTracker.recordReachable] must accept paths/buckets the declared-edge DFS
 * never saw. Deleting the fold on the grounds "the DFS already covers it" is a regression.
 */
class WalkReachabilityFoldTest {

    @Test
    fun `recordReachable folds walk-discovered facts the declared DFS did not seed`() {
        val tracker = MainReachabilityTracker(
            declaredDependencyMetadata = /* minimal fixture — mirror existing MainReachabilityTracker
                test construction, or DeclaredDependencyMetadata with an empty project map */,
            migratableProjectPaths = listOf(":app", ":substituted-lib")
        )
        tracker.recordReachable(
            projectPaths = setOf(":substituted-lib"),
            bucketNamesByProject = mapOf(":substituted-lib" to setOf("debug"))
        )
        assertEquals(setOf(":substituted-lib"), tracker.reachableMainProjectPaths.toSet())
        assertEquals(
            setOf("debug"),
            tracker.reachableMainBucketNamesByProject[":substituted-lib"].orEmpty().toSet()
        )
    }
}
```

(Fill the fixture from existing tracker/resolver test fixtures; the assertions are the
contract. Replace the KDoc placeholder with the ACTUAL shape from the evidence doc.)

- [ ] **Step 2: Rewrite the two KDocs** — replace archaeology ("mirroring the in-place mutation
  this replaced", "to match the out-param it replaced") with the domain reason: name the exact
  Gradle mechanism/evidence line that makes the walk fold load-bearing for MAIN roots, citing
  `reports/review/item2-channel-evidence.md`. Keep behavior untouched.

- [ ] **Step 3: Gates**

Run: `./gradlew :grazel-gradle-plugin:test --console=plain` → `BUILD SUCCESSFUL`
Run: `./gradlew verifyGrazelGoldenBaseline --console=plain` → `...diff are clean.`
(No PAX sweep — docs + test only.)

- [ ] **Step 4: Commit**

```bash
git add grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/resolution/MainReachabilityTracker.kt \
        grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt \
        grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/resolution/WalkReachabilityFoldTest.kt
git commit -m "docs(resolver): document why the MAIN-root walk-reachability fold is load-bearing

Item-2 investigation outcome (evidence: reports/review/item2-channel-evidence.md):
the dual channels are intentional, now stated as a domain invariant with a pinning
test instead of refactor archaeology."
```

---

### Task 4 (CONDITIONAL — either branch): unify the recordReachable asymmetry

Run ONLY if the evidence shows `blanksNeverOccur` (zero `GRAZEL-ITEM2 BLANK` lines in samples
AND PAX).

**Files:**
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/resolution/MainReachabilityTracker.kt:269-277`

- [ ] **Step 1: Replace the raw-addAll body**

```kotlin
    /**
     * Folds a [RootVisitOutcome]'s reachability delta into this tracker's accumulated state,
     * using the same union semantics [recordMainRoot] uses for [MainProjectEdgeScope].
     * Blank bucket names are filtered by [addReachableMainBuckets]; instrumented runs (see
     * reports/review/item2-channel-evidence.md) confirmed walk outcomes never produce blanks,
     * so this filter is inert here and unifies the two fold paths.
     */
    fun recordReachable(projectPaths: Set<String>, bucketNamesByProject: Map<String, Set<String>>) {
        reachableMainProjectPaths.addAll(projectPaths)
        bucketNamesByProject.forEach { (projectPath, bucketNames) ->
            addReachableMainBuckets(projectPath, bucketNames)
        }
    }
```

- [ ] **Step 2: Gates**

Run: `./gradlew :grazel-gradle-plugin:test --console=plain` → `BUILD SUCCESSFUL`
Run: `./gradlew verifyGrazelGoldenBaseline --console=plain` → `...diff are clean.`
If the golden moves, the blank-filter was NOT inert — revert and report (evidence
contradiction; do not force).

- [ ] **Step 3: Commit**

```bash
git add grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/resolution/MainReachabilityTracker.kt
git commit -m "refactor(resolver): unify recordReachable onto addReachableMainBuckets

Blank-name filter proven inert by item-2 instrumentation; deletes the
preserved out-param asymmetry. Byte-identical (golden verified)."
```

---

## Final verification

- Delete branch ran → PAX sweep already done in Task 3A Step 8; nothing further.
- Document branch ran → local gates only (already in-task).
- Whole-effort review: standard SDD final review over `git merge-base` of the effort's commits;
  include the evidence doc in the reviewer's inputs.

## Out of scope

TEST-root DFS seeding (converse question — evidence recorded in the doc, no action);
`shouldResolveMainHierarchyRoot` plan-time hoist (backlog 4); `RootContribution` protocol
cleanup beyond `shouldFoldWalkReachability`; item 3 (fixpoint → worklist).
