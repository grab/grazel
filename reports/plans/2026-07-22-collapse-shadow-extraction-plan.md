# Collapse Shadow Extraction Pipeline — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make each `TargetBuilder` the single owner of target-data selection (`selectData`),
so the facts pass derives from the same objects the render pass builds — deleting the four
shadow pipelines in `TargetReferenceFactsExtractor`.

**Architecture:** Introduce a `TargetData` wrapper seam (data → `TargetReferenceFacts`), move
each builder's selection logic into member functions its `build()` consumes, then add
`selectData` to the `TargetBuilder` interface and rewrite the extractor as a thin
builder-set dispatcher. Spec: `reports/specs/2026-07-22-collapse-shadow-extraction-design.md`.

**Tech Stack:** Kotlin, Gradle plugin, Dagger multibindings, JUnit4.

## Global Constraints

- **Byte-identity:** generated Bazel output must not move. Gates per task:
  `./gradlew :grazel-gradle-plugin:test --console=plain` then
  `./gradlew verifyGrazelGoldenBaseline --console=plain` (must print
  `Grazel golden baseline verified: migrateToBazel, task graph, bucket labels, and generated-file diff are clean.`).
  `verify-sample-bucket-labels.sh` has ONE documented appcompat/constraintlayout waiver — that
  specific failure only is acceptable.
- **One Gradle build at a time.** Never run bazelisk concurrently with Gradle.
- **Stage explicit paths** — never `git add -A`. Never stage `codedb.snapshot`.
- **Do not touch** `resolution/`, `bucket/`, `TopologicalSorter`,
  `CollectTargetMavenRepoReferencesTask`'s fixpoint, or `TargetVariantReachability.kt` helpers
  (they are consumed, not modified).
- **Resolved divergence decision (from spec §Divergence, unconstructible branch):**
  `VariantCompressionResult.init` already `require`s
  `variantToSuffix.values ⊆ targetsBySuffix.keys` (VariantCompressionResult.kt:40-46), so the
  suspected divergent state (non-empty `reachableSuffixes`, zero matching `targetsBySuffix`
  keys) cannot be constructed. The unified selection keeps the builder's shape and **deletes
  the extractor's dead `takeUnless(isEmpty)` fallback**; the invariant stays enforced at
  construction. Task 1 adds the executable proof.

---

## File map

| File | Change |
|---|---|
| `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/TargetData.kt` | **Create** — `TargetData` interface + 7 wrapper types (single owner of data→facts mapping) |
| `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/BazelTarget.kt` | Add `selectData` to `TargetBuilder` (Task 5) |
| `.../migrate/target/AndroidLibraryTargetBuilder.kt` | Selection members; `build()` consumes them |
| `.../migrate/target/AndroidBinaryTargetBuilder.kt` | Selection member; crashlytics stays render-only |
| `.../migrate/target/AndroidInstrumentationBinaryTargetBuilder.kt` | Selection member |
| `.../migrate/target/AndroidTestTargetBuilder.kt` | Selection member |
| `.../migrate/target/KotlinLibraryTargetBuilder.kt` | Selection member |
| `.../migrate/target/TargetReferenceFactsExtractor.kt` | Shrinks to builder-set dispatcher (Task 5) |
| `.../src/test/kotlin/com/grab/grazel/migrate/target/TargetDataTest.kt` | **Create** — wrapper mapping + dispatch tests |
| `.../src/test/kotlin/com/grab/grazel/gradle/variant/VariantCompressionResultInvariantTest.kt` | **Create** — unconstructibility proof |
| `.../src/test/kotlin/com/grab/grazel/migrate/target/TargetReferenceFactsDataMappingTest.kt` | Migrate assertions to wrappers (Task 5) |

Known parity deltas (each lives in exactly one place after this plan — flag ANY other
behavioural difference discovered mid-task; stop and report rather than silently absorbing):

1. **Crashlytics** (`AndroidBinaryTargetBuilder.crashlyticsDeps`) is render-only decoration:
   today's `androidBinaryFacts` does NOT include crashlytics deps, and the crashlytics target
   is an intra-package label needing no cross-package reference facts. It stays inside
   `build()`, NOT in `selectData` — keeping facts byte-identical.
2. **Instrumentation dispatch**: the extractor guards `hasTestInstrumentationRunner` inside
   `androidInstrumentationFacts`; the builder guards it in `canHandle`. Net-equivalent once
   dispatch goes through `canHandle` (Task 5).
3. **Unit-test warn log** (`No compression result for ...`): builder logs it, extractor
   doesn't. The shared member keeps the builder's log; the facts pass will now also log it.
   Log output is not gated — acceptable, note in commit message.

---

### Task 1: `TargetData` seam + compression-invariant proof

