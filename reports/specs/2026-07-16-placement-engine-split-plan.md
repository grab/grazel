# Placement Engine Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Slim `bucket/DependencyBucketPlacementEngine.kt` (824 ln) from three concerns to one by moving
the **input-synthesis** cluster and the **graph-adapter** class into their own files in the same
`bucket` package. Pure relocation — **byte-identical** generated output. Leaves the engine file as just
the placement algorithm + its two shared DTOs.

## Global Constraints

- **Pure relocation.** Only new-file `package` declarations and ONE `private`→`internal` change (on
  `BucketPlacementGraph`). NO body edits, NO logic, NO renames, NO reformatting, no other visibility
  changes. If the compiler wants anything else, STOP and report.
- **Byte-identity gate.** `./gradlew :grazel-gradle-plugin:test --console=plain` then
  `./gradlew verifyGrazelGoldenBaseline --console=plain` — success line + CLEAN generated-file diff.
- **Same package** (`com.grab.grazel.gradle.dependencies.bucket`) — same-package refs need no imports;
  expect ZERO import changes at call sites and ZERO test-file changes.
- Leave `BucketPlacementVariantInput` and `DependencyBucketPlacementPlan` (the shared DTOs) in the
  engine file.
- Stage explicit paths, never `git add -A`. Never commit `codedb.snapshot`. One Gradle build at a time.

---

### Task 1: Extract concerns C (input synthesis) and B (graph adapter) into their own files

Atomic task — both are same-package relocations; the module won't compile until all three files are
consistent.

**New file `BucketPlacementVariantInputs.kt`** ← move VERBATIM from `DependencyBucketPlacementEngine.kt`
(concern C), keeping visibilities exactly as they are (`internal` entry points, `private` helpers):
`DeclaredDependencyMetadata.mainBucketVariants`, `DeclaredDependencyMetadata.mainBucketVariantsByProject`,
`DeclaredDependencyMetadata.testBucketVariantsByProject`, `DeclaredVariantDependencyMetadata.testBucketExtendsFrom`,
`BucketPlacementVariantInput.appliesTo`, `ownerVariantFor`, `OwnerBucketSpec` (private data class),
`candidateOwnerBucketSpecs`, `String.bucketPartCapitalized`, `orderedCombinations`.

**New file `BucketPlacementGraph.kt`** ← move VERBATIM the `BucketPlacementGraph` class (concern B), and
change ONLY its declaration `private class BucketPlacementGraph` → `internal class BucketPlacementGraph`.

**`DependencyBucketPlacementEngine.kt`** keeps: `BucketPlacementVariantInput`,
`DependencyBucketPlacementPlan`, the `DependencyBucketPlacementEngine` class (concern A) and its private
helpers (`candidateDepsFor`, `withResolvedLeafMetadata`, `onlyDependenciesPresentIn`, `withInferredClosure`,
`withInferredClosureMetadata`). Delete the moved blocks.

- [ ] **Step 1:** Create `BucketPlacementVariantInputs.kt` with the `bucket` package decl + license
  header; move concern C's declarations into it verbatim.
- [ ] **Step 2:** Create `BucketPlacementGraph.kt` with package decl + header; move the class verbatim;
  apply the single `private`→`internal`.
- [ ] **Step 3:** Delete the moved blocks from `DependencyBucketPlacementEngine.kt`; fix up its imports
  (some imports it currently has may now belong only to the new files — move import lines to where
  they're used; remove now-unused ones). Change nothing but declarations moving + import lines.
- [ ] **Step 4: Gate**
```bash
cd /Users/arun.sampathkumar/work/grazel
./gradlew :grazel-gradle-plugin:test --console=plain
./gradlew verifyGrazelGoldenBaseline --console=plain
```
Expected: compiles, unit passes, golden success line + clean diff.
- [ ] **Step 5: Commit** (`git status` first; only `.kt` new/edited, nothing else):
```bash
git add -- '*.kt'
git commit -m "refactor: split placement-engine input-synthesis and graph-adapter into own files"
```
