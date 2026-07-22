# Clean-room design: Gradle→Bazel migration as a two-graph projection problem

*Designed purely from the problem brief. No implementation was consulted.*

---

## 0. The structural insight everything else follows from

There are **two different graphs** hiding in this problem, and the naive approach is slow because
it conflates them:

**G_P — the project graph (cheap).**
Nodes are `(module, variant)` pairs. Edges are the dependencies each module *declares*:
`(m, v) --scope--> (m', ·)` for project deps, and `(m, v) --scope--> group:artifact[:version]`
for external deps. Every edge in this graph is available from Gradle's configuration DSL
metadata (`configuration.dependencies`, the variant's configuration hierarchy) **without
triggering resolution**. Reading it is O(size of build scripts), not O(dependency resolution).

**G_A — the artifact graph (expensive).**
Nodes are Maven coordinates; edges are artifact→artifact transitive dependencies; node labels
are *selected versions* after Gradle's conflict resolution (forced versions, BOM/platform
alignment, substitution rules, highest-wins). This graph is only obtainable by an expensive
Gradle resolution, and — crucially — **version selection is a global property of a resolution
scope, not a local property of a module**. You cannot compute a module's "correct version of
okhttp" locally; it depends on everything else in the same classpath.

Now observe what each output actually needs:

| Output | Needs G_P? | Needs G_A? |
|---|---|---|
| Which modules to generate (no dangling refs) | reachability closure on G_P | no |
| Each module's BUILD target edges | that module's *direct declared* edges | no |
| Pinned artifact lists per bucket | seed set = union of declared externals | yes — once per bucket |

The reason BUILD files don't need G_A: **Bazel handles transitivity itself.** A
`kt_jvm_library` / `android_library` declares only direct deps; `api` vs `implementation` maps
to `exports` vs plain `deps`; and external references are *unversioned* labels
(`@maven//:group_artifact`) whose version lives solely in the pin file. So the per-module,
per-variant answer is a **pure projection of declared metadata** — zero resolutions.

The only thing that fundamentally requires expensive resolution is the version-selection
fixpoint, and because Bazel's `maven_install` enforces **one version per artifact per
repository bucket**, that fixpoint needs to be computed **once per bucket**, not once per
module. That is the entire trick: *resolve per universe, project per module.*

Everything below is bookkeeping around this insight.

---

## 1. Algorithm — five phases, single pass each

### Phase 0 — Cheap model sweep (no resolution)

Consumes: the Gradle project model.
Produces: G_P.

For every module, for every variant of that module, walk the variant's configuration hierarchy
(`debugImplementation extends implementation extends ...`) and record declared edges as tuples:

```
Edge = (fromModule, variant, scope, target)
  scope  ∈ {api, implementation, compileOnly, runtimeOnly,
            testImplementation, androidTestImplementation, kapt/annotationProcessor, ...}
  target ∈ ProjectRef(path) | MavenRef(group, artifact, declaredVersionOrNull)
```

Also record per-module facts needed for target choice: is it a binary, is it Android vs pure
Kotlin, does it have tests / androidTests, resolution-strategy rules (forces, excludes,
substitutions) declared anywhere.

Cost: O(M · V · d) map operations, no I/O beyond configuration. This is the *only* pass that
touches every module, and it is cheap by construction.

### Phase 1 — Generation set via closure on G_P (no resolution)

Consumes: G_P + the set of binary roots (app modules, instrumentation-test apps).
Produces: the **generation set** S = all `(module)` nodes that must get a BUILD file, plus the
per-module set of variants actually demanded.

BFS/DFS from the binaries over declared project edges of **all scopes, all variants**. This is
the load-bearing correctness decision:

> Module reachability is computed on the *declared* graph, never on any resolved classpath.

A library consumed only via `testImplementation` never appears on any binary's compile or
runtime classpath — so any design that discovers modules from resolution results silently drops
it. On G_P the `testImplementation` edge is just an edge; the closure includes the library, and
transitively everything *it* declares. No dangling reference is possible because "referenced"
and "reachable" are the same relation by construction (§4).

Variant demand propagates along the same walk: a `debug` app variant demanding a library maps
through Gradle's variant matching (build-type/flavor fallback) to a concrete variant of the
library; record `(module → demanded variants)`. If a module is reached only via test scopes,
demand its default/test variant.

Cost: O(|nodes| + |edges|) = O(M·V + E).

### Phase 2 — Bucketed version pinning (ALL the expensive work lives here)

Consumes: G_P restricted to S; the bucket definition the build expects.
Produces: per bucket b, a pin map `Pin_b : (group, artifact) → (version, repo, checksum)` and
the full transitive artifact closure for the `*_install.json`.

