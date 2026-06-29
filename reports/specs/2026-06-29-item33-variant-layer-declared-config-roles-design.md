# Item 33 — Move Declared-Metadata Configuration-Role Logic into the Variant Layer (Design)

> **Status:** Approved 2026-06-29 (brainstormed + grounded by an Opus feasibility investigation).
> **Executor:** Codex. **Behaviour change:** none — verbatim relocation of config-role
> classification from the dependencies layer to the variant layer. Golden EMPTY-diff.
> **Global Constraints + Verification Playbook + Code-quality stance:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`. **Depends on:** Item 26 (variant accessors exist),
> Items 29/30 (declared-metadata task reshaped to file boundaries).

> **⚠️ Execution note — delegate to subagents; protect the main context.** Verification and
> PAX runs go to focused subagents returning distilled results.

---

## Goal

`DeclaredDependencyMetadataCollector.kt` lives in `gradle.dependencies` but violates the
standing altitude rule **"the `gradle.variant` layer owns variant/configuration-role facts; the
dependencies layer consumes typed roles and does NOT do AGP access or configuration-name string
parsing."** It imports `BaseVariant` directly and re-implements configuration-name classification
that already (partly) lives in the variant layer. Move that knowledge up — **verbatim, no
algorithm change** — so the collector consumes typed configuration roles. Golden empty-diff.

This is the first dependencies→variant altitude relocation since the layering pass; it directly
applies the Item 1 code-quality stance (kill accidental complexity; the variant layer is the
proven-correct home for config-name knowledge — `ConfigurationParsingVariant` already owns it).

## Grounded current state (verified by Opus feasibility)

Violations, all in `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/DeclaredDependencyMetadataCollector.kt`:

**(A) AGP type access — the variant layer already wraps this:**
- `import com.android.build.gradle.api.BaseVariant` (`:19`); `variant.backingVariant as? BaseVariant`
  (`:82`); `androidLeafVariant`/`buildType`/`productFlavors` reads (`:93-98`).
- **Already exposed upstream:** `Variant.kt:131-141` provides `isWorkspaceAndroidLeaf`,
  `workspaceBuildTypeName`, `workspaceProductFlavorNames` — byte-identical logic, already
  consumed by the sibling `WorkspaceDependencyRootInputPlanner` (`:56-57,139`). The collector
  just re-implements it inline.

**(B) Configuration-name string classification (belongs in the variant layer):**
- `Configuration.isCompileOnlyDeclaration` (`:420-424`).
- `Configuration.isDeclarationBucket` (`:494-504`) + `declarationBucketExcludedNameFragments`
  (the 14-entry list, `:506-522`).
- `declarationConfigurationSuffixes` (`:549-554`) + `Configuration.declarationBucketName()`
  (`:556-567`) + `removeSuffixIgnoringCase` (`:569-575`).
- `Variant<*>.compileOnlyBucketName` (`:365-370`) — a pure `variantType`→name map (no parsing).

**(C) Raw extraction depending on (A)/(B) — stays in the dependencies layer:**
- `extractDeclaredExternalDependencyDeclarations` (`:524-547`), `extractDeclaredProjectDependencyIds`
  (`:577-593`), `extractCompileOnlyDependenciesByShortId` (`:595-626`),
  `Configuration.extractDeclaredExcludeRulesByShortId` (`:466-479`). Turning configs into
  `DeclaredExternalDependency`/`ResolvedDependency`/`ExcludeRule` is legitimate dependencies-layer
  modelling — it should consume the typed config sets from (B) but keep the construction.

**Precedent / duplication found:** `ConfigurationParsingVariant` already owns AGP config-name
parsing (`matchesVariantConfiguration`, `String.isAndroidTest/isUnitTest/isLint/isTest`),
confirming the variant layer is the accepted home. `declarationBucketName()` is **already shared**
across two call sites — `DeclaredDependencyMetadataCollector.kt:540` and `Dependencies.kt:348` —
so it is squatting in the wrong layer.

## Work (two tiers, both verbatim relocation)

### Tier 1 — consume existing variant accessors (S, near-zero risk)
1. Replace the inline AGP reads (`:82,93-98`) with the existing `variant.isWorkspaceAndroidLeaf`
   / `variant.workspaceBuildTypeName` / `variant.workspaceProductFlavorNames`. Remove the
   `import com.android.build.gradle.api.BaseVariant` (`:19`). **Verify** the accessor outputs are
   byte-identical to the inline reads before swapping (they are claimed identical — confirm).
2. Move `compileOnlyBucketName` (`:365-370`) onto `Variant<*>` as a variant-layer property (pure
   `variantType`→name; no name parsing).

### Tier 2 — relocate the name classification into the variant layer (M, verbatim)
3. Relocate into the `gradle.variant` package (alongside `ConfigurationParsingVariant`, **not**
   bloating the base `Variant<T>` interface body with new logic): the predicates + constant lists
   `isDeclarationBucket`, `declarationBucketExcludedNameFragments`, `declarationConfigurationSuffixes`,
   `declarationBucketName()`, `removeSuffixIgnoringCase`, `isCompileOnlyDeclaration`. **Move the
   bytes unchanged** — same lists, same order, same `replaceFirstChar`/`DEFAULT_VARIANT` fallback.
4. Expose typed variant-layer accessors the collector consumes. Use names that describe the
   declared-dependency metadata role, not generic Gradle "declaration" wording:
   - `variant.declaredDependencyConfigurations: Set<Configuration>` (the `variantConfigurations`
     subset where the relocated collector-specific `isDeclarationBucket` predicate is true),
   - `variant.compileOnlyDeclaredDependencyConfigurations: Set<Configuration>` (subset where the
     relocated `isCompileOnlyDeclaration` predicate is true).
   These are variant-general (JVM variants have declaration configs too), so a `Variant<*>`-level
   accessor is correct — but keep the AGP-specific reads behind the existing `workspace*` accessors,
   not new interface defaults (avoid repeating the Item 26 half-measure of pushing AGP types into
   the base interface).
5. Rewire the collector's extraction methods (C) to iterate
   `variant.declaredDependencyConfigurations` /
   `variant.compileOnlyDeclaredDependencyConfigurations` and call the relocated
   `Configuration.declarationBucketName()` from its new home. Keep the dependency-construction
   logic in the collector.
6. Rewire declared exclude extraction explicitly:
   `excludeRulesByShortId = extractDeclaredExcludeRulesByShortId(variant.declaredDependencyConfigurations)`.
   After this move, `Configuration.extractDeclaredExcludeRulesByShortId()` must not own or call
   declaration-bucket classification; it should only extract rules from a configuration that the
   variant layer has already classified as declared-dependency metadata.
7. Point `Dependencies.kt:348`'s use of `declarationBucketName()` at the same relocated function so
   the two call sites cannot drift.
8. `configurationNamesOf` try/catch (`:351-359`): leave it as-is in this item. Moving it is allowed
   only if required to remove a concrete dependency-layer variant-role violation discovered during
   implementation, and must remain generated-output empty-diff.

## The one hard constraint (output-affecting)

`declarationBucketName()` derives `bucketName`, which drives bucket **ownership/placement**. The
relocation must be **byte-identical**: same suffix list, same iteration order, same fallback.
Both call sites (`Collector.kt:540`, `Dependencies.kt:348`) must resolve to the **one** relocated
function — no copy, no divergence. If any derived bucket name changes, that is a bug in the move,
not an accepted diff — STOP. Bucket set-math (`DependencyBucketPlacementEngine`,
`BucketOwnershipPlanner`) is untouched (Item 22 proved it problem-essential).

## Explicitly OUT of scope — the landmine (deferred to a separate item)

**Do NOT merge the three divergent declaration-bucket classifiers in this item.** There are three
with the **same suffix set but different exclusion rules**:
- `isDeclarationBucket` (Collector — rejects `_`-prefix + 14 fragments),
- `isExternalDependencyDeclaration` (`Dependencies.kt:604` — rejects only `dependenciesmetadata`/`classpath`),
- `isCompileOnlyDeclaration` (Collector).

They are **not equivalent today**, so unifying them changes which configurations feed bucket
placement → changes derived buckets → breaks empty-diff and touches placement semantics. This item
relocates each **verbatim, separately** (the collector's predicates move; `Dependencies.kt`'s stays
as-is, only re-pointing the shared `declarationBucketName()`). Consolidating the classifiers is a
**proposed follow-up item** requiring per-call-site behaviour proof (a diff showing the unified
predicate reproduces each call site's configuration set on PAX), gated separately. Note it in the
roadmap as proposed; do not attempt it here.

## Safety mechanism

- **Sample golden EMPTY-diff** + **PAX generated diff stable** vs the frozen Item 10 baseline. Any
  output diff = stop-and-investigate.
- **No parity flag** — compile-time relocation; compiler + tests + golden are the net.
- **Size guard (Item 10):** no change expected.
- **Tests that pin behaviour:** `DeclaredDependencyMetadataCollectorTest`,
  `DependencyBucketPlacementEngineTest`, `AggregatedDependencyResolverTest`, plus the functional
  `migrateToBazel` diff suite. Add a focused variant-layer test for the new
  `declaredDependencyConfigurations`/`compileOnlyDeclaredDependencyConfigurations` accessors
  (assert the same config set the old predicates selected, including the declared-exclude path).

## Acceptance criteria

- `DeclaredDependencyMetadataCollector.kt` no longer imports `BaseVariant` and contains no
  configuration-name string classification (`isDeclarationBucket`, the fragment/suffix lists,
  `declarationBucketName`, `isCompileOnlyDeclaration` all moved to the variant layer).
- The collector consumes `variant.declaredDependencyConfigurations` /
  `variant.compileOnlyDeclaredDependencyConfigurations` and the existing `workspace*` AGP accessors.
- Declared exclude-rule extraction consumes already-classified declared-dependency configurations;
  no dependency-layer function filters with `isDeclarationBucket`.
- `compileOnlyBucketName` is exposed from `gradle.variant` as an internal extension/property in a
  focused variant-layer file, not added to the base `Variant<T>` interface body.
- `declarationBucketName()` has one home in the variant layer; `Collector` and `Dependencies.kt`
  both call it; the AGP type knowledge is not pushed into the base `Variant<T>` interface body.
- Sample golden empty-diff; PAX generated diff stable; PAX migrate + both APKs green; size guard
  no-increase; existing + new variant-accessor tests green.
- The three-classifier consolidation is recorded as a proposed follow-up item, not attempted here.

## Out of scope / Non-goal

- Merging/unifying the three declaration-bucket classifiers (separate gated item).
- Any change to bucket ownership/placement semantics, `VariantType`, or the set-math.
- Reshaping the declared-metadata task graph (Items 29/30/32).
