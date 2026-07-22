# Worklist Reference Collection — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the round-based fixpoint in `CollectTargetMavenRepoReferencesTask` with one
ordered pass + a deferred-activation worklist, and delete the singleton `ProjectReachabilityGroup`
wrapper.

**Architecture:** Pass 1 walks all projects in the existing consumers-first order exactly as
today's round 1 does (inactive visits still extract and merge); projects visited while inactive
land in an insertion-ordered `deferred` map. A drain loop then repeatedly activates the first
deferred project that has become referenced, until quiescence. Five round-protocol guards
(`settledProjects`-as-cross-round-state, `everVisited`, `maxRounds`, whole-facts snapshot
equality, pre-skip republish) disappear. Spec:
`reports/specs/2026-07-22-worklist-reference-collection-design.md`.

**Tech Stack:** Kotlin, Gradle plugin, JUnit4.

## Global Constraints

- **Byte-identity:** gates per code task:
  `./gradlew :grazel-gradle-plugin:test --console=plain` then
  `./gradlew verifyGrazelGoldenBaseline --console=plain` (must print `...generated-file diff are
  clean.`; only the documented appcompat/constraintlayout bucket-labels waiver is acceptable).
  **Any golden drift on Task 2 = stop, revert, report** — it disproves drain-timing equivalence;
  do not patch the golden.
- **Semantics contract (spec §Semantics):** every project gets ≤1 inactive extraction (pass 1)
  and ≤1 active extraction (pass 1 or drain) — identical to today's round semantics. NO
  target-name-granularity re-extraction (spec non-goal 1). Non-migratable projects: reported in
  progress, never extracted (as today).
- **Existing test contracts must pass with assertions UNCHANGED** (only
  `ProjectReachabilityGroup(...)` construction at call sites adapts):
  `WorkspacePlanTasksTest` — exact progress strings `"collecting (1/2): :ui-tests"`,
  `"collecting (2/2): :app"`; `callsByProject` all == 1; mis-ordered two-hop reaches `util2`.
- **Full PAX sweep is mandatory** (Task 3) regardless of golden result — samples may never
  exercise the drain at runtime (item-2 lesson).
- One Gradle build at a time; bazelisk never concurrent with Gradle. PAX repo is git-read-only
  (migrate overwriting generated files in place is allowed).
- Stage explicit paths; never `git add -A`; never stage `codedb.snapshot`.
- Do not touch `TargetReferenceFactsExtractor`, `TargetVariantReachability`, `resolution/`,
  `bucket/`, or `TopologicalSorter`'s sort algorithm (only the group-wrapper surface).

---

## File map

| File | Change |
|---|---|
| `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/TopologicalSorter.kt:168-197` | Task 1: delete `ProjectReachabilityGroup`; `consumersFirstGroups` → `consumersFirstProjects(): List<Project>` |
| `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/CollectTargetMavenRepoReferencesTask.kt` | Task 1: drop wrapper at call site. Task 2: worklist rewrite |
| `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/tasks/internal/WorkspacePlanTasksTest.kt` | Task 1: construction updates. Task 2: +2 drain-shape tests |
| `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/TopologicalSorterTest.kt` | Task 1: construction/return-type updates (assertions unchanged) |
| `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/DefaultDependencyGraphsTest.kt` | Task 1: same |

---

### Task 1: Delete `ProjectReachabilityGroup` (S6)

