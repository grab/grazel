# Pre-Merge Code-Quality Pass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every open code-quality item on `arun/dependencies-refactor` (inline docs, packaging/structure findings #1–#6, two altitude generalisations) with generated Bazel output kept byte-identical, verified through `reports/specs/VERIFICATION-GATES.md` on grazel + PAX.

**Architecture:** Three risk tiers → three commits (docs, structure, altitude), preceded by a gate-correction/pre-flight task and closed by a final PAX sign-off. Comment-only and `internal`-only tiers gate locally; the algorithm tier gates locally + full PAX.

**Tech Stack:** Kotlin, Gradle, Bazel (bazelisk), Dagger. Grazel Gradle plugin. rules_jvm_external / coursier. Opus documentation workflow (Workflow tool).

## Global Constraints

- **Byte-identity gate.** Generated output must stay identical to the accepted baseline. Local oracle: `./gradlew verifyGrazelGoldenBaseline --console=plain` (proxy enabled via `localMavenResolution.set(true)` in `build.gradle`). Any change that moves the golden diff is reverted.
- **Build serialization.** Only ONE Gradle build at a time (local or PAX). Never start a build while another Gradle/PAX build or build-running worker is active.
- **PAX non-destructive rule.** In `/Users/arun.sampathkumar/work/pax-android` NEVER run `git stash/checkout/reset/commit/add/clean/restore/switch/branch -D/push`. `migrateToBazel --rerun-tasks` overwriting generated files in place is fine; all verification is read-only.
- **No git for doc-workflow agents.** Workflow subagents receive ONLY file-path lists — no git, no bash-git, no writes outside assigned files. (Direct lesson from the git-wipe incident.)
- **Never commit `codedb.snapshot`.** Check no snapshot is staged before every commit.
- **Test-cycle adaptation.** These are behaviour-preserving refactors, not new features. The "test" for each task is therefore: **compile + existing unit suite green + golden byte-identical**, not a new failing test. New tests are added only where a task explicitly says so.
- **Local gate (referenced throughout):**
  ```bash
  cd /Users/arun.sampathkumar/work/grazel
  ./gradlew :grazel-gradle-plugin:test --console=plain
  ./gradlew verifyGrazelGoldenBaseline --console=plain
  ```
  Golden success prints: `Grazel golden baseline verified: migrateToBazel, task graph, bucket labels, and generated-file diff are clean.` Known waiver: `verify-sample-bucket-labels.sh` may fail ONLY on the pre-existing appcompat/constraintlayout one-sided-exclude assertion; any other failure is real.
- **Full PAX sweep (referenced throughout):**
  ```bash
  cd /Users/arun.sampathkumar/work/pax-android
  ./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks   # ~11 min, background
  git -C /Users/arun.sampathkumar/work/pax-android status --porcelain               # MUST be empty (clean HEAD)
  git -C /Users/arun.sampathkumar/work/pax-android diff --check
  cd /Users/arun.sampathkumar/work/grazel && reports/scripts/verify-pax-size-guard.sh --mode preserving   # 11/11/1945, no deltas
  cd /Users/arun.sampathkumar/work/pax-android
  ./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk   # ~3 min, background
  ./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test   # 3 pass
  ```
  Long builds run in background and chain on completion; monitor disk (`df -g /`), warn < 12Gi free.

---

### Task 1: Correct the PAX gate semantics + pre-flight oracle check

Reason: PAX latest output is now committed (HEAD clean). The old step-2 diff-shape baseline (`1854 files … 775167 deletions`) assumed uncommitted output and no longer applies. Fix the gate doc, then prove the committed baseline is the true oracle.

**Files:**
- Modify: `reports/specs/VERIFICATION-GATES.md:66-72`

- [ ] **Step 1: Rewrite step 2 of the PAX gates for clean-HEAD semantics**

Replace the current step 2 block (lines 66-72) with:

```markdown
2. **Clean-tree check** (read-only). PAX HEAD carries the accepted generated
   output as committed. A byte-identical migrate therefore leaves the tree
   **clean**:
   ```bash
   git -C /Users/arun.sampathkumar/work/pax-android status --porcelain
   git -C /Users/arun.sampathkumar/work/pax-android diff --check
   ```
   Pass condition: `status --porcelain` prints **nothing** (no modified,
   no untracked generated files). Any modified/untracked generated file is a
   **regression**. (Superseded baseline, when output was uncommitted: `1854
   files changed, 68 insertions(+), 775167 deletions(-)`.)
```

