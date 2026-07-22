# Clean-room design: Gradle→Bazel migration with the minimum number of expensive resolutions

Perf-first framing. Organizing question: **what is the theoretical minimum number of full
Gradle resolutions, and what can be reconstructed without any resolution at all?**

## 0. The central observation that makes the whole design

There are two fundamentally different kinds of information the output needs, and they have
wildly different costs:

1. **Declaration-level facts** — "module M, in configuration `debugImplementation`, declares
   a dep on `:foo` / `com.squareup.okhttp3:okhttp`". Reading `configuration.dependencies`
   (the *declared* set) is **cheap**: it is pure configuration-phase metadata, no dependency
   resolution is triggered. Same for the configuration `extendsFrom` hierarchy, applied
   plugins, and variant enumeration.

2. **Resolution-level facts** — "what exact version wins after conflict resolution, and what
   is the full transitive closure". Only touching `incoming.resolutionResult` /
   `resolvedConfiguration` is expensive. This is the *only* thing that costs O(graph) work
   per invocation.

Now the killer fact about the **target** system: Bazel's `maven_install`/pinned
`*_install.json` model has **one global version per artifact per repository bucket**. There
is no per-module version resolution in the output at all. `BUILD.bazel` files reference
external artifacts by version-less label (`@maven//:com_squareup_okhttp3_okhttp`); the
version lives once, in the pin file.

Therefore:

- **Per-module, per-variant fidelity of resolved versions is information the output cannot
  even express.** Resolving every module×variant computes data you throw away.
- The only resolution-level fact the output needs is: **for each repository bucket, the
  conflict-resolved version universe of the union of everything anyone declares** (plus its
  transitive closure).
- Everything else — which targets exist, which edges they have, per variant — is
  declaration-level and free.

**Theoretical minimum number of expensive resolutions = number of distinct version
universes you must pin = number of repository buckets, B.** B is a small constant (typically
1–3: `@maven`, maybe `@unstable_maven`, maybe a private-repo bucket). It is independent of
module count M and variant count V. The whole algorithm is built to hit that minimum.

## 1. Algorithm — passes in order

### Pass 1: Cheap declaration sweep (no resolution)

For every module, during Gradle configuration phase, collect:

- **Module descriptor**: applied plugins → target kind (android_binary / android_library /
  kt_jvm_library / test targets), package name, source sets, manifest, etc.
- **Variant model**: the list of variants (build-type × flavor) and, per variant, the set of
  declared configurations that feed its compile/runtime/test classpaths. This comes from the
  configuration `extendsFrom` DAG (`implementation` ⊆ `debugImplementation` ⊆
  `debugCompileClasspath`, plus `testImplementation`, `androidTestImplementation`,
  `kapt`/`annotationProcessor` variants). Pure metadata.
- **Declared edges**, tagged `(module, variant-scope, bucket-hint)`:
  - project deps → `ProjectEdge(from, to, scope, variants)`
  - external deps → `ArtifactEdge(from, group:artifact[:declaredVersion?], scope, variants,
    excludes, classifier)`

Cost: O(total declared edges E). No resolution triggered. Produces the **Edge Store** and
the **Module Store** (see data structures).

### Pass 2: Generation-set closure (no resolution)

Compute the set of modules that must have a `BUILD.bazel`:

```
GenSet = fixpoint(  seeds = all modules passing migration criteria,
                    step  = follow every ProjectEdge of *every* scope,
                            including testImplementation / androidTestImplementation )
```

This is a plain BFS over the Edge Store, O(M + E_project). Traversing **all** scopes is what
guarantees a library reachable only through a `testImplementation` edge is generated — the
closure is over declared edges, and a test-only edge is still a declared edge. No resolution
is needed to know a module is referenced; being referenced is a declaration-level fact.

If a referenced module fails migration criteria, that is a hard error surfaced now (fail
fast), not a dangling label discovered at Bazel build time.

### Pass 3: Seed aggregation per bucket (no resolution)