Buckets partition by *resolution universe*: at minimum `maven` (production compile+runtime)
and `android_test_maven`/test bucket(s), plus any custom-repository buckets. For each bucket,
perform **one** Gradle resolution of a *synthetic aggregate configuration* created on the root
project:

1. **Seed** it with the union of every declared external coordinate that attributes to this
   bucket, across all modules in S and all their demanded variants (for the test bucket: the
   union of every module's `testImplementation`/`androidTestImplementation` externals —
   including those of the test-only-reachable library).
2. **Copy onto it** the global resolution inputs that change version selection: forced
   versions, platform/BOM imports, exclusion rules, substitution/resolution strategies,
   and the repository list.
3. **Resolve once.** Gradle runs its conflict-resolution fixpoint over the whole universe and
   hands back the complete transitive closure with selected versions. That closure *is* the
   pin list for the bucket.

For the production bucket there is a fidelity refinement worth making: seed it from (or simply
resolve) the **binary roots' variant classpaths** directly — the app's compile+runtime
configuration per variant already *is* the aggregate of everything shippable, resolved exactly
as Gradle ships it. Test buckets have no such natural root (nothing depends on all test
classpaths), so they must use the synthetic-union seed. This asymmetry — *real roots for
production, synthetic union for test* — is forced by the graph shape, not a style choice.

Aggregate-vs-per-module semantics: the union resolution can select a version no individual
module selected (highest wins across the union). That is not a bug — it is exactly the
one-version-per-bucket world Bazel imposes; any per-module version diversity had to collapse
anyway. Highest-wins guarantees the aggregate selection dominates each module's own selection,
and forces/substitutions are honored because they were copied onto the synthetic configuration.

**Number of expensive resolutions: (binaries × their variants) + (test buckets), i.e. roughly
`|B|·V_app + k` — a small constant, independent of M.** On a thousands-of-modules project this
is the difference between ~5 resolutions and ~10,000.

### Phase 3 — Projection: per-module, per-variant BUILD generation (no resolution)

Consumes: G_P restricted to S; the pin maps.
Produces: one BUILD.bazel per module in S.

For each `(m, v)`, a pure function of already-held data:

- **Target kind** from module facts (android_binary / android_library / kt_jvm_library;
  test targets iff test sources+deps exist).
- **Edges** from the module's own declared list, mapped by scope:
  `api → deps + exports`; `implementation → deps`; `compileOnly → neverlink dep`;
  `testImplementation → deps of the test target`; `kapt/annotationProcessor → plugins`.
  Project refs become module labels (guaranteed present, §4); Maven refs become
  `@<bucket>//:group_artifact`.
- **Bucket attribution** must be a *function* (deterministic label): an artifact reached by a
  production scope anywhere attributes to the production bucket even if also used in tests;
  only artifacts appearing *exclusively* under test scopes attribute to a test bucket.
  Precedence: production > androidTest > test. Compute attribution once, globally, before
  emitting any label.
- **Variant collapsing**: compute per-variant dep sets; if identical across a module's demanded
  variants, emit one target; otherwise emit variant-suffixed targets. (Structural hash of the
  dep set makes this an O(1) comparison per variant.)

Cost: O(E) total.

### Phase 4 — Validation + canonical serialization (no resolution)

Two invariant checks, both O(output):

1. Every project label emitted ∈ S. (Cannot fail by construction; assert anyway — it converts
   any future bug into a loud error instead of a broken Bazel build.)
2. Every `@bucket//:g_a` label emitted ∈ `Pin_bucket`, and every Phase-2 seed ∈ its bucket's
   pin closure. Catches a resolution silently dropping a seed (e.g. an exclude rule eating it).

Serialization: sort all lists (targets, deps, pin entries) by canonical key; fixed JSON key
order; no timestamps. Byte-stability then follows from input-determinism — the pipeline has no
other nondeterminism source because every phase is a pure function of G_P + pin maps.

---

## 2. Data structures (the minimum the problem forces)

1. **`EdgeTable`** — the flat list/multimap of declared edges keyed `(module, variant, scope)`.
   This *is* G_P. Forced: it is the sole source of truth for both reachability and BUILD edges.
2. **`GenerationSet`** — `module → demanded variants`. Forced: the no-dangling-refs guarantee is
   literally "emit exactly this set".
3. **`Pin_b` maps + closure lists** — per bucket, `(group,artifact) → version/repo/checksum`
   and the ordered transitive closure. Forced: it is the output, and Phase 3 reads it for
   attribution.
4. **`Attribution`** — global `(group,artifact) → bucket`, computed once with the precedence
   rule. Forced: without it, the same artifact could be labeled into different buckets by
   different modules, producing nondeterministic or conflicting BUILD files.