**Files:**
- Create: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/TargetData.kt`
- Create: `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/variant/VariantCompressionResultInvariantTest.kt`
- Create: `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/migrate/target/TargetDataTest.kt`

**Interfaces:**
- Consumes: existing data classes (`AndroidLibraryData`, `AndroidUnitTestData`,
  `AndroidBinaryData`, `AndroidInstrumentationBinaryData`, `AndroidTestData`,
  `KotlinProjectData`, `UnitTestData`), `TargetReferenceFactsCollector.from(...)`,
  `MatchedVariant`.
- Produces: `internal interface TargetData { fun referenceFacts(): TargetReferenceFacts }`
  and wrappers `AndroidLibraryTargetData(data)`, `AndroidUnitTestTargetData(data)`,
  `AndroidBinaryVariantTargetData(matchedVariant, libraryData, binaryData)`,
  `AndroidInstrumentationTargetData(data)`, `AndroidTestTargetData(data)`,
  `KotlinLibraryTargetData(data)`, `KotlinUnitTestTargetData(data)` — Tasks 2–5 rely on these
  exact names.

- [ ] **Step 1: Write the failing invariant test**

```kotlin
// grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/variant/VariantCompressionResultInvariantTest.kt
package com.grab.grazel.gradle.variant

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Executable proof that the shadow-extraction divergent state is unconstructible:
 * `reachableCompressedTargetSuffixes` draws only from `variantToSuffix.values`, and this
 * invariant guarantees every such suffix is a `targetsBySuffix` key. Therefore a non-empty
 * reachable-suffix set can never filter `targetsBySuffix` down to empty — the extractor's
 * old `takeUnless(isEmpty)` fallback was dead code, deleted by the selectData refactor.
 * If this test ever fails, that deletion is no longer safe.
 */
class VariantCompressionResultInvariantTest {

    @Test
    fun `construction rejects variant mappings pointing at absent target suffixes`() {
        assertThrows(IllegalArgumentException::class.java) {
            VariantCompressionResult(
                targetsBySuffix = emptyMap(),
                variantToSuffix = mapOf("paidDebug" to "-debug"),
                expandedBuildTypes = emptySet()
            )
        }
    }
}
```

- [ ] **Step 2: Run it — it must PASS already** (the `require` exists at
  `VariantCompressionResult.kt:40-46`; this test is documentation, not new behaviour)

Run: `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.variant.VariantCompressionResultInvariantTest" --console=plain`
Expected: PASS. If it FAILS, STOP — the spec's divergence resolution is wrong; escalate.

- [ ] **Step 3: Write the failing wrapper-mapping test**

Mirror the construction style of `TargetReferenceFactsDataMappingTest.kt` (same package, same
`buildProject` helper). Cover: library wrapper carries deps/tags/plugins/lintChecks; binary
wrapper merges library+binary deps; unit-test wrapper carries associates.

```kotlin
// grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/migrate/target/TargetDataTest.kt
package com.grab.grazel.migrate.target

import com.grab.grazel.bazel.TestSize
import com.grab.grazel.bazel.starlark.BazelDependency.ProjectDependency
import com.grab.grazel.buildProject
import org.junit.Assert.assertEquals
import org.junit.Test

class TargetDataTest {

    @Test
    fun `android library target data maps deps into reference facts`() {
        val rootProject = buildProject("root")
        val depProject = buildProject("lib-dep", rootProject)
        // Construct AndroidLibraryData with deps = listOf(ProjectDependency(depProject,
        // suffix = "-debug")), everything else empty/defaults — copy the minimal
        // constructor shape from an existing AndroidLibraryData usage in tests.
        val facts = AndroidLibraryTargetData(data = /* AndroidLibraryData as above */)
            .referenceFacts()
        assertEquals(mapOf(":lib-dep" to setOf("lib-dep-debug")), facts.projectTargets)
    }

    @Test
    fun `android binary target data merges library and binary deps`() {
        val rootProject = buildProject("root")
        val libDep = buildProject("from-library", rootProject)
        val binDep = buildProject("from-binary", rootProject)
        // libraryData.deps = [ProjectDependency(libDep, "-debug")]
        // binaryData.deps  = [ProjectDependency(binDep, "-debug")]
        val facts = AndroidBinaryVariantTargetData(
            matchedVariant = /* any MatchedVariant fixture from existing tests */,
            libraryData = /* as above */,
            binaryData = /* as above */
        ).referenceFacts()
        assertEquals(
            mapOf(":from-library" to setOf("from-library-debug"), ":from-binary" to setOf("from-binary-debug")),
            facts.projectTargets
        )
    }
}
```

(The implementer fills the data-class constructor calls from existing test fixtures — the
assertions above are the contract. If `MatchedVariant` proves heavy to fixture, make
`matchedVariant` nullable-free by copying the builder pattern used in
`TargetVariantReachabilityTest`.)

