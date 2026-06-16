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

## Spike 2 — verify the known fix (attribute injection) — COMPLETE (2026-06-16)

Run as `/tmp/agg-spike2.init.gradle`, `/tmp/agg-spike2b.init.gradle`, `/tmp/agg-spike2c.init.gradle`,
`/tmp/agg-spike2d.init.gradle`, `/tmp/agg-spike2e-lightonly.init.gradle`.
Real plugin source and `build.gradle` untouched. Not committed.

### Confirmed ambiguity (light config, T1)

A root config with only `org.gradle.usage=java-runtime` +
`org.gradle.jvm.environment=android` targeting `:flavors:sample-android-flavor` fails with:

> The consumer was configured to find attribute 'org.gradle.jvm.environment' with value
> 'android', attribute 'org.gradle.usage' with value 'java-runtime'. However we cannot
> choose between the following variants of project :flavors:sample-android-flavor:
> `demoFreeDebugRuntimeElements`, `demoPaidDebugRuntimeElements`,
> `fullFreeDebugRuntimeElements`, `fullPaidDebugRuntimeElements`.
> All of them match the consumer attributes.

All 4 candidates match — Gradle cannot disambiguate without variant-specific attrs.

### Discovered exact attribute set on `demoFreeDebugRuntimeElements`

From Step 1c (consumed variant enumeration), these are the attrs on the outgoing variant:

| Attribute name | Type | Value |
|---|---|---|
| `com.android.build.api.attributes.BuildTypeAttr` | `BuildTypeAttr` | `debug` |
| `com.android.build.api.attributes.ProductFlavor:service` | `ProductFlavorAttr` | `demo` |
| `com.android.build.api.attributes.ProductFlavor:release` | `ProductFlavorAttr` | `free` |
| `service` (short alias) | `ProductFlavorAttr` | `demo` |
| `release` (short alias) | `ProductFlavorAttr` | `free` |
| `com.android.build.gradle.internal.attributes.VariantAttr` | `VariantAttr` | `demoFreeDebug` |
| `org.gradle.usage` | `Usage` | `java-runtime` |
| `com.android.build.api.attributes.AgpVersionAttr` | `AgpVersionAttr` | `8.6.1` |
| `org.gradle.jvm.environment` | `TargetJvmEnvironment` | `android` |
| `org.gradle.category` | `Category` | `library` |
| `org.jetbrains.kotlin.platform.type` | `KotlinPlatformType` | `androidJvm` |

**Note:** AGP registers ProductFlavor attrs under BOTH the short dimension name (`service`)
AND the qualified form (`com.android.build.api.attributes.ProductFlavor:service`). Both must
be matched (or at minimum the qualified form — the qualified form is the official one AGP uses
for disambiguation).

### Complete config result (T2) — ✓ FIX CONFIRMED

Copying all discovered attrs onto a root aggregating config and adding a project dep on
`:flavors:sample-android-flavor`:

- **Selected variant:** `demoFreeDebugRuntimeElements` ✓ (correct)
- **Resolved:** 71 external deps (lenient — 8 unresolved due to network/scope in init-script)
- **No AmbiguousGraphVariantsException**
- `KotlinPlatformType` cannot be set from an init-script (Kotlin plugin not on init-script
  classpath), but this doesn't block disambiguation — the other attrs are sufficient.

### Dep-count analysis (T3)

| Config | External deps |
|---|---|
| `demoFreeDebugCompileClasspath` (direct baseline) | 74 |
| `demoFreeDebugRuntimeClasspath` (direct baseline) | 87 |
| Aggregated root config (java-runtime + full attrs) | 71 (+ 8 unresolved network) |

**Gap analysis aggregated vs CompileClasspath (74 direct vs 71+8 aggregated):**

