# Merge Punch-List — `arun/dependencies-refactor`

> **Date:** 2026-07-08. **Purpose:** the *merge-readiness* gate (distinct from the altitude review in
> `reports/ALTITUDE-REVIEW-branch.md`, which answered "is the architecture sound?"). Three tracks:
> mechanical green, shortcut/commit hygiene, concrete layering violations. Method: `./gradlew check`
> + master-parity worktree check + three parallel finders, all verdicts reconciled against `master`.
> **Verdict below is triage only — no code was changed.**

---

## Bottom line

**No merge BLOCKERs.** Three MUST-FIX items before merge; everything else defers to roadmap Items 39+.
The red `./gradlew check` is **not** this branch's fault — it reproduces identically on `master`
(pre-existing environmental lint). Notably, the deep layering pass **dismissed three items the earlier
altitude review flagged** — the branch's real introduced debt is smaller than that review implied.

| Track | Result |
|---|---|
| 1. Mechanical green | Red, but pre-existing/environmental (master fails identically). Not a branch gate. |
| 2. Shortcut / hygiene | 2 MUST-FIX, 3 DEFER. No stray TODOs/commented code/debug prints/WIP residue. |
| 3. Layering violations | 1 MUST-FIX (branch-new), rest DEFER. Pure-JVM boundary intact. 3 prior flags dismissed. |

---

## MUST-FIX before merge (3)

### M1 — Vacuous byte-identity test *(hygiene)*
`grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/migrate/dependencies/MavenInstallLockfileReconstructorTest.kt:247`
Test `reconstruct keeps checked in rje lockfiles byte identical when urls do not change` builds
`File(path)` (line 253) from bare filenames (`"maven_install.json"`), resolved relative to the
subproject working dir — but the lockfiles live at the **repo root**, one level up. The
`if (lockfile.exists())` guard (line 254) is therefore always false, the loop body never runs, and the
test passes **vacuously**. The byte-identity contract — the whole point of the local-maven-resolution
correctness story — is never exercised.
**Fix:** anchor to `rootProject.projectDir` via a system property, or move fixtures into the subproject
test-resources tree and load via `javaClass.getResource(...)`. Then regenerate the golden fixtures.

### M2 — Reverted functional gate left the tree half-finished *(hygiene / commit)*
`grazel-gradle-plugin/src/functionalTest/kotlin/com/grab/grazel/migrate/BuildVariantTest.kt`
`ac9de09` ("Finalize workspace dependency refactor gates") added five functional assertions gating
`:resolveWorkspaceDependencies` (present in graph, SUCCESS first run, UP-TO-DATE on clean re-run,
re-runs on declaration edits and on project-edge edits). `d9ceb30` reverted `ac9de09` **wholesale** and
nothing re-applied just the assertions. Result: the production task (`ResolveWorkspaceDependenciesTask`,
wired via `WorkspaceDependencyInputsRegistrar`) is live, but its functional regression gate is gone —
only unit-shape coverage remains.
**Fix:** cherry-pick / re-author the five assertion blocks from the `ac9de09` diff back into
`BuildVariantTest`. (This is exactly the "temporary revert never re-applied" risk this pass was looking for.)