Partition every `ArtifactEdge` from every module in `GenSet` into its repository bucket
(user config maps group/coordinate patterns → bucket; default bucket otherwise). For each
bucket, union the declared coordinates into a **seed set**, carrying per-dependency excludes
and any user-level version overrides/forces. Deduplicate on `group:artifact` keeping the set
of declared versions (possibly empty for BOM-managed deps — keep the platform/BOM
declarations too, they go into the seed).

O(E_artifact) set union.

### Pass 4: The B expensive resolutions — one synthetic super-configuration per bucket

On the root project, create one detached/synthetic configuration per bucket. Populate it
with the bucket's seed set (all declared external coordinates + all declared
platforms/BOMs + global forces). Resolve it **once** via `incoming.resolutionResult`.

Each resolution yields, for that bucket:

- the **full transitive closure** of resolved `group:artifact:version` (the pin list),
- the **requested → selected mapping** (declared coordinate → conflict-winner, including
  module replacements/relocations), which we keep as the **Rewrite Map**.

This is exactly the semantics `maven_install` applies at Bazel build time — one global
conflict resolution over the union of seeds — so the numbers we pin are the numbers the
Bazel build actually needs. We are not approximating per-module Gradle resolution; we are
computing the target system's native semantics directly, once.

**Expensive-resolution count: B. Full stop.** For an app with 3,000 modules × 6 variants,
the naive plan is 18,000 resolutions; this plan is ~2.

(If, and only if, the project genuinely requires different versions per variant axis —
e.g. a `debug`-only bucket — that axis becomes an extra bucket. Buckets are the unit of
resolution *because* they are the unit of pinning. B grows with pin files, never with M×V.)

### Pass 5: Per-module, per-variant reconstruction (no resolution)

For each module in `GenSet`, for each variant, the BUILD-file deps are reconstructed
entirely from the Edge Store:

- deps of target T(variant v) = declared edges whose configuration participates in v's
  classpath per the Pass-1 `extendsFrom` model, split into `deps` / `exports` (api) /
  `plugins` (kapt) / test-target deps by scope.
- project edges → module target labels (guaranteed to exist by Pass 2).
- artifact edges → run the coordinate through the bucket's **Rewrite Map**
  (declared `group:artifact` → selected `group:artifact`) then emit the version-less bucket
  label. The Rewrite Map is what keeps a declared `com.google.guava:guava:jre` pointing at
  whatever coordinate actually won, so labels always land on a pinned artifact.

Only *direct* declared deps are emitted per target — Bazel resolves transitive external
deps through the maven repo rules and transitive project deps through target `exports`/deps.
We never need any module's transitive closure, which is precisely why we never resolve one.

### Pass 6: Emission + built-in consistency check

- Pin files: sort artifacts lexicographically per bucket, canonical JSON serialization →
  byte-for-byte stable across runs (stability comes from determinism of the sort + the fact
  that inputs are the declared seed set, which is stable).
- `WORKSPACE`: one `maven_install` per bucket from the pin list.
- BUILD files per module.
- **Emit-time assertion** (cheap, O(E)): every emitted label must be either (a) a module in
  `GenSet` or (b) a `group:artifact` present in its bucket's resolved pin list. Any miss is a
  generator bug and aborts the run. This turns "no dangling reference / no missing pin" from
  a hope into a checked invariant.

## 2. Data structures — the minimum the problem forces

| Structure | Shape | Why it is forced |
|---|---|---|
| **Module Store** | `module → {kind, variants, config-hierarchy}` | You cannot emit a target without knowing what kind it is and which configs feed which variant. |
| **Edge Store** | flat list/multimap of typed declared edges `(from, scope, variants, to)` | The single source of truth for BUILD edges *and* the closure *and* the seed sets. One structure, three consumers — anything less and you re-scan Gradle. |
| **GenSet** | set of modules | Dangling-reference guarantee. |
| **Seed sets** | `bucket → set<declared coordinate + excludes + boms>` | Input to the only expensive step. |
| **Pin lists** | `bucket → sorted set<G:A:V + repo/checksum>` | The deliverable. |
| **Rewrite Map** | `bucket → (requested G:A → selected G:A)` | Without it, declared labels can point at coordinates that conflict-resolution renamed away — the one place declaration-level and resolution-level facts must be joined. |

