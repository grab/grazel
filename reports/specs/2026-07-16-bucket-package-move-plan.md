# Bucket Package Move Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Relocate the cohesive 9-file bucket-ownership cluster (+ its tests) out of the crowded
`com.grab.grazel.gradle.dependencies` package into a new `com.grab.grazel.gradle.dependencies.bucket`
sub-package (sibling of `model/`), so dependency *resolution* and bucket *ownership planning* read as
separate concerns. Pure relocation — **byte-identical** generated output.

**Architecture:** Only `package` declarations and `import` lines change. Shared key types
(`ProjectDependencyBucket`, `DeclaredDependencyMetadata`, `DeclaredVariantDependencyMetadata`,
`DependencyIdentity` extensions, `OverrideTargets.mavenOverrideTarget`,
`mergeDependencyMetadataByMaxVersion`) stay in the parent `dependencies` package; the moved files
import them (child→parent). Resolution files that call into the cluster import from `.bucket`.

## Global Constraints

- **Pure relocation.** Only `package` + `import` lines change. NO body edits, NO renames, NO
  visibility changes, NO logic. If the compiler wants anything beyond an import, STOP and report.
- **Byte-identity gate.** `./gradlew :grazel-gradle-plugin:test --console=plain` then
  `./gradlew verifyGrazelGoldenBaseline --console=plain`. Success line: `Grazel golden baseline
  verified: migrateToBazel, task graph, bucket labels, and generated-file diff are clean.` Any
  generated-file drift ⇒ revert (a package move cannot legitimately move output).
- **Do NOT move the shared key types** listed above — they are consumed by resolution too.
- All bucket types are `internal` (module-wide) — no visibility widening needed, only imports.
- Stage explicit paths, never `git add -A`. Use `git mv` so history follows.
- One Gradle build at a time.

---

### Task 1: Move the bucket cluster into `dependencies.bucket` (one atomic task)

A package move is a single compile unit — the module won't compile until every `package`/`import` is
consistent — so this is one task, not split.

**Files to move (production, 9)** — `git mv` from
`grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/` to
`.../dependencies/bucket/`, then set each file's `package` to `com.grab.grazel.gradle.dependencies.bucket`:
`BucketOwnershipPlanner.kt`, `MainBucketPlanner.kt`, `TestBucketPlanner.kt`,
`DependencyBucketPlacementEngine.kt`, `BucketMetadataHelpers.kt`, `BucketReduction.kt`, `Coverage.kt`,
`DefaultBucketDependencyReducer.kt`, `DefaultOverrideCarrierPlanner.kt`.

**Files to move (test, 3)** — `git mv` from
`grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/` to
`.../dependencies/bucket/`, set `package` to match:
`BucketOwnershipPlannerTest.kt`, `DependencyBucketPlacementEngineTest.kt`,
`DefaultBucketDependencyReducerTest.kt`.
(NOTE: `variant/BucketHierarchyGraphTest.kt` is in the `variant` package — do NOT move it.)

- [ ] **Step 1: git mv** all 12 files into the new `bucket/` directories (main + test).

- [ ] **Step 2: Rewrite package declarations** in the 12 moved files to
  `com.grab.grazel.gradle.dependencies.bucket`.

- [ ] **Step 3: Resolve imports (compiler-driven).**
  - In the moved files, add imports for the parent-package symbols they reference (the shared key
    types listed in Global Constraints, plus anything else the compiler flags). Their existing
    `com.grab.grazel.gradle.dependencies.model.*` / `...variant.*` imports are unchanged.
  - In files that STAY in `dependencies` and reference moved types, add `com.grab.grazel.gradle.dependencies.bucket.*`
    imports. Known: `AggregatedDependencyResolver.kt` (constructs `BucketOwnershipPlanner`, uses
    `OwnershipPlannerInput`), `ComputeWorkspaceDependencies.kt` (uses `DefaultBucketDependencyReducer`,
    `DefaultOverrideCarrierPlanner`). **Grep the whole module** (`src/main` + `src/test`) for every
    reference to a moved type and resolve each — do not assume the known list is exhaustive.
  - Change nothing but `package`/`import` lines.

- [ ] **Step 4: Gate**
```bash
cd /Users/arun.sampathkumar/work/grazel
./gradlew :grazel-gradle-plugin:test --console=plain
./gradlew verifyGrazelGoldenBaseline --console=plain
```
Expected: compiles, unit passes, golden success line + clean diff.

- [ ] **Step 5: Commit**
```bash
git add -A -- '*.kt'    # picks up git mv renames for *.kt only; verify `git status` shows only .kt renames/edits
git commit -m "refactor: move bucket-ownership cluster into dependencies.bucket sub-package"
```
(Before committing, run `git status` and confirm nothing outside `*.kt` — no `codedb.snapshot`,
no scratch dirs — is staged.)
