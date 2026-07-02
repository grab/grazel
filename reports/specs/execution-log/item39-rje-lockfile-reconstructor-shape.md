# Item 39 - RJE Lockfile Reconstructor Shape

## 2026-07-02 - Start

- Starting Grazel commit: `c4e0cb2` (`docs: add next quality refactor specs`).
- PAX regression workspace: `/Users/arun.sampathkumar/work/pax-android`, branch
  `arun/grazel-refactor`, commit `d4105d1f64bd`, local status `M build.gradle`
  only. Do not commit PAX.
- Active spec:
  `reports/specs/2026-07-02-item39-rje-lockfile-reconstructor-shape-design.md`.
- Goal: split `MavenInstallLockfileReconstructor` into named RJE lockfile parser/model,
  URL rewriter, baseline merger, POM skip normalizer, hasher/Starlark repr, and
  renderer while preserving byte-identical lockfiles and proxy repin behavior.
- Pre-step resource check: Data volume about 92% used with about 33 GiB free. No
  cleanup performed.
- Step-0 proxy package/spec checkpoint was locally committed as `c4e0cb2` after
  focused proxy tests, local `migrateToBazel`, task graph, size guard, and diff
  checks passed.

## 2026-07-02 - RJE extraction red/green

- Subagent audit `Sagan` reviewed the original reconstructor and confirmed the
  high-risk invariants to preserve: RJE byte shape, null preservation, canonical
  repository-input hashing, resolved-artifact topo hash semantics, Java
  `String.hashCode()`, longest-prefix URL restore, and baseline/POM skip order.
- Subagent audit `Planck` reviewed test coverage and recommended direct tests
  for the new renderer/Starlark/hash/policy seams while keeping the existing
  checked-in-lockfile byte-identity integration test.
- Red state observed before production wiring:
  `./gradlew :grazel-gradle-plugin:test --tests "...StarlarkReprTest" --tests
  "...RulesJvmExternalLockfileRendererTest" --console=plain --no-daemon`
  failed in `compileTestKotlin` because the new collaborator APIs did not exist.
- Extracted `MavenInstallLockfileReconstructor` into a small pipeline over:
  `RulesJvmExternalLockfileParser`, `MavenLockfileRepositoryUrlRewriter`,
  `BaselineLockfileFactsMerger`, `PomPackagingSkipNormalizer`,
  `RulesJvmExternalLockfileHasher`, `StarlarkRepr`, and
  `RulesJvmExternalLockfileRenderer`.
- Mechanical failure after first extraction:
  `./gradlew :grazel-gradle-plugin:test --tests "...StarlarkReprTest" --tests
  "...RulesJvmExternalLockfileRendererTest" --tests
  "...MavenInstallLockfileReconstructorTest" --console=plain --no-daemon`
  failed in `compileKotlin`; root cause was importing a non-existent
  `kotlinx.serialization.json.content` top-level symbol in the new files.
  Fixed by removing those imports and using the existing `jsonPrimitive.content`
  property style.
- Added direct collaborator tests:
  `StarlarkReprTest`, `RulesJvmExternalLockfileRendererTest`, and
  `RulesJvmExternalLockfileTransformsTest`.
- Green focused command, 17s:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.migrate.dependencies.StarlarkReprTest" --tests
  "com.grab.grazel.migrate.dependencies.RulesJvmExternalLockfileRendererTest"
  --tests "com.grab.grazel.migrate.dependencies.RulesJvmExternalLockfileTransformsTest"
  --tests "com.grab.grazel.migrate.dependencies.MavenInstallLockfileReconstructorTest"
  --console=plain --no-daemon`.
- Current behavior stance: Item39 remains intended empty-diff/source-shape only.
  The existing `MavenInstallLockfileReconstructorTest` checked-in lockfile
  byte-identity test is still the primary regression guard for RJE render/hash
  compatibility.

## 2026-07-02 - Local and PAX verification

- Green full plugin test, 40s:
  `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon`.
- Green local generated-output checks:
  - `git diff --check`
  - `git diff --check master...HEAD`
  - `./gradlew migrateToBazel --console=plain --no-daemon`, 10s, no generated
    output drift.
  - `reports/scripts/verify-default-task-graph.sh`
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving`
    (`bucketCount=11`, `pinfileCount=11`, `totalArtifactRoots=1945`).
- Known pre-existing waiver remains:
  `reports/scripts/verify-sample-bucket-labels.sh` still fails with
  `WORKSPACE must not union one-sided appcompat exclude onto
  androidx.constraintlayout:constraintlayout`. There was no generated-output
  diff from this item; the same waiver is already recorded in `REVIEW-GUIDE.md`
  and prior execution logs.
- First PAX force-repin attempt deleted the maven install JSONs and failed
  before `pinMavenArtifacts` because downstream script generation expects
  lockfiles to exist. Root cause: missing-lockfile force-repin is not the
  supported feedback loop for this branch; use a bad existing signature instead.
  Restored the JSONs before retrying.
- Controlled PAX force-repin perturbation: changed one
  `android_test_maven_install.json` artifact hash, then ran:
  `cd /Users/arun.sampathkumar/work/pax-android && ./gradlew migrateToBazel
  --no-daemon --console=plain --stacktrace --rerun-tasks`.
- PAX force-repin result: green in 10m 6s. `pinMavenArtifacts` detected
  `android_test_maven` out of date, repinned all 11 maven repos through the local
  proxy, then the second freshness check passed.
- Proxy run summary from PAX:
  - 787 artifacts served from Gradle index
  - 788 POMs served from Gradle index
  - 0 origin fallbacks
  - 30 origin failures
  - 45 lockfile artifact fallbacks
  - 52 metadata-only artifact fallbacks
  - 1713 known alternate artifact probes
  - 0 artifact misses
  - 0 known POM failures
  - 0 request failures
  - 3716 checksum hits
  - 849 write-through cache hits
  - 1,921,502,568 bytes served in 72,571ms
- Post-PAX checks:
  - PAX `git status --short`: only `M build.gradle` (maintainer local proxy
    hook), no generated JSON or BUILD/WORKSPACE drift.
  - PAX `git diff --check`: passed.
  - `rg -n "localhost|127\\.0\\.0\\.1" WORKSPACE --glob '*maven_install.json'`:
    no matches; proxy URLs did not persist.
  - Grazel `reports/scripts/verify-pax-size-guard.sh --mode preserving`: passed,
    unchanged from baseline.
