# TestBucketPlanner Per-Bucket Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Extract the dense per-bucket body of `TestBucketPlanner.plannedTestBuckets` into a focused,
pure `planTestBucket(...)` function so the method reads as a clean nested loop, and add a worked
example to the KDoc. Behaviour-preserving; byte-identity gated.

**Architecture:** `plannedTestBuckets` currently inlines a per-project loop + a ~55-line per-bucket
body (compute output name → two coverage views → two subtraction passes → conditional emit) + a final
cleanup. Extract the per-bucket body into a pure function returning a small result; the loop just
collects and merges. Coverage-subtraction internals stay untouched.

**Tech Stack:** Kotlin, Gradle plugin. Byte-identity gated.

## Global Constraints

- **Behaviour-preserving only.** The per-bucket body moves verbatim — no logic edits, identical call
  order to `outputBucketNameForTestBucket` / `withoutTestDependenciesCoveredBy` /
  `withoutTestDependenciesCoveredByEveryLeaf`. The null-return replaces today's
  `if (testOnlyDependencies.isNotEmpty())` guard exactly.
- **Byte-identity gate.** After the task: `./gradlew :grazel-gradle-plugin:test --console=plain` then
  `./gradlew verifyGrazelGoldenBaseline --console=plain`. Success line: `Grazel golden baseline
  verified: migrateToBazel, task graph, bucket labels, and generated-file diff are clean.` Any
  generated-file drift ⇒ revert.
- **Scope: one file only** — `TestBucketPlanner.kt`. No new files. Do NOT touch
  `withoutTestDependenciesCoveredBy`, `withoutTestDependenciesCoveredByEveryLeaf`,
  `withoutMergedBaseTestDependenciesCoveredBy`, `scopedSiblingClosureDependenciesByShortId`, or the
  bucket-naming cluster.
- **KDoc:** no `\u` sequences (kapt copies KDoc into Java stubs).
- **Staging:** explicit `*.kt` paths, never `git add -A`.
- One Gradle build at a time.

---

### Task 1: Extract `planTestBucket` + `TestBucketResult`, de-closure the coverage helper, add worked-example KDoc

**Files:**
- Modify: `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/TestBucketPlanner.kt`

**Interfaces (produced, all `private` members of `TestBucketPlanner`):**
- `private data class TestBucketResult(val outputBucketName: String, val dependencies: Map<String, ResolvedDependency>, val declaredMetadata: Map<String, ResolvedDependency>)`
- `private fun coveredDepsByShortIdFor(coveredDepsByBucket: Map<String, List<CoveredDependency>>, bucketNames: Set<String>): Map<String, List<CoveredDependency>>` — the current nested local closure (lines ~202-208), lifted to a method taking `coveredDepsByBucket` explicitly.
- `private fun planTestBucket(projectPath: String, plan: DependencyBucketPlacementPlan, bucketName: String, dependencies: Map<String, ResolvedDependency>, coveredDepsByBucket: Map<String, List<CoveredDependency>>, declaredTestDependenciesByBucket: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>): TestBucketResult?` — the current per-bucket body (lines ~217-272). Returns `TestBucketResult(outputBucketName, testOnlyDependencies, declaredTestDependencies.filterKeys { it in testOnlyDependencies })` when `testOnlyDependencies` is non-empty, else `null`.

- [ ] **Step 1: Extract.** In `TestBucketPlanner.kt`:
  - Add `TestBucketResult`.
  - Lift `coveredDepsByShortIdFor` out of `plannedTestBuckets` into a private method with the explicit
    `coveredDepsByBucket` parameter; update its two call sites inside the extracted body to pass the map.
  - Move the per-bucket `plannedBuckets.forEach { (bucketName, dependencies) -> ... }` body verbatim into
    `planTestBucket(...)`, returning a `TestBucketResult?` (null in place of the old empty-guard skip).
  - Rewrite the loop in `plannedTestBuckets` to call `planTestBucket(...)` and, on non-null result,
    `buckets.mergeBucket(result.outputBucketName, result.dependencies)` +
    `addDeclaredOutputMetadata(declaredMetadataByOutputBucket, result.outputBucketName, result.declaredMetadata)`.
    The surrounding per-project setup (`coveredDepsByBucket`, `plannedBuckets` build) and the final
    `applyDeclaredMetadataByBucket(withoutMergedBaseTestDependenciesCoveredBy(...))` return are unchanged.

- [ ] **Step 2: Worked-example KDoc.** Add a concrete example to the class KDoc (or `plannedTestBuckets`
  KDoc): e.g. `:app` with `demoDebug`/`fullDebug` android-test leaves — a dep already in `default` is
  subtracted out of the `androidTest` bucket, while a dep used only by `demoDebug` survives into its
  leaf bucket. Comment-only, no `\u`.

- [ ] **Step 3: Gate**
```bash
cd /Users/arun.sampathkumar/work/grazel
./gradlew :grazel-gradle-plugin:test --console=plain
./gradlew verifyGrazelGoldenBaseline --console=plain
```
Expected: unit passes; golden success line, clean diff.

- [ ] **Step 4: Commit**
```bash
git add -- '*.kt'
git commit -m "refactor: extract planTestBucket from plannedTestBuckets; document coverage passes"
```