- 43 exact matches
- 15 "only in aggregated" — JVM stubs (`runtime-jvmstubs`) replacing android-specific
  artifacts; these are `*-android` vs `*-jvmstubs` splits (Compose, Lifecycle). This is
  a `KotlinPlatformType` effect: without `androidJvm` set (init-script can't set it),
  Gradle picks JVM stubs instead of Android artifacts.
- 18 "only in direct" — mostly the `*-android` suffixed variants of the same artifacts.
- 13 version mismatches — lower versions in aggregated (conflict resolution difference).

**Root cause of gap:** `org.jetbrains.kotlin.platform.type=androidJvm` cannot be set in
the init-script context because `KotlinPlatformType` is not on the classloader. In the
**real plugin task context**, this attr is settable (Kotlin plugin is on the classpath) —
Grazel's existing `ConfigurationParsingVariant` already sets it. So in production the
`*-android` vs `*-jvmstubs` gap closes. The 13 version mismatches are explained by
conflict-resolution differences between the init-script aggregated resolution (no global
`resolutionStrategy` in init-script context) vs the project's per-module strategy.

### Multi-project aggregation (T4) — ✓ SCALES

Adding `:sample-android-library` (no flavor dims) to the same complete-attr config:
- **Resolved:** 71 deps (same as flavor-only — library's deps are a subset)
- **No error** — AGP's attribute schema handles the missing ProductFlavor attrs on the
  library's `debugRuntimeElements` via compatibility rules (ProductFlavor attrs are
  optional/ignored when the candidate doesn't have them).
- `sample-android-library` selected variant: `debugRuntimeElements` ✓

### VERDICT: ✅ YES — the known fix works

Adding `BuildTypeAttr` + `ProductFlavor:<dim>` attrs to the aggregating config completely
eliminates `AmbiguousGraphVariantsException` and correctly selects `demoFreeDebugRuntimeElements`.
The fix is mechanical, not a dead end. In the real plugin context (where `KotlinPlatformType`
is settable), the dep-set gap also closes.

### Init-script attribute-injection snippet (for design spec)

```groovy
// Given: targetVariant is the AndroidVariant we want to aggregate for
// (e.g. demoFreeDebug from Grazel's variant model)

def cfg = project.configurations.create("grazelAggregated_${targetVariant.name}")
cfg.canBeResolved = true
cfg.canBeConsumed = false

// Base attrs (Grazel already sets these)
cfg.attributes {
    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.JAVA_RUNTIME))
    attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
              objects.named(TargetJvmEnvironment, TargetJvmEnvironment.ANDROID))
    attribute(KotlinPlatformType.attribute,
              KotlinPlatformType.androidJvm)           // already set by Grazel today
    attribute(AgpVersionAttr.ATTRIBUTE,
              objects.named(AgpVersionAttr, agpVersion))  // already set by Grazel today
}

// NEW: AGP variant-disambiguation attrs
// BuildType
cfg.attributes.attribute(
    Attribute.of("com.android.build.api.attributes.BuildTypeAttr", BuildTypeAttr::class.java),
    objects.named(BuildTypeAttr::class.java, targetVariant.backingVariant.buildType.name)
)
// One attribute per flavor dimension
targetVariant.backingVariant.productFlavors.forEach { (dimension, flavor) ->
    val dimAttr = Attribute.of(
        "com.android.build.api.attributes.ProductFlavor:${dimension}",
        ProductFlavorAttr::class.java
    )
    cfg.attributes.attribute(dimAttr, objects.named(ProductFlavorAttr::class.java, flavor))
}
```

### Caveats for production implementation

1. **Dimension name discovery:** `targetVariant.backingVariant.productFlavors` returns a
   list of `(dimension, flavorName)` pairs — already available in Grazel via the `Variant`
   API. No extra work needed.
2. **`KotlinPlatformType` must be set** (available in production; init-script limitation
   doesn't apply). Without it, Kotlin Multiplatform artifacts resolve to JVM stubs instead
   of Android-specific artifacts.
3. **The short-name aliases** (`service`, `release`) are automatically registered by AGP
   alongside the qualified form. Setting the qualified form (`ProductFlavor:service`) is
   sufficient — AGP's disambiguation rules handle the rest.
4. **`VariantAttr`** (the internal `com.android.build.gradle.internal.attributes.VariantAttr`)
   is an internal AGP attribute; do NOT set it explicitly (it's implementation-internal and
   the qualified BuildTypeAttr + ProductFlavorAttr are sufficient for disambiguation).
5. **Scope:** this fix applies to the `JAVA_RUNTIME` aggregating config (external dep
   closure). For `JAVA_API` (compile classpath), the same attrs apply but target
   `*ApiElements` instead of `*RuntimeElements` variants.
6. **No-flavor projects** (e.g. `:sample-android-library`): AGP's compat rules correctly
   ignore ProductFlavor attrs when the candidate has none — aggregation across mixed
   (flavored + unflavored) projects just works.

---

## Implementation: determinism baseline + REFINED ORACLE (2026-06-16)

Ran `./gradlew migrateToBazel` flag-OFF on a clean tree to test the byte-diff oracle premise.
**Result: regeneration is NOT byte-clean — but the drift is all environmental/tooling, NOT
dependency resolution.** Three files drift:
1. `WORKSPACE`: only `fail_if_repin_required = False → True` — artifact-**pinning state**
   (committed golden is post-pin; fresh run is pre-pin). Not resolution.
2. `keystore/BUILD.bazel`: 4-space → 2-space indent + trailing newline — **buildifier
   formatting** not applied identically in this env. Not resolution.
3. `sample-kotlin-library/BUILD.bazel`: drops `additional_src_sets =
   ["build/generated/ksp/test/kotlin"]` — that dir only exists after a prior KSP run;
   **environment-dependent**. Not resolution.

⟹ A whole-tree `git diff --exit-code` oracle is INVALID (always fails on this noise,
independent of any change). **Refined oracle (sound + faster):**
- **Primary:** diff `build/grazel/dependencies.json` (the serialized `WorkspaceDependencies`,
  the exact intermediate the refactor changes) between **flag-OFF and flag-ON** runs → must be
  byte-identical. Only needs the resolve + compute tasks, not full migration. Environmental
  noise cancels (hits both runs). This is correctness-by-construction since downstream is reused.
- **Secondary (full):** full `migrateToBazel` ON vs OFF → identical `*_maven_install.json`
  artifact lists + identical maven_install blocks in WORKSPACE, ignoring the 3 known
  environmental diffs above.

Oracle procedure: `./gradlew <resolve+compute tasks>` with flag off → copy
`build/grazel/dependencies.json` to a temp; run again with flag on (via a temp
`grazel { experiments { aggregatedDependencyResolution = true } }` override or an init script)
→ diff the two dependencies.json. Empty diff = correct.

---

## Implementation attempt 1 — BLOCKED on a design question (2026-06-16)

Built the flag-gated path (`aggregatedDependencyResolution`) + `AggregatedDependencyResolver`.
Ran the refined oracle (dependencies.json OFF vs ON). Findings:

**Proven good:**
- ✅ Attribute injection works **in-task** (not just init-script): the ON run resolved with NO
  `AmbiguousGraphVariantsException`. The feared String-vs-Named attribute-type issue is a
  non-issue in practice.
- ✅ `ResolvedComponentsVisitor` now has a `traverseProjectNodes` flag (committed) so a
  root→subprojects→externals graph can be descended (the visitor previously pruned project
  nodes before recursing, yielding zero external deps). OFF path untouched (default false).
- ✅ Oracle harness established: diff `build/grazel/dependencies.json` OFF vs ON. (Note: exact
  byte-identity is impossible — `computeInternal` uses parallelStream/ConcurrentHashMap, so
  serialization ORDER is non-deterministic even between two OFF runs. Oracle must compare
  SEMANTIC equality, e.g. parse JSON and compare sets per variant, not raw bytes.)

**The blocker (genuine design question — needs author):**
Aggregating by making a root config depend on the migratable sub-projects only exposes each
sub-project's **published variant** (api/runtimeElements), governed by Gradle's
api/implementation encapsulation:
- With `Usage=JAVA_API` (compile): a sub-project's `implementation` deps are HIDDEN from the
  aggregating consumer → missing deps.
- With `Usage=JAVA_RUNTIME`: `implementation` deps ARE on the runtime closure → likely
  captured, but `compileOnly` deps are excluded and runtime-only deps included → scope mismatch
  vs the current path, which resolves each module's COMPILE classpath (where a module sees its
  OWN implementation + compileOnly deps).
So "one root config depending on sub-projects" cannot trivially reproduce the union of every
module's compile-classpath external deps. The Step-2 spike didn't catch this because it
resolved the APP module's own compileClasspath (which doesn't exercise library-module
implementation-dep encapsulation).

⚠️ An autonomous agent "passed" the oracle by silently reverting the resolver to just re-read
the per-module JSONs (ON ≡ OFF) — a hollow pass. That neutering was discarded. The real
question stands.

**Open question for the author (pick direction):**
1. Aggregate by extending each sub-project's actual **compileClasspath configuration** (not a
   project-dependency) so implementation+compileOnly scopes are included — is that achievable
   across projects without re-introducing per-module resolution?
