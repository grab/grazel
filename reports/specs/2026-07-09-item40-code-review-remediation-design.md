# Item 40 — Code-Review Remediation Batch (Design / Execution Spec)

> **Status:** Approved 2026-07-09 (derived from the `/code-review` findings + the simplify pass + a
> read-only grounding probe).
> **Executor:** Claude Code (this session) via serialized Sonnet workers — **one Gradle build at a time**.
> **Behaviour change:** Group A is behaviour-*adjacent* (must be proven byte-identical + zero-divergence
> on PAX). Groups B–F are behaviour-preserving (golden empty-diff + PAX byte-identical). #2 is
> documentation-only.
> **Global Constraints + Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`. **Follows:** Items 39 + the code-review fixes (#3/#4) already landed.
> **Explicitly held for their own passes (NOT in this batch):** #1 `bucketSpecificity` (needs typed
> combo-bucket modelling — a design pass); perf #7 (double-computed declared-main table) and #8 (O(R×P)
> exclude-rule rebuild).

---

## Goal

Clear the actionable remainder of the code review without skipping real debt: harden the exclude-rule
hierarchy resolution (remove the last name-string special-cases), collapse the three genuine
duplications, memoise one hot-path scan, and document one intentional-but-confusingly-named gate.

## Verification strategy (build discipline)
All changes gate on `./gradlew :grazel-gradle-plugin:test` + `verifyGrazelGoldenBaseline` (generated-file
`git diff` EMPTY) run by the implementing worker. Group A additionally carries temporary
shadow-divergence logging validated by **one** final PAX run that also serves as the byte-identical gate
for the whole batch (non-destructive `git stash create` snapshot → migrate → diff, as in Items 39/#3/#4).
**Never run two Gradle builds concurrently.** The known `:sample-android:lintDemoFreeDebug` and
`verify-sample-bucket-labels.sh` appcompat/constraintlayout failures are pre-existing and out of scope.

---

## Group A — Exclude-rule hierarchy hardening (findings 1 + 2)
**File:** `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/DeclaredDependencyMetadataCollector.kt` (+ `AggregatedDependencyResolver.kt` construction site).

### Grounded state
- `variantHierarchyNamesByName` is built at two sites — `DeclaredDependencyMetadataCollector.kt:185` and
  `AggregatedDependencyResolver.kt:241` — each `variant.name -> setOf(name) + extendsFrom`. It is only
  ever looked up by key (never enumerated), and already contains `"default" -> setOf("default")`.
- `rulesFor` (`:397-420`) currently does `attrHierarchyNames.ifEmpty { selectedVariantHierarchyNames(displayName, ...) }`
  — so a non-null-but-unmapped attr silently falls through to the string heuristic.
- `selectedVariantHierarchyNames` (`:423-447`) ends with a hardcoded
  `when (displayName) { "apiElements","runtimeElements" -> variantHierarchyNamesByName[DEFAULT_VARIANT] }`.

### Work
1. **Seed the sentinel keys.** At both construction sites, add `"apiElements"` and `"runtimeElements"`
   mapped to `setOf(DEFAULT_VARIANT)` (matching the existing `"default"` entry's shape). Do this at the
   single point each map is built. (`AggregatedDependencyResolver.kt:251` already has its own
   `ifEmpty { setOf(DEFAULT_VARIANT) }` guard — leave that; seeding doesn't change it.)
2. **Delete** the `when (displayName) { "apiElements","runtimeElements" -> ... }` branch in
   `selectedVariantHierarchyNames`; its final `ifEmpty { ... }` becomes `ifEmpty { emptySet() }` (or drop
   the `ifEmpty` and return `hierarchyNames`), since the seeded keys now resolve those names via the
   normal `startsWith` scan.
3. **Fallback only when the attr is null.** Change `rulesFor` so the display-name heuristic is used only
   when `selectedVariantAttrName == null`; when the attr is non-null, use `variantHierarchyNamesByName[attrName].orEmpty()`
   directly (no string fallback). This is the behaviour-adjacent change: a non-null-but-unmapped attr now
   yields empty (→ `bucketRulesByShortId` path) instead of the string heuristic.
4. **Typed-path unit test.** Extend the test fakes so a resolved project dependency can carry a
   `VariantAttr` value (today `FakeAttributeContainer.getAttribute` returns null and `Fakes.kt addDependencyTo`
   hardwires it, so the typed fast-path is unreachable from tests). Add a `selectedVariantAttrName`-style
   parameter to the fake dependency builder and a test asserting the typed attr path resolves exclude
   rules via the map lookup (not the display-name fallback).
5. **TEMP shadow-divergence logging** (removed after PAX): in `rulesFor`, also compute the old
   `ifEmpty { selectedVariantHierarchyNames(...) }` result and `logger.warn("GRAZEL_A_DIVERGENCE|...")`
   when it differs from the new result. Marked `// TEMP:`.

