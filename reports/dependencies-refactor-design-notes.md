# Dependencies Refactor — Design Notes

> **Branch:** `arun/dependencies-refactor`
> **Companion:** [`dependency-resolution-to-workspace.md`](./dependency-resolution-to-workspace.md) — the full current-pipeline map. Read it first.
> **Purpose:** Living design record for replacing Grazel's per-(project × variant) dependency
> resolution with a cheaper aggregated approach. This is a **milestone checkpoint** — the
> investigation has established a viable path; implementation of the chosen bucketing model is
> the next phase.

---

## 1. Problem & Goal

Grazel resolves dependencies **per (project × variant)** — every module independently resolves
each of its variant classpaths (`ResolveVariantDependenciesTask`), writing per-module JSONs,
which `ComputeWorkspaceDependencies` then merges (max-version + dedup + transitive flatten +
override targets). This is **O(P × V)** full resolutions plus a large in-memory merge.

**Author-stated pain:** wall-clock time (too many resolutions) **and** peak memory (holding all
intermediate graphs during the merge — cf. recent `HeapStats` / "make resolved graph gc-able"
commits). The merge *logic* is sound; this is a performance refactor, not a correctness fix.

**Target:** resolve each variant's full dependency closure **once at an aggregated level**
(O(V), letting Gradle do native conflict resolution) instead of the O(P×V) fan-out + hand-rolled
merge — while producing the **same** `WorkspaceDependencies` so all downstream Bazel generation
is unchanged.

---

## 2. Settled Findings (verified)

### 2.1 `implementation` deps are visible to aggregation — encapsulation is NOT a blocker
Resolving a module's own resolvable classpath (`compileClasspath` / `runtimeClasspath`) exposes
that module's `implementation` deps. Gradle's api/implementation encapsulation only hides
`implementation` deps when consuming a **published** artifact from a repository — **not** in an
in-build multi-project resolution. Verified warm-cache: `:sample-android`'s
`demoFreeDebugRuntimeClasspath` graph contains `moshi`, `dagger`, `kotlin-stdlib` (123 external
deps). A binary (app) module's runtime classpath transitively contains every library module's
deps.

> **Measurement pitfall (cost us a wrong "definitive" conclusion):** use the resolution **graph**
> (`incoming.resolutionResult` / `allComponents`), never `lenientConfiguration.allModuleDependencies`
> — the lenient API silently drops UNRESOLVED nodes, and a cold Gradle cache leaves deps
> unresolved. Always warm the cache (run a real resolution first) before measuring, and measure
> in a real task context, not an init-script configuration phase.

### 2.2 O(V) aggregation is viable — two mechanisms
Both capture the full cross-project per-variant closure in one resolution per variant:

- **Binary-module classpath** — resolve the app module's `runtime/compileClasspath` per variant.
  Simple; captures everything reachable from the app. Gap: deps in library modules **not**
  reachable from any app (orphan/test-only) would be missed.
- **Custom consumable config** (preferred, most complete) — each migratable sub-project publishes
  a consumable config that `extendsFrom` its declared scopes (`implementation` + `api` +
  `<buildType>Implementation` + `<flavor>Implementation`) tagged with a **custom attribute**; a
  root resolvable config with the matching attribute depends on all migratable projects and
  resolves once. Verified: exposes `moshi`, `kotlin-stdlib`, and the full transitive closure (76
  deps across two sample projects). The custom attribute **uniquely identifies** the variant, so
  it sidesteps AGP's `AmbiguousGraphVariantsException` entirely — no `BuildTypeAttr`/`ProductFlavorAttr`
  juggling needed. See snippet in §4.3. **Caveat:** this is verified for **leaf** AGP variants.
  The *synthetic base buckets* (`default`, `debug`) are not real AGP leaf variants and have no
  clean root-aggregated analog for flavored projects — see §4.4.

### 2.3 The real remaining challenge: BUCKETING
Capturing the per-variant **union** is solved. The open problem is assigning each dep to the
correct **bucket** (`default` / `debug` / flavor / `androidTest` / `lint`), which maps 1:1 to a
Bazel `maven_install` repo.

The current pipeline's buckets come from the **synthetic variant hierarchy** (`default` →
buildType → flavor → leaf) plus base-subtraction: a dep lands in the most-general variant that
introduces it (so `freeDebug`/`paidDebug` shared deps collapse into `debug`/`default`). This
collapse is what minimizes artifact duplication across repos, and it's why `VariantBuilder`
deliberately creates many synthetic variant instances.