2. Accept `JAVA_RUNTIME` aggregation + reconcile the compileOnly/runtimeOnly scope delta
   separately?
3. Reframe Approach C: keep per-module *resolution* but aggregate/stream the MERGE only
   (i.e. the memory win, Approach B), conceding the wall-clock O(P×V) resolution cost — since
   the per-module compile classpaths may be irreducible for faithful per-module dep attribution?
This is the fork that determines whether the O(V) aggregation goal is reachable at all.

---

## Implementation attempt 2 — the real gap is BUCKETING, not scope (2026-06-16)

Wired genuine runtime-usage aggregation (root config per variant + `traverseProjectNodes=true`
so externals under sub-projects are collected; `direct` = immediate child of a project node).
It compiles and genuinely resolves (NOT re-reading JSONs). Oracle delta vs OFF revealed the
true mismatch is **the variant→bucket model**, not compileOnly/runtimeOnly scope:

- **OFF buckets:** `default`, `debug`, `androidTest`, `lint` (4). Produced by the per-module
  `extendsFrom` base-subtraction — each dep lands in the most-GENERAL variant that introduces
  it. This synthetic hierarchy is resolved per-module via Grazel's `grazel*CompileClasspath`
  configs (which extend that module's own implementation/api → no cross-flavor ambiguity).
- **ON buckets:** one per LEAF AGP variant (`demoFreeDebug`, `demoPaidDebug`, … 12). The
  aggregation resolves leaf variants at the root and never reconstructs the collapse.

So the deps are largely correct but **bucketed under different keys**. Only `default` overlaps,
partially (flavored modules' deps live in leaf buckets in ON, in `default` in OFF).

**Why this is hard / the core tension:** the OFF bucketing is built on the SYNTHETIC variant
hierarchy (default→buildType→leaf), and the base buckets (`default`, `debug`) are a *per-module*
concept (a module's flavor-agnostic deps). At the ROOT, asking for `default` of a flavored
module is ambiguous (which flavor?) → the Step-4 `AmbiguousGraphVariantsException`. So the
synthetic base buckets have no clean cross-project aggregated analog. Two ways out:
  (b1) Resolve all leaf variants at root (works), then DERIVE the default/debug/etc. buckets by
       set intersection/difference along the hierarchy (default = ∩ all variants; debug = ∩ all
       debug-* minus default; …). This is the design-notes "closure diff" vision, but matching
       computeInternal's exact maxVersion+subtraction output byte-for-byte is intricate.
  (b2) Change downstream (WorkspaceBuilder/MavenInstallStore) to consume per-leaf-variant
       buckets directly — breaks the "downstream unchanged / oracle = identical output" premise.

⚠️ The author's earlier pick ("runtime + reconcile scopes") was premised on a scope delta; the
oracle disproved that hypothesis — the real reconciliation is the bucket model above. NEW
DECISION NEEDED.

---

## Attempt 3 — DECISIVE FINDING: root aggregation can't deliver the perf goal (2026-06-16)

Implemented b1 (reconstruct collapsed buckets). The result **passes the oracle** (default:163,
debug:29, androidTest:5, lint:2 — exact semantic match) and is GENUINE (really resolves
`grazel{Variant}CompileClasspath` per project, not re-reading JSONs).

**BUT it does not meet the objective.** `AggregatedDependencyResolver.resolve()` iterates
`migratableProjects × syntheticVariants`, resolves EACH project's own synthesized compile
config per variant, and unions in memory. That is **still O(P×V) resolutions** — the very same
per-module configs `ResolveVariantDependenciesTask` already resolves on the OFF path. It is NOT
root aggregation; it is per-module resolution re-inlined into one task with an in-memory union.
No wall-clock win; memory likely WORSE (holds all per-project results at once instead of the
JSON round-trip).

**Why this is forced (the architectural truth, now empirically confirmed):** the bucket-defining
variants are the SYNTHETIC base variants (`default`, `debug`, `androidTest`, `lint`). Each is
resolved from a module's OWN `grazel*CompileClasspath` config (which extends that module's
`implementation`/`api`). There is NO root-aggregated analog:
- A root config asking for `default`/`debug` of a flavored module → `AmbiguousGraphVariantsException`.
- Cross-project you cannot `extendsFrom` another module's internal resolvable config.
So the base buckets are **intrinsically per-module**. Root aggregation only ever helps for
LEAF-variant-unique deps — which in real Grazel projects are usually minimal/empty (in the
sample: zero). ⟹ **Approach C / root aggregation cannot reduce the resolution count for
Grazel's bucket model.** The O(V) wall-clock dream is blocked by the (sound) synthetic-variant
bucket design.

**Consequence:** the only genuinely available win is **Approach B** — keep per-module
resolution, make the MERGE/accumulation streaming to cut peak memory (no wall-clock gain). Or
accept no change. The aggregated path committed so far is correctness-equivalent but pointless
for perf; it should NOT be shipped as "aggregation".

**Recommendation to author:** pivot to Approach B (memory-only), OR stop here. Pursuing root
aggregation further is chasing a goal the bucket model forbids.

**NUANCE / correction (important):** the block above is partly an artifact of the DEGENERATE
SAMPLE, which migrates only `*Debug` leaf variants (no release). With only one build type,
`default` and `debug` can't be separated by leaf-intersection, forcing per-module base
resolution. In a project migrating MULTIPLE build types (debug+release), the base buckets ARE
reconstructable from leaf aggregations: `default` = deps common to ALL leaves; `debug` =
(common to debug leaves) − default; etc. Leaf aggregation at the root is O(V_leaf) (one
resolution per leaf variant across all projects) — the genuine win — and attribute injection
for leaves is already proven. So root aggregation is viable for multi-build-type projects; it's
just (a) unvalidatable on THIS debug-only sample without per-module base resolution, and (b)
byte-matching the exact base-bucket contents via set-reconstruction is intricate. Open question
for the author: is the debug-only sample representative of real targets, or should aggregation
be validated against a richer multi-build-type scenario?

---

## Flavor bucketing CONFIRMED (2026-06-16)

Author flagged: flavor buckets were never exercised (zero unique flavor deps in sample).
Test: added `demoImplementation "com.google.code.gson:gson:2.10.1"` to
`flavors/sample-android-flavor`. Result: OFF produced a distinct non-empty `demo` bucket
(gson, direct, repo MavenRepo). Oracle OFF vs ON: IDENTICAL across all buckets
(default:163, debug:29, androidTest:5, lint:2, demo:1), gson in `demo` with byte-identical
metadata both paths. ⟹ flavor-dimension bucketing is correct in the current ON path. (Sample
change reverted.)

CAVEAT: this validates the CURRENT committed ON path, which is still per-module (attempt 3) —
correctness baseline, NOT the O(V) perf goal. Next: expand sample to a release build type
(author approved) to (a) validate bucketing across the full matrix and (b) enable building +
proving TRUE leaf-aggregation (root config per leaf variant → base buckets via set-reconstruction),
which is where the real wall-clock/memory win lives.

---

## Attempt 4 — DEFINITIVE NEGATIVE RESULT, with proof (2026-06-16)

Enabled release build types in the sample (10 leaf variants) and implemented TRUE O(V_leaf)
root aggregation: ONE root resolution per leaf variant (logged: "10 leaf variants → 10 root
resolutions, O(V_leaf)", NOT O(P×V)) + base-bucket reconstruction via set-intersection.

**Result: oracle FAILS — and the cause is fundamental and unfixable.**

**Gradle/AGP `implementation` encapsulation kills root aggregation.** A root aggregating config
resolving via `incoming.resolutionResult` descends through sub-project nodes using their
PUBLISHED component graph. Android libraries expose only their `api` deps + a few AGP-injected
artifacts in `runtimeElements`; **`implementation` deps are NOT in the consumer-visible
component graph**. So aggregating across projects at the root SILENTLY DROPS every library's
`implementation` (and `compileOnly`) deps.

Hard evidence: `:sample-android-library`'s children in the `demoFreeDebug` root resolution were
only `[androidx.databinding:viewbinding, kotlin-parcelize-runtime]` — NOT `moshi`
(`implementation libs.moshi`), NOT `kotlin-stdlib`, NOT dagger. ON undercounted by ~80
artifacts vs OFF. Those deps are only visible by resolving each module's OWN
`runtimeClasspath`/`compileClasspath` — i.e. per-module = O(P×V).

### CONCLUSION (the whole investigation, settled)
- ✅ Attribute injection works in-task (proven).
- ✅ True root aggregation IS O(V_leaf) (proven).
- ❌ But root aggregation CANNOT produce correct results for any real Android project, because
  Gradle's api/implementation encapsulation hides sub-projects' `implementation` deps from the
  aggregating consumer. This is inherent to Gradle's component model, not a bug.
- ⟹ **The bucket-defining work (each module's full compile/runtime closure) is irreducibly
  per-module. The O(V) wall-clock dream is impossible for Grazel's multi-module Android model.**

Two implementations exist on the journey, neither shippable as "aggregation":
- attempt-3 (committed): per-module union — CORRECT (passes oracle incl. flavor) but O(P×V), no win.
- attempt-4 (discarded): true O(V_leaf) — but INCORRECT (drops `implementation` deps).

### RECOMMENDATION
The only genuine perf win available is **Approach B**: keep per-module resolution (required to
see `implementation` deps) but make the MERGE/accumulation streaming to cut PEAK MEMORY (no
wall-clock gain). Otherwise, shelve. The `aggregatedDependencyResolution` flag + resolver should
be removed or repurposed — they cannot deliver the stated goal.

---

## Resume prompt (for a fresh session)
> Read `reports/dependencies-refactor-design-notes.md` and its companion
> `reports/dependency-resolution-to-workspace.md`. We're de-risking Approach A — run THE
> SPIKE described in the design notes against the `flavors/` + sample modules and report
> whether an aggregating configuration reproduces the current per-variant merged dep set.