### Gate
Unit + golden empty-diff on the sample; then the final PAX run must show **zero `GRAZEL_A_DIVERGENCE`**
and byte-identical output. Any divergence = a real attr-present-but-unmapped case → report before
proceeding. Strip the TEMP logging after PAX is green.

---

## Group B — DRY #5: `MavenDependency.fromShortId` factory
**File:** `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/bazel/starlark/BazelDependency.kt` (+ 4 call sites).

`BazelDependency.MavenDependency(repo, group, name)` is built from a `group:name` shortId at four sites,
each doing `val (group, name) = shortId.split(":")`: `OverrideTargets.kt:23`, `MavenInstallOverrideTargets.kt:41`,
`Dependencies.kt:466`, `ArtifactPinner.kt:162`. Add to `MavenDependency`'s companion:
```kotlin
fun fromShortId(shortId: String, repo: String = "maven"): MavenDependency {
    val (group, name) = shortId.split(":")
    return MavenDependency(repo = repo, group = group, name = name)
}
```
Adopt at all four sites (each passes its own `repo`). No validation exists to preserve — behaviour-preserving.

---

## Group C — DRY #6: shared `MavenCoordinates.parseOrNull`
**File:** `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/maven/MavenRepositoryPath.kt` (+ 2 call sites).

Only three sites parse a `group:name:version` GAV: `MavenCoordinates.parse` (`MavenRepositoryPath.kt:109`,
strict-3 throw), `ResolvedDependencyNotation.fromId` (`ResolveDependenciesResult.kt:116`, strict-3 throw),
`toDeclaredResolvedDependency` (`DeclaredDependencyMetadataCollector.kt:600`, size-3 + non-blank → null).
Add:
```kotlin
fun parseOrNull(gav: String): MavenCoordinates? {
    val parts = gav.split(":")
    if (parts.size != 3) return null
    val (group, module, version) = parts
    if (group.isBlank() || module.isBlank() || version.isBlank()) return null
    return MavenCoordinates(group, module, version)
}
```
- `MavenCoordinates.parse` delegates: `parseOrNull(gav) ?: error("Expected group:name:version coordinate, got $gav")`.
- `toDeclaredResolvedDependency`: replace the manual split+blank-check with
  `MavenCoordinates.parseOrNull(declaredDependencyId) ?: return null`.
- `ResolvedDependencyNotation.fromId`: **optional** — it returns a *different* type; adopt only if the
  conversion (`parseOrNull(id)?.let { ResolvedDependencyNotation(it.group, it.module, it.version) } ?: error(...)`)
  is not uglier than the current 3 lines. If it is, leave `fromId` and note it — the win is A+D.
- **Do NOT touch:** the 6-part wire-format `ResolvedDependencyNotation.parse`, the guaranteed-valid
  destructures in `DependencyResolutionService.kt`/`RootBazelFileBuilder.kt`, the 2-part shortId splits,
  or the `2..3` lockfile-key / `==2` KotlinExtension parsers — different contracts, not GAV.

Behaviour-preserving per site (each keeps its own throw/null contract).

---

## Group D — DRY #9: `reduceNonDefaultBuckets` shared helper
**Files:** `DefaultOverrideCarrierPlanner.kt:28`, `DefaultBucketDependencyReducer.kt:25` (+ new helper).