- [ ] **Step 2: Pre-flight — confirm the committed PAX baseline is the oracle**

Only if no other Gradle build is running. Run the migrate, then the clean-tree check:

```bash
cd /Users/arun.sampathkumar/work/pax-android
./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks
git -C /Users/arun.sampathkumar/work/pax-android status --porcelain
```
Expected: migrate `BUILD SUCCESSFUL`; `status --porcelain` prints nothing.
If it prints changed files, STOP — the committed baseline diverges from what the current plugin generates; investigate before proceeding (do not mutate PAX git).

- [ ] **Step 3: Commit the gate-doc correction**

```bash
cd /Users/arun.sampathkumar/work/grazel
git status --porcelain | grep -i snapshot && echo "SNAPSHOT STAGED - ABORT" || true
git add reports/specs/VERIFICATION-GATES.md
git commit -m "docs: correct PAX gate to clean-HEAD semantics"
```

---

### Task 2: Restore inline documentation via git-locked Opus workflow (Commit 1 — Tier 1)

**Files (documented in place; no signatures change):**
- Modify (primary): `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/BucketOwnershipPlanner.kt` (879 lines, 0 KDoc)
- Modify (clusters): `gradle/dependencies/` (bucket algorithm + workspace/render plan), `proxy/` (local-maven), `migrate/dependencies/` (rje lockfile, pinning), `gradle/variant/` (variant facts) — main + test sources changed on the branch.
- Create (workflow script): `scratchpad/restore-branch-docs.workflow.js`

**Interfaces:**
- Consumes: the branch diff file-list (computed in Step 1 by the orchestrator, NOT by agents).
- Produces: inline KDoc only. No code statement changes → golden hash unchanged.

- [ ] **Step 1: Compute the cluster file-lists (orchestrator only)**

```bash
cd /Users/arun.sampathkumar/work/grazel
git diff --name-only origin/master...HEAD -- '*.kt' | sort
```
Partition the result into the six clusters from the spec (bucket algorithm; local-maven proxy; rje lockfile; variant/config facts; workspace/render plan; artifact pinning). Keep the six path-lists in the orchestrator; each becomes one agent's `args`.

- [ ] **Step 2: Author the git-locked doc workflow script**

Write `scratchpad/restore-branch-docs.workflow.js`. Requirements the script MUST enforce:
- One writer agent per cluster (model: opus), given ONLY its file-path list and the instruction to add KDoc for a zero-context reader — class-level "what/why/how it fits the pipeline" plus non-obvious member docs. Explicitly forbid: editing any file outside the list, running git, changing any non-comment token.
- After all writers: one adversarial read-only verifier agent per cluster (model: opus) that reads the cluster's files and reports any KDoc that misdescribes the code (drift), returning structured `{file, line, issue}` findings. Verifiers have NO write and NO git access.
- The orchestrator (this session), not the agents, applies verifier-flagged corrections.
- `meta.phases`: `[{title:'Document'},{title:'Verify'}]`. Use `pipeline()` so each cluster verifies as soon as its docs are written.

- [ ] **Step 3: Run the workflow**

```bash
# via the Workflow tool: {scriptPath: "scratchpad/restore-branch-docs.workflow.js"}
```
Then apply any verifier-confirmed doc corrections inline (orchestrator edits, not agents).

- [ ] **Step 4: Confirm docs are comment-only (no code moved)**

```bash
cd /Users/arun.sampathkumar/work/grazel
git diff --stat origin/master...HEAD -- '*.kt' | tail -1
# Sanity: BucketOwnershipPlanner now carries KDoc
grep -c '/\*\*' grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/BucketOwnershipPlanner.kt
```
Expected: KDoc count > 0.

- [ ] **Step 5: Local gate**

Run the Local gate (Global Constraints). Expected: unit tests pass; golden prints the "diff are clean" success line. If golden diff moves, a doc introduced a non-comment edit — find and revert it.

