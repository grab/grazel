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

**Recommendation:** Target **A → evolving to C**, with **B's streaming as a safety net** for
any variants where aggregation's attribute matching can't be solved (aggregate what we can,
stream-merge the rest).

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
- Decision still pending from user before spec: which approach (A / A→C / hybrid).

## Resume prompt (for a fresh session)
> Read `reports/dependencies-refactor-design-notes.md` and its companion
> `reports/dependency-resolution-to-workspace.md`. We're de-risking Approach A — run THE
> SPIKE described in the design notes against the `flavors/` + sample modules and report
> whether an aggregating configuration reproduces the current per-variant merged dep set.