Both share the outer `parallelStream().filter{key!=DEFAULT}.filter{isNotEmpty}.collect(toConcurrentMap(...))`
frame + `.apply { put(DEFAULT_VARIANT, defaultClasspath) }`; they differ only in the per-bucket transform
(one filters + remaps values, the other filters only). Extract an `internal` top-level function in
`com.grab.grazel.gradle.dependencies`:
```kotlin
internal fun <V> reduceNonDefaultBuckets(
    classPaths: Map<String, Map<String, ResolvedDependency>>,
    defaultKey: String = DEFAULT_VARIANT,
    perBucketTransform: (default: Map<String, ResolvedDependency>, bucket: Map<String, ResolvedDependency>) -> Map<String, V>
): MutableMap<String, Map<String, V>>
```
returning the mutable concurrent map so each caller appends `.apply { put(defaultKey, default) }`. Each
caller supplies its inner-stream lambda; `DefaultOverrideCarrierPlanner`'s trailing sorted-`List`
`mapValues` stays in the caller. Concurrency semantics (`toConcurrentMap`, inner `parallelStream`, the
post-collect `put`) are identical to today — behaviour-preserving.

---

## Group E — Perf #10: memoise `collectMavenDeps` declaration index
**File:** `Dependencies.kt`.

`collectMavenDeps` rebuilds `directVariantDeclarationsByShortId` (`:335-360`) — a full scan of
`declaredDependencyConfigurations` — on every call for the same project+variantKey, while the sibling
`collectTransitiveMavenDeps` (`:431`) memoises by `VariantGraphKey` via `ConcurrentHashMap.computeIfAbsent`.
Add a parallel `ConcurrentHashMap<VariantGraphKey, Map<String, List<DirectVariantDeclaration>>>` at class
level and wrap the index build in `computeIfAbsent(variantKey)`. Inputs are execution-time-immutable
Gradle declared configs; `DefaultDependenciesDataSource` is `@Singleton` (build-lifetime), and
`DirectVariantDeclaration`'s live `ExternalDependency` refs are fine for that lifetime. Behaviour-preserving.

---

## Group F — #2: document the reachability gate (NO behaviour change)
**File:** `Dependencies.kt:327`.

Investigation verdict: `shouldFailOnMissingMavenDependency` is an **intentional reachability gate**
(introduced `860c569`), not a regression — it fails hard for reachable buckets and only skips
unreachable/inactive modules, with `hasMainBucketReachability()` preserving strict behaviour when there's
no reachability data. Add a KDoc on the function explaining this (the name reads like a simple flag but
the behaviour is reachability-based). **No code/behaviour change.** Finding #2 is thereby closed.

---

## Sequencing (serialized; one builder at a time)
1. Group A worker (impl + typed-path test + TEMP shadow logging) → sample unit + golden gate.
2. Groups B, C, D, E, F worker(s) → sample unit + golden gate each. (May be one worker across B–F since
   they touch mostly distinct files; watch the `Dependencies.kt` overlap between E and #5-site.)
3. **One** final PAX run (byte-identical + zero `GRAZEL_A_DIVERGENCE`).
4. Strip Group A TEMP logging; quick unit compile.
5. Commit as reviewable units (Group A; DRY B/C/D; perf E; doc F — or grouped sensibly).

## Hard constraints
- One Gradle build at a time; no worker builds while another does.
- Group A is the only behaviour-adjacent change — it MUST be zero-divergence + byte-identical on PAX or it
  stops for review. Groups B–F must be golden empty-diff + PAX byte-identical.
- Never re-weaken a test; never stage/commit `codedb.snapshot`.
- Do not touch the held items (#1, #7, #8) or the out-of-scope pre-existing failures.

## Acceptance criteria
- Group A: `when`-branch gone, seeded sentinel keys, null-only fallback, typed-path unit test present and
  green, zero PAX divergence, byte-identical.
- #5: `fromShortId` factory adopted at all 4 sites. #6: `parseOrNull` shared by ≥2 GAV sites, per-site
  contracts intact. #9: `reduceNonDefaultBuckets` adopted by both planners. #10: declaration index
  memoised. #2: intent KDoc added.
- Full unit suite green; golden empty-diff; PAX byte-identical (size guard + bounded audit unchanged).
