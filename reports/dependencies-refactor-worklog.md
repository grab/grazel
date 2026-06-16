# Grazel Dep Refactor — ITERATION LOG (frozen history)

> 🧊 **FROZEN HISTORICAL RECORD — not the living entry point.**
> This is the append-only run-by-run log (Runs 1→7) with the raw oracle numbers and diagnoses behind
> the decisions. It is preserved as **evidence/receipts** so the next agent doesn't re-run a failed
> experiment to re-learn its result. For the **current state, success bar, and next steps, read
> `reports/dependencies-refactor-HANDOFF.md`** — that is the single living source of truth.
>
> Reading guide: **Runs 1–5** = the ABANDONED custom-consumable O(V) approach and why it failed.
> **PIVOT** section + **Run 6 (spike)** + **Run 7 (milestone)** = the current app-leaf-classpath
> approach that achieves a passing `bazelisk build //...`. Anything in Runs 1–5 framed as "next fix"
> is obsolete (it targets the abandoned approach).

Repo: /home/arunkumar9t2/Work/projects/grazel  Branch: arun/dependencies-refactor
Spec: reports/dependencies-refactor-design-notes.md (§ refs below). Handoff: reports/dependencies-refactor-HANDOFF.md

## GOAL
Build true O(V) aggregation: custom-consumable-config, ONE root resolution per LEAF variant,
reconstruct base buckets (default/debug/androidTest/lint/flavor) via extendsFrom set-diff,
feed per-synthetic-variant-name ResolveDependenciesResult (DIRECT-only) into
ComputeWorkspaceDependencies.computeFromResults. Must pass §2.4 oracle (semantic dep.json OFF vs ON).

## KEY FILES
- gradle/dependencies/AggregatedDependencyResolver.kt  <- REWRITE (currently per-module O(PxV))
- gradle/dependencies/ResolvedComponentsVisitor.kt (has traverseProjectNodes)
- gradle/dependencies/ComputeWorkspaceDependencies.kt (computeFromResults, computeInternal)
- gradle/dependencies/model/ResolveDependenciesResult.kt (ResolvedDependency, Scope.COMPILE/KSP)
- tasks/internal/ComputeWorkspaceDependenciesTask.kt (flag branch)
- gradle/variant/Variant.kt, AndroidVariants.kt, VariantBuilder.kt (extendsFrom, isBase, leaf)

## CONSTRAINTS (design-notes §4.4)
- DIRECT-ONLY emission (else non-default buckets collapse into default).
- Base buckets NOT root-aggregatable directly (AmbiguousGraphVariantsException) -> leaf + set-diff.
- WARM CACHE + resolutionResult graph; NEVER lenientConfiguration.allModuleDependencies.
- KotlinPlatformType=androidJvm must be set.

## ORACLE
warm cache -> ./gradlew computeWorkspaceDependencies (OFF) -> save build/grazel/dependencies.json
-> add grazel{experiments{aggregatedDependencyResolution=true}} to root build.gradle -> rerun
-> git checkout -- build.gradle -> semantic per-variant set diff. Revert generated drift after.

