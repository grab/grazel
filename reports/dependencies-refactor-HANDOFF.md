# Dependencies Refactor — HANDOFF (start here)

> SUPERSEDED: do not use this file as the current resume entry point. Read
> `reports/dependencies-refactor-active-anchor.md` first, then
> `reports/dependencies-refactor-current-status.md`. This file is preserved only as historical
> evidence for bounded archaeology.

**Entry point for any agent resuming this work.** Read this top-to-bottom first, then the spec.

> ⚠️ **This file was rewritten 2026-06-16 after a PIVOT.** Earlier revisions of this handoff (and
> much of the design spec) describe a **custom-consumable-config O(V) aggregation** that has been
> **ABANDONED**. See [§ Abandoned approach (and why)](#abandoned-approach-and-why) so you understand
> the history and don't re-walk that dead path. The **current, working** approach is in
> [§ Current approach](#current-approach-app-leaf-classpath--set-bucketing).

## TL;DR — where things stand RIGHT NOW
- **Goal of the refactor:** make the faster aggregated dependency-resolution path the default,
  remove the old per-project/per-variant `ResolveDependencies` fan-out from the default generation
  path, and keep generated Bazel output aligned with master semantics except for intentional,
  documented bucket/lockfile diffs.
- **Current milestone:** the default path now uses aggregated resolution. `./gradlew migrateToBazel`
  regenerates cleanly, both Gradle unit and functional tests pass, the focused bucket/task-graph
  verifiers pass, and a plain **`bazelisk build //...` compiles all 239 targets successfully**.
- **Remaining known failure:** `bazelisk test //...` fails only the 8 generated lint test targets for
  existing sample lint/resource issues. The other 9 Bazel tests pass, and the explicit non-lint subset
  passes.
- **Current working state is still uncommitted.** Commit the resolver, task wiring, generated outputs,
  fixture updates, `.bazel/.default.bazelrc`, and `reports/*.md` together when packaging. Do not
  commit `codedb.snapshot` unless deliberately wanted; it is a tool artifact.
- **Read order:** (1) this file; (2) `reports/dependencies-refactor-worklog.md` — the frozen run log,
  see **Run 6 (spike)** and **Run 7 (milestone)**; (3) `reports/dependencies-refactor-design-notes.md` —
  the spec (NOTE: its §3/§4 describe the *abandoned* approach; treat as historical); (4)
  `reports/dependency-resolution-to-workspace.md` — the still-accurate current-pipeline / bucket map.

## Success bar
Use the master-generated Bazel files as the behavioral oracle, but do not require byte-identical
lockfiles. The current accepted standard is:
- The new path is convention-default `true`; the sample no longer opts in manually.
- Dry-run task graphs for `computeWorkspaceDependencies` and `migrateToBazel` do **not** schedule the
  old `*ResolveDependencies` fan-out.
- Generated labels preserve bucket semantics: common deps in `@maven`, debug deps in `@debug_maven`,
  androidTest deps in `@android_test_maven`, KSP/lint/artifact pinning intact.
- Every generated diff from master is either eliminated or documented with evidence.
- `bazelisk build //...` passes. Broad `bazelisk test //...` is allowed to remain red only for the
  isolated generated lint sample debt recorded in the goal log.

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
5. Collect declared Gradle exclude rules from migratable project configurations and attach them to
   emitted direct dependencies by `group:artifact`.
6. **Mechanical set-bucketing** over leaf closures plus explicit synthetic hierarchy buckets:
   - `default = (∩ all leaf closures + explicit default bucket) − non-default hierarchy ownership`
   - `<buildType> = explicit build-type bucket, or ∩ leaves of that build type, minus default`
   - `<flavor>   = explicit flavor bucket, or ∩ leaves with that flavor, minus default/build-type`
   - `<leaf>     = leaf closure − (default ∪ its buildType ∪ its flavors)`  (per-leaf residual)
   - `test`/`androidTest` = explicit test bucket ∪ leaf test intersections, minus direct main deps
   - `lint = lintChecks deps`
7. Emit one `ResolveDependenciesResult` per bucket (COMPILE + KSP scopes). Downstream
   `ComputeWorkspaceDependencies.computeFromResults` is **unchanged** — same pipeline as the OFF path.

**Why bucketing works even on this debug-heavy sample (the key insight):** the earlier fear was
"can't split `default` from `debug` when all leaves are `*Debug`." The **`staging` build type**
provides the contrast: `default = ∩` over BOTH debug and staging leaves, which excludes the
debug-only paging stack → paging correctly lands in the `debug` bucket. Validated lossless in the
spike (Run 6): `union(buckets) == union(leaves)`, 0 deps lost, no version conflicts.

### Known limitations / risks of the current approach (be honest with the next reader)
- **Not byte-identical to master** by design (see § Success bar). The current generated diff is
  intentionally documented rather than treated as a raw byte-for-byte oracle.
- **BOM filtering is heuristic:** components are skipped if their module name ends `-bom`/`.bom`
  (rules_jvm_external rejects pom-only `Unsupported packaging type: pom`). The doc-comment mentions a
  `org.gradle.category == platform` attribute check as the "real" signal, but the code currently uses
  only the name suffix. If a non-`-bom`-named platform leaks in and breaks pinning, switch to the
  attribute check.
- **Global vs per-module conflict resolution** can pick different versions than OFF (the original
  oracle's tail risk). Fine under the relaxed bar; revisit only if a specific version breaks a build.
- **`compileOnly`-into-every-leaf** (step 3) is deliberately broad — it pushes library `compileOnly`
  deps into `default`. Correct for `@maven` mapping but coarser than OFF's per-variant placement.
- **`bazelisk test //...` lint failures remain sample debt:** generated lint targets currently fail
  for duplicate generated resources and existing sample lint findings. Non-lint Bazel tests pass.

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

> Residual history note: `ResolvedComponentsVisitor.traverseProjectNodes` /
> `VisitResult.directFromProject` started during the abandoned approach but are now used by the
> current resolver. They were fixed to preserve direct ownership on repeated nodes and to ignore
> root dependency constraints as direct dependencies.

---

## Git state
- Branch `arun/dependencies-refactor`, pushed to origin, with the final working state currently in
  the local working tree.
- Important untracked source/docs that should be considered for packaging:
  - `reports/dependencies-refactor-goal-log.md`
  - `reports/scripts/verify-default-task-graph.sh`
  - `reports/scripts/verify-sample-bucket-labels.sh`
  - `grazel-gradle-plugin/src/test/projects/hybrid-dependency-substitution/.bazelversion`
  - `grazel-gradle-plugin/src/test/projects/hybrid-dependency-substitution/app/src/main/res/layout/layout_main.xml`
- Leave `codedb.snapshot` out unless the user explicitly wants tool-index artifacts.
- **Known-good fallback if the current path regresses:**
  - The pre-pivot per-module **O(P×V)** resolver (correct but no perf win) can be recovered via
    `git show 9c714fe:grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`.
    ⚠️ Caveat: `9c714fe`'s commit *subject* is an unrelated KDoc fix — it just happens to still carry
    the O(P×V) resolver. Earlier notes claimed it "passes the original oracle"; that claim is
    **unverified** (it predates the byte-identical oracle runs). Treat it as "a correct-but-slow
    resolver to diff against," not a blessed baseline. To find the true last per-module commit,
    `git log --oneline -- <resolver path>` and pick the one before the pivot (`8307e1f`).

## Reproduce / verify the milestone
```bash
./gradlew :grazel-gradle-plugin:test --console=plain
./gradlew :grazel-gradle-plugin:functionalTest --console=plain
./gradlew migrateToBazel --console=plain
./gradlew computeWorkspaceDependencies --dry-run --console=plain
./gradlew migrateToBazel --dry-run --console=plain
reports/scripts/verify-default-task-graph.sh
reports/scripts/verify-sample-bucket-labels.sh
git diff --check
bazelisk build //...
```

## Next steps (priority order)
1. Package/commit the working tree, excluding `codedb.snapshot` unless explicitly desired.
2. Decide whether to address generated lint tests now. Current `bazelisk test //...` fails only the 8
   generated lint targets; the non-lint subset passes.
3. Optional polish: improve BOM detection from suffix-based to Gradle platform attribute-based if a
   non-`*-bom` platform appears in real projects.
4. Optional perf proof: measure `computeWorkspaceDependencies` wall-time before/after on a larger
   project. The design now resolves binary leaf classpaths instead of P×variant module permutations.

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
