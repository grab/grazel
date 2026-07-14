# Packaging & Structure Review — `arun/dependencies-refactor`

Scope: package placement, file organization, and cross-file ordering/naming for the changed
code on `arun/dependencies-refactor` (repo root `/Users/arun.sampathkumar/work/grazel`).

**Status (2026-07-14): all six findings resolved on this branch** as the structure tier of the
pre-merge code-quality pass (see `2026-07-14-branch-code-quality-pass-plan.md`), each verified
behaviour-preserving through the golden byte-identity gate. Per-finding resolution notes are in the
Summary section below. The original report text is retained for context.

Findings are ordered by confidence/value. Each carries a risk note covering imports touched,
Groovy-consumed (public) API surface, and git-blame cost.

---

## 1. `toMavenRepoName` family lives in `migrate.dependencies` but is owned by `gradle.dependencies`

- **File:** `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/dependencies/Repository.kt`
- **Issue:** This file holds the maven-repo *naming* vocabulary — `BASE_MAVEN_REPO`,
  `MAVEN_COMPILE_FILTER_TAG_PREFIX`, `String.toMavenRepoName()`,
  `String.toMaterializedMavenRepoName()`. These are pure `variantName -> bucketName` string
  helpers with no migration/rendering dependency (the only import is
  `gradle.variant.DEFAULT_VARIANT`). Yet the heaviest consumers are in the *lower* layer:
  seven files in `gradle/dependencies/` import them —
  `WorkspacePlanBuilder.kt` (a central file of this branch, `import
  com.grab.grazel.migrate.dependencies.toMavenRepoName`), plus `OverrideTargets.kt`,
  `WorkspaceRenderPlanBuilder.kt`, `TargetReferenceFactsCollector.kt`, `Dependencies.kt`,
  `MavenInstallStore.kt`, and `model/ResolveDependenciesResult.kt`. So `gradle.dependencies`
  (resolution/planning) reaches *up* into `migrate.dependencies` (rendering) for a naming
  primitive. That is a layering inversion the diagram in `WorkspacePlanBuilder`'s own KDoc
  ("resolution -> ... -> workspace/pinning -> render") argues against.
- **Suggested move:** Relocate the four naming declarations to `gradle.dependencies` (e.g. a
  new `gradle/dependencies/MavenRepoNaming.kt`, or fold into an existing bucket-naming file).
  Leave any genuinely render-side helpers behind in `migrate/dependencies/Repository.kt`.
  This flips the dependency direction so `migrate.dependencies` depends on `gradle.dependencies`
  (correct) instead of the reverse.
- **Risk:** **Public API surface** — `toMavenRepoName`/`toMaterializedMavenRepoName`/
  `BASE_MAVEN_REPO`/`MAVEN_COMPILE_FILTER_TAG_PREFIX` are `public` (no `internal`), so they are
  part of the Groovy-visible surface; a package change is a source-incompatible move for any
  external caller and must be confirmed against the published API contract before proceeding.
  **Imports touched:** ~10 files change their import line (7 in `gradle.dependencies`, 3 in
  `migrate.dependencies`). **Blame:** these lines predate the branch (last touched by
  `960fbb2`/`3f2b0ef`), so a move rewrites blame on stable, previously-reviewed code — non-trivial
  cost. Recommend doing this as an isolated commit if approved.

---

## 2. `MavenRepositoryPath.kt` filename does not match its primary types