- [ ] **Step 4: Run to verify it fails to compile** (types don't exist yet)

Run: `./gradlew :grazel-gradle-plugin:compileTestKotlin --console=plain`
Expected: FAIL — unresolved reference `AndroidLibraryTargetData`.

- [ ] **Step 5: Implement `TargetData.kt`**

The wrapper bodies are the existing adapter functions from the bottom of
`TargetReferenceFactsExtractor.kt` (lines 255-312), restated verbatim. The old top-level
functions are NOT deleted yet (the extractor still uses them until Task 5) — this transient
duplication is deliberate and dies in Task 5.

```kotlin
// grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/TargetData.kt
package com.grab.grazel.migrate.target

import com.grab.grazel.gradle.dependencies.TargetReferenceFactsCollector
import com.grab.grazel.gradle.dependencies.model.TargetReferenceFacts
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.migrate.android.AndroidBinaryData
import com.grab.grazel.migrate.android.AndroidInstrumentationBinaryData
import com.grab.grazel.migrate.android.AndroidLibraryData
import com.grab.grazel.migrate.android.AndroidTestData
import com.grab.grazel.migrate.android.AndroidUnitTestData
import com.grab.grazel.migrate.kotlin.KotlinProjectData
import com.grab.grazel.migrate.kotlin.UnitTestData

/**
 * A unit of selected target data: what a [com.grab.grazel.migrate.TargetBuilder] decided to
 * generate for a project, before rendering. The single seam between selection (builder-owned)
 * and the reference-facts pass — facts are derived from the same objects the builders render,
 * making facts/render divergence structurally impossible.
 */
internal interface TargetData {
    fun referenceFacts(): TargetReferenceFacts
}

internal data class AndroidLibraryTargetData(
    val data: AndroidLibraryData
) : TargetData {
    override fun referenceFacts(): TargetReferenceFacts = TargetReferenceFactsCollector.from(
        deps = data.deps,
        tags = data.tags,
        plugins = data.plugins,
        lintChecks = data.lintConfigData.lintChecks.orEmpty()
    )
}

internal data class AndroidUnitTestTargetData(
    val data: AndroidUnitTestData
) : TargetData {
    override fun referenceFacts(): TargetReferenceFacts = TargetReferenceFactsCollector.from(
        deps = data.deps,
        tags = data.tags,
        associates = data.associates
    )
}

/**
 * Binary selection carries the matched variant alongside both extracted data objects:
 * `build()` needs the variant for target naming and crashlytics decoration, while
 * [referenceFacts] merges the two dep lists exactly as the old `androidBinaryReferenceFacts`
 * did. Crashlytics deps are deliberately absent here — they are render-only decoration
 * (intra-package label) and were never part of the facts.
 */
internal data class AndroidBinaryVariantTargetData(
    val matchedVariant: MatchedVariant,
    val libraryData: AndroidLibraryData,
    val binaryData: AndroidBinaryData
) : TargetData {
    override fun referenceFacts(): TargetReferenceFacts = TargetReferenceFactsCollector.from(
        deps = libraryData.deps + binaryData.deps,
        plugins = libraryData.plugins,
        lintChecks = libraryData.lintConfigData.lintChecks.orEmpty()
    )
}

internal data class AndroidInstrumentationTargetData(
    val data: AndroidInstrumentationBinaryData
) : TargetData {
    override fun referenceFacts(): TargetReferenceFacts = TargetReferenceFactsCollector.from(
        deps = data.deps,
        tags = data.tags,
        associates = data.associates,
        instruments = data.instruments
    )
}

internal data class AndroidTestTargetData(
    val data: AndroidTestData
) : TargetData {
    override fun referenceFacts(): TargetReferenceFacts = TargetReferenceFactsCollector.from(
        deps = data.deps,
        tags = data.tags,
        lintChecks = data.lintConfigData.lintChecks.orEmpty(),
        associates = data.associates,
        instruments = data.instruments
    )
}

internal data class KotlinLibraryTargetData(
    val data: KotlinProjectData
) : TargetData {
    override fun referenceFacts(): TargetReferenceFacts = TargetReferenceFactsCollector.from(
        deps = data.deps,
        tags = data.tags,
        plugins = data.plugins,
        lintChecks = data.lintConfigData.lintChecks.orEmpty()
    )
}

internal data class KotlinUnitTestTargetData(
    val data: UnitTestData
) : TargetData {
    override fun referenceFacts(): TargetReferenceFacts = TargetReferenceFactsCollector.from(
        deps = data.deps,
        tags = data.tags,
        associates = data.associates
    )
}
```

- [ ] **Step 6: Run the new tests**

Run: `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.migrate.target.TargetDataTest" --tests "com.grab.grazel.gradle.variant.VariantCompressionResultInvariantTest" --console=plain`
Expected: PASS (all).

- [ ] **Step 7: Full unit suite**

Run: `./gradlew :grazel-gradle-plugin:test --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/TargetData.kt \
        grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/migrate/target/TargetDataTest.kt \
        grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/variant/VariantCompressionResultInvariantTest.kt
git commit -m "refactor(target): introduce TargetData seam for builder-owned selection

Wrappers restate the facts adapters verbatim (old adapters deleted when the
extractor swaps in a later commit). Invariant test proves the suspected
facts/render divergent state is unconstructible."
```

---

### Task 2: `AndroidLibraryTargetBuilder` — selection members

**Files:**
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/AndroidLibraryTargetBuilder.kt`

**Interfaces:**
- Consumes: `AndroidLibraryTargetData`, `AndroidUnitTestTargetData` (Task 1);
  `reachableMatchedVariants` / `reachableCompressedTargetSuffixes`
  (`TargetVariantReachability.kt`, unchanged).
- Produces: `internal fun selectLibraryData(project: Project): List<AndroidLibraryData>` and
  `internal fun selectUnitTestData(project: Project): List<AndroidUnitTestData>` — Task 5's
  `selectData` override composes exactly these two.

- [ ] **Step 1: Refactor — selection into members, `build()` consumes them**

Replace the body of the class (keep constructor, module, `canHandle`, `toAndroidLibTarget`
as-is). The reconciled `selectLibraryData` keeps the builder's branch shape; the
non-empty-suffixes path needs no emptiness fallback (Task 1's invariant test is the proof).

```kotlin
    override fun build(project: Project): List<BazelTarget> =
        selectLibraryData(project).map { it.toAndroidLibTarget() } +
            selectUnitTestData(project).map { unitTestData ->
                unitTestData.toUnitTestTarget()
            }

    /**
     * The single selection of android_library data for this project: reachable (or referenced)
     * variants, folded through variant compression. Consumed by [build] for rendering and by
     * the reference-facts pass via [selectData] — one implementation, no facts/render drift.
     *
     * When compression yields a non-empty reachable-suffix set, the filtered map is used
     * as-is: `VariantCompressionResult`'s construction invariant guarantees every reachable
     * suffix is a `targetsBySuffix` key, so the filter cannot be empty (see
     * `VariantCompressionResultInvariantTest`).
     */
    internal fun selectLibraryData(project: Project): List<AndroidLibraryData> {
        val androidBuildVariants = reachableMatchedVariants(
            project = project,
            variantType = VariantType.AndroidBuild,
            variantMatcher = variantMatcher,
            variantCompressionService = variantCompressionService,
            dependencyResolutionService = dependencyResolutionService,
            workspaceRenderPlanService = workspaceRenderPlanService
        )
        val compressionResult = variantCompressionService.get().get(project.path)
            ?: run {
                project.logger.error("Compressed result does not exist for this project")
                return extractLibraryData(project, androidBuildVariants)
            }
        val reachableSuffixes = reachableCompressedTargetSuffixes(
            variantToSuffix = compressionResult.variantToSuffix,
            reachableVariantNames = androidBuildVariants.mapTo(mutableSetOf()) { variant ->
                variant.variantName
            }
        )
        if (reachableSuffixes.isEmpty()) {
            return extractLibraryData(project, androidBuildVariants)
        }
        return compressionResult.targetsBySuffix
            .filterKeys { suffix -> suffix in reachableSuffixes }
            .values
            .toList()
    }

    private fun extractLibraryData(
        project: Project,
        androidBuildVariants: Set<MatchedVariant>
    ): List<AndroidLibraryData> = androidBuildVariants.map { matchedVariant ->
        androidLibraryDataExtractor.extract(project, matchedVariant)
    }

    /**
     * When variant compression is active, multiple reachable test variants can resolve to the
     * same compressed suffix; extracting one [AndroidUnitTestData] per variant would produce
     * duplicate targets. Variants are grouped by resolved suffix and the alphabetically-first
     * variant per group is extracted as the stable representative.
     */
    internal fun selectUnitTestData(project: Project): List<AndroidUnitTestData> {
        val compressionResult = variantCompressionService.get().get(project.path)
        val testVariants = reachableMatchedVariants(
            project = project,
            variantType = VariantType.Test,
            variantMatcher = variantMatcher,
            variantCompressionService = variantCompressionService,
            dependencyResolutionService = dependencyResolutionService,
            workspaceRenderPlanService = workspaceRenderPlanService
        )
        return if (compressionResult != null) {
            testVariants
                .groupBy { matchedVariant ->
                    variantCompressionService.get().resolveSuffix(
                        projectPath = project.path,
                        variantName = matchedVariant.variantName,
                        fallbackSuffix = matchedVariant.nameSuffix,
                        logger = project.logger
                    )
                }
                .values
                .map { variantsForSuffix ->
                    val representative = variantsForSuffix.minBy { it.variantName }
                    unitTestDataExtractor.extract(project, representative)
                }
        } else {
            project.logger.warn(
                "No compression result for ${project.path}, generating uncompressed unit test targets"
            )
            testVariants.map { matchedVariant ->
                unitTestDataExtractor.extract(project, matchedVariant)
            }
        }
    }
```

Delete the old `build` body, `extractLibraryTargets`, and `unitTestsTargets` (their logic now
lives in the members above). Behavioural note: the old `build()` mapped
`targetsBySuffix` values through `toAndroidLibTarget()` inline; the refactor maps the same
values through the same function — same order (`filterKeys` preserves LinkedHashMap order),
same output.

- [ ] **Step 2: Full unit suite**

Run: `./gradlew :grazel-gradle-plugin:test --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Golden baseline (byte-identity)**

Run: `./gradlew verifyGrazelGoldenBaseline --console=plain`
Expected: `Grazel golden baseline verified: migrateToBazel, task graph, bucket labels, and generated-file diff are clean.`

- [ ] **Step 4: Commit**

```bash
git add grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/AndroidLibraryTargetBuilder.kt
git commit -m "refactor(target): AndroidLibraryTargetBuilder owns library/unit-test selection

selectLibraryData/selectUnitTestData are the single selection implementations;
build() consumes them. Byte-identical (golden verified)."
```

---

### Task 3: Binary + instrumentation builders — selection members

**Files:**
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/AndroidBinaryTargetBuilder.kt`
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/AndroidInstrumentationBinaryTargetBuilder.kt`

**Interfaces:**
- Consumes: `AndroidBinaryVariantTargetData`, `AndroidInstrumentationTargetData` (Task 1).
- Produces: `internal fun selectBinaryData(project: Project): List<AndroidBinaryVariantTargetData>`
  and `internal fun selectInstrumentationData(project: Project): List<AndroidInstrumentationBinaryData>`.

- [ ] **Step 1: `AndroidBinaryTargetBuilder` — selection member, crashlytics stays in `build()`**

```kotlin
    override fun build(project: Project): List<BazelTarget> =
        selectBinaryData(project).flatMap { selected ->
            val intermediateTargets = mutableListOf<BazelTarget>()
            val crashlyticsDeps = crashlyticsDeps(
                project,
                selected.matchedVariant,
                intermediateTargets
            )
            listOf(
                AndroidBinaryTarget(
                    name = "${selected.binaryData.name}${selected.matchedVariant.nameSuffix}",
                    srcs = selected.libraryData.srcs,
                    deps = selected.libraryData.deps + selected.binaryData.deps + crashlyticsDeps,
                    multidex = selected.binaryData.multidex,
                    debugKey = selected.binaryData.debugKey,
                    dexShards = selected.binaryData.dexShards,
                    incrementalDexing = selected.binaryData.incrementalDexing,
                    enableCompose = selected.binaryData.compose,
                    enableDataBinding = selected.binaryData.databinding,
                    customPackage = selected.binaryData.customPackage,
                    packageName = selected.binaryData.packageName,
                    manifest = selected.libraryData.manifestFile,
                    manifestValues = selected.binaryData.manifestValues,
                    resConfigFilters = selected.binaryData.resConfigs,
                    resourceSets = selected.libraryData.resourceSets,
                    resValuesData = selected.libraryData.resValuesData,
                    buildConfigData = selected.libraryData.buildConfigData,
                    lintConfigData = selected.libraryData.lintConfigData,
                    minSdkVersion = selected.binaryData.minSdkVersion,
                    plugins = selected.libraryData.plugins,
                )
            ) + intermediateTargets
        }

    /**
     * Selects the app variants to generate (reachable, or referenced by an already-rendered
     * target) with their extracted library+binary data. Crashlytics decoration is deliberately
     * NOT selection: it is render-only (intra-package target) and never contributed to
     * reference facts — see AndroidBinaryVariantTargetData's KDoc.
     */
    internal fun selectBinaryData(project: Project): List<AndroidBinaryVariantTargetData> {
        val isReachableBucket = reachableBucketPredicate(project, dependencyResolutionService)
        val referencedTargetNames = workspaceRenderPlanService.get().referencedTargetNames(project.path)
        return variantMatcher.matchedVariants(
            project = project,
            variantType = VariantType.AndroidBuild,
            appVariantFilter = { appVariant ->
                appVariant.isReachableTargetVariant(isReachableBucket) ||
                    isReferencedGeneratedTarget(
                        targetName = "${project.name}${normalizeVariantSuffix(appVariant.name)}",
                        referencedTargetNames = referencedTargetNames
                    )
            }
        ).map { matchedVariant ->
            AndroidBinaryVariantTargetData(
                matchedVariant = matchedVariant,
                libraryData = androidLibraryDataExtractor.extract(project, matchedVariant),
                binaryData = androidBinaryDataExtractor.extract(project, matchedVariant)
            )
        }
    }
```

Delete the old `buildAndroidBinaryTargets`; keep `crashlyticsDeps` unchanged.

- [ ] **Step 2: `AndroidInstrumentationBinaryTargetBuilder` — selection member**

```kotlin
    override fun build(project: Project): List<BazelTarget> =
        selectInstrumentationData(project).map { data -> data.toTarget() }

    /**
     * Selects instrumentation binaries for reachable app variants, keeping only those with
     * sources — an instrumentation binary without srcs is not generated and must not
     * contribute reference facts either.
     */
    internal fun selectInstrumentationData(project: Project): List<AndroidInstrumentationBinaryData> {
        val isReachableBucket = reachableBucketPredicate(project, dependencyResolutionService)
        return variantMatcher.matchedVariants(
            project = project,
            variantType = VariantType.AndroidTest,
            appVariantFilter = { appVariant ->
                appVariant.isReachableTargetVariant(isReachableBucket)
            }
        ).map { matchedVariant ->
            androidInstrumentationBinDataExtractor.extract(
                project = project,
                matchedVariant = matchedVariant,
                sourceSetType = SourceSetType.JAVA_KOTLIN,
            )
        }.filter { data -> data.srcs.isNotEmpty() }
    }
```

(`matchedVariants` returns a `Set`; `.map` on it yields a `List` — same iteration order as
the old `forEach`, so target order is unchanged.)

- [ ] **Step 3: Full unit suite, then golden**

Run: `./gradlew :grazel-gradle-plugin:test --console=plain` → `BUILD SUCCESSFUL`
Run: `./gradlew verifyGrazelGoldenBaseline --console=plain` → `...generated-file diff are clean.`

- [ ] **Step 4: Commit**

```bash
git add grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/AndroidBinaryTargetBuilder.kt \
        grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/AndroidInstrumentationBinaryTargetBuilder.kt
git commit -m "refactor(target): binary and instrumentation builders own their selection

Crashlytics decoration stays render-only in build(), matching today's facts
behaviour. Byte-identical (golden verified)."
```

---

### Task 4: Android-test + Kotlin builders — selection members

**Files:**
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/AndroidTestTargetBuilder.kt`
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/KotlinLibraryTargetBuilder.kt`

**Interfaces:**
- Consumes: `AndroidTestTargetData`, `KotlinLibraryTargetData`, `KotlinUnitTestTargetData` (Task 1).
- Produces: `internal fun selectAndroidTestData(project: Project): List<AndroidTestData>` and
  `internal fun selectKotlinData(project: Project): List<TargetData>`.

- [ ] **Step 1: `AndroidTestTargetBuilder`**

```kotlin
    override fun build(project: Project): List<BazelTarget> =
        selectAndroidTestData(project).map { androidTestData -> androidTestData.toTarget() }

    /**
     * Selects the build variants of a `com.android.test` module that are reachable or
     * referenced by an already-rendered target, with their fully-extracted test data.
     */
    internal fun selectAndroidTestData(project: Project): List<AndroidTestData> {
        val isReachableBucket = reachableBucketPredicate(project, dependencyResolutionService)
        val referencedTargetNames = workspaceRenderPlanService.get().referencedTargetNames(project.path)
        return variantMatcher.matchedVariants(
            project,
            VariantType.AndroidBuild,
        ).filter { matchedVariant ->
            matchedVariant.isReachableProjectVariant(isReachableBucket) ||
                isReferencedGeneratedTarget(
                    targetName = "${project.name}${matchedVariant.nameSuffix}",
                    referencedTargetNames = referencedTargetNames
                )
        }.map { matchedVariant ->
            val androidLibraryData = androidLibraryDataExtractor.extract(
                project = project,
                matchedVariant = matchedVariant
            )
            val androidBinaryData = androidBinaryDataExtractor.extract(
                project = project,
                matchedVariant = matchedVariant
            )
            androidTestDataExtractor.extract(
                project = project,
                matchedVariant = matchedVariant,
                androidLibraryData = androidLibraryData,
                androidBinaryData = androidBinaryData
            )
        }
    }
```

- [ ] **Step 2: `KotlinLibraryTargetBuilder`**

```kotlin
    override fun build(project: Project): List<BazelTarget> =
        selectKotlinData(project).map { selected ->
            when (selected) {
                is KotlinLibraryTargetData -> selected.data.toKotlinLibraryTarget()
                is KotlinUnitTestTargetData -> selected.data.toUnitTestTarget()
                else -> error("Unexpected TargetData for Kotlin project: $selected")
            }
        }

    /**
     * A Kotlin project contributes its library + unit-test data only when reachable (or
     * already referenced) — the same three-signal gate documented on [isReachableJvmProject].
     */
    internal fun selectKotlinData(project: Project): List<TargetData> {
        if (!isReachableJvmProject(project, dependencyResolutionService, workspaceRenderPlanService)) {
            return emptyList()
        }
        return listOf(
            KotlinLibraryTargetData(projectDataExtractor.extract(project)),
            KotlinUnitTestTargetData(kotlinUnitTestDataExtractor.extract(project))
        )
    }
```

(Import `com.grab.grazel.migrate.kotlin.toUnitTestTarget` — already imported. The `when` here
is over this builder's own two selection types, not a cross-type dispatch ladder; it is the
inverse mapping of `selectKotlinData` and lives beside it.)

- [ ] **Step 3: Full unit suite, then golden**

Run: `./gradlew :grazel-gradle-plugin:test --console=plain` → `BUILD SUCCESSFUL`
Run: `./gradlew verifyGrazelGoldenBaseline --console=plain` → `...generated-file diff are clean.`

- [ ] **Step 4: Commit**

```bash
git add grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/AndroidTestTargetBuilder.kt \
        grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/KotlinLibraryTargetBuilder.kt
git commit -m "refactor(target): android-test and kotlin builders own their selection

Byte-identical (golden verified)."
```

---

### Task 5: `selectData` on the interface + extractor swap + shadow-pipeline deletion

**Files:**
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/BazelTarget.kt:46-49`
- Modify: all five builders (add `override fun selectData`)
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/TargetReferenceFactsExtractor.kt` (rewrite)
- Modify: `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/migrate/target/TargetReferenceFactsDataMappingTest.kt` (retarget to wrappers)

**Interfaces:**
- Consumes: every `select*Data` member from Tasks 2–4, wrapper types from Task 1.
- Produces: `TargetBuilder.selectData(project: Project): List<TargetData>`;
  `TargetReferenceFactsExtractor.collect(project)` keeps its exact signature (its caller,
  `CollectTargetMavenRepoReferencesTask.kt:61`, is untouched).

- [ ] **Step 1: Pre-check — facts merge must be order-insensitive**

The old `collect()` when-ladder had a fixed evaluation order; a Dagger `Set<TargetBuilder>`
does not guarantee iteration order. Read `TargetReferenceFacts` + `merged()`
(`gradle/dependencies/model/WorkspacePlan.kt:49`, `TargetReferenceFactsCollector.kt`) and
confirm merging is commutative (set/map unions). If any merged field is order-sensitive
(e.g. a list that preserves insertion order into serialized output), STOP and report — the
fix would be sorting inside `merged()`, which is a behaviour question to adjudicate, not
silently patch.

- [ ] **Step 2: Add `selectData` to the interface**

```kotlin
// grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/BazelTarget.kt
interface TargetBuilder {
    fun build(project: Project): List<BazelTarget>
    fun canHandle(project: Project): Boolean

    /**
     * The data this builder would render for [project] — the single source of selection
     * truth. [build] renders exactly this data; the reference-facts pass derives facts from
     * exactly this data. One implementation per target type, so facts and render cannot
     * diverge.
     */
    fun selectData(project: Project): List<TargetData>
}
```

(add `import com.grab.grazel.migrate.target.TargetData`.)

- [ ] **Step 3: Implement the five overrides (composing Tasks 2-4 members)**

```kotlin
// AndroidLibraryTargetBuilder
    override fun selectData(project: Project): List<TargetData> =
        selectLibraryData(project).map(::AndroidLibraryTargetData) +
            selectUnitTestData(project).map(::AndroidUnitTestTargetData)

// AndroidBinaryTargetBuilder
    override fun selectData(project: Project): List<TargetData> = selectBinaryData(project)

// AndroidInstrumentationBinaryTargetBuilder
    override fun selectData(project: Project): List<TargetData> =
        selectInstrumentationData(project).map(::AndroidInstrumentationTargetData)

// AndroidTestTargetBuilder
    override fun selectData(project: Project): List<TargetData> =
        selectAndroidTestData(project).map(::AndroidTestTargetData)

// KotlinLibraryTargetBuilder
    override fun selectData(project: Project): List<TargetData> = selectKotlinData(project)
```

Where a builder's `build()` re-derives what `selectData` returns (library builder maps the
same two selections; binary builder flatMaps `selectBinaryData`), leave `build()` as written
in Tasks 2-4 — S2 memoization is explicitly out of scope.