- [ ] **Step 6: Commit**

```bash
cd /Users/arun.sampathkumar/work/grazel
git status --porcelain | grep -i snapshot && echo "SNAPSHOT STAGED - ABORT" || true
git add -A -- '*.kt'
git commit -m "docs: restore inline documentation across dependency subsystem"
```

---

### Task 3: Structure #5 — rename `TasksManager.kt` → `TaskManager.kt`

**Files:**
- Rename: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/TasksManager.kt` → `.../TaskManager.kt`

`internal class TaskManager` is at line 39. Same package → no import changes.

- [ ] **Step 1: Rename**

```bash
cd /Users/arun.sampathkumar/work/grazel
git mv grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/TasksManager.kt \
       grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/TaskManager.kt
```

- [ ] **Step 2: Compile**

```bash
./gradlew :grazel-gradle-plugin:compileKotlin --console=plain
```
Expected: `BUILD SUCCESSFUL`. (No commit — Tier 2 commits once at Task 8.)

---

### Task 4: Structure #2 — split `MavenRepositoryPath.kt` into `MavenCoordinates.kt` + `MavenPath.kt`

**Files:**
- Delete: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/maven/MavenRepositoryPath.kt`
- Create: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/maven/MavenCoordinates.kt` (holds `MavenCoordinates` — lines 41-121 of the original)
- Create: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/maven/MavenPath.kt` (holds `MavenPath` — lines 21-39 — and top-level `isConcreteMavenArtifactPath` — lines 123-128)

All `internal`, same package `com.grab.grazel.maven` → no import changes anywhere.

- [ ] **Step 1: Create `MavenCoordinates.kt`**

License header + `package com.grab.grazel.maven` + `import java.io.File` + the `internal data class MavenCoordinates(...)` body verbatim from original lines 41-121.

- [ ] **Step 2: Create `MavenPath.kt`**

License header + `package com.grab.grazel.maven` + the `internal data class MavenPath(...)` (original 21-39) and `internal fun isConcreteMavenArtifactPath(path: String)` (original 123-128). No `java.io.File` import needed (MavenPath uses only MavenCoordinates + String).

- [ ] **Step 3: Delete the original and compile**

```bash
cd /Users/arun.sampathkumar/work/grazel
git rm grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/maven/MavenRepositoryPath.kt
./gradlew :grazel-gradle-plugin:compileKotlin --console=plain
```
Expected: `BUILD SUCCESSFUL`.

---

### Task 5: Structure #6 — split `JvmVariant`/`JvmVariantData` out of `Variant.kt`

**Files:**
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/variant/Variant.kt` (remove `JvmVariantData` line 212, `JvmVariant(...)` factory line 222, `class JvmVariant` line 235 → end)
- Create: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/variant/JvmVariant.kt`

Same package `com.grab.grazel.gradle.variant` → API-safe, no import changes for callers.

- [ ] **Step 1: Move the JVM concrete-variant block**

Cut from `Variant.kt` the `class JvmVariantData(...)`, the `fun JvmVariant(project, variantType)` factory, and the `class JvmVariant(...) : Variant<JvmVariantData>` (original lines ~212-317). Paste into new `JvmVariant.kt` with license header + `package com.grab.grazel.gradle.variant` + only the imports those types actually use (from the original import block: `ApplicationVariant`/`BaseVariant`/`LibraryVariant`/`TestVariant`/`UnitTestVariant` as referenced, `MoreObjects`, `hasKapt`, `hasKsp`, the `VariantType.*` used, `Project`, `Configuration`). Leave `Variant<T>` + shared vocabulary in `Variant.kt`; prune now-unused imports from `Variant.kt`.

- [ ] **Step 2: Compile**

```bash
./gradlew :grazel-gradle-plugin:compileKotlin --console=plain
```
Expected: `BUILD SUCCESSFUL`. If unused-import warnings appear in `Variant.kt`, prune them.

---

### Task 6: Structure #1 — move maven-repo naming family to `gradle.dependencies` and make `internal`

**Files:**
- Delete: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/dependencies/Repository.kt` (its entire content IS the naming family)
- Create: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/MavenRepoNaming.kt`
- Modify imports in 3 `migrate.dependencies` consumers (add import); remove now-redundant imports in 7 `gradle.dependencies` consumers.