## DESIGN INSIGHT (the one that makes O(V) work on the debug-only sample)
Do NOT use leaf-aggregation + intersection to derive base buckets — it FAILS on the debug-only
sample (can't separate default from debug when all leaves are *Debug).
INSTEAD: custom-consumable-config keyed by SYNTHETIC variant name.
- For each synthetic variant V (the SAME set attempt-3 uses: isBase || extendsOnlyFromDefaultVariants
  => default/debug/androidTest/lint/flavor-singles), on each migratable project create a CONSUMABLE
  config `grazelExport_<V>` that `extendsFrom` that project's `grazel<V>CompileClasspath`
  (which already encodes the correct declared scopes — default = main impl/api WITHOUT debugImpl;
  debug = + debugImpl), tagged with custom attr `com.grab.grazel.export = <V>`.
- Root resolvable config per V with matching custom attr, depends on all migratable projects.
- Resolve ONCE per synthetic V => O(V) root resolutions (vs attempt-3's O(P×V)).
- Custom attr UNIQUELY identifies => NO AmbiguousGraphVariantsException, and correctly separates
  default vs debug because the underlying grazel<V>CompileClasspath configs differ in scope.
- Walk graph traverseProjectNodes=true; direct = immediate child of a project node (OR across projects).
- Produce ONE ResolveDependenciesResult per synthetic variant NAME, DIRECT-only.
- computeFromResults/computeInternal does the bucketing (subtract non-default vs default) AS BEFORE.
  So NO manual set-diff needed — same downstream as attempt-3, just fewer resolutions.
KSP: keep per-project (needs artifact download) as attempt-3 does.
RISK to watch in oracle: does extendsFrom(grazel<V>CompileClasspath) into a consumable correctly
expose the declared deps for root resolution? (Investigation B1 said consumable-extendsFrom-resolvable
works.) Verify direct-flag + nested deps + repository match OFF.

## PROGRESS LOG
- [done] Pushed branch to origin (9c714fe). Created HANDOFF + this worklog.
- [done] Read current AggregatedDependencyResolver.kt (per-module, O(PxV)) + ResolvedComponentsVisitor
  (HAS traverseProjectNodes + VisitResult.directFromProject already committed).
- [done] DESIGN: per synthetic variant, create consumable `grazelExportCompile<V>` on each project
  (extendsFrom grazel<V>CompileClasspath + custom attr `com.grab.grazel.export`=`compile-<V>`),
  root resolvable `grazelAggregatedCompile<V>` (same attr) depends on all participating projects,
  resolve ONCE, walk traverseProjectNodes=true, emit external deps where shortId in UNION of
  declared directDepShortIds across projects (direct-only). Gradle conflict-resolves => one node per
  module (no manual maxVersion). KSP stays per-project. computeFromResults unchanged.
- [in-progress] Rewriting AggregatedDependencyResolver.kt resolveVariant -> aggregated.
- [ ] Compile.
- [ ] Oracle OFF vs ON. Record residual here.
- RISKS: exec-time config creation/resolution; attr-match uniqueness (set ONLY custom attr on
  root+consumable); cross-project conflict-res vs maxVersion; nested transitiveDeps global-vs-local.

## ORACLE RESULTS (append each run)

### Run 1 (O(V) aggregation v1) — FAIL. Compiles + runs clean (no exceptions/warnings).
ON output: only `default` bucket, 19 deps (all from JVM/Kotlin-only projects). OFF: 4 buckets
(default:163, debug:29, androidTest:5, lint:2 = 199). ON `default`'s 19 = strict subset of OFF;
4 version mismatches (ON LOWER: annotation 1.0.0 vs 1.8.1; kotlin-stdlib-jdk7/8 1.8.21 vs 1.9.20;
annotations 13.0 vs 23.0.0). transitiveClasspath ON=5 vs OFF=40.

DIAGNOSIS (3 problems):
1. **Android projects contribute NOTHING.** debug/androidTest/lint buckets entirely missing;
   default only has JVM-project deps. => the custom-consumable aggregation is NOT exposing Android
   modules' deps through the root resolution. (attempt-3 found grazel<V>CompileClasspath by name and
   resolved them directly and PASSED — so the configs exist; the problem is my
   consumable-extendsFrom-grazel<V>CompileClasspath + root-consume path doesn't surface Android deps.)
2. **Version divergence** (4 deps lower in ON): root aggregating configs don't inherit the project's
   `resolutionStrategy { force ... }` (allprojects). Need to apply force rules to the root configs.
3. transitiveClasspath under-populated (follows from #1).

### NEXT DEBUG STEPS (for continuation)
- INSTRUMENT: log per variant: participatingProjects.size, and per-project the root-resolution graph
  child count + which consumable variant Gradle selected for each Android project dep (is attr
  matching picking grazelExportCompile<V>?). This pinpoints where Android projects drop out.
- LIKELY FIX for #1: have the consumable `extendsFrom` the DECLARED scopes directly
  (implementation, api, <buildType>Implementation, <flavor>Implementation) — Investigation B (design
  §4.3) used exactly this and it exposed moshi/impl deps. My v1 instead extendsFrom
  grazel<V>CompileClasspath (a resolvable config); that indirection may not propagate Android deps as
  outgoing. Map synthetic variant V -> its declared scope configs (see ConfigurationParsingVariant /
  the metadata extendsFrom logic that grazel<V>CompileClasspath itself uses).
- FIX for #2: copy `resolutionStrategy` (force/substitutions) onto the root aggregating config, or
  apply the project's force rules. Check how grazel<V>CompileClasspath gets its versions right.
- Re-run oracle. The OFF baseline buckets to match: default:163, debug:29, androidTest:5, lint:2.

### STATE: O(V) v1 committed as WIP (compiles, FAILS oracle). Flag default-off so normal use unaffected.
  To get a WORKING (but O(PxV)) ON path back, git revert to the attempt-3 resolver (commit 329f438
  had it; or `git show 9c714fe:...AggregatedDependencyResolver.kt`).

- [in-progress] Wrote O(V) aggregated resolveAggregatedCompileDeps. Dispatching compile+oracle.

### Iteration 1 fix applied: consumable now extendsFrom declarable parents (src.extendsFrom), 
with fallback to extend src directly when extendsFrom empty (lintChecks). Re-running oracle.
Relaxed bar: version diffs OK; care = all buckets present + Android impl deps (moshi/dagger) surface.

### Run 2 (iteration 1: extend declarable parents) — still FAIL, but sharper diagnosis.
Compiles+runs clean. ON: default=19 (moshi NOW present 1.15.0!), debug/androidTest/lint=0.
default missing 144 (incl dagger, ALL compose/lifecycle transitives, timber). 4 version
mismatches (ON LOWER: annotation 1.0.0, kotlin-stdlib-jdk7/8 1.8.21, annotations 13.0).
transitiveClasspath ON=5 vs 40.

SHARP DIAGNOSIS (root cause now clear):
- The emitted direct deps carry SHALLOW/empty nested transitiveDeps, AND versions are OLDER.
  Both symptoms point to ONE cause: my consumable + root configs set ONLY the custom `export`
  attribute and OMIT the STANDARD attributes (Usage=JAVA_RUNTIME, Category=LIBRARY,
  TargetJvmEnvironment=ANDROID, KotlinPlatformType=androidJvm, AgpVersionAttr). Without them,
  Gradle can't select correct variants of TRANSITIVE external deps -> shallow graph + wrong
  (older) versions. ConfigurationParsingVariant.applyAttributes sets exactly these on
  grazel<V>CompileClasspath; investigation B's WORKING config had them; my impl dropped them.
- COMPLICATION (mixed projects): the single root config aggregates Android + JVM-only
  (sample-kotlin-library) projects. One attribute set can't be right for both ecosystems.
  Android attrs may break JVM-project consumption (and vice-versa). May need TWO root configs
  per variant (android-attrs + jvm-attrs) unioned, OR set attrs per-consumable by project type.
- debug/androidTest/lint=0: likely same shallow-resolution failure cascading (their
  variant-specific transitives don't resolve), or participating-project resolution degraded.

ITERATION 2 (if continuing): add standard attrs (mirror ConfigurationParsingVariant.applyAttributes)
to consumable+root; ADD INSTRUMENTATION (log per variant: participating projects, selected
consumable variant per project dep, resolved graph size) to SEE what happens; handle mixed
Android/JVM (maybe per-project-type attrs or split configs). Then oracle.
ASSESSMENT: needs instrumented hands-on debugging; blind remote-oracle iteration is low-yield now.

### Run 3 (iter2: copy standard attrs onto aggregating configs) — REGRESSION. Compiles, no warnings.
ON: default=0 (was 19!), debug=2, androidTest=0, lint=0. moshi/dagger/timber/compose ALL absent.
Adding standard attrs (Usage/Category/TargetJvmEnv/KotlinPlatformType/AgpVersion via copyAttributes)
+ export attr made variant selection WORSE — even JVM deps vanished. The attr-copy approach is
finicky/wrong: the combined attr set likely breaks consumable selection (ambiguity or no-match).

## META-CONCLUSION (after 3 iterations: v1 19-deps, iter1 19+moshi, iter2 regression to ~0)
Blind edit→remote-oracle iteration is THRASHING. Each cycle ~10min and I'm guessing at Gradle
attribute-matching internals without seeing the actual resolution. STOP blind iteration.
NEXT SESSION MUST INSTRUMENT FIRST (before any more code changes):
- Log, for one variant (e.g. default): the root config's requested attributes; for each project
  dependency, which consumable variant Gradle SELECTED (or the selection failure); the resolved
  graph node count + first 2 levels of children; whether grazelExportCompile<V> was even created
  + its outgoing dependencies.
- This will reveal WHY buckets are empty (no-match? wrong variant? empty consumable?).
KNOWN-GOOD FALLBACK: the per-module resolver at commit 9c714fe PASSES the oracle (correct, but
O(PxV)). If O(V) proves too costly to land, ship that (rename/honest-doc) or keep researching.
HYPOTHESES to test with instrumentation: (a) export attr alone (no std attrs) → shallow transitive
res (iter1); (b) export+std attrs → selection breaks (iter2). Maybe: std attrs on ROOT only (for
transitive res) + export-only on consumable (for selection), or a compatibility/disambiguation rule.
The mixed Android+JVM single-root-config tension is likely fundamental — may need per-ecosystem
root configs.

### Run 4 (DIAGNOSTIC instrumentation) — DECISIVE DATA. Two confirmed root causes:
AggDbg output revealed:
1. CROSS-ECOSYSTEM UNRESOLVED: root config has ONE attr set. For default/test the donor heuristic
   picked a JVM project's compileClasspath -> rootAttrs include jvm.environment=standard-jvm,
   kotlin.platform.type=jvm, jvm.version=17. => ALL Android projects come back
   "UNRESOLVED: Could not resolve project :sample-android". A single root config CANNOT span
   Android + JVM projects (incompatible jvm.environment). Confirmed.
2. CUSTOM CONSUMABLE LOSES TO STANDARD VARIANT: where a project DID resolve, Gradle selected the
   project's own apiElements / debugApiElements / debugRuntimeElements (children=1 = only `api` dep),
   NOT my grazelExportCompile<V> consumable. So implementation deps never surface. The standard attrs
   on root match apiElements; the export attr is not decisive (absent-on-apiElements == compatible).
   Only ONE case picked my consumable (demo: sample-android-tests -> grazelExportCompileDemo).
Also: demo/free/full/paid/androidTest have directShortIds=0 (no flavor/androidTest-specific EXTERNAL
deps in sample — correct to be empty). debug directShortIds=5, default=37 (those are the real ones).

### FIX DIRECTIONS (need careful Gradle attribute-schema work — NOT blind iteration):
- Cause 1: do NOT use one mixed root config. Either (a) separate root aggregating configs per
  ecosystem (android-attr config consuming Android projects; jvm-attr config consuming JVM projects),
  then union; or (b) per-project resolve but still O(P) not O(PxV)... ; or (c) resolve each project's
  consumable via a dedicated resolvable config with that project's own attrs.
- Cause 2: force selection of grazelExportCompile<V> over apiElements. Options: make `export` a
  MANDATORY attribute via attributesSchema + compatibility/disambiguation rules so apiElements (no
  export) is INCOMPATIBLE; or give the consumable a unique Usage value; or use a separate dependency
  configuration target `project(path, configuration='grazelExportCompile<V>')` to select it explicitly
  (bypasses attribute matching entirely — likely the SIMPLEST robust fix!).
  >> STRONG LEAD: `dependencies.add(project(path: p, configuration: "grazelExportCompileX"))` targets
     the consumable BY NAME, no attribute matching, no apiElements competition, no ecosystem mismatch.
     This likely fixes BOTH causes at once. TRY THIS NEXT.

### Run 5 (iter3: target consumable by configuration NAME) — BREAKTHROUGH, big progress.
Config-targeting (project(path, configuration:"grazelExportCompile<V>")) FIXED cause 2: projects now
resolve to grazelExportCompile<V> (NOT apiElements). All 4 buckets populated.
Per-bucket OFF->ON: default 163->59 (30 matched, 133 only-OFF, 29 only-ON), debug 29->21 (18 matched,
9 only-OFF, 1 only-ON, 2 ver-mismatch), androidTest 5->24 (5 matched, 19 only-ON!), lint 2->2 PERFECT.
New `test` bucket in ON (2: junit, hamcrest) absent in OFF.
AggDbg: debug -> ALL projects resolve to grazelExportCompileDebug (android attrs donor). 
default -> rootAttrs are JVM (standard-jvm, jvm) because donor heuristic picked a JVM project =>
the 4 ANDROID projects UNRESOLVED => 133 missing (moshi/dagger/compose). JVM projects resolve fine.

REMAINING ROOT CAUSE (cause 1, now isolated): `default` & `test` mix Android+JVM projects; one root
config has ONE ecosystem attr set. donor=JVM => Android projects unresolved. debug works (all-android).

### NEXT FIX (clear): PER-ECOSYSTEM root configs.
In resolveAggregatedCompileDeps: group participating projects by ecosystem (read source config's
`org.gradle.jvm.environment` attr value: "android" vs "standard-jvm"/absent). For each ecosystem group
create a separate root config with THAT group's donor attrs + export, target that group's consumables
by configuration name, resolve, union emitted deps across groups. O(V x ecosystems) ~ O(2V), still
<< O(PxV). This should resolve the 4 Android projects in `default` -> recover moshi/dagger/compose.
SECONDARY tail (after per-ecosystem): androidTest 19 only-ON (over-inclusion) + new `test` bucket +
debug 2 ver-mismatches (kotlin-stdlib-common 1.3.50 vs 1.5.31, coroutines 1.3.0 vs 1.5.2 - older,
stale transitive path). Under RELAXED bar (bazel build passes) some over/under may be tolerable but
missing default deps are NOT. Reconcile after per-ecosystem fix.

## PIVOT (author-directed, 2026-06-16): binary-module classpath + mechanical bucketing
Abandon custom-consumable aggregation (fought Gradle variant matching, lost). NEW:
- Resolve the BINARY (app) module's OWN real leaf classpaths Gradle already provides
  (e.g. sample-android `demoFreeDebugCompileClasspath`/`...RuntimeClasspath`) — these fully resolve
  the whole transitive closure incl. all library impl deps (verified earlier: 123 deps, moshi+dagger).
  No consumable, no attr matching, no ecosystem mismatch.
- Mechanical set-bucketing over leaf closures: default = ∩ all leaves; debug = ∩ debug-leaves − default;
  flavor = ∩ flavor-leaves − default; etc. Feed computeFromResults.
- test/androidTest/lint: resolve app's *UnitTest/*AndroidTest classpaths + lint separately.
- VALIDATION = `bazel build //...` passes (functional), NOT byte-diff. Relaxed bar.
- CAVEAT: debug-only sample => can't split default vs debug by set-math (debugImpl in all leaves
  → lands in default). Functionally fine under relaxed bar.
GOAL: non-byte-identical first compiling bazel build = milestone.
RESEARCH FIRST (no assuming): correct Gradle API to resolve another project's leaf classpath from
root at execution time; project-isolation/config-on-demand caveats; AGP config naming; how existing
ResolveVariantDependenciesTask accesses per-project configs.

### Run 6 (SPIKE: app runtime classpath + mechanical set-bucketing) — VALIDATED. Pivot is low-risk.
:sample-android has 8 leaf RuntimeClasspath configs ({demo,full}x{free,paid}x{debug,staging}; release
filtered). Each resolves full closure (123 debug / 118 staging) incl moshi+dagger+compose(34 @1.7.8).
Set-bucketing LOSSLESS: default=∩all=118; debug=+5 (paging stack, correctly isolated!); staging=+0;
flavors(demo/full/free/paid)=+0; per-leaf=+0. union(buckets)==union(leaves)=123, 0 lost. NO version
conflicts. UnitTest +junit/hamcrest; AndroidTest +espresso stack. 
=> My earlier "debug-only can't split default/debug" fear was WRONG: staging build type provides the
contrast (default=∩ over debug AND staging leaves excludes paging => paging lands in debug bucket).
APPROACH CONFIRMED. Now implement in resolver: resolve app leaf RUNTIME classpaths, mechanical bucket,
feed computeFromResults. Validate = dep coverage then bazel build (functional, relaxed bar).

### Run 7 (PIVOT IMPLEMENTED + compileOnly gap closed) — MILESTONE ACHIEVED. bazel build //... PASSES.
Commit 8307e1f = pivot impl (app leaf RuntimeClasspath + set-bucketing, lossless, compiles).
Uncommitted follow-up (the "milestone push") closes the known compileOnly gap:
  - Union each leaf's CompileClasspath with RuntimeClasspath (captures compileOnly + api-of-libs);
    same union applied to UnitTest + AndroidTest closures.
  - com.android.test standalone modules added as app roots (declare own deps).
  - BOM/platform filtering: skip *-bom / *.bom pom-only components (rules_jvm_external rejects
    "Unsupported packaging type: pom") — both as components and in transitive dep sets.
  - Non-app library compileClasspath unioned into every leaf -> unreachable compileOnly/lint deps
    land in intersection (default bucket) -> @maven.
VALIDATION (relaxed bar, author-directed = bazel build passes, NOT byte-diff):
  - ./gradlew migrateToBazel (flag ON) -> BUILD SUCCESSFUL; pinMavenArtifacts reported UP-TO-DATE
    (on-disk generated json artifacts already matched current resolver).
  - bazelisk build //... -> "Build completed successfully, 2119 total actions" across 239 targets.
    Re-run cached: EXIT 0. Full closure builds: KotlinCompile/Kapt/Dagger ComponentProcessor/
    DataBinding/DexMerger/apk all succeed -> moshi/dagger/compose/paging deps all resolved correctly
    into @maven + per-variant repos.
=> The aggregated O(V-leaf) resolution path produces a FUNCTIONALLY CORRECT Bazel build. Milestone met.
REMAINING (not blockers for milestone): commit the in-flight resolver work; run grazel unit/functional
tests (./gradlew check) to ensure no regression on the flag-OFF path; consider bazelisk test //...;
update HANDOFF.md (currently describes the abandoned pre-pivot O(V) custom-consumable approach).