- [ ] **Step 4: Rewrite the extractor as the thin dispatcher**

Replace the entire class body and delete: `androidLibraryFacts`, `androidBinaryFacts`,
`androidInstrumentationFacts`, `standaloneAndroidTestFacts`, `kotlinFacts`,
`androidLibraryData`, `androidUnitTestData`, `reachableAndroidLibraryData`, the `collect()`
when-ladder, and ALL private top-level `referenceFacts()` adapter functions at the bottom of
the file (lines 255-312) — their logic now lives only in `TargetData.kt`. Keep the two
`internal` adapters ONLY if Step 6's test migration still needs them; otherwise delete those
too (preferred: delete, migrate the tests).

```kotlin
@Singleton
internal class TargetReferenceFactsExtractor
@Inject
constructor(
    private val targetBuilders: Set<@JvmSuppressWildcards TargetBuilder>
) {

    /**
     * Reference facts for [project], derived from exactly the data its target builders would
     * render ([TargetBuilder.selectData]) — dispatch, selection, and data are all shared with
     * the render pass, so facts/render divergence is structurally impossible.
     */
    fun collect(project: Project): TargetReferenceFacts =
        targetBuilders
            .filter { builder -> builder.canHandle(project) }
            .flatMap { builder -> builder.selectData(project) }
            .map(TargetData::referenceFacts)
            .merged()
}
```