**Interfaces:**
- Produces: `internal const val BASE_MAVEN_REPO`, `internal const val MAVEN_COMPILE_FILTER_TAG_PREFIX`, `internal fun String.toMavenRepoName()`, `internal fun String.toMaterializedMavenRepoName()` in package `com.grab.grazel.gradle.dependencies`.

- [ ] **Step 1: Create `MavenRepoNaming.kt`**

License header + `package com.grab.grazel.gradle.dependencies` + `import com.grab.grazel.gradle.variant.DEFAULT_VARIANT`, then the four declarations from old `Repository.kt`, each prefixed `internal`:

```kotlin
/** Name of the aggregated `maven_install` repository that the default variant maps to. */
internal const val BASE_MAVEN_REPO = "maven"

/** Label prefix of a compile-filter tag pointing at the aggregated `@maven` repository. */
internal const val MAVEN_COMPILE_FILTER_TAG_PREFIX = "@$BASE_MAVEN_REPO//:"

internal fun String.toMavenRepoName() = when (this) {
    DEFAULT_VARIANT -> BASE_MAVEN_REPO
    else -> replace("([a-z])([A-Z]+)".toRegex(), "\$1_\$2")
        .toLowerCase() + "_maven"
}

internal fun String.toMaterializedMavenRepoName() = when {
    this == BASE_MAVEN_REPO || endsWith("_maven") -> this
    else -> toMavenRepoName()
}
```

- [ ] **Step 2: Delete old `Repository.kt`**

```bash
cd /Users/arun.sampathkumar/work/grazel
git rm grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/dependencies/Repository.kt
```

- [ ] **Step 3: Fix imports in the 7 `gradle.dependencies` consumers**

These are now same-package; remove any `import com.grab.grazel.migrate.dependencies.{BASE_MAVEN_REPO|MAVEN_COMPILE_FILTER_TAG_PREFIX|toMavenRepoName|toMaterializedMavenRepoName}` line from:
`OverrideTargets.kt`, `TargetReferenceFactsCollector.kt`, `WorkspacePlanBuilder.kt`, `WorkspaceRenderPlanBuilder.kt`, `MavenInstallStore.kt`, `Dependencies.kt`, `model/ResolveDependenciesResult.kt` (all under `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/`).

Locate the exact lines:
```bash
grep -rn "import com.grab.grazel.migrate.dependencies.\(BASE_MAVEN_REPO\|MAVEN_COMPILE_FILTER_TAG_PREFIX\|toMavenRepoName\|toMaterializedMavenRepoName\)" grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies
```

- [ ] **Step 4: Fix imports in the 3 `migrate.dependencies` consumers**

`LocalMavenPinningWorkspace.kt`, `ClasspathReduction.kt`, `MavenInstallArtifactsCalculator.kt` must now import from the new package. For each symbol they reference, add e.g. `import com.grab.grazel.gradle.dependencies.BASE_MAVEN_REPO`. Confirm which symbols each uses:
```bash
grep -n "BASE_MAVEN_REPO\|MAVEN_COMPILE_FILTER_TAG_PREFIX\|toMavenRepoName\|toMaterializedMavenRepoName" \
  grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/dependencies/{LocalMavenPinningWorkspace,ClasspathReduction,MavenInstallArtifactsCalculator}.kt
```
Also scan the whole tree for any other references (tests included):
```bash
grep -rn "com.grab.grazel.migrate.dependencies.\(BASE_MAVEN_REPO\|toMavenRepoName\|toMaterializedMavenRepoName\|MAVEN_COMPILE_FILTER_TAG_PREFIX\)" grazel-gradle-plugin/src
```
Fix any additional hits (main or test) to the new package.

- [ ] **Step 5: Compile main + test**

```bash
./gradlew :grazel-gradle-plugin:compileKotlin :grazel-gradle-plugin:compileTestKotlin --console=plain
```
Expected: `BUILD SUCCESSFUL`. (`internal` visibility is module-wide, so cross-package use inside the plugin module stays legal.)

---