### M3 — Divergent declaration-bucket classifier + render-time placement *(layering, branch-new)*
`grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/Dependencies.kt`
- `isExternalDependencyDeclaration` (L600-610) is a **second** declaration-bucket classifier that
  diverges from the canonical `isDeclarationBucket` in
  `gradle/variant/VariantDependencyConfigurationRoles.kt` (L44-72): it excludes only
  `dependenciesmetadata` + `classpath`, where the canonical list also excludes
  `ksp`/`annotationprocessor`/`kapt`/`lint`/`archives`/`_`-prefixed. **Concrete split:**
  `kspRelease`/`kspDebug` are treated as external declaration buckets here but excluded by the variant
  layer. (Latent — correctness is gated by the byte-identical pin + PAX baseline, so it isn't
  *misbehaving* today, but it's a real classification fork waiting to bite.)
- `bucketSpecificity()` (L253-261) ranks bucket placement at render time inside `collectMavenDeps` —
  L5 doing L3's ownership job.
**Fix:** delete `isExternalDependencyDeclaration`, route through the variant layer's `isDeclarationBucket`;
drive placement from the L3 plan instead of re-ranking via `bucketSpecificity()`. This is the one genuine
branch-introduced layering violation.

---

## DEFER — roadmap Items 39+ (not merge gates)

- **D1 — `reports/` directory (104 planning/spec files).** New at repo root, not `.gitignore`'d, not
  plugin source. Inflates the PR diff. **Reviewer policy call:** do these ship in the PR, move to a
  branch/wiki, or get ignored? Decide explicitly so they aren't mistaken for docs. *(hygiene)*
- **D2 — `AggregatedDependencyResolver` A/C branch duplication.** Two identical two-arm
  test-vs-main routing branches (~L483-485, ~L558-563) extractable into a ~5-line
  `dispatchToHierarchyBucket(...)` helper. Readability only; Site B fan-out is essential, leave it.
- **D3 — `@Ignore` on `SourcePathTest.kt:46`** ("flaky in parallel; covered by unit coverage").
  Predates the branch (message/typo touched here). Verify the unit coverage is real, then delete the
  inert test rather than leave a broken window.
- **D4 — `ExperimentsExtension.limitDependencyResolutionParallelism`** `@Deprecated(WARNING)` no-op,
  intentional (compat), tested. Consider escalating to `DeprecationLevel.ERROR` in a follow-up.
- **D5 — `verifyGrazelGoldenBaseline` task** added to root `build.gradle`. Confirm this is meant to
  ship as a permanent verification task vs. being CI/dev scaffolding.
- **D6 — Pre-existing AGP couplings (NOT branch debt):** `DependenciesGraphsBuilder` routing via AGP
  `BaseVariant.hierarchy` (load-bearing — typed substitution would silently drop kapt/AP edges);
  `MavenInstallArtifactsCalculator` `DefaultMavenArtifactRepository` internal type;
  `BazelDependency`/`bazel.exec` `Project` imports. All present on `master`. Out of scope.

---

## Dismissed — earlier altitude review over-flagged these (deep re-check cleared them)

These were Bucket-1 items in `ALTITUDE-REVIEW-branch.md`; the deeper single-purpose pass cleared them:

- **`BucketOwnershipPlanner` variantType-from-string** — DISMISSED. It does **not** re-derive type from
  a rendered string; it synthesizes a candidate name then validates against the typed
  `plan.variantTypesByBucketName` map. `testHierarchyBucketClosuresFor` takes `variantType: VariantType`
  as a typed param.
- **Scattered KSP string heuristics** (`WorkspaceKspConfigurations.isKspDeclarationBucket`,
  `CollectKspProcessorDependenciesTask` `startsWith("ksp")`) — DISMISSED. Those symbols/heuristics do
  not exist; both use the typed `variant.kspConfiguration` accessor and precomputed `processorClasspath`.
  (The only real KSP-name coupling is the `ksp*` gap inside M3.)
- **`usesService()` gap on 6 tasks** — DISMISSED. Every task holding an `@Internal` BuildService
  `Property` has a matching `usesService(...)` at registration (verified across all 8 tasks).
- **KSP_MAVEN read-back = feedback loop** — DISMISSED. It's a one-directional
  compute→serialize→deserialize→render handoff; the read happens in a task ordered strictly after the
  producer. No planning decision is re-driven from output.

---

## Track 1 detail — why the red build is not a gate

`./gradlew check` fails only at `:sample-android:lintDemoFreeDebug`: 2 `MissingConstraints` lint errors
in `sample-android/src/main/res/layout/activity_main.xml:73` (an `EditText` with no vertical
constraint). That file is **untouched** by the branch; the lint baseline commit (`fdc95a0`) predates the
branch; the branch changes no AGP/lint version (`lint_maven_install.json` is only reformatted v2→v3).
A worktree run of the identical task on `master` (`1d6c91e`) **fails identically**. Conclusion:
pre-existing environmental lint drift (local SDK/lint vs. CI). Not a regression, not a branch merge gate
— but whoever owns CI should know local `check` is red on `master` too.

---

## Suggested sequencing

1. **M1 + M2 together** — restore the two safety nets (real byte-identity test, functional gate). These
   are the actual merge risk: the correctness story currently rests on gates that are partly inert.
2. **M3** — the one layering fix; empty-diff-able if `isDeclarationBucket` classifies the PAX inputs the
   same way (verify against the byte-identical gate).
3. Merge. File D1-D5 as Items 39+; D6 stays out of scope with a roadmap line so `Dependencies.kt` /
   the AGP couplings aren't mistaken for unfinished work.