Verify `emptyList<TargetReferenceFacts>().merged() == TargetReferenceFacts()` (the old
`else -> TargetReferenceFacts()` branch for non-handled projects). If `merged()` cannot take
an empty list, guard with `.ifEmpty { return TargetReferenceFacts() }`.

Unused imports and the seven extractor constructor params, `variantMatcher`,
`variantCompressionService`, `dependencyResolutionService`, `workspaceRenderPlanService` all
go. Check `TargetModule.kt` / Dagger wiring: `Set<TargetBuilder>` multibinding already exists
for `ProjectBazelFileBuilder`; the extractor's `@Inject` constructor picks it up with no
module change expected.

- [ ] **Step 5: Compile**

Run: `./gradlew :grazel-gradle-plugin:compileKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`. Fix any leftover references to deleted adapters (they should
only be in the mapping test, handled next).

- [ ] **Step 6: Migrate `TargetReferenceFactsDataMappingTest` to the wrappers**

Retarget each test from the deleted top-level adapters to the wrapper types, preserving every
assertion verbatim, e.g.:

```kotlin
        val facts = AndroidUnitTestTargetData(
            data = AndroidUnitTestData(
                /* identical constructor args as today */
            )
        ).referenceFacts()
```

(same for `AndroidInstrumentationTargetData` and `KotlinUnitTestTargetData`). Fold these into
`TargetDataTest.kt` if that reads better — one file owning data→facts tests mirrors one file
owning the mapping.