### Task 7: Structure #3 — move Gradle fact-collection builders out of `proxy/LocalMavenResolvedFacts.kt`

**Files:**
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/proxy/LocalMavenResolvedFacts.kt` (keep DTO `LocalMavenResolvedFacts` line 35 + the `PomFileResolver` fun-interface line 286 + `PomFileResolution` sealed line 290)
- Create: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/LocalMavenResolvedFactsBuilder.kt`
- Create: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/GradleModuleCacheFileResolver.kt`
- Create: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/GradlePomFileResolver.kt`

Move these declarations (from current `LocalMavenResolvedFacts.kt`) into `gradle.dependencies`:
- `LocalMavenResolvedFactsBuilder` (l.42), `metadataOnlyComponentGavs` (l.237), `mergeArtifactIndexes` (l.226), the artifact/component index builders referenced by the builder → `LocalMavenResolvedFactsBuilder.kt`.
- `GradleModuleCacheFileIndexBuilder` (l.118), `GradleModuleCacheFileResolver` (l.144), `putMavenFile` (l.252), `singleMavenFileOrNull` (l.269) → `GradleModuleCacheFileResolver.kt`.
- `GradlePomFileResolver` (l.296), `PomArtifactQuery` (l.372), `PomCacheLookup` (l.376) → `GradlePomFileResolver.kt`.

Keep in `proxy/LocalMavenResolvedFacts.kt`: the `LocalMavenResolvedFacts` DTO, `PomFileResolver` fun-interface, `PomFileResolution` sealed interface (the proxy hand-off surface).

**Interfaces:**
- Consumes: `com.grab.grazel.maven.MavenCoordinates`/`MavenPath`/`isConcreteMavenArtifactPath` (Task 4), Gradle `ResolvedComponentResult`/`ResolvedArtifactResult`.
- Produces: same public type names, new package `com.grab.grazel.gradle.dependencies`. Introduces a `proxy -> gradle.dependencies` dependency (acceptable, matches flow).

- [ ] **Step 1: Create the three new files in `gradle.dependencies`**