5. **Module fact record** — binary?, android?, variants, has-tests, resolution-strategy rules.
   Forced: target-kind selection and Phase-2 rule copying need it.

Nothing else. Notably absent: any cache of per-module resolution results, any resolved-graph
slice per module.

## 3. Complexity

- **Expensive Gradle resolutions:** `O(|binaries| · V_binary + |test buckets|)` — a handful,
  **independent of module count**. The naive baseline is `O(M · V)`.
- **Time (our code):** Phase 0 `O(M·V·d)`, Phase 1 `O(M·V + E)`, Phase 3 `O(E)`, Phase 4
  `O(E + A)` where A = pinned artifacts. Total linear in the size of the declared graph.
- **Space:** `O(M·V + E + A)` — the EdgeTable dominates; everything is held once, no per-module
  duplicated closures (Bazel owns transitivity, so we never materialize per-module transitive
  sets).
- **Fixpoint accounting:** the only fixpoint computation in the whole design is Gradle's
  version conflict resolution, delegated and run `O(buckets)` times. Every pass we implement
  ourselves is a single pass over a static graph — G_P does not change while we process it, so
  no iteration-to-convergence is ever needed.

## 4. Correctness argument

**No dangling module.** S is defined as the closure of the binaries under *declared* project
edges of all scopes/variants. Every project label emitted in Phase 3 is a declared edge of some
module in S; closure membership of the edge's target is immediate. So "emitted reference ⇒
member of S ⇒ BUILD file generated" holds by construction, and Phase 4's assert makes any
regression fail fast.

**No missing edge.** Edges are copied from each module's own declared list — the same list
Gradle compiles against for direct deps — not reconstructed from any resolved graph. There is
no approximation step that could lose one. Transitive edges are deliberately *not* our problem:
Bazel recomputes them from direct deps + exports + the pin closure.

**The `testImplementation`-only library (the trap).** Three separate guarantees, one per phase:
(1) Phase 1 reaches it because closure walks test-scope edges; it gets a BUILD file.
(2) Its own declared externals enter the Phase-2 seed union (seeds come from *all modules in S*,
not from root classpaths), so they and their transitives are pinned.
(3) Phase 3 emits its targets from its own declared edges like any other module. Nothing in the
pipeline distinguishes "reachable only via test scope" except bucket attribution — which is the
desired behavior.

**Complete pins.** Seed union ⊇ every declared external of every generated module in every
demanded variant; Gradle's resolution closure ⊇ seeds ∪ their transitives; Phase 4 invariant #2
verifies both inclusions mechanically.

**Identical compilation.** Direct-dep fidelity from declared edges; version fidelity from
delegating selection to Gradle itself with the real forces/BOMs/excludes copied in; the only
intentional divergence is version *unification* across the bucket, which Bazel mandates
regardless and which highest-wins keeps ≥ every module's own selection.

**Byte-stability.** Pure functions of deterministic inputs + total canonical ordering at
serialization. No iteration order of any hash map ever reaches the output.

## 5. What I would NOT build (and why)

- **Per-module Gradle resolution, or any cache/memoization layer for it.** The entire design
  exists so that per-module resolution is never needed; a cache for it is optimizing the thing
  you should delete.
- **Per-module transitive-closure reconstruction** (slicing the root resolution's graph to
  recover "module m's full classpath"). Bazel computes transitivity from direct deps; carrying
  per-module closures is O(M·A) space for zero output value.
- **A homemade version-conflict resolver** replaying Gradle's rules on declared metadata.
  Gradle's resolution semantics (BOMs, alignment, substitution, capability conflicts) are far
  too rich to reimplement faithfully; one synthetic resolution per bucket buys the real thing
  at constant cost.
- **`select()`-based variant merging in v1.** Per-variant targets (with hash-collapse when
  identical) are simpler, and in practice most libraries have identical dep sets across
  variants; add `select()` only if target explosion is measured, not presumed.
- **Incremental/differential re-resolution across runs.** Byte-stable output plus a
  from-scratch pipeline that is linear in E and constant in resolutions is already fast enough;
  incrementality adds a cache-invalidation problem the correctness bar doesn't pay for.
- **Discovering modules from resolved classpaths** (the "just walk the app's resolution result"
  shortcut). Tempting because the data is already in hand after Phase 2 — and precisely the
  design that drops test-only-reachable libraries. Reachability stays on G_P, always.

## 6. Summary in one sentence

Build the declared graph cheaply and treat it as the single source of truth for *structure*
(which modules, which edges); pay for Gradle resolution only where the problem is genuinely
global — version selection — and pay for it once per artifact universe (per bucket / binary
root), then project structure and pins back onto modules as pure, order-canonicalized
functions: `O(binaries·variants + buckets)` resolutions instead of `O(modules·variants)`.