Aggregation produces each leaf variant's full closure; the collapsed buckets are then derived by
**set-difference along the `extendsFrom` hierarchy** (`default` = deps common to all leaves;
`debug` = common-to-debug-leaves − `default`; etc.). This reconstruction is sound and now
unblocked (§2.1 means the closures are complete). Matching the current output exactly is the
intricate part — and the aggregated union is a **superset** of the current output (see §6 watch
list: coordinate normalization, KMP split artifacts).

### 2.4 The oracle
A whole-tree `git diff --exit-code` is **invalid** — regeneration drifts on environment/tooling
noise unrelated to resolution: `fail_if_repin_required` pinning state in `WORKSPACE`, buildifier
formatting, and `additional_src_sets` (depends on a prior KSP run existing).

**Use instead:** semantic equality of `build/grazel/dependencies.json` (the serialized
`WorkspaceDependencies` — the exact intermediate this refactor changes) between **flag-OFF and
flag-ON** runs of `computeWorkspaceDependencies`. Parse the JSON and compare per-variant **sets**
(ids/versions/`direct`/`overrideTarget`/`repository`/`dependencies`) — *not* raw bytes:
`computeInternal` uses `parallelStream`/`ConcurrentHashMap`, so serialization order is
non-deterministic even between two OFF runs. Environmental noise cancels because it hits both runs.

**Procedure:** run `./gradlew computeWorkspaceDependencies` (flag OFF) → copy
`build/grazel/dependencies.json` aside → toggle the flag ON by adding
`grazel { experiments { aggregatedDependencyResolution = true } }` to the root `build.gradle`
(or via an init-script) → run the task again → semantic-diff the two JSONs (a small Python/jq
script comparing per-variant sets). Empty semantic diff = correct.

---

## 3. Chosen Direction

**Preserve the current bucketing** (same `WorkspaceDependencies` output → keep the §2.4 oracle,
no user-facing change), built on the aggregate approach:

1. Resolve each variant's full closure once via aggregation (§2.2; custom consumable config
   preferred), warm-cache, using the resolution graph (§2.1 pitfall).
2. Reconstruct the collapsed buckets via `extendsFrom` set-difference (§2.3) and feed per-variant
   results into the existing `ComputeWorkspaceDependencies` so downstream is untouched.

**Author's framing (keep an open mind on the accumulation step):** the bucketing/accumulation may
be done **differently** from how the current code does it (which accumulates inside each
per-module `ResolveVariantDependenciesTask`). Because aggregation yields the per-variant union
directly, the current **module-level dedup may be unnecessary** — don't assume the existing
collapse mechanism must be reproduced step-for-step, only its *output*.

Deferred (lower priority): KSP processor resolution stays a separate axis (needs artifact
download for class-name extraction). Interaction with variant compression
(`AnalyzeVariantCompressionTask`, branch `arun/variant-compression-part-2`) is complementary.

---

## 4. Technical Reference

### 4.1 Variant model & bucketing
- `extendsFrom` on a Grazel `Variant` is a **logical** hierarchy (not Gradle's
  `Configuration.extendsFrom`) — used for task ordering and base-dep subtraction (`Variant.kt:38,42`).
- Bucket routing is `MavenInstallStore` (`(variant, group, name) → repoName`), queried
  specific→general with a candid bare-`@maven` fallback. See companion doc for the full hierarchy.
- Synthetic configs `grazel*CompileClasspath` (`ConfigurationParsingVariant.kt:197-229`) carry
  `AgpVersionAttr`, `TargetJvmEnvironment=ANDROID`, `Usage=JAVA_RUNTIME`, `KotlinPlatformType=androidJvm`
  — but **no** `BuildTypeAttr`/`ProductFlavorAttr` (variant identity is in the config name).

### 4.2 AGP variant attribute injection (only if aggregating real AGP variants directly)
Needed only if NOT using the custom-attribute approach (§4.3). Confirmed to eliminate
`AmbiguousGraphVariantsException`. Do **not** set the internal `VariantAttr`.

The exact attribute set AGP puts on a leaf variant's outgoing config (e.g.
`demoFreeDebugRuntimeElements`), discovered by enumerating it:

| Attribute | Value (example) |
|---|---|
| `com.android.build.api.attributes.BuildTypeAttr` | `debug` |
| `com.android.build.api.attributes.ProductFlavor:<dimension>` (one per dim) | e.g. `service`→`demo`, `release`→`free` |
| `org.gradle.usage` | `java-runtime` |
| `com.android.build.api.attributes.AgpVersionAttr` | `8.6.1` |
| `org.gradle.jvm.environment` | `android` |
| `org.gradle.category` | `library` |
| `org.jetbrains.kotlin.platform.type` | `androidJvm` |