- [ ] **Step 7: Add the dispatcher test**

Append to `TargetDataTest.kt`:

```kotlin
    @Test
    fun `collect returns empty facts for a project no builder handles`() {
        val project = buildProject("plain-java")  // no android/kotlin plugins applied
        val extractor = TargetReferenceFactsExtractor(targetBuilders = emptySet())
        assertEquals(TargetReferenceFacts(), extractor.collect(project))
    }
```

- [ ] **Step 8: Full unit suite, golden, bazel analysis**

Run: `./gradlew :grazel-gradle-plugin:test --console=plain` → `BUILD SUCCESSFUL`
Run: `./gradlew verifyGrazelGoldenBaseline --console=plain` → `...generated-file diff are clean.`
Run: `bazelisk build --nobuild //...` (only after Gradle finishes) →
`Build completed successfully`, zero `no such package` / `no such target`.

The golden gate is the decisive check for this task: the facts pass changed engines, and
byte-identical generated output proves the new engine selects exactly what the old one did
on every sample shape (including `sample-android-test-util*`, flavors, and compression).

- [ ] **Step 9: Commit**

```bash
git add grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/BazelTarget.kt \
        grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/AndroidLibraryTargetBuilder.kt \
        grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/AndroidBinaryTargetBuilder.kt \
        grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/AndroidInstrumentationBinaryTargetBuilder.kt \
        grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/AndroidTestTargetBuilder.kt \
        grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/KotlinLibraryTargetBuilder.kt \
        grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/TargetReferenceFactsExtractor.kt \
        grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/migrate/target/TargetDataTest.kt \
        grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/migrate/target/TargetReferenceFactsDataMappingTest.kt
git commit -m "refactor(target): facts pass consumes builder-owned selectData

TargetReferenceFactsExtractor shrinks to a canHandle-dispatched thin adapter
over Set<TargetBuilder>; the four shadow selection pipelines and the collect()
when-ladder are deleted. Facts now derive from the same objects the builders
render. Byte-identical (golden + bazel analysis verified)."
```

---

## Final verification (after Task 5)

1. Full local gates already run in Task 5 (unit, golden, `bazelisk build --nobuild //...`).
2. **PAX sweep is NOT required** if the golden stayed byte-clean (this plan expects zero
   output movement). If ANY gate showed output drift, stop and follow
   `reports/specs/VERIFICATION-GATES.md` §PAX 1-6 before merging, and treat the drift as a
   finding to adjudicate — this plan promises byte-identity.
3. Whole-branch review (SDD final review) over `git merge-base` of the task commits.

## Explicitly out of scope

S2 memoization (facts + render currently both invoke `select*Data`/extractors — the 2×
extraction predates this plan and is unchanged by it); reachability channels (item 2);
fixpoint→worklist (item 3); any `resolution/`/`bucket/` change.