**Files:**
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/TopologicalSorter.kt:168-197`
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/CollectTargetMavenRepoReferencesTask.kt` (action + the pass functions' `projectGroups` parameter)
- Modify: the three test files (construction only)

**Interfaces:**
- Produces: `ProjectReachabilityOrder.consumersFirstProjects(graphs, variantTypeFilter): List<Project>`
  and `collectTargetMavenRepoReferencesByGroup(projects: List<Project>, ...)` (function name
  unchanged in this task; Task 2 renames it). Task 2 consumes these.

- [ ] **Step 1: Replace the wrapper in `TopologicalSorter.kt`**

Delete the `ProjectReachabilityGroup` data class (line 168). Rename `consumersFirstGroups` to
`consumersFirstProjects`, return type `List<Project>`, and where it currently wraps
(`ProjectReachabilityGroup(listOf(node.project))`, line ~193) emit `node.project` directly. The
produced project sequence must be identical — this is a wrapper removal, not an ordering change.
Keep the existing KDoc, adjusted to the new return type.

- [ ] **Step 2: Update the task call site**

In `CollectTargetMavenRepoReferencesTask.action()`: `reachabilityGroups` becomes
`List<Project>`; the off-graph append loses its `.map { ProjectReachabilityGroup(listOf(it)) }`;
`totalProjects` becomes `orderedProjects.size`; `graphProjects` becomes `orderedProjects.toSet()`
equivalent (`reachabilityProjects.toSet()`). Thread `projects: List<Project>` through
`collectTargetMavenRepoReferencesByGroup` / `...ToFixedPoint` / `...SinglePass` — the two-level
`groups.forEach { group -> group.projects.fold... }` iteration flattens to one
`projects.fold(...)`. All behavior identical.

- [ ] **Step 3: Update the three test files** — drop the wrapper at construction sites
(e.g. `projectGroups = listOf(a, b).map { ProjectReachabilityGroup(listOf(it)) }` →
`projects = listOf(a, b)`); every assertion stays byte-for-byte.

- [ ] **Step 4: Gates**

Run: `./gradlew :grazel-gradle-plugin:test --console=plain` → `BUILD SUCCESSFUL`
Run: `./gradlew verifyGrazelGoldenBaseline --console=plain` → `...generated-file diff are clean.`

- [ ] **Step 5: Commit**

```bash
git add grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/TopologicalSorter.kt \
        grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/CollectTargetMavenRepoReferencesTask.kt \
        grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/tasks/internal/WorkspacePlanTasksTest.kt \
        grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/TopologicalSorterTest.kt \
        grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/DefaultDependencyGraphsTest.kt
git commit -m "refactor(resolver): delete ProjectReachabilityGroup singleton wrapper

consumersFirstGroups -> consumersFirstProjects returning List<Project>; the
group abstraction never grouped. Byte-identical (golden verified)."
```

---

### Task 2: The worklist

**Files:**
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/CollectTargetMavenRepoReferencesTask.kt` (everything below the task class)
- Modify: `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/tasks/internal/WorkspacePlanTasksTest.kt` (rename call sites + 2 new tests)

**Interfaces:**
- Consumes: Task 1's flattened `List<Project>` signatures.
- Produces: `internal fun collectTargetMavenRepoReferences(projects, canMigrate,
  factsForProject, workspaceRenderPlanService, reporter, isIntrinsicallyReachable): TargetReferenceFacts`
  (renamed from `collectTargetMavenRepoReferencesByGroup`; same defaulted
  `isIntrinsicallyReachable = { true }`).

- [ ] **Step 1: Write the two failing drain-shape tests** (append to `WorkspacePlanTasksTest`;
they use the NEW function name, so they fail to compile until Step 2)

```kotlin
    @Test
    fun `collect target references visits a never-activated project exactly once`() {
        // A non-intrinsically-reachable project that nothing ever references must be extracted
        // exactly once (the pass-1 inactive visit) and never reprocessed by the drain.
        val rootProject = buildProject("root")
        val appProject = buildProject("app", rootProject)
        val orphanProject = buildProject("orphan", rootProject)
        val workspaceRenderPlanService = WorkspaceRenderPlanService.register(rootProject).get()
        val callsByProject = mutableMapOf<String, Int>()

        val references: TargetReferenceFacts = collectTargetMavenRepoReferences(
            projects = listOf(orphanProject, appProject),
            canMigrate = { true },
            factsForProject = { project ->
                callsByProject[project.path] = callsByProject.getOrDefault(project.path, 0) + 1
                when (project.path) {
                    ":app" -> TargetReferenceFactsCollector.from(
                        deps = listOf(
                            MavenDependency(repo = "debug_maven", group = "com.example", name = "app-dep")
                        )
                    )
                    else -> TargetReferenceFactsCollector.from()
                }
            },
            workspaceRenderPlanService = workspaceRenderPlanService,
            reporter = ProgressReporter.NoOp,
            isIntrinsicallyReachable = { project -> project.path == ":app" }
        )

        assertEquals(mapOf(":orphan" to 1, ":app" to 1), callsByProject)
        assertEquals(setOf("debug_maven"), references.repoNames)
    }

    @Test
    fun `collect target references drain reports activated projects distinctly`() {
        // Drain-path progress: pass 1 reports "collecting (i/n)"; a deferred project activated
        // by the drain reports "collecting (activated): :path" — pinned so the reporter contract
        // is deliberate, not incidental.
        val rootProject = buildProject("root")
        val cProject = buildProject("c", rootProject)
        val utilProject = buildProject("util", rootProject)
        val workspaceRenderPlanService = WorkspaceRenderPlanService.register(rootProject).get()
        val progressMessages = mutableListOf<String>()

        collectTargetMavenRepoReferences(
            projects = listOf(utilProject, cProject),
            canMigrate = { true },
            factsForProject = { project ->
                when (project.path) {
                    ":c" -> TargetReferenceFactsCollector.from(
                        deps = listOf(ProjectDependency(utilProject, suffix = "-gps-pax-debug"))
                    )
                    else -> TargetReferenceFactsCollector.from()
                }
            },
            workspaceRenderPlanService = workspaceRenderPlanService,
            reporter = ProgressReporter(progressMessages::add),
            isIntrinsicallyReachable = { project -> project.path == ":c" }
        )

        assertEquals(
            listOf(
                "collecting (1/2): :util",
                "collecting (2/2): :c",
                "collecting (activated): :util"
            ),
            progressMessages
        )
    }
```

- [ ] **Step 2: Replace the three pass functions with the worklist**

Delete `collectTargetMavenRepoReferencesToFixedPoint`,
`collectTargetMavenRepoReferencesSinglePass`, and `collectProjectReferences`. Rename
`collectTargetMavenRepoReferencesByGroup` → `collectTargetMavenRepoReferences` and replace the
whole block with:

```kotlin
/**
 * Collects reference facts with a single consumers-first pass plus a deferred-activation drain,
 * then normalizes and republishes the settled view into [workspaceRenderPlanService] — the
 * ordering between raw accumulation and normalization is load-bearing, not incidental (the
 * render plan is also populated incrementally mid-pass with pre-normalization state).
 *
 * Semantics (unchanged from the round-based fixpoint this replaces): every project is extracted
 * at most once while inactive (pass 1) and at most once when it first becomes active — either
 * immediately in pass 1, or by the drain once a later-visited project's reference activates it.
 * A reference to a *different target name* of an already-processed project never re-extracts;
 * activation is project-path-granular by design.
 *
 * @param isIntrinsicallyReachable Marks projects whose inclusion doesn't depend on being
 * referenced first — typically true roots that nothing else in the dependency graph points at
 * (e.g. binaries). Anything else stays deferred until a recorded reference activates it.
 * Defaults to `{ true }`, i.e. everything settles in pass 1.
 */
internal fun collectTargetMavenRepoReferences(
    projects: List<Project>,
    canMigrate: (Project) -> Boolean,
    factsForProject: (Project) -> TargetReferenceFacts,
    workspaceRenderPlanService: WorkspaceRenderPlanService,
    reporter: ProgressReporter,
    isIntrinsicallyReachable: (Project) -> Boolean = { true }
): TargetReferenceFacts {
    val referenceFacts = collectToQuiescence(
        projects = projects,
        canMigrate = canMigrate,
        factsForProject = factsForProject,
        workspaceRenderPlanService = workspaceRenderPlanService,
        reporter = reporter,
        isIntrinsicallyReachable = isIntrinsicallyReachable
    )
    val references = referenceFacts.normalized()

    workspaceRenderPlanService.populateRenderPlan(references.asRenderPlan())
    return references
}

/**
 * Pass 1 visits every project in consumers-first order — publishing the accumulated facts
 * before each visit so extraction observes the current render plan, exactly as the old round 1
 * did. A migratable project that is inactive at visit time (neither [isIntrinsicallyReachable]
 * nor referenced yet) still contributes its extraction, and is parked in [deferred] in visit
 * order. The drain then repeatedly activates the first deferred project that has become
 * referenced (references only ever grow, so scanning in original order mirrors what the old
 * round n+1 would have processed first) until a full scan activates nothing. Termination is
 * structural: [deferred] only shrinks.
 */
private fun collectToQuiescence(
    projects: List<Project>,
    canMigrate: (Project) -> Boolean,
    factsForProject: (Project) -> TargetReferenceFacts,
    workspaceRenderPlanService: WorkspaceRenderPlanService,
    reporter: ProgressReporter,
    isIntrinsicallyReachable: (Project) -> Boolean
): TargetReferenceFacts {
    val totalProjects = projects.size
    var accumulated = TargetReferenceFacts()
    val deferred = LinkedHashMap<String, Project>()

    projects.forEachIndexed { index, project ->
        workspaceRenderPlanService.populateRenderPlan(accumulated.asRenderPlan())
        reporter.report("collecting (${index + 1}/$totalProjects): ${project.path}")
        if (!canMigrate(project)) return@forEachIndexed
        val isActive = isIntrinsicallyReachable(project) ||
            workspaceRenderPlanService.isReferencedProjectPath(project.path)
        accumulated = mergeTargetReferenceFacts(accumulated, factsForProject(project))
        if (!isActive) {
            deferred[project.path] = project
        }
    }

    while (true) {
        workspaceRenderPlanService.populateRenderPlan(accumulated.asRenderPlan())
        val activated = deferred.values.firstOrNull { project ->
            workspaceRenderPlanService.isReferencedProjectPath(project.path)
        } ?: break
        deferred.remove(activated.path)
        reporter.report("collecting (activated): ${activated.path}")
        accumulated = mergeTargetReferenceFacts(accumulated, factsForProject(activated))
    }

    return accumulated
}
```

Equivalence notes the implementer must respect (and the reviewer will check):
- **Publish-before-visit** is kept in both phases — extraction must observe accumulated state.
- **`isActive` is evaluated BEFORE extraction** (as today at old lines 368-372), so a project
  cannot self-activate via its own facts.
- **Non-migratable**: reported in progress, never extracted, never deferred (old
  `collectProjectReferences` line 364-367 behavior — note old code reported before the
  canMigrate check too).
- **Inactive extraction still merges** (old code always called `factsForProject`) — do NOT
  "optimize" it away; the reachable-fallback gates inside the extractor are what make inactive
  facts empty, and that emptiness is their behavior, not this function's.
- Delete the now-unused `settledProjects`/`everVisited`/`maxRounds` machinery and their KDocs
  wholesale; update the task-level KDoc on `action()` that references the fixed-point iteration
  (last paragraph) to describe the pass+drain instead.

- [ ] **Step 3: Update the three existing test call sites** to the new name
(`collectTargetMavenRepoReferences`, `projects = listOf(...)`) — every assertion unchanged.

- [ ] **Step 4: Full suite**

Run: `./gradlew :grazel-gradle-plugin:test --console=plain` → `BUILD SUCCESSFUL` — the three
pre-existing contracts (exact progress strings, one-call-per-project, mis-ordered two-hop) plus
the two new drain tests all green.

- [ ] **Step 5: Golden + bazel analysis**

Run: `./gradlew verifyGrazelGoldenBaseline --console=plain` → `...generated-file diff are clean.`
**Any drift: STOP, revert, report** (drain-timing non-equivalence — do not patch).
Run: `bazelisk build --nobuild //...` → `Build completed successfully`.

- [ ] **Step 6: Commit**

```bash
git add grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/CollectTargetMavenRepoReferencesTask.kt \
        grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/tasks/internal/WorkspacePlanTasksTest.kt
git commit -m "refactor(resolver): worklist replaces round-based fixpoint in reference collection

One consumers-first pass + deferred-activation drain; deletes settledProjects/
everVisited/maxRounds/per-round snapshot equality/pre-skip republish. Same
process-once-when-first-activated semantics; byte-identical (golden + bazel
analysis verified)."
```

---

### Task 3: Full PAX sweep (controller-run, mandatory)

Per `reports/specs/VERIFICATION-GATES.md` §PAX 1–6, in order; long builds in background:
1. Migrate: `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
   (~11 min) → exit 0.
2. Clean-tree: `git -C /Users/arun.sampathkumar/work/pax-android status --porcelain` → empty;
   `diff --check` clean.
3. Size guard: `reports/scripts/verify-pax-size-guard.sh --mode preserving` →
   bucketCount=11, pinfileCount=11, totalArtifactRoots=1945, no per-repo deltas.
4. APK: `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
   //app:app-gps-pax-debug-android-test.apk` → `Build completed successfully`.
5. Focused tests: the 3 targets → `3 tests pass`.
6. Graph analysis over the CI set: `QUERY_BAZEL_BIN=bazelisk BAZEL_ARGS="--config=ci"
   scripts/bazel/diff/list_unit_test_targets > /tmp/ut_targets.txt` then
   `bazelisk build --nobuild --keep_going --config=ci --target_pattern_file=/tmp/ut_targets.txt`
   → `Analyzed 1442 targets`, zero `no such package`/`no such target`.

Pass condition: ALL six. Any deviation: stop, report, no push, no PAX git writes.

### Task 4: `/simplify` pass (controller-run)

Run the `/simplify` skill over the effort's diff (`<Task-1-base>..HEAD`, code only). Apply only
byte-identity-safe fixes (gates: unit + golden); skip-with-reason anything judged false-positive
per this plan's semantics contract.

## Final verification

Whole-effort adversarial review (Opus, per standing directive) over the effort's commits with
the spec + this plan + the existing `WorkspacePlanTasksTest` contracts as inputs, focused on:
pass/drain equivalence to round semantics (especially publish points and `isActive` ordering),
progress-reporter contract, and whether any deleted guard had un-replicated behavior.

## Out of scope

Ordering-producer change (topo-sort of the merged quotient — documented follow-up in the spec);
`dependedUponProjects`; extractor/reachability files; `resolution/`; `bucket/`;
target-name-granularity activation.