AGP registers ProductFlavor attrs under both a short alias (`service`) and the qualified form
(`com.android.build.api.attributes.ProductFlavor:service`) — setting the **qualified form** is
sufficient. **`KotlinPlatformType` must be set** to `androidJvm` (Grazel already does in-task; it
is *not* settable from an init-script). If omitted, Kotlin-Multiplatform artifacts resolve to JVM
stubs (`*-jvmstubs`) instead of the Android artifacts (`*-android`), silently changing the dep set.

```kotlin
attribute(Attribute.of("com.android.build.api.attributes.BuildTypeAttr", BuildTypeAttr::class.java),
          objects.named(BuildTypeAttr::class.java, targetVariant.backingVariant.buildType.name))
targetVariant.backingVariant.productFlavors.forEach { (dimension, flavor) ->
    attribute(Attribute.of("com.android.build.api.attributes.ProductFlavor:$dimension",
                           ProductFlavorAttr::class.java),
              objects.named(ProductFlavorAttr::class.java, flavor))
}
```

### 4.3 Custom consumable config (preferred aggregation vehicle)
Per migratable sub-project, one consumable config per variant; a root resolvable config with the
matching custom attribute depends on all migratable projects and resolves once per variant.
`extendsFrom` the **declared scopes** (Option A — `implementation`/`api`/`<buildType>Implementation`),
so the root does one native conflict resolution across all projects' declared deps. (Extending the
already-resolved `*RuntimeClasspath` also works but re-resolves transitives — wasteful.) The
consumable must be created before the root resolution (plugin-apply / `afterEvaluate`), not at
task time.

```groovy
// On each sub-project:
configurations.create("grazelExportAll<Variant>") {
    canBeConsumed = true; canBeResolved = false
    extendsFrom configurations.implementation, configurations.api   // + <buildType>/<flavor> scopes
    attributes { attribute(Attribute.of("com.grab.grazel.export", String), "all-<variant>") }
}
// On root: resolvable config with the same "com.grab.grazel.export" attribute,
// depending on all migratable projects; resolve via incoming.resolutionResult.allComponents.
```

### 4.4 Current code state (committed on this branch)
- `ExperimentsExtension.aggregatedDependencyResolution` flag (default off).
- `ResolvedComponentsVisitor.traverseProjectNodes` option (default false). Committed for the
  root→subprojects→externals traversal, but the current resolver hardcodes `false` and does **not**
  use it yet. OFF path untouched.
- `AggregatedDependencyResolver` + `ComputeWorkspaceDependencies.computeFromResults` wired into
  `ComputeWorkspaceDependenciesTask` behind the flag.

