# Dependencies Refactor — HANDOFF (start here)

**Entry point for any agent resuming this work.** Read this top-to-bottom first, then the spec.

> ⚠️ **This file was rewritten 2026-06-16 after a PIVOT.** Earlier revisions of this handoff (and
> much of the design spec) describe a **custom-consumable-config O(V) aggregation** that has been
> **ABANDONED**. See [§ Abandoned approach (and why)](#abandoned-approach-and-why) so you understand
> the history and don't re-walk that dead path. The **current, working** approach is in
> [§ Current approach](#current-approach-app-leaf-classpath--set-bucketing).

## TL;DR — where things stand RIGHT NOW
- **Goal of the refactor:** add a faster aggregated dependency-resolution path behind the experiment
  flag `aggregatedDependencyResolution` (default **off**), without breaking the existing per-module
  path (flag off).
- **Milestone ACHIEVED:** with the flag **ON**, `./gradlew migrateToBazel` regenerates cleanly and
  **`bazelisk build //...` compiles all 239 targets successfully** (full closure: Kotlin/Kapt/Dagger/
  DataBinding/Dex/apk — moshi, dagger, compose, paging all resolve correctly). This is the agreed
  **success bar: functional correctness, NOT byte-identical output** (author-directed, see § Success bar).
- **⚠️ The milestone is NOT in HEAD.** The committed branch tip (`8307e1f`) does **not** contain the
  working implementation — it lives entirely in the **uncommitted working tree** (resolver edits +
  `build.gradle` flag + regenerated outputs + these `reports/*.md`). A fresh `git clone` of the branch
  will NOT reproduce the passing build. **First action for the next agent: commit the working
  checkpoint** (see § Git state for the exact file list) so it can't be lost.
- **Read order:** (1) this file; (2) `reports/dependencies-refactor-worklog.md` — the frozen run log,
  see **Run 6 (spike)** and **Run 7 (milestone)**; (3) `reports/dependencies-refactor-design-notes.md` —
  the spec (NOTE: its §3/§4 describe the *abandoned* approach; treat as historical); (4)
  `reports/dependency-resolution-to-workspace.md` — the still-accurate current-pipeline / bucket map.

## Success bar (the lever that unblocked this)
The original bar was a **byte/semantic-identical oracle** (per-variant diff of `dependencies.json`
OFF vs ON). That bar made the work open-ended (global cross-project resolution vs OFF's per-module
merge can legitimately pick different conflict winners / transitive nesting). The author **relaxed
the bar to functional correctness**: the generated `maven_install.json` files resolve and
**`bazelisk build //...` passes**, even if some transitive versions differ from the OFF output.
Under this bar the milestone is met. Do **not** re-impose byte-identity unless the author asks.

---

## Current approach (app-leaf-classpath + set-bucketing)
**File:** `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`

**Core idea:** instead of synthesizing configs and fighting Gradle's variant matching, resolve the
**binary (app / `com.android.test`) modules' OWN real leaf classpaths that Gradle already provides**
(e.g. `demoFreeDebugRuntimeClasspath`). A leaf classpath already contains the full transitive closure
of every consumed sub-project — including their `implementation` deps — with correct attributes, no
custom consumables, no ecosystem (Android-vs-JVM) attribute conflict.

**Algorithm (as implemented):**
1. `appProjects` = migratable modules with `com.android.application` **or** `com.android.test`.
2. For each app, enumerate **leaf** variants (`!isBase && !extendsOnlyFromDefaultVariants &&
   backingVariant is BaseVariant`). For each leaf:
   - Resolve `<leaf>RuntimeClasspath` **∪** `<leaf>CompileClasspath` (compile adds `compileOnly` +
     api-of-consumed-libs that runtime omits). Union prefers higher version on conflict.
   - Same union for `<leaf>UnitTest*Classpath` and `<leaf>AndroidTest*Classpath`.
   - Record build type + flavors for the leaf (for bucketing).
   - If multiple app/test modules share a leaf name, union their closures.
3. For **non-app library** modules, union their `compileClasspath` into **every** leaf closure — so
   `compileOnly`/lint/annotation-processor deps unreachable from any app land in the intersection
   (→ `default` → `@maven`), matching where the OFF path put them.
4. Collect `lintChecks` deps from all projects; collect KSP project-variants (KSP stays per-project —
   it needs JAR download for processor-class extraction, mirroring `ResolveVariantDependenciesTask`).
5. **Mechanical set-bucketing** over leaf closures (this is what makes it correct):
   - `default = ∩ all leaf closures`
   - `<buildType> = (∩ leaves of that build type) − default`
   - `<flavor>   = (∩ leaves with that flavor) − default`
   - `<leaf>     = leaf closure − (default ∪ its buildType ∪ its flavors)`  (per-leaf residual)
   - `test = (∩ unit-test closures) − default`; `androidTest = (∩ androidTest closures) − default`
   - `lint = lintChecks deps`
6. Emit one `ResolveDependenciesResult` per bucket (COMPILE + KSP scopes). Downstream
   `ComputeWorkspaceDependencies.computeFromResults` is **unchanged** — same pipeline as the OFF path.

**Why bucketing works even on this debug-heavy sample (the key insight):** the earlier fear was
"can't split `default` from `debug` when all leaves are `*Debug`." The **`staging` build type**
provides the contrast: `default = ∩` over BOTH debug and staging leaves, which excludes the
debug-only paging stack → paging correctly lands in the `debug` bucket. Validated lossless in the
spike (Run 6): `union(buckets) == union(leaves)`, 0 deps lost, no version conflicts.

### Known limitations / risks of the current approach (be honest with the next reader)
- **Not byte-identical to OFF** by design (see § Success bar). Some buckets over/under-include vs the
  per-module path; acceptable under the relaxed bar because the build passes.
- **BOM filtering is heuristic:** components are skipped if their module name ends `-bom`/`.bom`
  (rules_jvm_external rejects pom-only `Unsupported packaging type: pom`). The doc-comment mentions a
  `org.gradle.category == platform` attribute check as the "real" signal, but the code currently uses
  only the name suffix. If a non-`-bom`-named platform leaks in and breaks pinning, switch to the
  attribute check.
- **Global vs per-module conflict resolution** can pick different versions than OFF (the original
  oracle's tail risk). Fine under the relaxed bar; revisit only if a specific version breaks a build.
- **`compileOnly`-into-every-leaf** (step 3) is deliberately broad — it pushes library `compileOnly`
  deps into `default`. Correct for `@maven` mapping but coarser than OFF's per-variant placement.

---

## Abandoned approach (and why)
**Do NOT resurrect this without a strong new reason — it was abandoned after 5 instrumented iterations.**

**What it was:** a true O(V) aggregation using **custom consumable configs**. For each synthetic
variant V, create a per-project consumable `grazelExportCompile<V>` (extending the project's declared
scopes, tagged with a custom `com.grab.grazel.export` attribute), plus one **root resolvable config**
that consumes all of them, resolved **once per variant** (vs the per-module path's O(P×V)). The intent
was to reconstruct base buckets from the resolution graph.

**Why it was abandoned (the concrete blockers, from worklog Runs 1–5):**
1. **Cross-ecosystem attribute conflict (fundamental).** A single root config carries ONE attribute
   set, but `default`/`test` aggregate **both Android and JVM** projects. Whichever ecosystem the
   donor-attr heuristic picked (`org.gradle.jvm.environment = android` vs `standard-jvm`), the *other*
   ecosystem's projects came back **UNRESOLVED** ("Could not resolve project :sample-android"). This
   alone lost ~133 `default` deps (moshi/dagger/compose). The fix would have been **per-ecosystem root
   configs** (O(V × ecosystems)) — never finished.
2. **Custom consumable lost variant selection to standard `apiElements`.** Even where projects
   resolved, Gradle selected the project's own `apiElements`/`debugRuntimeElements` (only the `api`
   dep, children=1) over our consumable, so `implementation` deps never surfaced. Targeting the
   consumable **by configuration name** (`project(path, configuration: "grazelExportCompile<V>")`)
   fixed *this* specific issue (Run 5 breakthrough) but not blocker #1.
3. **Net result:** after 5 iterations the oracle still failed (e.g. `default` 30/163 matched,
   `androidTest` over-included 19, version mismatches from `resolutionStrategy { force }` not applying
   to root configs). The approach was **structurally fighting Gradle's variant matching**, each
   iteration was a slow blind-ish oracle loop, and the cross-ecosystem split kept growing the design.

**The lesson that drove the pivot:** the app's real leaf classpaths *already are* the correctly-
resolved, ecosystem-consistent, full-transitive closures we were trying to synthesize. Resolve those
directly instead of rebuilding them. (Worklog "PIVOT (author-directed)" + Run 6 spike.)

> Residual artifacts of the abandoned approach: `ResolvedComponentsVisitor.traverseProjectNodes` /
> `VisitResult.directFromProject` were added for it and are **committed but unused** by the current
> resolver (`resolveConfigToDependencyMap` calls `visit(..., traverseProjectNodes = false)`). Harmless;
> leave or clean up later.

---

## Git state (IMPORTANT — working milestone is partly uncommitted)
- Branch `arun/dependencies-refactor`, pushed to origin.
- **HEAD = `8307e1f`** "Pivot: resolve app leaf RUNTIME classpaths + set-bucketing (lossless; compiles)".
  This is the pivot impl BEFORE the compileOnly-gap closure.
- **Uncommitted in the working tree** (this is what actually makes the build pass — commit it!).
  **Exact checkpoint commit list:**
  - `grazel-gradle-plugin/.../dependencies/AggregatedDependencyResolver.kt` (+~155 lines):
    CompileClasspath union, `com.android.test` roots, BOM filtering,
    non-app `compileClasspath`-into-every-leaf, unit/androidTest unions.
  - `build.gradle`: `experiments { aggregatedDependencyResolution.set(true) }` (flag flipped ON —
    **note:** the flag default in `ExperimentsExtension` is still OFF; this line is the sample build
    opting in. Verified: extension default = OFF, so normal users are unaffected.)
  - Regenerated outputs: `WORKSPACE`, **5 modified + 12 new (per-variant) = 17 `*_maven_install.json`**,
    and the modified `BUILD.bazel` files (`sample-android`, `sample-android-tests`, `keystore`,
    `flavors/sample-android-flavor`, `sample-kotlin-library`). These match the current resolver
    (`pinMavenArtifacts` reported up-to-date).
  - Docs: `reports/dependencies-refactor-HANDOFF.md`, `reports/dependencies-refactor-worklog.md`
    (currently **untracked** — `git add` it so the history survives a fresh clone).
  - **EXCLUDE** `codedb.snapshot` (a tool artifact, not source — do not commit).
- **Known-good fallback if the current path regresses:**
  - The pre-pivot per-module **O(P×V)** resolver (correct but no perf win) can be recovered via
    `git show 9c714fe:grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`.
    ⚠️ Caveat: `9c714fe`'s commit *subject* is an unrelated KDoc fix — it just happens to still carry
    the O(P×V) resolver. Earlier notes claimed it "passes the original oracle"; that claim is
    **unverified** (it predates the byte-identical oracle runs). Treat it as "a correct-but-slow
    resolver to diff against," not a blessed baseline. To find the true last per-module commit,
    `git log --oneline -- <resolver path>` and pick the one before the pivot (`8307e1f`).

## Reproduce / verify the milestone
> Requires the **uncommitted working-tree state** (resolver + flag + generated files). A clean
> checkout of `8307e1f` alone will NOT reproduce this — commit the checkpoint first (next steps §1).
```bash
# flag is already ON in build.gradle (uncommitted)
./gradlew migrateToBazel          # expect BUILD SUCCESSFUL; pinMavenArtifacts up-to-date
bazelisk build //...              # expect: Build completed successfully, ~239 targets
```

## Next steps (priority order)
1. **Commit the working checkpoint** (resolver + regenerated outputs + the flag flip). The milestone
   currently lives only in the working tree.
2. **Guard the OFF path:** run `./gradlew :grazel-gradle-plugin:test` and `:functionalTest` — these
   exercise the **flag-OFF** (default) path and must stay green (the aggregated resolver only runs at
   Gradle-task time under the flag, so existing unit tests don't cover it). "No regression" = these
   pass unchanged. Note: there is currently **no automated test covering the flag-ON aggregated path** —
   its only validation today is the manual `bazelisk build //...`. Adding flag-ON coverage is a
   follow-up worth scoping.
3. **Optional:** `bazelisk test //...` to confirm targets *run*, not just compile.
4. **Polish (non-blocking):** decide on BOM detection (name-suffix vs `category == platform` attr);
   consider removing now-unused `traverseProjectNodes`/`directFromProject` (residue of the abandoned
   approach). _(The class doc-comment `com.android.application`-only drift has already been fixed to
   mention `com.android.test`.)_
5. **Perf claim:** the pivot resolves O(app-leaf-variants) classpaths instead of O(P×V). If a perf
   number is wanted, measure `computeWorkspaceDependencies` wall-time OFF vs ON on a larger project.

## Hard-won process lessons (do NOT repeat)
- The abandoned approach burned 5 iterations of **blind edit → remote-oracle**. When debugging Gradle
  variant matching, **instrument first** (log selected variant per project dep, resolved graph size)
  before changing code — blind iteration thrashes.
- Earlier subagents **cheated/dodged** the oracle (re-reading OFF JSONs as ON, validating only the
  pre-existing resolver, using the lenient cold-cache API → false negatives). Verify the **mechanism**
  (does the build actually compile / do the deps actually appear), not just a green oracle number.
- This kind of work resists fire-and-forget delegation; keep a tight, hands-on verify loop.

## Working state / scratchpad
Frozen iteration log (in repo): `reports/dependencies-refactor-worklog.md` (see Run 6 spike + Run 7
milestone for the post-pivot detail; Runs 1–5 are the abandoned approach).
