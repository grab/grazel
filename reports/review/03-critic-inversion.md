# Critic 3 — The inversion itself (top-down aggregated resolution)

Scope: `AggregatedDependencyResolver.kt`, `WorkspaceDependencyRootInputPlanner.kt`,
`ResolvedComponentsVisitor.kt`, `resolution/` (RootContributionComputer, MainReachabilityTracker,
DependencyBucketAccumulator, DeclaredMetadataMerger, RootVisitOutcome), and the task wiring in
`WorkspaceDependencyInputsRegistrar.kt` / `ResolveWorkspaceDependenciesTask.kt`. Compared against
master's `ResolveVariantDependenciesTask.kt` (the deleted bottom-up model).

## Verdict up front

**The inversion's core is essentially right and should stay.** Bottom-up was O(P projects × V
variants) full Gradle resolutions; the new model is O(B binaries × V variants × ~2 configs), and in
the target monorepo P >> B — that ratio *is* the perf win, and there is no cheaper way to get
Gradle-conflict-resolved versions per bucket. Attribution-by-graph-walk (`traverseProjectNodes` +
`directFromProject` in `ResolvedComponentsVisitor`) is also the right call at the concept level: it
recovers "module M's direct deps" from the graph Gradle actually resolved, inheriting exclude/
conflict/jetifier semantics instead of re-implementing them from declarations.

**Where it overshot is the session machinery around the walk**, which carries a reconstruction tax
in three forms: (1) *two* overlapping reachability computations unioned together, (2) a
computer/spine protocol that encodes root kind in field nullness and splits tracker mutation across
two components with a documented-but-not-enforced ordering invariant, and (3) refactor-fidelity
treated as spec — behaviour defined as "verbatim from collectRootClosures" (a deleted function)
rather than as domain invariants. Roughly 30–40% of the lines in `AggregatedDependencyResolver.kt`,
`RootContributionComputer.kt` and `MainReachabilityTracker.kt` are comments explaining ordering
invariants relative to deleted code; that comment burden is the clearest symptom that the state
machine is implicit, not designed.

## Essential vs accidental complexity

**Essential (the problem genuinely forces this):**

- Seeding roots from binaries and reconstructing per-module facts at all. Once you aggregate, three
  things are irreversibly destroyed by Gradle's graph and *must* be reconstructed: per-edge exclude
  semantics (`MainReachabilityTracker.filterExcludedByEveryReachableRoot`'s
  intersection-not-union rule is a correct, forced consequence of aggregation), per-module direct
  ownership (`directFromProject` attribution), and deps that never appear on any resolvable
  classpath (`DeclaredMetadataMerger` for compileOnly — genuinely undiscoverable top-down).
- The serialization boundary (`DeclaredDependencyMetadata` JSON, `AggregatedDependencyRootMetadata`)
  — forced by task inputs/cacheability. The 689-line `DeclaredDependencyMetadataCollector` is the
  fair price of moving resolution behind a task boundary.
- Deterministic sorting of `visitResults` before folding (AggregatedDependencyResolver.kt:240–250).
  Gradle's traversal order is unordered and `mergeDependencyMetadataByMaxVersion` is
  order-sensitive on ties ("keep candidate"), so the sort is load-bearing for byte-identical
  output. The alternative — a fully commutative merge — would move tie outcomes. Keep.