**What the committed `AggregatedDependencyResolver` actually is:** a complete (~300-line)
**per-module** resolver — for each `(project, synthetic-variant)` it resolves that project's own
`grazel<Variant>CompileClasspath`, max-version-merges across projects, and handles exclude rules +
KSP. It **passes the §2.4 oracle** (correct output, including flavor buckets) but is **O(P×V) — no
perf win**. It is *not* a stub; it is a correct fallback to be **superseded** by the §3 root-aggregation
approach. (Note: the `ComputeWorkspaceDependenciesTask` KDoc currently claims "O(V) root
configurations" — that comment is inaccurate; the resolver is O(P×V). Fix the comment when reworking it.)

**Why it resolves per-module (the constraint a rewrite must respect):** the synthetic base buckets
(`default`, `debug`) are *not* real AGP leaf variants. Asking a root config for `default`/`debug` of
a flavored module throws `AmbiguousGraphVariantsException` unless you inject `BuildTypeAttr` + every
`ProductFlavorAttr` (§4.2). The custom-attribute vehicle (§4.3) avoids this **for leaf variants only**.
So root-aggregating the *synthetic base buckets* directly is the hard part — the §3 plan resolves
**leaf** variants at the root and reconstructs the base buckets via set-diff, rather than aggregating
the base buckets directly.

**Direct-only emission (constraint — a rewrite WILL trip on this):** the resolver emits only **direct**
deps per result, with transitives carried in `ResolvedDependency.dependencies`. If you instead emit all
transitive components, `computeInternal`'s `reducedClasspath` step collapses every non-`default` bucket
to empty (every transitive also appears in the large `default` closure and gets subtracted away),
silently merging the whole graph into `default`. Keep emission direct-only.

### 4.5 File index
| Concern | Path (under `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/`) |
|---|---|
| Per-variant resolution (OFF) | `tasks/internal/ResolveVariantDependenciesTask.kt` |
| Merge → WorkspaceDependencies | `gradle/dependencies/ComputeWorkspaceDependencies.kt` |
| Data model | `gradle/dependencies/model/ResolveDependenciesResult.kt` |
| Graph→ResolvedDependency | `gradle/dependencies/ResolvedComponentsVisitor.kt` |
| New aggregated path | `gradle/dependencies/AggregatedDependencyResolver.kt` |
| Task wiring | `tasks/internal/ComputeWorkspaceDependenciesTask.kt`, `TasksManager.kt` |
| Variant model | `gradle/variant/Variant.kt`, `AndroidVariants.kt`, `VariantBuilder.kt` |
| Bucket routing | `gradle/dependencies/MavenInstallStore.kt` |
| Flag | `extension/ExperimentsExtension.kt` |

---

## 5. Investigation Timeline (condensed — lessons & dead-ends)

Chronological record so the reasoning isn't re-derived. Conclusions superseded by §2 are marked.

1. **Spike 1 — aggregate real AGP leaf configs.** Works for leaf variants; resolving a synthetic
   base bucket (`default`/`debug`) of a flavored module throws `AmbiguousGraphVariantsException`.
   Surfaced that aggregation must cover all scope-groups (compile/test/KSP/lint), not compile-only.
2. **Spike 2 — attribute injection.** Confirmed `BuildTypeAttr` + `ProductFlavorAttr` per dimension
   removes the ambiguity (§4.2). Later made moot by the custom-attribute approach (§4.3).
3. **Attempts 1–3 — flag + resolver.** Established the flag, the `traverseProjectNodes` visitor
   option, and the oracle harness. Attempt 3 passes the oracle but is per-module (O(P×V)) — correct
   output, no perf win. (An intermediate agent once "passed" by reverting to re-read the OFF JSONs —
   a hollow pass; discarded.)
4. **Flavor bucketing confirmed.** Adding `demoImplementation gson` produced a distinct `demo`
   bucket, identical OFF vs ON.
5. **Attempt 4 — "DEFINITIVE NEGATIVE" — *SUPERSEDED, was a false negative.*** True O(V_leaf)
   aggregation (proven: 10 leaf variants → 10 root resolutions) appeared to drop sub-projects'
   `implementation` deps, concluding root aggregation impossible. **Wrong:** the cause was the
   cold-cache + `lenientConfiguration` measurement pitfall (§2.1), not real encapsulation. The
   deps were present in the graph as UNRESOLVED nodes the lenient API silently dropped.
6. **Custom-config + warm-cache verification — overturned #5.** §2.1/§2.2: implementation deps are
   visible warm-cache; the custom consumable config exposes the full closure cross-project in one
   resolution. O(V) aggregation is viable.

---

## 6. Next Steps

1. Implement §3: supersede the current per-module `AggregatedDependencyResolver` (§4.4) with
   custom-consumable-config aggregation (one resolution per variant), warm-cache, resolution-graph API.
   Respect the §4.4 constraints (synthetic base buckets via leaf set-diff, not direct root aggregation;
   direct-only emission).
2. Reconstruct collapsed buckets via `extendsFrom` set-diff; feed into `computeFromResults`. Keep an
   open mind on the accumulation method (module-level dedup likely unnecessary — §3).
3. Validate with the §2.4 semantic oracle (OFF vs ON `dependencies.json`), then measure wall-clock +
   peak heap vs OFF to confirm the win.

**Reconciliation watch list** (the aggregated union is a superset of the current output — these are
the known deltas to handle):
- **Coordinate normalization.** The graph yields post-Jetifier `androidx.*` coordinates; the OFF
  union still carries some legacy `android.arch.*` coordinates. Reconcile if old coordinates matter.
- **KMP split artifacts.** The graph includes `-android`-suffixed Kotlin-Multiplatform split
  artifacts (e.g. `foundation-android`); `maven_install` likely wants the base coordinate
  (`foundation`). Decide normalization.
- **Scope coverage** — cover compile/test/androidTest/KSP/lint, not compile-only.
- **Orphan library modules** not on any app classpath; per-module `resolutionStrategy`/substitution
  divergence.

## Resume prompt (fresh session)
> Read `reports/dependencies-refactor-design-notes.md` (this file) + companion
> `reports/dependency-resolution-to-workspace.md`. Settled: O(V) aggregation is viable (§2);
> remaining work is bucketing reconstruction (§3). Implement §6 steps 1–3 behind the
> `aggregatedDependencyResolution` flag and validate with the §2.4 oracle.
