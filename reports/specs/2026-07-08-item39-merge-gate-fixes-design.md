# Item 39 — Merge-Gate Fixes (Design / Execution Spec)

> **Status:** Approved 2026-07-08 (derived from `reports/MERGE-PUNCHLIST-branch.md`, all findings
> reconciled against `master`).
> **Executor:** Claude Code (this session) — **not Codex**. Hands-on edits + verification here.
> **Behaviour change:** M1 test-only (no production behaviour). M2 is **withdrawn** (intentional revert,
> no gap). M3 is a production refactor that **must be byte-identical** (golden + PAX gate). If M3 changes
> generated output, STOP and surface — do not merge a silent output change.
> **Global Constraints + Verification Playbook + code-quality stance:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`.
> **Scope:** the three MUST-FIX items that gate merge of `arun/dependencies-refactor`. Everything in the
> punch-list's DEFER bucket is explicitly out of scope here (roadmap Items 40+).

---

## Goal

Restore the one safety net the branch leans on but that is inert (M1 vacuous byte-identity test), and
collapse the one genuine branch-new classifier duplication (M3), so the branch merges with its
correctness story actually enforced rather than nominally present. (M2 was investigated and withdrawn —
the revert it flagged was intentional and left no gap.)

## Why these items (and not the rest)

The correctness of the local-maven-resolution + dependency-refactor work rests on the **byte-identical
pin lockfiles**. The punch-list found that gate partially disarmed and one broken-window classifier:
- **M1:** the unit test asserting lockfile reconstruction is byte-identical **never runs its body**. Real
  merge risk — fix first.
- **M2 (withdrawn):** the flagged "reverted functional gate" was an intentional revert of `UP_TO_DATE`
  assertions the task can't satisfy; coverage is intact via the consumer + unit test. No work.
- **M3:** `Dependencies.kt` re-derives a config *role* from config *names* (`isExternalDependencyDeclaration`)
  at a higher altitude than the `Variant<*>` API, which is the source of truth. Fix = consume the variant
  API's typed `declaredDependencyConfigurations` fact and delete the ad-hoc classifier. Empty-diff-gated.

---

## M1 — Un-vacuum the byte-identity reconstruction test

**File:** `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/migrate/dependencies/MavenInstallLockfileReconstructorTest.kt`
**No build-file change.**

### Grounded current state
- The test `reconstruct keeps checked in rje lockfiles byte identical when urls do not change`
  (`:246-265`) iterates `CHECKED_IN_LOCKFILES` (`:289-296`) — six **bare filenames**
  (`maven_install.json`, `debug_…`, `test_…`, `android_test_…`, `ksp_…`, `lint_…`).
- Line 253 builds `File(path)`; for a Gradle `Test` task the working dir is the subproject
  (`grazel-gradle-plugin/`), but the six lockfiles live at the **repo root**. All six are confirmed
  present at root.
- Line 254 guards with `if (lockfile.exists())` → always false → the `assertThat(...).isEqualTo(...)`
  body (`:256-262`) never executes. The test passes vacuously.
- **Existing convention to reuse:** `com.grab.grazel.util.ROOT_PATH` (`src/test/.../util/Paths.kt`)
  already derives the repo root from the JVM's `user.dir` and strips a trailing `grazel-gradle-plugin`
  segment. It works whether the JVM runs from the subproject or the repo root, and — critically — bakes
  **no absolute path** into the `Test` task's inputs, so the build cache key stays relocatable across
  machines/checkouts. (Do **not** inject `rootDir.absolutePath` via `systemProperty`; that would make the
  cache key machine-specific.)

### Work
Resolve the lockfiles against `ROOT_PATH` and **remove the silent-skip guard** so the loop can never go
vacuous again:
```kotlin
import com.grab.grazel.util.ROOT_PATH
// ...
CHECKED_IN_LOCKFILES.forEach { name ->
    val lockfile = ROOT_PATH.resolve(name).toFile()
    assertThat(lockfile.exists())
        .describedAs("checked-in lockfile %s must exist for the fidelity gate", name)
        .isTrue()
    val lockfileContents = lockfile.readText()
    assertThat(
        reconstructor.reconstruct(
            lockfileContents = lockfileContents,
            canonicalRepositoryInputs =
                canonicalRepositoryInputsFromLockfileRepositories(lockfileContents)
        )
    ).isEqualTo(lockfileContents)
}
```
(Reuse the single `readText()` result rather than reading twice as the original did. `ROOT_PATH` is
already imported/available in the test module, matching `Paths.kt`'s convention — no new
system-property plumbing.)

### Expected outcome & the real risk
With `proxyToCanonicalUrl = emptyMap()` (no URL rewrite), reconstruction should reproduce each file
**byte-for-byte** — but this is a strict test of the reconstructor's JSON serialization fidelity against
rje 6.10's Python `json.dump` formatting (indent, key order, separators, trailing newline). Two outcomes:
- **Passes** → the gate is genuinely restored. Done.
- **Fails on one or more lockfiles** → this is a *real* fidelity finding the vacuous test was hiding.
  Diagnose: is it (a) a reconstructor serialization gap (fix the reconstructor), or (b) the
  `canonicalRepositoryInputsFromLockfileRepositories` inputs not matching what produced the file? Fix the
  true cause. Do **not** re-weaken the test to make it pass. If the fix is non-trivial, stop and report
  before proceeding — a byte-fidelity bug is itself a merge-relevant finding.

### Verification
`./gradlew :grazel-gradle-plugin:test --tests "*MavenInstallLockfileReconstructorTest*"` — the fidelity
test executes its body (add a temporary `println`/count assertion during dev to prove the loop runs, then
remove it) and passes for all six lockfiles.

---

## M2 — WITHDRAWN (the revert was intentional; no coverage gap)

**Verdict:** not a merge issue. Do not restore the reverted assertions.

The punch-list read `d9ceb30` (revert of "Finalize workspace dependency refactor gates") as a
half-finished oversight. On inspection with the maintainer, the revert was **intentional and correct**:

- `ResolveWorkspaceDependenciesTask` is `@CacheableTask` but carries
  `@get:Input workspaceDependencyRootComponents: ListProperty<ResolvedComponentResult>`. A
  `ResolvedComponentResult` `@Input` is the accepted/master-like posture but does **not** yield a stable
  up-to-date fingerprint, so the task does not reliably report `UP_TO_DATE` on a clean re-run.
- The reverted assertions gated exactly that — `:resolveWorkspaceDependencies` `UP_TO_DATE` on no-edit
  re-run. Asserting behaviour the task doesn't have was flaky-to-false; reverting them was right.
- **No coverage was lost.** The task is unit-covered (`ResolveWorkspaceDependenciesTaskTest.kt`), and its
  output is consumed by `:computeWorkspaceDependencies` (`dependsOn` + `flatMap { workspaceDependencyResults }`),
  whose SUCCESS/UP_TO_DATE outcomes `BuildVariantTest` **does** gate across every edit scenario. So
  `:resolveWorkspaceDependencies` is transitively forced to run-and-succeed on all those paths; wrong
  output would surface downstream.

**Residual (non-blocking, informational):** because the task can't go `UP_TO_DATE`, the aggregated
resolution re-runs on every `migrateToBazel` even with no changes — a performance trade-off inherent to
the accepted `ResolvedComponentResult` posture, not a correctness bug. Optionally note in the roadmap;
not part of this item.

---

## M3 — Consume the typed variant fact instead of classifying config names

**Files:** `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/Dependencies.kt`,
`grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/variant/VariantDependencyConfigurationRoles.kt`

### Altitude framing (the actual defect)
The `Variant<*>` API is the **source of truth** for configurations and their roles. Any higher layer that
re-derives a config *role* by matching a config *name* is a code smell — an ad-hoc classifier at the
wrong altitude. `Dependencies.kt`'s `isExternalDependencyDeclaration` is precisely that: a private
name-matcher used at `:345` to filter raw `variant.variantConfigurations`. The fix is **not** to share a
string classifier upward (that launders the smell); it is to consume the typed fact the variant layer
already exposes and stop classifying names above the variant layer entirely.

### Grounded current state (corrects the punch-list's headline)
- `Dependencies.kt` defines a private `Configuration.isExternalDependencyDeclaration` (`:600-610`):
  excludes names containing `dependenciesmetadata`/`classpath`; accepts names ending
  `implementation`/`api`/`compileonly`/`runtimeonly`. Used at `:342-345` to filter
  `variant.variantConfigurations` inside `collectMavenDeps`.
- The variant layer **already exposes the typed fact**:
  `Variant<*>.declaredDependencyConfigurations` (`VariantDependencyConfigurationRoles.kt:23-25`) ==
  `variantConfigurations.filterTo(linkedSetOf()) { it.isDeclarationBucket }`. Here `isDeclarationBucket`
  (`:44-54`) is correctly **private** to the variant layer and is the single source of truth for the
  role. It has the **identical accept clause** to `isExternalDependencyDeclaration` and a **superset
  exclusion list** (`declarationBucketExcludedNameFragments`, `:56-72`: adds `annotationprocessor`,
  `kapt`, `ksp`, `lint`, `jacoco`, `archives`, `_`-prefix, etc.).
- **Correction to the finder:** the flagged "`kspRelease`/`kspDebug` are misclassified" example is
  **wrong** — those names fail the `endsWith` accept clause in *both* classifiers, so neither selects
  them. Because the accept clauses are identical and the exclusion set is a strict superset,
  `declaredDependencyConfigurations ⊆ {c | c.isExternalDependencyDeclaration}`: switching can only ever
  *remove* configs whose name both ends with a declaration suffix **and** contains an extra excluded
  fragment (e.g. a hypothetical `…kaptImplementation`). No standard AGP/KGP config matches, so the
  behavioural delta is **latent/unreachable in practice** — which is why PAX is byte-identical today.
- **Conclusion:** M3 removes an ad-hoc higher-altitude classifier and routes the consumer through the
  variant API's typed fact. Correctness is proven by the golden/PAX byte-identity gate staying empty.

### Work
1. In `Dependencies.kt` (`:342-346`), replace the raw filter
   `variant.variantConfigurations.asSequence().filter { it.isExternalDependencyDeclaration }` with the
   typed variant accessor:
   ```kotlin
   variant.declaredDependencyConfigurations.asSequence()
       .flatMap { configuration -> configuration.dependencies.filterIsInstance<ExternalDependency>() ... }
   ```
   (import `com.grab.grazel.gradle.variant.declaredDependencyConfigurations`). The rest of the
   `flatMap`/`DirectVariantDeclaration` construction is unchanged.
2. Delete the now-unused private `Configuration.isExternalDependencyDeclaration` (`:600-610`) from
   `Dependencies.kt`. Do **not** promote or duplicate `isDeclarationBucket` — it stays private to the
   variant layer as the single source of truth. After this, `Dependencies.kt` performs **no config-name
   role classification** on this path.
3. **`bucketSpecificity()` stays put — explicitly OUT of scope for M3.** Grounding shows it is not a
   simple placement duplicate: it is a specificity heuristic used to *rank/disambiguate* candidate
   declarations (`bestErrorDeclaration` at `:288-297` and the `minWithOrNull` selection at `:402-408`).
   It still parses variant/bucket names and arguably belongs in the L3 plan, but relocating it is a
   genuine design change, not empty-diff, and is not a merge gate. Record it as a roadmap DEFER (Item
   40+) — it is part of the broader "`Dependencies.kt` still classifies/ranks at render time" epicenter,
   not this commit.

### Verification (this is the gate — mandatory)
1. `./gradlew :grazel-gradle-plugin:test :grazel-gradle-plugin:functionalTest` green.
2. **Byte-identity gate:** `./gradlew verifyGrazelGoldenBaseline` (the branch's own golden verifier;
   script at `reports/scripts/verify-grazel-golden-baseline.sh`) → generated sample output **unchanged**.
3. If available, the PAX bounded baseline audit stays no-change (maintainer-run;
   `reports/scripts/audit-pax-bounded-baseline.sh`).
4. **If any generated output changes:** the divergence was load-bearing after all. STOP, do not commit,
   report exactly which target/dep changed. Do not fold a silent output change into a "cleanup" commit.

---

## Hard constraints
- M1/M2 touch tests + one `build.gradle` line only — no production behaviour change.
- M3 must be byte-identical: golden empty-diff + PAX no-change are pass/fail gates, not nice-to-haves.
- No new stray markers/commented code/debug prints introduced (this spec exists to *remove* broken
  windows, not add them).
- Never re-weaken a test to make it pass. A failing un-vacuumed test is a finding, not an obstacle.

## Out of scope (→ roadmap Items 40+)
- `bucketSpecificity()` relocation into the L3 plan (see M3.3).
- The `reports/` directory policy call (ship-in-PR vs. move vs. ignore).
- `verifyGrazelGoldenBaseline` permanence, `@Ignore`d `SourcePathTest`, the `@Deprecated(WARNING)` no-op,
  the `AggregatedDependencyResolver` A/C dedup, and all pre-existing AGP couplings — all DEFER per the
  punch-list.

## Acceptance criteria
- M1: the fidelity test executes its body for all six checked-in lockfiles and passes (or a genuine
  reconstruction fidelity bug is found and fixed, not hidden).
- M2: withdrawn — no change required.
- M3: `isExternalDependencyDeclaration` is gone, the single canonical `isDeclarationBucket` is the only
  declaration-bucket classifier, and the golden + PAX byte-identity gates are unchanged.
- `./gradlew :grazel-gradle-plugin:test :grazel-gradle-plugin:functionalTest` green. (The pre-existing
  `:sample-android:lintDemoFreeDebug` environmental failure is out of scope — it fails identically on
  `master`.)