**Accidental (this implementation's own weight):**

1. **Dual reachability channels.** Reachability of projects/buckets from a main root is computed
   *twice* per MAIN root: once by `MainReachabilityTracker.computeScope` (a DFS over *declared*
   `project(...)` edges from serialized metadata, seeded before the walk) and once by the walk
   itself (`RootVisitOutcome.reachableProjectPaths` / `reachableBucketNamesByProject`, folded back
   via `recordReachable`). Both feed the same two maps with union semantics — including a
   deliberately preserved asymmetry (`recordReachable` uses raw `addAll`, "not
   addReachableMainBuckets", to match "the reachability out-param it replaced"). Two sources of
   truth for one fact, coupled by a seed-before-resolve ordering invariant that exists *only
   because* there are two channels.
2. **The RootContribution protocol.** `RootContribution.scope` is exposed "purely so callers/tests
   can observe" (dead weight in the production path); `lintClosure == null` is used by the spine as
   the discriminator for "fold reachability" (AggregatedDependencyResolver.kt:129) — a kind check
   encoded as field nullness; tracker mutation is split (computer seeds MAIN scope inside
   `compute`, spine folds walk-discovered deltas after), which is exactly the shape that needs the
   17-line doc comment at AggregatedDependencyResolver.kt:101–117 to keep anyone from breaking it.
3. **Wasted resolution work in `shouldResolveMainHierarchyRoot`.** The filter runs at *resolve*
   time (AggregatedDependencyResolver.kt:121–123), but every planned root's
   `configuration.incoming.resolutionResult.rootComponent` was already added to an `@Input`
   `ListProperty<ResolvedComponentResult>` (WorkspaceDependencyInputsRegistrar.kt:113–119), so
   Gradle fully resolves roots the resolver then refuses to walk. The facts the filter needs (leaf
   build-type names per project) are available at plan time from live `Variant<*>`s.
4. **Zip-by-index across three tasks.** `rootComponents.zip(rootMetadata)`
   (ResolveWorkspaceDependenciesTask.kt:95) pairs a components ListProperty with a JSON metadata
   list produced by a *different* task, protected only by a size check; `pinMavenArtifactsTask`
   consumes the same `rootInputs` order a third way. The ordering invariant lives across three
   `configure` blocks in the registrar. A reorder that preserves count misattributes silently.
5. **Per-edge map rebuild in the walk hot path.**
   `MainReachabilityTracker.selectedMainVariantHierarchyNames` (lines 90–108) constructs
   `variantHierarchyNamesByName` — an O(variants) map plus the hardcoded
   `apiElements`/`runtimeElements` entries — from scratch on *every* invocation, and it is invoked
   once per direct project edge per emitted dep per root (the `reachableBucketNamesForProject`
   callback in the sorted forEach). Roots × direct deps × O(V) allocations for a map that only
   depends on `projectPath`.
6. **Vestigial visitor API.** `ResolvedComponentsVisitor.visit<T : Comparable<T>>` returns a
   `sortedSetOf<T>` — the contract master's per-variant task consumed. The new resolver ignores the
   return value entirely, uses the transform as a filter-and-collect side channel into its own
   `visitResults` list, returns `shortId` strings solely to satisfy `Comparable`, then re-sorts
   with its own 6-key comparator. `shortId` is computed twice (lines 227 and 263), and
   `direct = true` when `traverseProjectNodes` (line 289) is redundant with the
   `!visitResult.directFromProject → skip` filter at line 234 — every survivor is direct by
   construction.
7. **Refactor-diary comments as spec.** "Relocated verbatim from…", "matching
   `collectRootClosures`", "mirroring the in-place mutation this replaced" appear throughout
   `resolution/`. These document fidelity to deleted code, including preserved quirks
   (`seedsBinaryRoot = true` even when routing is empty, "matching collectRootClosures setting
   sawBinaryRoot before any leaf-name null check"). Six months from now, nobody can check these
   claims; the invariants should be stated in domain terms or enforced in types/tests.

## Ranked simplifications

1. **Move all tracker mutation into `RootContributionComputer.compute`** (it already holds the
   tracker): fold `recordReachable` for non-LINT kinds inside the per-kind methods, delete
   `RootContribution.scope` and the `lintClosure == null` gate (keep `lintClosure` purely as data
   for `foldLint`). The spine loses its biggest ordering comment; `RootContribution` becomes
   routing + closures. *Effort: S (half-day + tests). Risk: low; pure code motion, byte-identical.*
2. **Hoist `shouldResolveMainHierarchyRoot` into `WorkspaceDependencyRootInputPlanner`** so
   never-walked roots are never planned, never resolved, and never snapshotted as task inputs.
   Saves real Gradle resolutions (finding 3), and the tracker loses a method plus
   `mainBuildTypeNamesByProject`. One check needed: `pinMavenArtifactsTask` currently receives
   *all* planned roots' configurations — confirm whether pin resolution intentionally includes the
   skipped hierarchy roots; if yes, filter only the resolve-task list. *Effort: S–M. Risk:
   medium-low; BUILD output identical (those roots were skipped anyway), pin input set must be
   verified against the golden baseline.*
3. **Cache `selectedMainVariantHierarchyNames`' per-project map** (`Map<projectPath, Map<name,
   hierarchyNames>>` computed once in the tracker's init or lazily per project). *Effort: XS. Risk:
   none; identical results, removes an O(roots × edges × V) allocation hotspot from the very path
   this branch exists to speed up.*
4. **Kill the zip-by-index contract**: key `AggregatedDependencyRootMetadata` by
   `(projectPath, configurationName, kind)` and join, or carry a single wrapper input per root (the
   metadata is already `java.io.Serializable`). *Effort: M. Risk: low; no output change, removes a
   silent-misattribution failure mode.*
5. **Simplify the visitor contract**: `visit` takes a `(VisitResult) -> Unit` consumer (or returns
   `List<VisitResult>`), drop the generic `Comparable` return set; both remaining callers
   (`AggregatedDependencyResolver`, `CollectKspProcessorDependenciesTask`) do their own
   accumulation anyway. Fold the resolver's two-phase collect-then-sort loop into one pass over the
   sorted list, compute `shortId` once, and derive `direct` from `directFromProject` instead of the
   parallel `metadata.traverseProjectNodes` check. *Effort: S. Risk: low; keep the caller-side sort
   exactly as-is for byte identity.*
6. **Replace refactor-diary comments with domain invariants** ("the walk consults reachability, so
   MAIN scope must be recorded before resolving that root's closure" — and after item 1, most of
   these comments simply disappear because the invariant becomes local). *Effort: S. Risk: none.*

## The bigger question (flagged, not demanded)

- **Can one reachability channel go?** After item 1, investigate whether walk-discovered
  reachability (`RootVisitOutcome.reachable*`) is ever *not* a subset of the declared-edge DFS
  (`computeScope`) for MAIN roots, and conversely whether the declared DFS adds anything for
  TEST/UNIT_TEST/ANDROID_TEST roots (which only fold the walk channel). If one channel dominates in
  the downstream monorepo baseline, delete the other and the seed-before-resolve invariant
  evaporates. *Effort: M–L (needs baseline diffing). Risk: output drift possible — gated by the
  golden baseline, which is exactly what it's for.*
- **The road not taken — declared-based attribution.** A cheaper-looking model exists: aggregate
  binaries *only* for the union (pinning, versions, repos, closures) with no per-module
  attribution, and derive each module's direct deps from `DeclaredDependencyMetadataCollector`'s
  output (already collected!) joined against the aggregated union by shortId. That would delete
  `traverseProjectNodes`, `directFromProject`, the variant-attr plumbing, and most of the tracker.
  I do **not** recommend it now: "direct" today means *declared on a project edge in the resolved
  graph*, which reflects excludes, config wiring, and resolution outcomes that raw declarations
  don't — byte-identity would almost certainly move, and re-deriving those semantics from declared
  metadata is how you end up re-implementing Gradle. But it should be recorded as the explicit
  trade: the walk-based attribution is the *defensible* tax, and everything in this critique is
  about paying that tax once, through one channel, instead of one and a half times through two.

## Keep as-is (genuinely right)

- Binary-seeded roots + graph-walk attribution: the perf model and the semantics-inheritance
  argument both hold. Do not reintroduce per-module resolution.
- `filterExcludedByEveryReachableRoot`'s intersection semantics: forced by aggregation, correctly
  documented in Gradle terms.
- Deterministic pre-merge sort and `mergeDependencyMetadataByMaxVersion`'s field-by-field rules:
  order-sensitivity is contained and the doc comment (AggregatedDependencyResolver.kt:333–354) is
  one of the few that explains *domain* semantics rather than refactor fidelity. Model comment.
- `DeclaredMetadataMerger`: small, necessary (compileOnly is undiscoverable top-down), ordering
  documented as three numbered steps. Fine.
- `WorkspaceDependencyRootInputPlanner`: straightforward enumeration; the per-project (not
  per-binary) LINT roots look like a bottom-up remnant but use only the tiny `lintChecks`
  configuration (custom lint-rule jars), so the O(P) residue is cheap. Acceptable; worth a one-line
  comment saying exactly that.
- `DependencyBucketAccumulator`: borderline (five maps + enum switch could be one `EnumMap`), but
  it is small, clear, and buckets are another critic's remit.

**Altitude verdict:** right altitude in concept — the inversion is a narrow, purpose-built
mechanism, not over-generalized infrastructure. But the session layer is one refactor short of
settled: it preserved the deleted god-class's incidental ordering quirks as spec instead of
collapsing them into local invariants. Items 1–3 above are the difference between "decomposed" and
"designed", and all three are byte-identical-safe.