Nothing per-module-per-variant is ever *resolved* or stored beyond declared edges.

## 3. Complexity

Let M = modules, V = variants/module, E = total declared edges, A = distinct external
artifacts (with transitives), B = buckets.

- **Expensive Gradle resolutions: B** (constant; the theoretical minimum, since each pin
  file *is* a resolution output and you cannot pin without resolving at least once per
  version universe).
- Declaration sweep: O(M·V + E), all configuration-phase metadata.
- Closure: O(M + E).
- Reconstruction + emission: O(E + M·V) with hash lookups into the Rewrite Map.
- Space: O(E + A + M·V) — the Edge Store dominates.

The end-to-end wall clock is one Gradle configuration pass + B resolutions + linear
bookkeeping. Naive is Θ(M·V) resolutions; this is Θ(1) in M and V.

## 4. Correctness argument

- **No missing edge.** Every BUILD edge is a declared edge, and Pass 1 enumerates *all*
  declared configurations of *all* modules — there is no sampling and no scope filter.
  Transitive edges are intentionally absent from BUILD files because Bazel supplies them
  (maven repo rules for external, `exports` for `api` project deps); emitting only direct
  declared deps is not an approximation, it is the correct Bazel shape.
- **No dangling module.** GenSet is a fixpoint over all-scope project edges; any label
  emitted in Pass 5 refers to a module the closure already admitted (asserted in Pass 6).
  The `testImplementation`-only library case is covered *by construction*: the closure does
  not care why an edge exists, only that it is declared.
- **Complete pins.** The seed of each bucket is the union of every declared external
  coordinate of every generated module (all scopes, kapt included); the single resolution
  closes it transitively. An artifact can only be missing from the pin if no generated
  module declares a path to it — in which case no BUILD file references it either, and the
  Pass 6 assertion would catch any generator inconsistency.
- **Stable pins.** Deterministic seed (sorted union of declared coords) + deterministic
  resolver + canonical sorted serialization ⇒ byte-identical output across runs.
- **Version fidelity.** The pinned versions are the result of *global* conflict resolution,
  which is exactly what the Bazel build enforces at runtime. Matching per-module Gradle
  resolution would be *less* correct for the target system, not more.

## 5. What I would NOT build, and why

- **Per-module or per-variant Gradle resolution, in any form** (including "resolve lazily on
  demand", caching layers over per-module resolution, or parallelizing the naive loop).
  The output cannot represent per-module versions, so the work is unrepresentable-by-
  construction. Caching a computation you never need is still waste.
- **A resolved-graph mirror / full transitive graph per module.** Bazel reconstructs
  transitivity; storing it duplicates state that can drift and buys nothing.
- **Reachability-from-binaries pruning for pins.** Tempting ("only pin what the app graph
  needs"), but it breaks test-only and androidTest-only libraries and their artifacts, and
  it requires runtime-graph resolution to compute. Union-of-declared is both cheaper and
  strictly safer; dead declared deps costing a few extra pins is an acceptable trade.
- **Honoring per-module `resolutionStrategy` blocks.** Global pinning makes per-module
  strategies unenforceable in the output; instead surface them as explicit global overrides
  in tool config and warn on ones we detect but cannot honor. Building machinery to merge N
  conflicting per-module strategies into one universe is unbounded complexity for a
  semantics Bazel cannot express.
- **A post-generation Bazel dry-build verification loop as part of the algorithm.** Useful
  as CI, wrong as a crutch: the emit-time label/pin assertion already checks the only
  invariants the generator itself can break, at O(E) instead of a full analysis.
- **Incrementality/persistent caches between runs.** With B≈2 resolutions and otherwise
  linear work, a full regeneration is already fast; cache invalidation machinery would be
  the most bug-prone component in the system for marginal gain.

## 6. Minimum forced mechanism set (summary)

1. One declaration sweep (Module Store + Edge Store).
2. One all-scope closure (GenSet).
3. B synthetic super-configuration resolutions (pins + Rewrite Map).
4. One reconstruction/emission pass with the emit-time consistency assertion.

Anything beyond these four is optional engineering, not a requirement of the problem.