Each: license header + `package com.grab.grazel.gradle.dependencies` + the imports that block actually uses (copy from the proxy file's import list, keeping only what each moved block references) + the moved declarations verbatim. Keep every type `internal`.

- [ ] **Step 2: Trim `proxy/LocalMavenResolvedFacts.kt`**

Remove the moved declarations. Keep DTO + `PomFileResolver` + `PomFileResolution`. Prune imports now unused (e.g. `ResolvedComponentResult`, `MavenModule`, `ConcurrentHashMap`) — keep only what the DTO/interfaces reference.

- [ ] **Step 3: Update consumers' imports**

Consumers of the moved builders: `proxy/LocalMavenProxyServer.kt`, `tasks/internal/PinMavenArtifactsTask.kt`, and test `proxy/LocalMavenResolvedFactsTest.kt`. Add `import com.grab.grazel.gradle.dependencies.LocalMavenResolvedFactsBuilder` (and any other moved type they name). Find references:
```bash
grep -rn "LocalMavenResolvedFactsBuilder\|GradleModuleCacheFileResolver\|GradlePomFileResolver\|GradleModuleCacheFileIndexBuilder\|metadataOnlyComponentGavs\|PomArtifactQuery\|PomCacheLookup" grazel-gradle-plugin/src
```
Update each hit to the new package. The DTO/`PomFileResolver`/`PomFileResolution` references stay pointing at `com.grab.grazel.proxy`.

- [ ] **Step 4: Compile main + test**

```bash
./gradlew :grazel-gradle-plugin:compileKotlin :grazel-gradle-plugin:compileTestKotlin --console=plain
```
Expected: `BUILD SUCCESSFUL`.

---

### Task 8: Structure #4 — relocate rje package-overview KDoc + Tier-2 gate & commit

**Files:**
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/dependencies/RulesJvmExternalLockfile.kt` (move the subsystem-overview KDoc block off the file header; leave a short model+parser-scoped doc)
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/dependencies/MavenInstallLockfileReconstructor.kt` (receive the overview block as its class KDoc)

Note ordering: this runs AFTER Task 2 restored docs, so reconcile with whatever overview text now exists (this supersedes the "stale item #4" concern in the structure review).

- [ ] **Step 1: Move the overview block**

Cut the subsystem-wide "rules_jvm_external lockfile & maven-install artifact rendering … Domain vocabulary used throughout this package" KDoc from `RulesJvmExternalLockfile.kt`'s header. Paste it as the class KDoc of `internal class MavenInstallLockfileReconstructor` (the fallback entry point the block describes). Leave `RulesJvmExternalLockfile.kt` with a brief doc scoped to the `RulesJvmExternalLockfile` data class + `RulesJvmExternalLockfileParser` only.

- [ ] **Step 2: Compile**

```bash
./gradlew :grazel-gradle-plugin:compileKotlin --console=plain
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Reconcile the structure-review report**

In `reports/specs/dependency-refactor-structure-review.md`, mark findings #1–#6 as applied (brief "Applied on <this branch>" note per item) so the report no longer reads as outstanding.

- [ ] **Step 4: Tier-2 local gate**

Run the Local gate (Global Constraints). Expected: unit tests pass; golden "diff are clean". If golden moves, a structure move perturbed output (unexpected for `internal`/same-name moves) — bisect the six findings and revert the offender.

- [ ] **Step 5: Commit (Commit 2 — Tier 2)**

```bash
cd /Users/arun.sampathkumar/work/grazel
git status --porcelain | grep -i snapshot && echo "SNAPSHOT STAGED - ABORT" || true
git add -A
git commit -m "refactor: resolve packaging/structure findings #1-#6"
```

---

### Task 9: Altitude — unify main vs Test/AndroidTest planning pipeline

Highest regression risk. Oracle-driven: apply the smallest generalisation, prove byte-identical, keep or revert.

**Files:**
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/BucketOwnershipPlanner.kt`
- Modify (if a shared helper lands there): `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/BucketSetMath.kt`

Current shape: `plan()` (l.58) calls `planMainBuckets()` (l.93) then `planTestBuckets(variantType = Test, …)` (l.355) and `planTestBuckets(variantType = AndroidTest, …, inheritedTestCoveredDeps = testBuckets…)`. `planTestBuckets`→`plannedTestBuckets` (l.470) parallels `planMainBuckets`'s per-project bucket walk (`declaredMetadataByOutputBucket`, `linkedMapOf` accumulation, covered-deps-by-short-id). The two test invocations already share `planTestBuckets`; the target is the residual main-vs-test duplication (per-bucket accumulation + covered-dependency plumbing).

- [ ] **Step 1: Establish the oracle baseline**

```bash
cd /Users/arun.sampathkumar/work/grazel
./gradlew verifyGrazelGoldenBaseline --console=plain
```
Expected: green. This is the reference the refactor must not move.

- [ ] **Step 2: Extract the shared per-bucket accumulation helper**

Identify the accumulation logic duplicated between `planMainBuckets` (l.109-146 area: `declaredMetadataByOutputBucket` build + `applyDeclaredMetadata`) and `plannedTestBuckets` (l.478-510 area: `buckets`/`declaredMetadataByOutputBucket` + `addPlannedBucket`). Extract the common shape into ONE private helper (parameterised by the variant-specific inputs), and call it from both. Do NOT change what values are computed — only where the code lives. Keep `linkedMapOf` insertion order identical (ordering is load-bearing for the golden hash — proven earlier for `merge()`).

- [ ] **Step 3: Compile + unit tests**

```bash
./gradlew :grazel-gradle-plugin:test --console=plain
```
Expected: `BUILD SUCCESSFUL`, suite green.

- [ ] **Step 4: Golden byte-identity check (the gate that matters)**

```bash
./gradlew verifyGrazelGoldenBaseline --console=plain
```
Expected: "diff are clean". **If the diff moves, `git checkout -- <the planner file>` (grazel repo only — this file, NOT PAX) and abandon this generalisation** — record it as "not safely unifiable" and move to Task 10. Byte-identity outranks the refactor.

---

### Task 10: Altitude — merge the `canCover*` predicate family + Tier-3 gate & commit

**Files:**
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/BucketSetMath.kt:89-111`

Current: `CoveredDependency.canCover` (l.89), `ResolvedDependency.canCoverDeclaredPlaceholder` (l.95), `CoveredDependency.rootsSupersetClosureOf` (l.107) are three related predicates used in `withoutDependenciesCoveredByShortId` (l.47-85). Target: reduce duplication/clarify without changing any boolean outcome.

- [ ] **Step 1: Merge shared predicate structure**

Where the three predicates repeat `direct`/identity checks, factor the shared sub-expression into a single well-named private helper and have each predicate call it. Preserve every boolean result exactly — this is presentation only. Do not reorder the `exactCoveredDependency ?: supersetClosureCoveredDependency ?: firstCoveredDependency` precedence in the caller.

- [ ] **Step 2: Compile + unit tests**

```bash
./gradlew :grazel-gradle-plugin:test --console=plain
```
Expected: suite green.

- [ ] **Step 3: Golden byte-identity check**

```bash
./gradlew verifyGrazelGoldenBaseline --console=plain
```
Expected: "diff are clean". If it moves, revert this file (`git checkout -- .../BucketSetMath.kt`, grazel repo only) and record as not-unifiable.

- [ ] **Step 4: Tier-3 full PAX sweep**

Run the Full PAX sweep (Global Constraints). Serialize builds; launch migrate + APK in background; monitor disk. Expected: PAX tree clean after migrate; `diff --check` clean; size guard 11/11/1945 no deltas; APK `Build completed successfully`; focused tests `3 tests pass`.

- [ ] **Step 5: Commit (Commit 3 — Tier 3)**

```bash
cd /Users/arun.sampathkumar/work/grazel
git status --porcelain | grep -i snapshot && echo "SNAPSHOT STAGED - ABORT" || true
git add -A
git commit -m "refactor: generalise bucket main/test pipeline and covered-dependency predicates"
```
(If BOTH altitude generalisations were abandoned as not-unifiable, skip this commit and record the finding in the structure review instead.)

---

### Task 11: Final sign-off — full PAX sweep + push

- [ ] **Step 1: Final full PAX sweep**

Run the Full PAX sweep once more against the tip of the branch (all three commits present). Expected: identical clean/size/APK/test results as Task 10.

- [ ] **Step 2: Push**

```bash
cd /Users/arun.sampathkumar/work/grazel
git log --oneline origin/arun/dependencies-refactor..HEAD
git push origin arun/dependencies-refactor
```
Expected: fast-forward push; MR !165 updated.

---

## Self-Review

**Spec coverage:**
- Gate adaptation (clean-HEAD PAX) → Task 1. ✔
- Tier 1 docs (git-locked Opus workflow, adversarial verify, BucketOwnershipPlanner) → Task 2. ✔
- Tier 2 findings #1 (move+internal), #2 (split), #3 (fact-builder move), #4 (KDoc relocate), #5 (rename), #6 (JvmVariant split) → Tasks 3-8. ✔
- Tier 3 altitude (main/test unify, canCover merge, oracle-driven) → Tasks 9-10. ✔
- Gate cadence (local per tier; PAX at algorithm + final) → Tasks 8 (local), 10 (local+PAX), 11 (PAX). ✔
- Non-destructive PAX rule, no-git-for-agents, byte-identity → Global Constraints + Task 2 Step 2. ✔

**Placeholder scan:** No TBD/TODO. Refactor tasks use compile+golden as the test cycle (declared in Global Constraints), not stub "write tests" steps. Altitude tasks carry a concrete revert rule rather than open-ended "handle edge cases".

**Type consistency:** Symbol names (`BASE_MAVEN_REPO`, `toMavenRepoName`, `toMaterializedMavenRepoName`, `MAVEN_COMPILE_FILTER_TAG_PREFIX`, `MavenCoordinates`, `MavenPath`, `isConcreteMavenArtifactPath`, `LocalMavenResolvedFactsBuilder`, `GradleModuleCacheFileResolver`, `GradlePomFileResolver`, `PomFileResolver`, `PomFileResolution`, `MavenInstallLockfileReconstructor`, `JvmVariant`, `JvmVariantData`) match the grepped source. Target packages consistent: naming family + fact builders → `com.grab.grazel.gradle.dependencies`; DTO/interfaces stay in `com.grab.grazel.proxy`.