- **File:** `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/maven/MavenRepositoryPath.kt`
- **Issue:** The file is named `MavenRepositoryPath` but defines no type by that name. Its
  primary declarations are `MavenPath`, `MavenCoordinates` (the larger of the two, with the
  canonical-name logic), and the top-level `isConcreteMavenArtifactPath`. A reader grepping for
  `MavenCoordinates` or `MavenPath` will not find the file by name, and the sibling
  `MavenRepositoryUrl.kt` (which *does* match its single function's intent) sets an inconsistent
  precedent.
- **Suggested change:** Rename the file to `MavenPath.kt` (matches the parse entry point most
  callers touch) or split `MavenCoordinates` into its own `MavenCoordinates.kt`. Given
  `MavenCoordinates` is the richer type and reused independently (module-cache resolution, lockfile
  fallback, artifact indexing), a `MavenCoordinates.kt` + `MavenPath.kt` split reads best.
- **Risk:** **Imports:** none — same package, so no import statements change; a pure file rename.
  **Public surface:** both types are `internal`, no Groovy exposure. **Blame:** the file is new on
  this branch, so a rename/split costs almost nothing in blame terms. Lowest-risk item here; the
  only reason it is deferred is the byte-identity gate treats file moves conservatively.

---

## 3. `LocalMavenResolvedFacts.kt` is a fact-*collection* file misfiled in `proxy`, and does too much

- **File:** `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/proxy/LocalMavenResolvedFacts.kt`
- **Issue (placement):** The `proxy` package should own the HTTP-serving concern
  (`LocalMavenProxyServer`, `LocalMavenProxyService`). This file instead contains the machinery
  that *reads Gradle's resolution* to build those facts: `LocalMavenResolvedFactsBuilder`,
  `ResolvedArtifactIndexBuilder`, `ResolvedComponentIndexBuilder`, `GradleModuleCacheFileResolver`,
  `GradleModuleCacheFileIndexBuilder`, `GradlePomFileResolver`, plus the `PomFileResolver`/
  `PomArtifactQuery`/`PomCacheLookup` interfaces. That is Gradle-graph extraction, conceptually a
  `gradle.dependencies` (or `maven`) concern that the proxy merely *consumes*. The data class
  `LocalMavenResolvedFacts` is the legitimate hand-off type; the builders are not proxy code.
- **Issue (file organization):** One 417-line file holds a DTO, five builder/resolver classes, two
  functional interfaces, a sealed `PomFileResolution`, and several free functions
  (`mergeArtifactIndexes`, `metadataOnlyComponentGavs`, `putMavenFile`, `singleMavenFileOrNull`).
  These are separable units.
- **Suggested move/split:**
  - Keep `LocalMavenResolvedFacts` (the pure hand-off DTO) and the `PomFileResolver` interface
    near the proxy, or in `maven`.
  - Move `LocalMavenResolvedFactsBuilder`, `ResolvedArtifactIndexBuilder`,
    `ResolvedComponentIndexBuilder`, `GradleModuleCacheFileResolver`/`...IndexBuilder`, and
    `GradlePomFileResolver` into `gradle/dependencies/` (they already import
    `ResolvedComponentResult`, `ResolvedArtifactResult` and walk configurations, exactly what that
    package does). Consider one file per resolver (`GradleModuleCacheFileResolver.kt`,
    `GradlePomFileResolver.kt`, `ResolvedMavenArtifactIndex.kt`).
- **Risk:** **Imports:** the split touches import lines in `LocalMavenProxyService`
  (`facts.pomFileResolver`, `LocalMavenResolvedFacts`) and any test that constructs the builders;
  it also introduces a `proxy -> gradle.dependencies` dependency (acceptable, matches flow).
  **Public surface:** all types are `internal` — no Groovy exposure. **Blame:** file is new on the
  branch, so blame cost is low, but this is the highest-effort split (many types) and most likely
  to perturb hashing if the proxy's behavior is exercised in a byte-identity golden test — verify
  the golden gates re-pass after the move. Do this only if the layering benefit is judged worth it.

---

## 4. Package-level KDoc for the whole rje subsystem sits on `RulesJvmExternalLockfile.kt`, which only holds the model

- **File:** `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/dependencies/RulesJvmExternalLockfile.kt`
- **Issue:** Lines 28-72 are a subsystem-wide doc block ("rules_jvm_external lockfile & maven-install
  artifact rendering ... Domain vocabulary used throughout this package") that documents
  `MavenInstallData`, `MavenInstallLockfileReconstructor`, `RulesJvmExternalLockfileHasher`,
  `StarlarkRepr`, etc. — none of which live in this file. The file itself contains only the
  `RulesJvmExternalLockfile` data class and `RulesJvmExternalLockfileParser`. A reader opening the
  renderer or hasher will not find the orienting comment; a reader opening the model gets a wall of
  text about neighbours. (By contrast the renderer/hasher/`StarlarkRepr` are correctly split into
  their own files and `StarlarkRepr`'s rje-coupling is deliberately documented — those placements
  are fine and are **not** flagged.)
- **Suggested change:** Move the package-overview block to a `package-info`-style location — either
  a dedicated `package.kt`/doc file in `migrate/dependencies/`, or onto the most central type
  (`MavenInstallLockfileReconstructor`, which the flow text describes as the fallback entry point).
  Leave `RulesJvmExternalLockfile.kt` with a short doc scoped to the model + parser only.
- **Risk:** **Imports:** none — comment-only relocation. **Public surface:** none (all `internal`).
  **Blame:** touches only this new file's header; trivial. The reason it is not a mechanical fix is
  judgment is required on *where* the overview should live (which type is "the" anchor), which a
  human should decide.

---

## 5. Class `TaskManager` lives in `TasksManager.kt` (file/type name mismatch)

- **File:** `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/TasksManager.kt`
- **Issue:** The file is `TasksManager.kt` (plural) but declares `internal class TaskManager`
  (singular). Kotlin permits this, but it defeats find-file-by-type and is inconsistent with the
  one-type-per-matching-filename convention used elsewhere in `tasks/internal/`
  (`PinMavenArtifactsTask.kt` -> `PinMavenArtifactsTask`, etc.).
- **Suggested change:** Rename the file to `TaskManager.kt` to match the class. (Alternatively
  rename the class to `TasksManager`, but the class name is referenced from DI/wiring, so renaming
  the *file* is the cheaper of the two.)
- **Risk:** **Imports:** none — same package, file rename only, no import lines change. **Public
  surface:** `TaskManager` is `internal`; not Groovy-visible. **Blame:** the file pre-exists the
  branch (2022 header), so a rename rewrites blame on old code — modest cost. This is close to
  mechanical but is deferred because it is a rename of a pre-existing, widely-wired file on a
  hash-gated branch; a human should batch it with other `tasks/internal` touches if any.

---

## 6. `Variant.kt` mixes the `Variant<T>` contract with a concrete `JvmVariant` implementation

- **File:** `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/variant/Variant.kt`
- **Issue:** The file opens with the subsystem KDoc and the `Variant<T>` interface plus the shared
  enums/extensions (`VariantType`, `DefaultVariants`, `Classpath`, the `Variant<*>.*` helpers) —
  all appropriate "model" content. It then also carries the full concrete `JvmVariant` /
  `JvmVariantData` implementation (lines 278-383), including AGP-config-name parsing
  (`compileClasspath`, `kaptTest`, `kspKotlinProcessorClasspath`, ...). The Android concrete
  variants live in their own files (`ConfigurationParsingVariant.kt`, `VariantBuilder.kt`); the JVM
  concrete variant is the odd one out, bundled into the interface file. This makes `Variant.kt`
  read as both "the contract" and "one implementation of it".
- **Suggested split:** Extract `JvmVariant` + `JvmVariantData` (+ the `JvmVariant(...)` factory) into
  `gradle/variant/JvmVariant.kt`, leaving `Variant.kt` as the interface + shared vocabulary. This
  matches how the Android variants are already organised and shortens the most-read file in the
  package.
- **Risk:** **Imports:** none — same package; callers of `JvmVariant`/`JvmVariantData` need no
  import change. **Public surface:** `Variant`, `VariantType`, `JvmVariant`, `JvmVariantData`,
  `DefaultVariants` and the many `val Variant<*>.*` extensions are all `public` and Groovy-visible,
  but a *same-package file split* does not change their fully-qualified names, so it is API-safe.
  **Blame:** `Variant.kt` is a long-lived file (touched by many prior commits); moving ~100 lines
  out rewrites blame for that block — moderate cost. Recommend only if the package is being touched
  anyway.

---

## Items considered and deliberately NOT flagged

- **`StarlarkRepr.kt` in `migrate.dependencies` rather than `bazel/starlark`** — it looks generic,
  but its own KDoc pins it to reproducing `rules_jvm_external`'s `hash(repr(...))` byte-for-byte; it
  is intentionally lockfile-coupled, not a general Starlark emitter. Correct where it is.
- **`RulesJvmExternalLockfileHasher.kt` / `...Renderer.kt` split** — already well-separated,
  single-responsibility files. No change.
- **`MavenInstallLockfileFallbackIndex.kt` in `proxy`** — it is consumed by the proxy to widen the
  re-pin allow-list and reasonably co-locates with the proxy; its cross-import into
  `migrate.dependencies` (`MavenInstallLockfileArtifactKey`, `mavenInstallJsonName`) is the expected
  direction. Borderline, but not worth the move on a hash-gated branch.
- **`BucketOwnershipPlanner.kt` size (~1100 lines)** — large, but it is a single cohesive algorithm
  with tightly-coupled private helpers; splitting would scatter the ownership logic across files and
  hurt readability more than it helps. Its set-math is already extracted to `BucketSetMath.kt`.
  Leave as-is.

---

## Summary

6 recommendations, in descending value/confidence:

1. Move `toMavenRepoName` naming family from `migrate.dependencies` to `gradle.dependencies`
   (fixes a layering inversion; **public API**, highest blame cost).
   **Applied:** moved to `gradle/dependencies/MavenRepoNaming.kt` and made `internal` (they are
   pipeline implementation details, not intended plugin API), removing the public-surface concern.
2. Rename `MavenRepositoryPath.kt` to match its `MavenPath`/`MavenCoordinates` types (lowest risk).
   **Applied:** split into `maven/MavenCoordinates.kt` + `maven/MavenPath.kt`.
3. Move Gradle fact-collection builders out of `proxy/LocalMavenResolvedFacts.kt` into
   `gradle.dependencies`, splitting the file (highest effort).
   **Applied:** builders/resolvers moved to `gradle/dependencies/LocalMavenResolvedFactsBuilder.kt`,
   `GradleModuleCacheFileResolver.kt`, `GradlePomFileResolver.kt`; the DTO `LocalMavenResolvedFacts`
   plus `PomFileResolver`/`PomFileResolution` stay in `proxy` as the hand-off surface.
4. Relocate the rje package-overview KDoc off `RulesJvmExternalLockfile.kt` to the subsystem anchor.
   **Moot / superseded:** the misplaced overview block this referenced was reverted before the
   documentation pass; the pass instead placed the rje orientation directly on the subsystem anchor
   `MavenInstallLockfileReconstructor` (its ordered-steps class KDoc), which is exactly the desired
   end-state. `RulesJvmExternalLockfile.kt` now carries only model+parser-scoped docs. No move needed.
5. Rename `TasksManager.kt` -> `TaskManager.kt` to match the class. **Applied.**
6. Split `JvmVariant`/`JvmVariantData` out of `Variant.kt`. **Applied:** moved to
   `gradle/variant/JvmVariant.kt`; `Variant.kt` retains the interface + shared vocabulary.

All six resolved; verified byte-identical through the golden gate.
