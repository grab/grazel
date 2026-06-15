# Dependencies Refactor — Design Notes & Spike Plan

> **Status:** Design exploration in progress (brainstorming). No code written yet.
> **Branch:** `arun/dependencies-refactor`
> **Companion doc:** [`dependency-resolution-to-workspace.md`](./dependency-resolution-to-workspace.md) — the full pipeline map. Read it first.
> **Purpose of this doc:** Checkpoint the design discussion so it can be resumed in a fresh session (the spike) without re-deriving context.

---

## Goal

Reduce the cost of Grazel's Gradle→Bazel dependency resolution. Today it does a
**per-(project × variant)** resolution fan-out, then merges all results at the end.
This "blows up" as modules × variants grow.

**Confirmed by author:**
- The pain is **wall-clock time** (too many full resolutions) **and memory** (holding all
  intermediate graphs during the merge — cf. recent `HeapStats` + "make resolved graph
  gc-able" commits).
- The **merge logic itself is sound/correct** — this is a performance refactor, not a
  correctness fix. Do NOT rewrite the merge semantics for their own sake.

---

## The Reframing (key insight)

Author's fear was: *"my approach is essentially what Gradle does internally, since it still
needs to compute a classpath per variant."* — **Half true.**

- Gradle genuinely must resolve **each variant's** classpath separately. That is
  irreducible: **O(V)** distinct resolved graphs. (This is also why per-variant Bazel
  `maven_install` trees exist — see companion doc. That rationale is unchanged.)
- But Grazel resolves **O(P × V)**: every project independently resolves its own
  `freeDebugCompileClasspath`, and then a hand-rolled merge (maxVersion + dedup + topo-sort)
  unions across projects. **That merge is reimplementing Gradle's own conflict resolution.**
- A project's `freeDebugCompileClasspath` *already transitively contains* every module-and-
  external dependency reachable in freeDebug. So an **aggregating configuration** resolving
  the freeDebug graph across all migratable projects **once** would let Gradle do natively
  what the merge approximates.

**Candidate shift:** `O(P×V)` full resolutions + giant merge → **`O(V)` aggregated
resolutions, no merge.** Wins on both axes (wall-clock and memory).

---

## Key technical facts (verified against source)

From `gradle/variant/` and `tasks/internal/ResolveVariantDependenciesTask.kt`:

1. **Resolution is graph-only (cheap-ish), not artifact download.** The task adds
   `configuration.incoming.resolutionResult.rootComponent` (a `ResolvedComponentResult`
   tree — coordinates + versions, no JARs). *Exception:* KSP downloads artifacts for direct
   processors only (to read processor class names from manifests).
   `ResolveVariantDependenciesTask.kt:471-473`.

2. **Variant attributes — THE GATING UNKNOWN.** Grazel-synthesized resolvable configs
   (`grazel*CompileClasspath`, created in `ConfigurationParsingVariant.applyAttributes`,
   `ConfigurationParsingVariant.kt:197-229`) carry only:
   - `AgpVersionAttr.ATTRIBUTE`
   - `TARGET_JVM_ENVIRONMENT_ATTRIBUTE` = ANDROID
   - `USAGE_ATTRIBUTE` = JAVA_RUNTIME
   - `KotlinPlatformType.attribute` = androidJvm

   **No `BuildTypeAttr` / `ProductFlavorAttr`.** Variant identity is encoded in the
   **config name** + the logical `extendsFrom` hierarchy — NOT Gradle attribute matching.
   For *real* AGP leaf variants (`AndroidVariant`, e.g. `freeDebug`) the task resolves AGP's
   own `freeDebugCompileClasspath`, which DOES carry AGP's full variant attributes.
   ⟹ Aggregating leaf-variant AGP configs is plausible; aggregating the synthetic buckets
   would need added attributes or a different aggregation level.

3. **No cross-project config aggregation exists today.**
   - `rootResolveDependenciesTask` (`ResolveVariantDependenciesTask.kt:250`) is only a
     **task-graph** fan-in (`dependsOn`), not a configuration aggregation.
   - `baseDependenciesJsons` (`:108`) is file-based parent-subtraction *after* independent
     resolution.
   - `limitDependencyResolutionParallelism` (`:538-556`) is scheduling control only.

4. **`extendsFrom` in Grazel `Variant` is logical**, not Gradle's `Configuration.extendsFrom`
   — used for task ordering + base-dep subtraction. (`Variant.kt:38,42`)

5. **Bucket routing** is `MavenInstallStore` (`(variant,group,name) → repoName`), queried
   specific→general with a candid bare-`@maven` fallback ("could be incorrect but makes for
   easier testing"). See companion doc.

---

## Approaches considered

### A — Aggregated per-variant resolution (recommended core)
Create V root resolvable configs, each extending all migratable projects' matching-variant
classpath configs; resolve each once. Cross-project union + conflict resolution moves into
Gradle's resolver.
- ✅ Kills both axes (O(P×V)→O(V); no in-memory merge).
- ⚠️ Make-or-break: the attribute obstacle (fact #2) — can an aggregating config select the
  right variant of each project dependency?
- ⚠️ Large change to the core resolution model.

### B — Streaming merge only (memory-only, low risk)
Keep resolution count; make the merge fully streaming (fold + discard per project, never
hold all P×V graphs). Extends existing gc-able work.
- ✅ Low risk, exact correctness.
- ❌ Does nothing for wall-clock (still O(P×V) resolutions). Insufficient alone.

### C — A, plus per-module deps become cheap reads
Aggregated resolution (A) for bucket contents + versions; per-module **direct** deps read
from *declared* configs (no graph resolution) and versioned via lookup against the global
per-variant map. Per-module work drops from "resolve a graph" to "read declared deps + map
lookup."
- ✅ Strictly best on both axes if per-module attribution holds.
- ⚠️ Most invasive; must match today's direct-dep correctness.

**DECISION (author):** Target **C**. Desired end state, in the author's words: *"root-call the
resolution, let Gradle handle it, and give me the full list per bucket."* I.e. one aggregated
resolution per variant at the root; Gradle produces each variant's full closure; the
per-bucket lists are then derived (see below). **B's streaming is the safety net** only for
variants where aggregation's attribute matching (fact #2) can't be solved.

**How "full list per bucket" is derived under C:**
- One root resolution per variant V → the full resolved closure for V (Gradle does conflict
  resolution natively; replaces the hand-rolled merge).
- A dependency is assigned to the **most-general variant whose aggregated closure contains it
  at that (group:artifact, version)** — i.e. bucket = diff across the V closures along the
  `extendsFrom` hierarchy (`freeDebug`'s bucket = freeDebug closure − debug closure − default
  closure, etc.). This diff is cheap set math over already-resolved lists, NOT a per-project
  merge.
- Per-module **direct** deps (for each module's BUILD target) come from reading *declared*
  configs (no resolution) + version lookup against the variant closure. This is the C-specific
  piece that must reproduce today's direct-dep correctness.

---

## THE SPIKE (next action — de-risk Approach A)

**Hypothesis to prove:** A single aggregating Gradle configuration can resolve a variant's
graph across multiple projects and produce the **same external-dependency set + versions**
that the current per-project fan-out + merge produces.

**Suggested steps:**
1. Pick a sample with real fan-out — `sample-android` (app) + `sample-android-library` +
   `sample-kotlin-library` + `flavors/` (has product flavors → exercises the hard case).
2. Capture the **baseline**: run the existing pipeline (`./gradlew migrateToBazel` or just
   the resolve/compute tasks) and record the resolved external deps + versions per variant
   (e.g. from the generated `*_maven_install.json` or the `build/grazel/**/dependencies.json`
   / `build/grazel/dependencies.json`).
3. **Prototype aggregation** (throwaway, e.g. an init script or a scratch task): create a
   root resolvable configuration that `extendsFrom`/depends on each migratable project's
   `freeDebugCompileClasspath` (start with the *real AGP leaf variant* configs — they carry
   attributes, so this is the easiest case). Resolve `incoming.resolutionResult.rootComponent`.
4. **Compare**: does the aggregated graph's `(group:artifact → version)` set match the
   baseline merged set for `freeDebug`? Diff carefully.
5. **Then test the hard case**: try aggregating a **synthetic bucket** (e.g. `default` or
   `debug`) where no buildType/flavor attribute exists. Does resolution fail / pick wrong
   variants? This tells us how much of fact #2 we must solve.
6. **Measure**: rough wall-clock + peak heap for baseline vs aggregated on a larger module
   set if available.

**Success criteria:** aggregated `freeDebug` set == merged `freeDebug` set (modulo the
maxVersion-vs-native-conflict-resolution differences, which should be ≤ a handful and
explainable). If yes → A is viable, proceed to full design. If attribute matching fights us
on synthetic buckets → quantify the gap and decide A-partial + B-streaming hybrid.

---

## Open questions / risks to keep in mind

- **Per-module resolution rules.** If individual modules use custom `resolutionStrategy`,
  dependency substitution, or forced versions, a global aggregating config applies ONE rule
  set and could diverge. *Mitigating thought:* Bazel's single `maven_install` per variant
  already can't represent per-module version divergence, and Grazel's merge already collapses
  it (maxVersion) — so aggregation likely loses nothing Grazel isn't already collapsing.
  Substitutions are the sharper edge; check whether sample/target projects use them.
- **Per-module direct-dep attribution (Approach C).** BUILD targets need each module's
  *direct* deps. Must confirm declared-config reading reproduces today's direct-dep set.
- **KSP** stays a separate, smaller axis (needs artifact download for processor extraction);
  out of scope for the core resolution aggregation.
- **Variant compression** (`AnalyzeVariantCompressionTask`, other branch
  `arun/variant-compression-part-2`) reduces V and is complementary — note interaction.

---

## Process state

- We are mid-**brainstorming skill** (superpowers). HARD-GATE: no implementation until a
  design is presented and approved. The spike is **investigation/throwaway**, not the
  implementation of the refactor — keep spike code out of the real pipeline.
- After the spike: resume design → write spec to
  `docs/superpowers/specs/YYYY-MM-DD-dependencies-refactor-design.md` → writing-plans.
- Approach decided: **C** (root-aggregated resolution per variant → full list per bucket via
  closure diffs; declared-config reads for per-module direct deps). Spike still needed to
  de-risk the attribute obstacle before writing the spec.

## Spike Results (2026-06-16)

Run as throwaway init-scripts (`/tmp/agg-spike-*.init.gradle`); real pipeline untouched.
Comparison target: **demoFreeDebug** on the sample project.

**Sample reality that reshaped the test:** the sample's flavor variants contribute **zero
unique *external* deps** — flavor deltas are *project* dependencies (`demoImplementation
project(...)`), which don't go to maven buckets. The only real external split is `default`
(163) vs `@debug_maven` (+29, from `debugImplementation androidx.paging:paging-runtime`). So
the external-dep comparison is `default ∪ debug` (168 entries).

### Step 2 — easy case (real AGP leaf variant): ✅ SUCCEEDED
Aggregating the **real AGP `demoFreeDebugCompileClasspath`** across projects resolves
correctly — cross-project variant selection "just works" because those configs carry AGP's
full attributes. Resolved in ~9s. **113 external deps.**
- ⚠️ **API caveat:** `incoming.resolutionResult` (graph-walk) OOM-killed the daemon *inside
  an init script* that also loaded the plugin's Kotlin compilation; the older
  `resolvedConfiguration.lenientConfiguration.allModuleDependencies` was memory-safe. This is
  likely an init-script artifact — the real `ResolveVariantDependenciesTask` already uses
  `resolutionResult` fine in the normal task graph. Note it; not a blocker.

### Step 3 — diff vs baseline: explained
111 exact · 2 only-aggregated · 57 only-baseline · 24 version mismatches.
- **2 only-aggregated:** `kotlin-parcelize-runtime`, `kotlin-android-extensions-runtime` —
  Kotlin plugin runtime, harmless.
- **57 only-baseline:** test / lint-tooling (`com.android.tools.*`) / KSP-processor-transitive
  deps. These exist because Grazel's *synthetic* `grazelDefaultCompileClasspath` extends a
  **broader scope set** (test, KSP, lint) than a pure `*CompileClasspath`. ⟹ **Real design
  requirement:** the aggregation must cover the same scopes Grazel covers today (compile +
  test + KSP + lint), not just compile classpath. Not a blocker, but it means "one config per
  variant" is really "one config per (variant × scope-group)".
- **24 version mismatches:** aggregated versions are higher. The subagent read this as
  "more correct (native conflict resolution)". ⚠️ **Treat with care:** the baseline side was
  a *reconstructed union* of `default ∪ debug` buckets, not a true single-variant resolution,
  so this diff is partly apples-to-oranges. The leaf-variant resolution *is* the right
  per-variant answer; the spec's correctness check should compare aggregated-leaf-resolution
  against a freshly-resolved single AGP variant classpath, NOT against the bucket union.

### Step 4 — hard case (synthetic bucket, no buildType/flavor attr): ❌ FAILS (as feared)
- No-flavor project (`sample-android-library`): silently picks `debug` — undefined/accidental.
- Multi-flavor project (`flavors/sample-android-flavor`, 8 ApiElements variants):
  **`AmbiguousGraphVariantsException`** — Gradle cannot choose between
  `demoFreeDebug/demoPaidDebug/fullFreeDebug/fullPaidDebugApiElements`.
- **Required to fix:** set `com.android.build.api.attributes.BuildTypeAttr` **and every**
  `ProductFlavorAttr:<dimension>` on the aggregating config. No partial disambiguation exists.

### VERDICT: **PARTIAL → VIABLE with a clear prerequisite**
Approach C/A works **if the aggregating config carries full variant attributes**. The clean
path is to **aggregate the real AGP per-variant configs** (`${variant}CompileClasspath` etc.,
which already carry the attributes) rather than Grazel's attribute-light synthetic configs —
OR to copy AGP's `BuildTypeAttr`+`ProductFlavorAttr` onto the aggregating config. Either way
the gating unknown (fact #2) is resolved: it's a known, mechanical fix, not a dead end.

### Implications for the spec
1. Aggregate per variant at the root using **attribute-complete** configs (prefer real AGP
   variant configs; carry buildType + all flavor attrs).
2. Aggregate **all scope-groups** Grazel covers (compile, test, androidTest, KSP, lint) — the
   57-dep gap proves compile-only is insufficient.
3. Per-bucket lists = ordered set-difference across the V variant closures along `extendsFrom`
   (unchanged from the plan).
4. Per-module **direct** deps from declared configs + version lookup (the C-specific piece).
5. Correctness check must compare against **true single-variant resolutions**, not the
   bucket-union (see Step 3 caveat).
6. Decide resolution API (`resolutionResult` vs `resolvedConfiguration`) with the OOM note in
   mind.

**Next step:** write the design spec
(`docs/superpowers/specs/YYYY-MM-DD-dependencies-refactor-design.md`), then writing-plans.

---

## Resume prompt (for a fresh session)
> Read `reports/dependencies-refactor-design-notes.md` and its companion
> `reports/dependency-resolution-to-workspace.md`. We're de-risking Approach A — run THE
> SPIKE described in the design notes against the `flavors/` + sample modules and report
> whether an aggregating configuration reproduces the current per-variant merged dep set.
