# Item 38 - Local Maven Resolution Pin Integration

## 2026-07-01 checkpoint

- Scope in progress:
  - `experiments.localMavenResolution` remains default `false`.
  - Flag-on path should only hydrate/start the proxy when the existing pinner
    decides a repin is required; already-pinned skip path must not pay proxy
    setup cost.
  - The existing variant-owned root input planning remains the source of root
    configurations for proxy fact hydration. Do not rebuild classpath names or
    root selection in the pinner.
- Repository transfer decision:
  - PAX repository configuration must flow smoothly into the proxy through the
    existing Gradle repository data source and `RepositoryWithAuth` model.
  - The assumption is that because proxy facts and auth originate from Gradle's
    repository model, PAX-compatible repositories should be compatible with the
    proxy workflow. Verification must prove this; do not add PAX-specific
    repository hacks.
- Verification order decision:
  - Use the Grazel repo itself as the first flag-on proxy test before moving to
    full PAX verification.
  - After Grazel flag-on cold/changed pinning is green, run the PAX loop from
    its clean committed baseline.
- RJE hash reconstruction decision:
  - Because default `@maven` uses external Starlark variables such as
    `DAGGER_ARTIFACTS`, `GRAB_BAZEL_COMMON_ARTIFACTS`, and
    `DAGGER_REPOSITORIES`, Grazel should not try to fully re-derive every
    expanded declared artifact hash from its own model in this slice.
  - Keep RJE-produced per-artifact input hash entries from the localhost
    lockfile when artifacts are unchanged, recompute the repository input hash
    from canonical repository specs after URL rewrite, and fully recompute
    `__RESOLVED_ARTIFACTS_HASH` from the rewritten lockfile.
- Implemented so far:
  - Pure Kotlin `MavenInstallLockfileReconstructor` with tests for repository
    URL rewrite, repository input hash, resolved hash, dependency cycles, and
    no-op byte identity for checked-in lockfiles.
  - `MavenInstallWorkspaceRepositoryRewriter` that rewrites only quoted URLs in
    `repositories = [...]` blocks.
  - `LocalMavenPinningWorkspace` for canonical/proxy WORKSPACE swap and active
    lockfile reconstruction.
  - `DefaultArtifactPinner` now explicitly awaits the worker queue after pin
    script submission, which is required before reconstruction.
  - `LocalMavenProxyService.repositoryRewrite()` maps `/r/{index}/` URLs back
    to canonical Gradle repositories using the same repository order as the
    server route.
  - `PinMavenArtifactsTask` receives root configurations from
    `WorkspaceDependencyInputsRegistrar` and builds proxy facts lazily only for
    flag-on repin.
- Verification so far:
  - Red test observed for missing `LocalMavenPinningWorkspace`.
  - Focused green:
    `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.migrate.dependencies.DefaultArtifactPinnerTest.assert maven install json generation is successful" --tests "com.grab.grazel.migrate.dependencies.LocalMavenPinningWorkspaceTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallWorkspaceRepositoryRewriterTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallLockfileReconstructorTest" --console=plain --no-daemon`
- Forced proxy pin failure classification:
  - Forced the cold/changed pin path in the Grazel sample by temporarily
    enabling `experiments.localMavenResolution` in the root `build.gradle` and
    corrupting only the local `maven_install.json` signature. These two files
    are test mutations and must not be committed.
  - Last forced run:
    `./gradlew pinMavenArtifacts --console=plain --no-daemon --stacktrace`
    wrote `build/item38-debug/pin-after-closure-gavs.log` and failed during
    `bazelisk run @unpinned_*//:pin`.
  - Mechanical classification of unique HTTP 500 jar/AAR requests:
    71 unique artifact paths; 0 were present in
    `WorkspacePlan.repoPlan[*].pinInputs`; 0 were present in nested
    `ResolvedDependency.dependencies` strings; 61 already had matching files
    in `~/.gradle/caches/modules-2/files-2.1`; 10 were absent from the Gradle
    module cache, including `org.jetbrains.kotlinx:atomicfu:0.17.0`,
    `org.jetbrains.kotlinx:atomicfu-jvm:0.17.0`, and several Kotlin 1.9.24
    tooling artifacts.
  - Root cause: Item38 hydrated the proxy from `WorkspacePlan.pinInputs` plus
    nested dependency strings only. That is too narrow. `maven_install` may
    also include external Bazel tool artifacts (`DAGGER_ARTIFACTS`,
    `GRAB_BAZEL_COMMON_ARTIFACTS`) and Coursier may request POM-derived
    closure artifacts that are not Gradle root classpath components.
  - Decision: keep hard-fail semantics for missing **Gradle-known** component
    artifacts. For non-Gradle closure artifacts discovered by Coursier, the
    proxy may first look in Gradle's module cache by Maven path and then use a
    counted origin pass-through if the cache lacks the artifact. This is not a
    blanket fallback for Gradle misses; it is a classified gap between
    Gradle-resolved facts and Coursier-only closure needed to reproduce a
    vanilla lockfile.
- Remaining before Item38 can be called green:
  - Finish pinner validation/error handling against real flag-on repin.
  - Add/adjust focused tests for flag default and proxy wiring where practical.
  - Run Grazel flag-off generated-output gate.
  - Run Grazel flag-on cold/changed pinning first-level proxy test.
  - Run simplify-pass after the large Item38 slice is locally green.
  - Run PAX migrate/build/test from the clean committed baseline; do not commit
    PAX changes.

## 2026-07-01 forced proxy pin green checkpoint

- Root cause for the RJE signature mismatch:
  - RJE/Starlark reads JSON `null` shasums as Starlark `None`.
  - The first lockfile reconstructor pass fixed `repr(null)` but still read
    shasums with `jsonPrimitive.content`, converting JSON null to the literal
    string `"null"`.
  - That poisoned metadata/skipped artifacts such as
    `org.jetbrains.kotlin:kotlin-stdlib-common`; their bad resolved hashes then
    propagated into AndroidX/Compose/Lifecycle nodes and RJE rejected
    `android_test_maven_install.json`.
  - Fix: build `typeInfo["sha"]` with the same JSON-to-Starlark conversion used
    for other values, so JSON null remains Kotlin null and renders as Starlark
    `None`. Also align the manual RJE traversal with Bazel's documented
    `dict.popitem()` first-entry behavior.
- Reference used:
  - Inspected RJE's local `private/rules/v3_lock_file.bzl` and Bazel's public
    dict API docs. Current Bazel docs state `dict.popitem()` removes the first
    pair; do not port it as Kotlin `last()`.
- Regression coverage added:
  - `MavenInstallLockfileReconstructorTest` now covers null shasum hashes and a
    dependent artifact whose `dependency_hashes` must receive the corrected
    null-shasum hash.
- Forced Grazel proxy pin verification:
  - Restored lockfiles from `build/item38-lockfile-baseline`, temporarily
    enabled `experiments.localMavenResolution` in root `build.gradle`, and
    corrupted only `maven_install.json.__INPUT_ARTIFACTS_HASH.repositories` to
    force repinning.
  - Command passed:
    `./gradlew pinMavenArtifacts --console=plain --no-daemon --stacktrace`
    with log `build/item38-debug/pin-after-null-sha-fix.log`.
  - Summary from the successful run:
    `Local Maven resolution served 189 artifacts from Gradle index, 113 POMs
    from Gradle cache, 0 unknown metadata POMs from origin, 210 known alternate
    artifact misses, 0 artifact misses, in 19913ms`.
  - RJE accepted all reconstructed lockfiles; no `invalid signature` remained.
- Cleanup after forced run:
  - Restored all `*maven_install.json` files from
    `build/item38-lockfile-baseline`.
  - Removed the temporary root `build.gradle` flag-on mutation. The committed
    default remains flag-off.
- Focused verification after cleanup:
  - `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.migrate.dependencies.LocalMavenProxyServerTest" --tests "com.grab.grazel.migrate.dependencies.LocalMavenPinningWorkspaceTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallLockfileReconstructorTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallWorkspaceRepositoryRewriterTest" --tests "com.grab.grazel.tasks.internal.PinMavenArtifactsTaskTest" --console=plain --no-daemon`
    passed.
- Flag-off generated-output verification:
  - Resource checkpoint before local gate: about 34GiB free on
    `/System/Volumes/Data`; no stale Gradle/Bazel cleanup required.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed with the
    experiment defaulted off and pinning skipped as up to date.
  - Generated BUILD/WORKSPACE/json files had no diff after the run.
  - `git diff --check` passed.
- Broader local test:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed.
- Remaining:
  - Run simplify-pass for the Item38 slice.
  - Run PAX migrate/build/test from clean baseline; do not commit PAX.

## 2026-07-01 repository input signature fix

- Correction to the previous "forced proxy pin green" checkpoint:
  - The null-shasum fix was necessary, but the pin output still logged a POM
    packaging problem and later exposed an RJE `repositories` input-signature
    mismatch.
  - Root cause: `MavenInstallLockfileReconstructor` recomputed
    `__INPUT_ARTIFACTS_HASH.repositories` from lockfile output repository URL
    keys. RJE signs the original `repository_ctx.attr.repositories` list, which
    is the list of JSON repository input strings after Starlark evaluates
    external variables. The lockfile output map omits configured repositories
    that served no artifacts, so it is not a valid input-signature source.
- Reference evidence:
  - Inspected local rules_jvm_external source:
    `private/rules/coursier.bzl` computes
    `all_hashes["repositories"] = hash(repr(sorted(repositories)))`, where
    `repositories` is `repository_ctx.attr.repositories`.
  - `specs.bzl` serializes plain URL repositories as
    `{ "repo_url": "..." }`; Kotlin must mirror that exact JSON string shape
    before applying Java/Starlark string hash semantics.
- Fix:
  - Added a typed render sidecar:
    `build/grazel/maven/maven-install-repository-inputs.json`.
  - `GenerateRootBazelScriptsTask` writes repo name -> canonical RJE repository
    input strings from the exact `MavenInstallData` set used to render
    `WORKSPACE`.
  - `PinMavenArtifactsTask` reads the sidecar as a `RegularFileProperty`
    `@InputFile`; no JSON payload crosses configuration/task boundaries.
  - `LocalMavenPinningWorkspace` now reconstructs each active lockfile with
    the sidecar entries for that repo and fails closed if an entry is missing.
  - Dagger's external `DAGGER_REPOSITORIES` values are modeled explicitly for
    the hash input list; `GRAB_BAZEL_COMMON` contributes artifacts only.
- Regression coverage:
  - `MavenInstallLockfileReconstructorTest` now covers an unused configured
    repository affecting the repository hash even though it does not appear in
    the lockfile output repository map.
  - `LocalMavenPinningWorkspaceTest` now supplies sidecar repository inputs for
    active reconstruction.
- Verification:
  - Focused tests passed:
    `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.migrate.dependencies.MavenInstallLockfileReconstructorTest" --tests "com.grab.grazel.migrate.dependencies.LocalMavenPinningWorkspaceTest" --tests "com.grab.grazel.migrate.dependencies.DefaultArtifactPinnerTest" --console=plain --no-daemon`.
  - Forced local proxy pin passed after restoring saved lockfiles, temporarily
    enabling `experiments.localMavenResolution`, and corrupting only root
    `maven_install.json.__INPUT_ARTIFACTS_HASH.repositories`:
    `./gradlew pinMavenArtifacts --console=plain --no-daemon --stacktrace`.
  - RJE post-reconstruction validation accepted all materialized repos:
    `android_test_maven`, `debug_maven`, `ksp_maven`, `lint_maven`, `maven`,
    and `test_maven`.
  - Summary from the passing run:
    `Local Maven resolution served 189 artifacts from Gradle index, 113 POMs
    from Gradle cache, 0 unknown metadata POMs from origin, 210 known alternate
    artifact misses, in 21433ms`.
- Cleanup:
  - Restored all checked-in `*maven_install.json` files from
    `build/item38-lockfile-baseline`.
  - Removed the temporary root `build.gradle` experiment flag; default remains
    off.

## 2026-07-01 simplify-pass cleanup

- Four cleanup review agents completed for Item38: reuse, simplification,
  efficiency, and altitude.
- Applied safe cleanup without changing intended output:
  - Removed duplicated credential URL construction in `MavenRules`.
  - Removed production lockfile-reconstructor fallback that derived repository
    input specs from lockfile output repository keys. Production now requires
    the render sidecar for canonical RJE repository inputs.
  - Scoped proxy WORKSPACE mutation to `LocalMavenPinningWorkspace.withProxyRepositories`.
    Pin activation, pin script execution, reconstruction, and validation run
    after canonical WORKSPACE restoration.
  - Kept Gradle facts neutral by exposing `metadataOnlyGavs`; short-id
    translation now happens in the pin task.
  - Cached Gradle module-cache listings by Maven coordinates.
- Deferred with rationale:
  - Structured proxy rendering instead of regex WORKSPACE rewrite is a broader
    renderer/pinner seam refactor.
  - Upstream serialized local Maven facts are a broader task-boundary design.
  - RJE hash/rendering remains intentionally mirrored and guarded by tests plus
    live RJE validation.
- Verification:
  - `git diff --check` passed.
  - Focused tests passed:
    `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.migrate.dependencies.MavenInstallLockfileReconstructorTest" --tests "com.grab.grazel.migrate.dependencies.LocalMavenPinningWorkspaceTest" --tests "com.grab.grazel.migrate.dependencies.DefaultArtifactPinnerTest" --tests "com.grab.grazel.gradle.dependencies.LocalMavenResolvedFactsTest" --console=plain --no-daemon`.

## 2026-07-01 post-cleanup local verification

- Default-off gate:
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed.
  - Generated BUILD/WORKSPACE/json/downloader/databinding diff stayed empty.
  - `git diff --check` passed.
- Forced flag-on proxy gate:
  - Temporarily enabled `experiments.localMavenResolution`.
  - Corrupted root `maven_install.json.__INPUT_ARTIFACTS_HASH.repositories`.
  - `./gradlew pinMavenArtifacts --console=plain --no-daemon --stacktrace`
    passed.
  - RJE accepted reconstructed lockfiles after scoped proxy restoration.
  - Summary:
    `Local Maven resolution served 189 artifacts from Gradle index, 113 POMs
    from Gradle cache, 0 unknown metadata POMs from origin, 210 known alternate
    artifact misses, in 24639ms`.
  - Restored root `build.gradle` and all checked-in lockfiles from
    `build/item38-lockfile-baseline`.
  - Generated-output diff is empty after cleanup; `git diff --check` passed.

## 2026-07-01 broader local unit gate

- `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed.
- Output included expected existing noisy pinner and configuration-resolution
  test logs; no failures.

## 2026-07-01 PAX migrate gate

- Baseline:
  - PAX repo: `/Users/arun.sampathkumar/work/pax-android`.
  - Branch: `arun/grazel-refactor`.
  - Commit: `d4105d1f64bd2f1930e1030e42647a214002c48d`.
  - Worktree was clean before migrate.
- Command:
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
- Result:
  - Passed in 13m 3s.
  - Pinning skipped as up to date.
  - Representative timings:
    - declared metadata: 2327 projects / 2327 shards in 731ms.
    - workspace dependency resolution: 496 deps / 2451 roots in 23800ms.
    - target tag plan: 17090 targets in 19594ms.
    - variant compression: 2096 projects in 56660ms.
    - target Maven repo references: 2327 modules in 38402ms.
  - PAX `git status --short`, `git diff --check`, and `git diff --stat` were
    empty after migrate. Baseline did not move.
- Disk/resource action:
  - Post-migrate disk dropped to about 11GiB free.
  - Ran PAX `./bazel.sh shutdown` and `./bazel.sh clean --expunge`.
  - Free space remained low because stale temporary Bazel output bases were
    still present.
  - Stopped stale temporary JUnit Bazel servers, shut down Grazel Bazel, and
    removed the stale private Bazel output root.
  - Final checkpoint: about 73GiB free; private Bazel root about 294M; PAX
    `bazel-cache` preserved.
- Next:
  - PAX APK build gate.
  - PAX focused Bazel test gate.

## 2026-07-01 PAX APK build gate

- Command:
  - `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
- Result:
  - Passed.
  - First attempt hit a transient remote-cache missing blob for
    `food-root-gps-pax-debug-stubs_r.srcjar`.
  - The PAX wrapper retried automatically and the retry completed
    successfully.
  - Successful invocation: 471.860s elapsed, 50452 total actions, 42091 disk
    cache hits, 1634 remote cache hits.
- Post-build:
  - PAX `git status --short` empty.
  - PAX `git diff --check` passed.
  - Disk: about 75GiB free; private Bazel root about 4.0G.
- Next:
  - Focused PAX Bazel tests.

## 2026-07-01 PAX focused Bazel test gate

- Command:
  - `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test`
- Result:
  - Passed.
  - `Executed 0 out of 3 tests: 3 tests pass`.
  - 20.232s elapsed, 11701 total actions, 9857 disk cache hits.
- Post-test:
  - PAX `git status --short` empty.
  - PAX `git diff --check` passed.
  - Disk remained healthy: about 75GiB free; private Bazel root about 4.0G.
- PAX verification summary:
  - `migrateToBazel` passed and generated no diff against the committed PAX
    baseline.
  - Debug APK + android-test APK build passed after wrapper retry of a
    transient remote-cache missing blob.
  - Focused Bazel test targets passed.

## 2026-07-01 final local guards

- `git diff --check` passed.
- `git diff --check master...HEAD` passed.
- `reports/scripts/verify-default-task-graph.sh` passed.
- `reports/scripts/verify-sample-bucket-labels.sh` failed only on the known
  pre-existing appcompat/constraintlayout exclude-union waiver:
  `WORKSPACE must not union one-sided appcompat exclude onto androidx.constraintlayout:constraintlayout`.
  The tracked `HEAD:WORKSPACE` already has this block and this waiver is
  documented in the broader execution logs/review guide.
- `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed:
  11 buckets, 11 pinfiles, 1945 artifact roots; no per-repo artifact root
  deltas.

## 2026-07-01 lockfile-only artifact fallback follow-up

- Maintainer constraint:
  - Do not infer RJE semantics from generated output alone. Bazel/Starlark
    repository rules are implemented by Java-backed functions, so any mirrored
    hash/path behavior must be grounded in Bazel/RJE source.
  - RJE source inspection remains the authority for this slice:
    `private/rules/coursier.bzl`, `V3LockFile.java`, `Coordinates.java`, and
    `StarlarkRepr.java`.
- Failure classification after forced local proxy repin:
  - A scoped audit found 28 unique concrete proxy `500` paths.
  - All 28 were already represented in the active checked-in
    `maven_install.json` lockfiles.
  - None were direct `workspace-plan.json` pin inputs; 9 were only present as
    legacy `jetifierSource` metadata.
  - This is not a broad missing-Gradle-facts problem and not the earlier
    `collection-ktx` transitive closure issue. These are exact lockfile replay
    artifacts needed when RJE/Coursier reconstructs from existing lockfiles.
- Design decision:
  - Keep the proxy strict. Do not add broad origin fallback for arbitrary
    concrete artifacts.
  - The pinning layer reads exact artifact paths from the active materialized
    RJE lockfiles and passes only those paths as an allow-list to the proxy.
  - The proxy may fetch/cache those exact allow-listed paths from origin; all
    other concrete artifact misses remain hard failures.
  - For lockfile entries with POM packaging, also allow the corresponding jar
    probe path because Coursier probes it during reconstruction even when the
    active lockfile represents the component as `:pom`.
- Implementation:
  - Added `MavenInstallLockfileArtifactPaths.kt` to parse active RJE v3
    lockfiles and reproduce RJE repository paths for jar, sources, aar, and
    POM-packaging jar probes.
  - Added `LocalMavenResolvedFacts.allowedOriginArtifactPaths`.
  - `PinMavenArtifactsTask` builds the allow-list from active lockfiles for
    the repos it is pinning and keeps lockfile knowledge in the pinning layer.
  - `LocalMavenProxyServer` records lockfile fallbacks separately from normal
    artifact-index hits, metadata-only fallbacks, alternate-artifact probes,
    and unknown artifact misses.
- Verification:
  - Focused tests passed:
    `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.migrate.dependencies.LocalMavenProxyServerTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallLockfileArtifactPathsTest" --tests "com.grab.grazel.gradle.dependencies.WorkspacePlanBuilderTest.repo plan includes transitive closure of default-owned carriers for coursier version forcing" --console=plain --no-daemon`.
  - Added red-then-green coverage for exact lockfile artifact fallback and
    POM-packaging jar probe path derivation.
  - Forced local proxy migrate passed:
    `./gradlew migrateToBazel --console=plain --no-daemon --stacktrace --rerun-tasks`.
  - Passing local proxy summary:
    `Local Maven resolution served 157 artifacts from Gradle index, 112 POMs
    from Gradle cache, 0 origin fallbacks, 18 lockfile artifact fallbacks, 23
    metadata-only artifact fallbacks, 153 known alternate artifact probes, 0
    artifact misses, 538305816 bytes served, in 21171ms`.
  - No proxy `500` lines were present in the forced migrate log.
- Current cleanup state:
  - Temporary root `build.gradle` experiment flag is removed.
  - Temporary root `maven_install.json.__INPUT_ARTIFACTS_HASH.repositories = 0`
    mutation is restored to the normal value.
  - Remaining local diff still includes source/tests plus generated Grazel
    outputs from migration runs; generated-output diff still needs final
    classification before commit.

## 2026-07-01 merged-origin lockfile artifact fix

- Additional failure found during Grazel Bazel verification:
  - The first lockfile allow-list implementation let root `maven_install.json`
    regenerate with `jar: null` and `skipped` entries for Kotlin artifacts such
    as `org.jetbrains.kotlin:kotlin-parcelize-runtime`.
  - `bazelisk build //... --remote_download_outputs=all` then failed to compile
    `sample-android-library` because `kotlinx.parcelize.Parcelize` package
    metadata was missing from the regenerated lockfile.
- Source-grounded root cause:
  - RJE `V3LockFile.java` records `skipped` when a resolved artifact has no
    downloaded file or SHA, and `coursier.bzl` skips `http_file` generation for
    those entries.
  - The proxy serves Gradle-cached POMs independent of requested repo index.
    Coursier can therefore accept a module from `/r/0`, request the jar from
    `/r/0`, and record `jar: null` when repo 0 lacks the jar even though a later
    configured repository has it.
- Fix:
  - Exact active-lockfile artifact requests now use merged-origin lookup:
    try the requested origin first, then the remaining configured origins in
    deterministic order.
  - This merged lookup is restricted to exact active lockfile paths. Unknown
    concrete artifact paths still hard fail, alternate artifact probes still
    return 404, and all-origin misses for active lockfile paths now return 500
    instead of allowing RJE to write a null-sha/skipped lockfile.
- Focused regression coverage:
  - `LocalMavenProxyServerTest` covers repo-0 miss/repo-1 hit for an active
    lockfile parcelize-style jar.
  - `LocalMavenProxyServerTest` covers fail-closed behavior when every origin
    misses an active lockfile artifact.
- Verification:
  - `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.migrate.dependencies.LocalMavenProxyServerTest" --console=plain --no-daemon`
    passed.
  - Forced local proxy migrate passed after temporary local-only enablement:
    `./gradlew migrateToBazel --console=plain --no-daemon --stacktrace --rerun-tasks`.
  - Passing local proxy summary after this fix:
    `Local Maven resolution served 157 artifacts from Gradle index, 112 POMs
    from Gradle cache, 13 origin fallbacks, 44 lockfile artifact fallbacks, 10
    metadata-only artifact fallbacks, 153 known alternate artifact probes, 0
    artifact misses, 732421863 bytes served, in 22592ms`.
  - Active generated lockfiles after forced migrate:
    `maven_install.json`, `debug_maven_install.json`, and
    `android_test_maven_install.json` each have `nil_sha=0` and `skipped=0`.
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed.
  - Default-off `./gradlew migrateToBazel --console=plain --no-daemon` passed
    and skipped pinning as up-to-date.
  - `bazelisk build //... --verbose_failures --remote_download_outputs=all`
    passed.
  - Default `bazelisk build //... --verbose_failures` still fails on the known
    remote-output materialization issue opening a symlinked manifest under
    `bazel-out`; this is not a dependency/classpath failure.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed:
    11 buckets, 11 pinfiles, 1945 artifact roots; no PAX baseline deltas.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails on the known
    pre-existing appcompat/constraintlayout one-sided exclude-union waiver.

## 2026-07-01 default-output regression guard correction

- Regression caught while revalidating PAX against the committed
  `arun/grazel-refactor` baseline:
  - Default PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
    passed, but produced generated diffs in `WORKSPACE`,
    `debug_maven_install.json`, `android_test_maven_install.json`,
    `hms_maven_install.json`, and `test_maven_install.json`.
  - This violated the Item 38 hard invariant: `experiments.localMavenResolution`
    is off by default, so flag-off output must be byte-identical to the
    pre-work PAX baseline.
- Root cause:
  - The fixed-point closure expansion in `MavenInstallRootArtifacts.kt` leaked
    into normal `maven_install.artifacts` generation.
  - That added more artifact roots to repos even when the proxy experiment was
    disabled. The change was not acceptable as a default-output side effect.
- Fix:
  - Restored `MavenInstallRootArtifacts.kt` to the previous non-fixed-point
    root expansion.
  - Removed the `WorkspacePlanBuilderTest` that encoded the leaked
    closure-forcing behavior.
  - Kept the local Maven proxy work constrained to proxy serving and
    lockfile-path fallback behavior; do not change default artifact roots as a
    side effect of Item 38.
- Verification after fix:
  - Focused tests passed:
    `WorkspacePlanBuilderTest`, `LocalMavenProxyServerTest`, and
    `MavenInstallLockfileArtifactPathsTest`.
  - Grazel `./gradlew migrateToBazel --console=plain --no-daemon` passed and
    left generated outputs clean.
  - PAX default
    `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
    passed in 13m 40s and PAX `git status --short`, `git diff --stat`, and
    `git diff --check` were all clean.
- Current gate status:
  - Default/flag-off baseline is clean again.
  - PAX default APK build passed after the correction:
    `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
    completed successfully in 217.132s.
  - PAX focused Bazel tests passed after the correction:
    `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test`
    completed successfully in 16.364s; 3 test targets passed from cache.
  - PAX `git status --short` and `git diff --check` stayed clean after both
    Bazel gates.
  - Forced Grazel proxy repin was rerun after this correction by temporarily
    enabling `experiments.localMavenResolution` and corrupting only
    `maven_install.json.__INPUT_ARTIFACTS_HASH.repositories`.
  - Command passed:
    `./gradlew pinMavenArtifacts --console=plain --no-daemon --stacktrace`.
  - Passing proxy summary:
    `Local Maven resolution served 156 artifacts from Gradle index, 110 POMs
    from Gradle cache, 0 origin fallbacks, 44 lockfile artifact fallbacks,
    9 metadata-only artifact fallbacks, 153 known alternate artifact probes,
    0 artifact misses, 732456897 bytes served, in 19442ms`.
  - Cleanup after forced repin removed the temporary experiment flag; generated
    `WORKSPACE` and `*_maven_install.json` outputs remained clean.
  - Continue with PAX proxy-specific flag-on validation.

## 2026-07-01 current regression guard

- Baseline rule for the next debugging slice:
  - The already-working default path is the committed PAX baseline at
    `/Users/arun.sampathkumar/work/pax-android` branch
    `arun/grazel-refactor`, commit
    `d4105d1f64bd2f1930e1030e42647a214002c48d`.
  - Do not accept any flag-off generated-output diff, PAX build regression, or
    PAX focused-test regression while debugging the Item 38 flag-on proxy
    workflow.
- Fresh status check:
  - Grazel branch `arun/dependencies-refactor` is at `2cbac7d` with only the
    Item 38 proxy experiment diff plus this log update.
  - PAX branch `arun/grazel-refactor` is clean at
    `d4105d1f64bd2f1930e1030e42647a214002c48d`.
- Current open issue remains isolated to the experiment path:
  - PAX flag-on forced proxy migrate reaches `:pinMavenArtifacts` and fails in
    root `maven` on the `com.grab.rtc:sinch:6.25.8` jar/aar dual-coordinate
    case.
  - Earlier active-lockfile alternate-probe failures for
    `hyperdocdetect`, `androidx.preference:preference`,
    `androidx.compose:compose-bom`, and
    `androidx.constraintlayout:constraintlayout` are fixed.

## 2026-07-01 repository-input hook decision

- Regression guard rechecked before continuing:
  - Grazel branch `arun/dependencies-refactor` is at `2cbac7d` with only the
    Item 38 proxy experiment diff and logs dirty.
  - PAX branch `arun/grazel-refactor` is clean at baseline
    `d4105d1f64bd2f1930e1030e42647a214002c48d`.
- PAX flag-on forced proxy migrate after the null-shasum/non-POM lockfile path
  fix no longer fails on the earlier `com.grab.rtc:sinch:6.25.8` HTTP 500.
  The proxy served the POM, AAR, and JAR successfully.
- New isolated blocker:
  - RJE rejects the reconstructed root `maven_install.json` with repository
    input hash mismatch: actual reconstructed hash `-1395933409` vs expected
    PAX final WORKSPACE hash `-2080637180`.
  - `build/grazel/maven/maven-install-repository-inputs.json` still records
    the default `maven` repo inputs including `DAGGER_REPOSITORIES`
    (`maven.google.com`, Sonatype snapshots, Maven Central).
  - PAX build logic later removes `+ DAGGER_REPOSITORIES` from final
    `WORKSPACE`, so final RJE attributes contain only the two PAX configured
    repositories. The sidecar and final WORKSPACE disagree.
- Altitude decision from maintainer discussion:
  - Do not parse final generated `WORKSPACE` to rediscover repository inputs.
  - Do not hardcode a PAX workaround in Grazel's pinner.
  - Expose a typed customer-side hook in the Maven install model so customers
    can intentionally omit external repository variables for a named
    `maven_install` repo. The same model must feed both WORKSPACE rendering and
    `maven-install-repository-inputs.json`.
  - PAX can then remove its post-generation `+ DAGGER_REPOSITORIES` string edit
    or make it a no-op by declaring the hook. This preserves one source of
    truth for RJE repository input signatures.

## 2026-07-01 customer hook + lockfile baseline-preservation slice

- Implemented model-level hook:
  - `MavenInstallExtension.excludeExternalRepositoryVariables(repoName, vararg variableNames)`
    records external repository variable omissions by `maven_install` repo name.
  - `MavenInstallArtifactsCalculator` filters those variables while building
    `MavenInstallData`, so both WORKSPACE rendering and repository-input
    sidecar generation consume the same model.
  - This is the intended customer-side replacement for post-generation
    WORKSPACE string surgery.
- Focused hook coverage:
  - `maven install can omit external repository variables for a named repo`.
  - `external repository variable omissions are scoped by repo name`.
- PAX flag-on forced migrate with the temporary hook:
  - The earlier RJE repository-input signature mismatch moved forward.
  - Root `maven` sidecar now held only the two PAX repositories:
    `mobile--android` and `dl.anagog.com`.
  - The run exposed a lockfile URL reconstruction bug: canonical artifact URLs
    were joined without a slash.
- Lockfile URL fix:
  - `MavenInstallLockfileReconstructor` now canonicalizes lockfile repository
    URL prefixes with exactly one trailing slash before appending artifact
    paths.
  - Regression test added for canonical lockfile repository URLs.
- Second PAX flag-on forced migrate after the slash fix:
  - Command passed.
  - No `127.0.0.1`/`localhost` residue was found in WORKSPACE or active
    lockfiles.
  - In-flow validation probes passed.
  - Proxy summary: `788` Gradle artifact hits, `808` Gradle POM hits, `0`
    origin fallbacks, `97` lockfile artifact fallbacks, `0` metadata-only
    artifact fallbacks, `1710` known alternate artifact probes, `0` artifact
    misses, `1921031937` bytes served, `112517ms`.
  - Remaining diff was byte-level lockfile metadata drift, not unresolved
    artifacts: one `com.google.guava:guava` shasum changed across root/debug/
    lint/android_test lockfiles; `androidx.compose:compose-bom:pom` appeared in
    `skipped`; resolved input hashes rippled from those facts.
- Baseline-preservation fix:
  - `DefaultArtifactPinner` snapshots active lockfiles before invoking RJE.
  - `LocalMavenPinningWorkspace` passes those snapshots into reconstruction.
  - `MavenInstallLockfileReconstructor` preserves baseline shasums for
    unchanged artifact facts and preserves baseline skipped entries. New
    POM-packaging skips are still added only when there is no matching baseline
    artifact.
  - Focused tests cover unchanged shasum preservation and skipped-entry
    preservation.
- Verification after baseline-preservation code:
  - Focused reconstructor/workspace/pinner tests passed.
  - Grazel default `migrateToBazel` was rerun and generated outputs stayed
    clean.
  - `git diff --check` passed.
- Next required check:
  - Re-run PAX forced local proxy migrate with the temporary hook and
    experiment flag. The gate is byte-clean generated lockfiles, no localhost
    residue, zero artifact misses, and PAX restored clean afterward.

## 2026-07-01 PAX forced proxy rerun timing notes

- Run in progress:
  - Command:
    `cd /Users/arun.sampathkumar/work/pax-android && ./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`.
  - Temporary local PAX edits: enabled `experiments.localMavenResolution`,
    added `excludeExternalRepositoryVariables("maven", "DAGGER_REPOSITORIES")`,
    and corrupted root `maven_install.json` repository hash to force repin.
- Timing observations so far:
  - Gradle/PAX configuration reached declared-metadata shard execution after
    roughly 5-6 minutes wall-clock in this run.
  - `mergeDeclaredDependencyMetadata` reported:
    `Collected declared dependency metadata for 2327 projects across 2327 shards
    in 683ms (35247531 bytes, mode PROJECT_TASK_FANOUT)`.
  - The run then advanced through patch syncs into `:resolveWorkspaceDependencies`.
  - `resolveWorkspaceDependencies` reported:
    `Resolved 496 deps across 2451 roots in 27171ms`.
  - The run advanced into `:computeWorkspaceDependencies` and
    `:computeWorkspacePlan`.
- Still pending:
  - Record `pinMavenArtifacts`, local proxy summary, final migrate wall-clock,
    generated diff shape, and PAX cleanup status.

## 2026-07-01 PAX forced proxy rerun result

- Result:
  - Forced PAX proxy migrate completed successfully:
    `BUILD SUCCESSFUL in 12m 45s`, `4749 actionable tasks: 4749 executed`.
  - Local Maven proxy summary:
    `Local Maven resolution served 788 artifacts from Gradle index, 808 POMs
    from Gradle cache, 0 origin fallbacks, 97 lockfile artifact fallbacks,
    0 metadata-only artifact fallbacks, 1710 known alternate artifact probes,
    0 artifact misses, 1921031937 bytes served, in 108703ms`.
- Generated-output check before cleanup:
  - `git status --short` showed only `M build.gradle`.
  - `git diff --stat` showed only the two temporary local PAX config lines.
  - `rg -n "127\\.0\\.0\\.1|localhost" WORKSPACE *maven_install.json *_maven_install.json`
    returned no matches.
  - `git diff --check` returned no issues.
- Interpretation:
  - The customer hook fixed the repository-input signature mismatch without
    generated-WORKSPACE feedback.
  - Baseline lockfile preservation removed the remaining byte-level shasum/
    skipped drift; active lockfiles are byte-clean against the committed PAX
    baseline after forced proxy repin.
- Still pending:
  - Restore the temporary PAX config and run the final PAX Bazel build/test
    gates against the clean committed baseline.

## 2026-07-01 PAX final Bazel gates

- PAX cleanup after forced proxy migrate:
  - Restored temporary `build.gradle` changes.
  - PAX `git status --short` and `git diff --check` returned no output before
    the Bazel build gate.
- APK build gate:
  - Command:
    `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`.
  - Result:
    build completed successfully in `214.653s`; Bazel reported `1 total
    action`.
- Still pending:
  - Run focused PAX Bazel tests and final PAX cleanliness checks.

## 2026-07-01 PAX final test and cleanliness result

- Focused Bazel test gate:
  - Command:
    `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test`.
  - Result:
    `Build completed successfully`, `Executed 0 out of 3 tests: 3 tests pass`,
    elapsed `17.093s`.
- Final PAX cleanliness:
  - `git status --short` returned no output.
  - `git diff --check` returned no output.
  - `rg -n "127\\.0\\.0\\.1|localhost" WORKSPACE *maven_install.json *_maven_install.json`
    returned no matches.
- PAX status:
  - The forced local-proxy migrate, APK build, focused tests, no-localhost
    check, and clean-worktree guard all passed against the committed PAX
    baseline without committing any PAX changes.

## 2026-07-01 Grazel local gates before final review

- Generated-output and whitespace guards:
  - No generated `BUILD.bazel`/`WORKSPACE`/maven-install output diffs.
  - `git diff --check` and `git diff --check master...HEAD` passed.
- Script gates:
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed with
    all counts unchanged from PAX baseline:
    bucketCount `11`, pinfileCount `11`, totalArtifactRoots `1945`.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails on the known
    pre-existing appcompat/constraintlayout exclude-union waiver.
- Gradle gates:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed
    in `43s`.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed in `9s`.
  - Local migrate timing highlights:
    `Resolved 45 deps across 54 roots in 108ms`; target tags `32 targets in
    66ms`; variant compression `2 projects in 61ms`; target references
    `10 modules in 158ms`.
- Review gate in progress:
  - Four simplify-pass review agents and one adversarial review agent are
    running. Real findings must be fixed or rejected with evidence before
    final status.

## 2026-07-02 post-review hardening

- Fixed adversarial correctness findings:
  - Removed arbitrary request-time Gradle cache artifact serving. The proxy no
    longer accepts `MavenArtifactFileResolver`; all concrete artifact hits must
    be in the explicit artifact index or a declared fallback class.
  - Changed lockfile reconstruction from blind baseline preservation to
    fail-closed validation:
    - same artifact metadata + changed shasums now fails;
    - current skipped entries that were baseline artifacts now fail.
  - Added credential-aware repository rewrite:
    - temporary `WORKSPACE` rewrite uses canonical aliases, including
      credentialed Basic-auth URLs;
    - final lockfile reconstruction restores the exact canonical URL form from
      generated repository input specs.
- Fixed style/altitude cleanup in the touched proxy files:
  - Removed policy-heavy receiver helpers from the proxy server, pinning
    workspace, lockfile path extractor, and reconstructor.
  - Reused `MavenCoordinates.mavenRelativePaths` for lockfile artifact path
    derivation instead of hand-rolled Maven path strings.
- Focused tests passed:
  - `LocalMavenProxyServiceTest`
  - `LocalMavenPinningWorkspaceTest`
  - `MavenInstallLockfileArtifactPathsTest`
  - `MavenInstallLockfileReconstructorTest`
  - `LocalMavenProxyServerTest`
  - `MavenInstallArtifactsCalculatorTest`
  - Command elapsed about `23s`.
- Known future architecture debt not fixed in this slice:
  - Temporary proxy still mutates rendered `WORKSPACE` text and metadata-only
    `override_targets`; a fully typed temporary render path is a future spec.
  - Active lockfile artifact fallback still reads existing lockfiles by design
    as a compatibility fallback. It is now fail-closed for shasum/skipped drift,
    but replacing it with typed Gradle facts is future architecture work.
- Next:
  - Run PAX forced local-proxy migrate again. The stricter reconstruction may
    expose real shasum/skipped drift; if it fails, debug the artifact source
    rather than restoring blind baseline preservation.

## 2026-07-02 forced proxy rerun after review hardening

- First rerun with stricter reconstruction failed in `pinMavenArtifacts`:
  - Symptom:
    `Local Maven reconstruction skipped artifacts that existed in the baseline: com.grab.rtc:sinch`.
  - Evidence:
    PAX proxy log showed requests and successful downloads for
    `com/grab/rtc/sinch/6.25.8/sinch-6.25.8.pom`,
    `.aar`, `.jar`, `.pom.md5/.sha1`, `.aar.md5/.sha1`, and `.jar.md5/.sha1`.
  - Root cause:
    rules_jvm_external emits the plain `com.grab.rtc:sinch` coordinate with
    `shasums.jar = null` and also lists it in `skipped`, while the concrete
    artifact is `com.grab.rtc:sinch:aar`. The guard confused "skipped but has a
    current artifact record" with "missing from current artifacts."
- Fix:
  - `MavenInstallLockfileReconstructor` now fails only for skipped baseline
    artifacts that are absent from the current `artifacts` map.
  - Present skipped artifact records still go through normal metadata/shasum
    validation.
  - Added a focused regression test for the skipped/null plain coordinate plus
    concrete `:aar` companion shape.
- Verification:
  - `MavenInstallLockfileReconstructorTest` passed in `20s`.
  - Full forced PAX local-proxy migrate passed after the fix:
    `BUILD SUCCESSFUL in 12m 10s`, `4749 actionable tasks: 4749 executed`.
  - Proxy summary:
    `761 artifacts from Gradle index`,
    `808 POMs from Gradle cache`,
    `0 origin fallbacks`,
    `123 lockfile artifact fallbacks`,
    `0 metadata-only artifact fallbacks`,
    `1713 known alternate artifact probes`,
    `0 artifact misses`,
    `1921023543 bytes served`,
    `97636ms`.
  - Tracked PAX files were restored to the committed baseline after the forced
    proxy experiment; `git status --short` and `git diff --check` returned no
    output, and no localhost residue was found in tracked generated files.
- Next:
  - Run normal PAX migrate/build/test gates from the clean baseline.
  - Re-run simplify/adversarial review after the post-review fixes.

## 2026-07-02 clean-baseline PAX guard

- Normal PAX migrate from restored committed baseline passed:
  `BUILD SUCCESSFUL in 9m 17s`, `4749 actionable tasks: 4749 executed`.
- APK build guard passed in `232.395s`:
  `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`.
- Focused PAX test guard passed:
  `Executed 0 out of 3 tests: 3 tests pass`.
- Cleanliness checks:
  `git status --short`, `git diff --check`, and localhost/proxy residue scan over
  tracked `WORKSPACE`/maven-install JSON files were clean/no-match.
- Next:
  - Run Grazel local gates.
  - Run final proxy altitude/style scan plus simplify/adversarial review.

## 2026-07-02 local Grazel guard

- `:grazel-gradle-plugin:test` passed in `45s`.
- Local `migrateToBazel` passed in `14s`; timing lines remained small:
  declared metadata merge `16ms`, workspace dependency resolution `90ms`,
  target tag collection `76ms`, variant compression `66ms`, target reference
  collection `147ms`.
- `verify-default-task-graph.sh`, `verify-pax-size-guard.sh --mode preserving`,
  `git diff --check`, and `git diff --check master...HEAD` passed.
- `verify-sample-bucket-labels.sh` still fails with the known appcompat/
  constraintlayout exclude warning; not introduced by this proxy slice.
- Next:
  - Final proxy altitude/style scan.
  - Final simplify/adversarial review and any needed post-fix verification.

## 2026-07-02 altitude review fixes

- Accepted review fixes:
  - POMs are now precomputed into `LocalMavenResolvedFacts.pomIndex`; the server
    no longer invokes Gradle/cache callbacks on request.
  - Lockfile artifact fallback fetches from the requested origin only.
  - Known Gradle concrete artifact misses fail before metadata-only or active
    lockfile fallback can run.
  - Repository input transport now carries typed canonical URLs; the pinner no
    longer parses repository-input JSON to recover `repo_url`, and external
    repository URLs become proxy origins.
  - Remaining hidden receiver helpers in the local Maven facts path were removed.
- Focused proxy/facts tests passed in `17s`.
- Still pending:
  - Local generation and PAX guard reruns after the altitude fixes.
  - Final simplify/adversarial review.

## 2026-07-01 proxy altitude/style review follow-up

- Subagent review findings triaged:
  - Accepted and fixed:
    - Moved Maven repository path value types (`MavenPath`, `MavenCoordinates`,
      concrete artifact path classification) from `gradle.dependencies` to
      neutral `com.grab.grazel.maven`; proxy/lockfile code no longer imports
      Maven path parsing from the Gradle facts package.
    - Replaced the lockfile hash reconstruction's raw `Map<String, Any?>` /
      `Any?` Starlark representation with a sealed `StarlarkValue` model.
    - Scoped metadata-only `override_targets` removal to actual
      `override_targets` blocks instead of any matching string-keyed line.
    - Renamed the repository proxy temporary carrier from `Input` to `Plan`.
    - Added focused tests for metadata-only checksum serving and active lockfile
      repo filtering.
    - Corrected metadata-only classification so only Gradle known components,
      not configured extra override artifacts, can become metadata-only.
  - Deferred/kept with rationale:
    - Eager `pomIndex` construction stays for this slice. Earlier post-review
      altitude fix intentionally removed live Gradle/cache callbacks from the
      HTTP request path; the task action now snapshots POM facts before serving.
      Revisit only if timing shows POM indexing is material.
    - Active lockfile artifact fallback remains compatibility debt for the
      remaining lockfile-only artifacts, but is bounded to active repos, exact
      paths, non-known concrete artifacts, and the requested proxy origin.
    - Baseline lockfile reconstruction remains part of the pinning guard for
      rules_jvm_external skipped/shasum preservation until a pure current-output
      model is proven against PAX.
    - Public external repository exclusion DSL remains because PAX needs a
      customer-side hook for variable-backed repository bundles; avoid PAX-only
      hardcoding.
- Focused verification:
  - `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.LocalMavenResolvedFactsTest" --tests "com.grab.grazel.migrate.dependencies.LocalMavenProxyServerTest" --tests "com.grab.grazel.migrate.dependencies.LocalMavenPinningWorkspaceTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallLockfileReconstructorTest" --tests "com.grab.grazel.migrate.dependencies.MavenInstallLockfileArtifactPathsTest" --tests "com.grab.grazel.gradle.dependencies.LocalMavenProxyServiceTest" --console=plain --no-daemon`
    passed in `14s`.
- Still pending:
  - Re-run forced PAX local-proxy repin after the latest altitude/style fixes.
  - Restore PAX temp experiment/lockfile perturbations after that run.
  - Then run normal PAX and Grazel gates.

## 2026-07-02 proxy contract correction after forced PAX failure

- Forced PAX repin initially failed in `:pinMavenArtifacts` with local proxy
  `HTTP 500` responses for lockfile-only concrete artifacts such as
  `androidx.benchmark:benchmark-junit4`,
  `com.grab.cfm.analytics_kit:analytics_kit_mock`, `org.mockito:mockito-inline`,
  `app.cash.turbine:turbine-jvm`, `com.squareup.okhttp3:mockwebserver`,
  `com.google.truth:truth`, `com.android.tools.lint:lint-tests`, and
  `com.grab.cfm.log_kit:log_kit_mock`.
- Root cause: `knownArtifactGavs` conflated "pinnable root/final artifact" with
  "Gradle-resolved concrete component artifact". That made exact active-lockfile
  compatibility paths fail before the bounded lockfile fallback could serve
  non-root pinnable artifacts.
- Fix:
  - Removed `knownArtifactGavs` from `LocalMavenResolvedFacts` and proxy config.
  - Concrete artifact hard-fail now uses `knownComponentGavs` only, so known
    Gradle components still fail closed when their concrete artifact is missing.
  - Non-root pinnable artifacts remain fail-closed unless their exact active
    lockfile path authorizes the bounded requested-origin fallback.
  - Added/updated focused proxy tests for this contract, and named the local
    Maven pinner factory seam as `LocalMavenResolutionPinContextFactory`.
  - Reworked metadata-only override-target filtering into an explicit scanner
    instead of mutating parser state inside a collection predicate.
- Verification:
  - Focused proxy/facts/pinner suite passed in `20s` before the final scanner
    cleanup and `17s` after it.
  - Forced PAX local-proxy repin passed:
    `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
    with temp `experiments.localMavenResolution=true` and intentionally
    perturbed lockfile repository hashes.
  - PAX forced-run timing/result:
    - declared metadata fanout: `2327` projects, `2327` shards, `622ms`;
    - root dependency resolution: `496` deps across `2451` roots in `25296ms`;
    - target tag collection: `17090` targets in `17399ms`;
    - local Maven proxy: `762` Gradle-index artifact hits, `808` Gradle-index
      POM hits, `0` origin fallbacks, `71` lockfile artifact fallbacks, `52`
      metadata-only artifact fallbacks, `1710` alternate artifact probes,
      `0` artifact misses, `1921510632` bytes, `111930ms`;
    - `BUILD SUCCESSFUL in 12m 55s`.
  - PAX temp `build.gradle` and maven-install JSON perturbations were restored;
    `git status --short` in PAX is clean.
- Remaining:
  - Run normal PAX baseline migrate/build/test gates.
  - Run broad Grazel gates after final altitude/style fixes.
  - Run final simplify/adversarial review.

## 2026-07-02 - proxy altitude/style mapping after adversarial fixes

- Altitude map:
  - Gradle dependency layer owns local Maven facts, Gradle cache/POM indexing,
    proxy lifecycle, repository auth translation, proxy origins, and proxy
    server request handling.
  - Task layer owns orchestration: reading workspace/repository-input files,
    building local Maven facts from root configurations, computing active
    lockfile compatibility paths, configuring the proxy, and adapting neutral
    proxy URL mappings into pinner rewrite data.
  - Pinner/migrate layer owns temporary WORKSPACE rewrite, pin-script execution,
    active lockfile reconstruction, validation, and proxy timing/stat logging.
  - Neutral Maven path parsing lives in `com.grab.grazel.maven` and is shared by
    Gradle facts, proxy serving, and lockfile helpers.
- Fixed altitude/style leaks:
  - Moved `LocalMavenProxyServer`, `LocalMavenProxyOrigin`,
    `LocalMavenProxyAuth`, and `LocalMavenProxyStats` from
    `migrate.dependencies` to `gradle.dependencies`, so the Gradle proxy
    service no longer imports HTTP lifecycle types from the pinner/migrate
    package.
  - Removed the unused production `LocalMavenProxyService.baseUrl()` method,
    which could start the server before canonical repository URLs were known.
  - Collapsed the two-step `configure()` then `repositoryMappings()` service API
    into one cohesive `configure(...) : LocalMavenProxyRepositoryMappings`
    call. The mappings now come from the same origin plan used to configure and
    start the server.
  - Added server-origin tracking so a reused build service closes/recreates the
    server if canonical repository origins genuinely change instead of returning
    stale `/r/N/` mappings.
  - Renamed the generated-output canonical URL field to
    `canonicalUrlForGeneratedOutput`.
  - Replaced raw POM lookup constructor lambdas with named `PomArtifactQuery`
    and `PomCacheLookup` fun interfaces.
  - Added `MavenCoordinates.canonicalMavenRelativePath(...)` and used it for
    lockfile artifact path derivation instead of relying on
    `mavenRelativePaths(...).first()`.
  - Replaced the changed `DefaultMavenArtifactRepository.toMavenRepository()`
    receiver helper with `toMavenRepository(repository = ...)`.
- Remaining consciously deferred debt:
  - `LocalMavenPinningWorkspace` still temporarily edits generated WORKSPACE
    text to remove metadata-only `override_targets` during proxy pinning. This
    is a real altitude debt, but the proper fix is a typed temporary pin
    workspace render path that prunes override targets before rendering. A
    string-scanner tweak would not solve the layer issue; keep current tested
    behavior for this slice unless final review makes it blocking.
  - Active lockfile artifact fallback remains bounded compatibility debt:
    active repos only, exact paths only, requested origin only, non-known
    Gradle components only. The forced PAX proxy repin verified this path with
    `0` artifact misses.
- Verification:
  - Focused proxy/facts/pinner suite passed in `19s` after the package/API
    move, and passed again in `23s` after POM/path/helper style cleanup.

## 2026-07-02 - additional proxy sidecar cleanup

- Fixed two more audit findings:
  - Moved `LocalMavenResolutionPinContext` next to
    `LocalMavenResolutionPinContextFactory` in the pinner file. The
    `LocalMavenPinningWorkspace` file now owns only temporary WORKSPACE and
    lockfile mutation helpers.
  - Replaced parallel repository sidecar maps
    (`repositoriesByName` string specs plus `repositoryUrlsByName`) with a
    typed `MavenInstallRepositoryInput(repositoryInputSpec, canonicalUrl)` list
    per repo. The exact `repositoryInputSpec` string is still preserved for
    rules_jvm_external hash reconstruction, but URL extraction can no longer
    drift from the corresponding repository input spec.
- Verification:
  - Focused proxy/facts/pinner/calculator suite passed in `28s`.

## 2026-07-02 - simplify-pass follow-up

- Ran the requested simplify-pass review over the Item 38 proxy/pinner slice in
  four angles: reuse, simplification, efficiency, and altitude.
- Accepted and fixed:
  - Reused `RepositoryAuth` directly in the local Maven proxy origin instead of
    maintaining a duplicate proxy auth hierarchy.
  - Reused `mavenInstallJsonName(...)` for pin target selection and stale
    lockfile cleanup.
  - Reused `MavenCoordinates` for Maven install artifact conversion and
    metadata-only short-id derivation.
  - Reused `repositoryInputSpec(...)` in lockfile/workspace tests that need
    rules_jvm_external repository-input hash fixtures.
  - Parsed concrete Maven proxy request GAV once per request branch.
  - Reused one active-lockfile iterator in snapshot and reconstruction paths.
  - Removed the dead `isConcreteArtifactPath(...)` wrapper.
  - Reused supported Maven repository calculation once per
    `MavenInstallArtifactsCalculator.get(...)` call instead of once per repo.
- Rejected/deferred with rationale:
  - Did not change `PomArtifactQuery`, `PomCacheLookup`, or
    `LocalMavenResolutionPinContextFactory` back to raw function types because
    the branch style preference is named seams over opaque callbacks.
  - Did not return `MavenInstallRepositoryRewrite` directly from
    `LocalMavenProxyService`; that would make the Gradle proxy service depend
    on a pinner/migrate model again. Neutral mappings are intentional.
  - Kept `MavenInstallRepositoryInput.repositoryInputSpec` alongside
    `canonicalUrl` because the exact string is the RJE hash input. Pairing it
    with the URL removes drift while preserving byte-identical RJE semantics.
  - Deferred batched POM resolution, batched pin-status Bazel probes, origin
    response streaming, checksum memoization, baseline-lockfile temp snapshots,
    lazy backup hashes, typed temporary WORKSPACE rendering, typed external repo
    bundles, and an explicitly versioned RJE lockfile reconstructor. These are
    valid follow-up performance/architecture items, not cleanup-safe changes.
- Verification:
  - Focused proxy/facts/pinner/calculator suite passed in `29s` after the
    simplify fixes.
  - Full `:grazel-gradle-plugin:test` passed in `39s` after the first
    simplify wave.

## 2026-07-02 - post-altitude verification gates

- PAX normal baseline:
  - Resource checks showed about `28-32GiB` free. PAX Bazel private output root
    was about `17G`; PAX `bazel-cache` was about `14G`; no cleanup was needed.
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
    --rerun-tasks` passed in `11m 43s`.
  - Normal migrate timings:
    - declared metadata fanout: `2327` projects/`2327` shards in `625ms`;
    - dependency resolution: `496` deps/`2451` roots in `23823ms`;
    - target tag collection: `17090` targets in `15960ms`.
  - PAX generated output stayed clean against the committed baseline:
    `git status --short` and `git diff --shortstat` were empty.
  - `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed in `235.478s`.
  - `./bazel.sh test --test_output=errors
    //app-utils:app-utils-gps-pax-debug-test
    //app-test:app-test-gps-pax-debug-test
    //application-initializer:application-initializer-gps-pax-debug-test`
    passed in `20.240s`; `3 tests pass`.
  - PAX `git diff --check` passed; PAX `git status --short` remained clean.
- Grazel:
  - Focused proxy/facts/pinner/calculator suite passed in `29s` after final
    simplify fixes.
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed
    in `44s`.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed in `12s`.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed with
    unchanged counts: bucket count `11`, pinfile count `11`, total artifact
    roots `1945`.
  - `git diff --check` and `git diff --check master...HEAD` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails on the known
    appcompat/constraintlayout exclude sample assertion. This is an accepted
    pre-existing waiver, not a proxy/pinner regression.
- Pending before final:
  - final adversarial review;
  - fix any confirmed findings and rerun impacted checks.

## 2026-07-02 - final altitude review, cleanup, and rerun

- Final review status:
  - The altitude/style reviewer returned findings; the correctness and
    verification reviewers did not return before timeout and were closed. Do
    not treat those two as approvals.
- Fixed from the altitude/style review:
  - `PinMavenArtifactsTask` no longer owns repository URL extraction, active
    lockfile path discovery, proxy mapping conversion, or metadata-only short
    ID derivation. Those now live behind
    `LocalMavenResolutionPinContextAdapter` in the pinner boundary; the task
    wires providers/services only.
  - `ArtifactPinner` no longer imports or knows `LocalMavenProxyStats`; it logs
    via neutral `LocalMavenResolutionStats`.
  - `MavenInstallRepositoryRewrite` is now a first-class pinner model in its
    own file, with proxy-service mappings kept neutral.
  - `MavenInstallLockfileArtifactKey` is now the single parser/model for RJE
    lockfile artifact keys and is shared by path extraction and lockfile
    reconstruction.
  - New receiver-style proxy helpers were rewritten as explicit functions with
    named parameters to match the maintainer's source-shape preference.
  - `GradlePomFileIndexBuilder` now tries the Gradle module-cache POM fallback
    for `additionalGavs` even when a GAV is not present as a resolved graph
    component ID.
- Remaining conscious debt:
  - Temporary `WORKSPACE` text pruning in `LocalMavenPinningWorkspace` remains
    bounded to proxy pinning. Correct follow-up is typed temporary pin-workspace
    rendering.
  - Final proxy-pinning perf work remains for later: batched POM query if
    measured slow, batched pin-status probes, origin streaming, checksum
    memoization, and versioned RJE lockfile adapter.
- Final Grazel verification:
  - Focused proxy/facts/pinner/calculator suite passed in `23s`.
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed
    in `37s`.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed in `9s`.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed with
    unchanged counts: bucket count `11`, pinfile count `11`, total artifact
    roots `1945`.
  - `git diff --check` and `git diff --check master...HEAD` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails on the known
    appcompat/constraintlayout exclude assertion; accepted pre-existing waiver.
- Final PAX verification:
  - PAX baseline: branch `arun/grazel-refactor`, SHA
    `d4105d1f64bd2f1930e1030e42647a214002c48d`.
  - Resource checks: about `27GiB` free before final PAX run and about `21GiB`
    free after final migrate. No cleanup was performed.
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
    --rerun-tasks` passed in `11m 5s`.
  - Final migrate timings:
    - declared metadata fanout: `2327` projects/`2327` shards in `418ms`;
    - dependency resolution: `496` deps/`2451` roots in `22294ms`;
    - target tag collection: `17090` targets in `17104ms`;
    - variant compression: `2096` projects in `46640ms`;
    - target reference collection: `2327` modules in `33425ms`.
  - PAX generated output stayed clean: `git status --short`,
    `git diff --shortstat`, and `git diff --check` were empty/passed.
  - `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed in `225.308s`.
  - `./bazel.sh test --test_output=errors
    //app-utils:app-utils-gps-pax-debug-test
    //app-test:app-test-gps-pax-debug-test
    //application-initializer:application-initializer-gps-pax-debug-test`
    passed in `16.851s`; `3 tests pass`.

## 2026-07-02 - forced sample proxy repin after final review

- Correction to the previous "final" status: normal PAX/Grazel verification was
  green, but the forced proxy repin sample gate found an additional repository
  selection edge case. This section supersedes the earlier final status until
  the post-fix PAX proxy gate is rerun.
- Reproduction:
  - Temporarily enabled `experiments.localMavenResolution` in root
    `build.gradle` and perturbed root `maven_install.json` repository hash to
    force repinning.
  - `./gradlew pinMavenArtifacts --console=plain --no-daemon --stacktrace`
    initially failed for root `@unpinned_maven`.
- Root cause:
  - Active lockfile artifacts were allowed to fall back to origin, but a miss
    from repository `0` was converted to HTTP `500`, so Coursier did not get a
    chance to try repository `1`.
  - After changing that to a repository miss, another issue remained: origin
    write-through cache entries and Gradle-backed POMs were effectively
    repository-independent. That could make Coursier believe a Maven Central
    artifact belonged to the Google repository path.
- Fix:
  - Active lockfile origin fallback now preserves repository miss semantics; a
    missing artifact from a single configured repository returns that origin's
    miss instead of proxy-hard-failing.
  - Origin write-through cache is scoped by repository index.
  - Gradle cached POMs are served repository-independently only when concrete
    artifact bytes are also available from the Gradle artifact index.
  - Active lockfile facts now include both Maven paths and GAVs; those GAVs are
    fed into the Gradle/cache fact builder so existing pinned artifacts can be
    served from Gradle's module cache when present.
- Tests added/updated:
  - proxy tests for active-lockfile repository fallback, repo-scoped origin
    cache, and POM repo-selection behavior;
  - lockfile fact test for deriving GAVs from RJE v3 lockfile entries.
- Verification:
  - Focused proxy + lockfile path tests passed.
  - Forced sample proxy repin passed:
    `./gradlew pinMavenArtifacts --console=plain --no-daemon --stacktrace >
    build/item38-debug/sample-forced-after-repo-scoped-proxy.log 2>&1`.
  - Forced sample proxy summary: `163` artifact hits from Gradle index, `159`
    POM hits from Gradle index, `106` origin fallbacks, `22` lockfile artifact
    fallbacks, `15` metadata-only artifact fallbacks, `180` known alternate
    artifact probes, `0` artifact misses, `731322798` bytes served, in
    `33720ms`.
  - No `localhost`/`127.0.0.1` leaked into `WORKSPACE` or `*maven_install.json`.
  - Temporary forced-run edits were removed; root `build.gradle` and
    `maven_install.json` are back to their non-forced state.

## 2026-07-02 - post-adversarial altitude/source-shape pass

- Prompted by a final altitude/cohesion review over the local Maven proxy
  feature. Two read-only subagents reviewed layer boundaries and source shape.
- Accepted fixes:
  - Moved `LocalMavenResolutionPinContextAdapter` and its Gradle/proxy
    functional interfaces from `migrate/dependencies` to `gradle/dependencies`.
    The migrate pinner boundary now keeps only the neutral
    `LocalMavenResolutionPinContext` and repository rewrite value model; Gradle
    proxy/fact hydration lives in the Gradle dependency layer.
  - Replaced eager `pomIndex` precomputation with a lazy memoized
    `PomFileResolver`. `LocalMavenResolvedFacts` now carries the resolver, and
    `LocalMavenProxyServer` calls it only when a requested POM belongs to a
    Gradle-backed concrete artifact. Origin-bound/lockfile-only POMs no longer
    query Gradle/cache before repository selection.
  - Scoped `MavenInstallWorkspaceRepositoryRewriter` so URL replacement only
    happens inside generated `maven_install(... repositories = [...])` blocks,
    not arbitrary Starlark `repositories` fields.
- Deferred consciously:
  - `LocalMavenPinningWorkspace` still mutates the real generated `WORKSPACE`
    temporarily while Bazel runs. This is existing pinner-style scratch-state
    behavior and remains bounded to the proxy pinning window; the altitude
    correct follow-up is a typed temporary pin WORKSPACE renderer or isolated
    Bazel workspace, not more broad text mutation.
  - `maven-install-repository-inputs.json` remains a file-backed Gradle model
    artifact parsed inside `PinMavenArtifactsTask` action. This is not the
    rejected JSON-string configuration-phase shortcut, but it should stay
    visible as the current model transport seam.
- Focused verification:
  - `./gradlew :grazel-gradle-plugin:test --tests
    "com.grab.grazel.gradle.dependencies.LocalMavenResolvedFactsTest" --tests
    "com.grab.grazel.gradle.dependencies.LocalMavenProxyServerTest" --tests
    "com.grab.grazel.gradle.dependencies.LocalMavenProxyServiceTest" --tests
    "com.grab.grazel.migrate.dependencies.MavenInstallWorkspaceRepositoryRewriterTest"
    --tests "com.grab.grazel.tasks.internal.PinMavenArtifactsTaskTest"
    --console=plain --no-daemon` passed in `22s`.
- Required next gates:
  - rerun forced sample proxy repin after the lazy POM/scoped rewriter changes;
  - rerun broad Grazel gates;
  - rerun forced PAX proxy verification and normal PAX flag-off build/test
    guard before claiming final.

### Forced sample proxy repin after altitude fixes

- Temporary edits: enabled `experiments.localMavenResolution` in root
  `build.gradle` and perturbed root `maven_install.json` repository hash to
  force repinning.
- Command:
  `./gradlew pinMavenArtifacts --console=plain --no-daemon --stacktrace >
  build/item38-debug/sample-forced-after-lazy-pom-altitude.log 2>&1`.
- Result: passed in `39s`.
- Proxy summary: `163` artifacts from Gradle index, `159` POMs from Gradle
  index, `0` origin fallbacks, `22` lockfile artifact fallbacks, `15`
  metadata-only artifact fallbacks, `180` known alternate artifact probes, `0`
  artifact misses, `731322798` bytes served, in `29855ms`.
- No `localhost`/`127.0.0.1` leaked into `WORKSPACE` or `*maven_install.json`.
- Temporary edits were removed; `build.gradle`, `WORKSPACE`, and sample
  lockfiles are clean.

### Local Grazel gates after altitude fixes

- Resource check before broad local gates: about `56GiB` free on the data
  volume; idle Bazel/Gradle daemons were not cleaned.
- `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed
  in `40s`.
- `./gradlew migrateToBazel --console=plain --no-daemon` passed in `10s`.
- `reports/scripts/verify-default-task-graph.sh` passed.
- `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed with
  unchanged PAX baseline counts: bucket count `11`, pinfile count `11`, total
  artifact roots `1945`.
- `git diff --check` and `git diff --check master...HEAD` passed.
- `reports/scripts/verify-sample-bucket-labels.sh` still fails on the known
  appcompat/constraintlayout exclude assertion; accepted pre-existing waiver,
  not local Maven proxy related.

### Forced PAX proxy verification after altitude fixes

- PAX baseline workspace before the forced run:
  branch `arun/grazel-refactor`, commit
  `d4105d1f64bd2f1930e1030e42647a214002c48d`, clean.
- Temporary forced-run edits:
  - enabled `grazel.experiments.localMavenResolution`;
  - added `excludeExternalRepositoryVariables("maven", "DAGGER_REPOSITORIES")`
    for the proxy pinning run;
  - perturbed root `maven_install.json` repository hash to force repinning.
- Command:
  `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks
  > /Users/arun.sampathkumar/work/grazel/build/item38-debug/pax-forced-proxy-after-lazy-pom-altitude-migrate.log
  2>&1`.
- Result: passed in `13m58s`.
- Timings:
  - declared dependency metadata fanout: `2327` projects across `2327`
    shards in `1230ms`;
  - workspace dependency resolution: `496` deps across `2451` roots in
    `23850ms`;
  - variant compression analysis: `2096` projects in `62363ms`;
  - local Maven proxy pinning: `104161ms`.
- Proxy summary: `788` artifacts from Gradle index, `788` POMs from Gradle
  index, `283` origin fallbacks, `45` lockfile artifact fallbacks, `52`
  metadata-only artifact fallbacks, `1710` known alternate artifact probes,
  `0` artifact misses, `1921510962` bytes served.
- No `localhost`/`127.0.0.1` leaked into PAX generated `WORKSPACE` or
  `*maven_install.json` files.
- Temporary PAX edits were removed; PAX status is clean again after the forced
  run.
- Normal flag-off PAX guard:
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
    --rerun-tasks > build/item38-debug/pax-normal-after-altitude-migrate.log
    2>&1` passed in `9m55s`.
  - Timings: declared metadata fanout `2327` projects/`2327` shards in
    `616ms`; workspace dependency resolution `496` deps/`2451` roots in
    `24317ms`; target tags `17090` targets in `16655ms`; variant compression
    `2096` projects in `64684ms`.
  - `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk >
    build/item38-debug/pax-normal-after-altitude-apk-build.log 2>&1` passed in
    `215.080s`.
  - `./bazel.sh test --test_output=errors
    //app-utils:app-utils-gps-pax-debug-test
    //app-test:app-test-gps-pax-debug-test
    //application-initializer:application-initializer-gps-pax-debug-test >
    build/item38-debug/pax-normal-after-altitude-focused-tests.log 2>&1`
    passed in `16.360s`; Bazel reported `3 tests pass`.
  - PAX `git diff --check` passed and PAX status is clean.

### Post-final byte-identity correction

- Audit found the remaining hard done gap: forced proxy repin must be
  byte-identical to vanilla generated lockfiles. The previous PAX forced run
  passed functionally but added baseline-existing POM-packaging artifacts such
  as `androidx.compose:compose-bom:pom` to `skipped`.
- Root cause: `MavenInstallLockfileReconstructor` normalized every
  POM-packaging key into `skipped`, including POM artifacts that already existed
  in the baseline lockfile and were not skipped by vanilla rules_jvm_external.
- Fix: baseline artifact names are parsed once and passed into
  POM-packaging skip normalization. Only POM-packaging roots absent from the
  baseline artifacts are synthesized into `skipped`; baseline-existing POMs
  preserve the baseline skip state.
- Regression test added:
  `reconstruct preserves baseline pom packaging skipped state`.
- Focused reconstructor test passed in `12s`:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.migrate.dependencies.MavenInstallLockfileReconstructorTest"
  --console=plain --no-daemon`.
- Forced sample proxy repin passed in `40s` with `156` Gradle artifact hits,
  `159` Gradle POM hits, `0` origin fallbacks, `141` origin failures, `22`
  lockfile fallbacks, `15` metadata-only fallbacks, `0` artifact misses, `0`
  known POM failures, `842` checksum hits, `318` write-through cache hits, and
  `730992384` bytes served in `30736ms`. Sample generated outputs were
  byte-identical; no generated `WORKSPACE` or Maven install JSON contained
  `localhost`/`127.0.0.1`.
- Forced PAX proxy repin with
  `excludeExternalRepositoryVariables("maven", "DAGGER_REPOSITORIES")` passed
  in `13m13s`:
  `build/item38-debug/pax-forced-after-baseline-pom-preserve-migrate.log`.
  Proxy summary: `787` Gradle artifact hits, `788` Gradle POM hits, `0` origin
  fallbacks, `30` origin failures, `45` lockfile fallbacks, `52`
  metadata-only artifact fallbacks, `1713` alternate artifact probes, `0`
  artifact misses, `0` known POM failures, `3716` checksum hits, `849`
  write-through cache hits, and `1921502568` bytes served in `96949ms`.
- PAX generated files were byte-identical after forced repin. The only PAX diff
  was the temporary `build.gradle` hook/experiment toggle; those edits were
  removed and PAX status is clean.
- Final lightweight checks after the follow-up diff:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed
    in `44s`;
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed in `10s`;
  - `reports/scripts/verify-default-task-graph.sh` passed;
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed with
    unchanged counts `11/11/1945`;
  - `git diff --check` and `git diff --check master...HEAD` passed;
  - generated `WORKSPACE` and Maven install JSON files contain no
    `localhost`/`127.0.0.1`;
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails with the
    known pre-existing appcompat/constraintlayout assertion.
- Final forced PAX proxy repin was rerun after this review fix to prove the
  final code, not just the prior commit. With temporary
  `excludeExternalRepositoryVariables("maven", "DAGGER_REPOSITORIES")`,
  `experiments.localMavenResolution`, and a perturbed root
  `maven_install.json` repository hash, PAX
  `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
  --rerun-tasks` passed in `13m37s`:
  `build/item38-debug/pax-forced-after-skipped-merge-review-fix-migrate.log`.
  Proxy summary: `787` Gradle artifact hits, `788` Gradle POM hits, `0`
  origin fallbacks, `30` origin failures, `45` lockfile fallbacks, `52`
  metadata-only artifact fallbacks, `1713` alternate artifact probes, `0`
  artifact misses, `0` known POM failures, `3716` checksum hits, `849`
  write-through cache hits, and `1921502568` bytes served in `118134ms`.
- Final forced PAX generated diff was byte-identical: only the temporary
  `build.gradle` lines appeared, no generated Maven install JSON or `WORKSPACE`
  contained `localhost`/`127.0.0.1`, temporary PAX edits were removed, and PAX
  status is clean.
- Final PAX Bazel gates were rerun on the clean baseline after the review fix:
  - `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk` passed in `220.593s`;
  - `./bazel.sh test --test_output=errors
    //app-utils:app-utils-gps-pax-debug-test
    //app-test:app-test-gps-pax-debug-test
    //application-initializer:application-initializer-gps-pax-debug-test`
    passed in `16.392s` with `3 tests pass`;
  - PAX `git diff --check` passed and PAX status is clean.

### Review follow-up: baseline skipped merge

- Final read-only review found that baseline skip state was still not fully
  authoritative. The reconstructor filtered synthesized POM skips for baseline
  artifacts, but merged `currentSkipped` before that filter. A proxy/current
  lockfile could therefore mark a baseline-existing POM artifact as skipped and
  still change the reconstructed output even when the baseline did not skip it.
- Fix: raw `currentSkipped` remains available for the existing safety check,
  then `currentSkippedForMerge` removes baseline artifact names before skipped
  entries are merged. Baseline skipped entries come from the baseline lockfile.
- Regression test added:
  `reconstruct ignores current skipped state for baseline pom packaging
  artifact`.
- Focused reconstructor test passed in `19s`:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.migrate.dependencies.MavenInstallLockfileReconstructorTest"
  --console=plain --no-daemon`.
- Post-review lightweight gates:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed
    in `40s`;
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed in `10s`;
  - `reports/scripts/verify-default-task-graph.sh` passed;
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed with
    unchanged counts `11/11/1945`;
  - `git diff --check` and `git diff --check master...HEAD` passed;
  - generated `WORKSPACE` and Maven install JSON files contain no
    `localhost`/`127.0.0.1`;
  - `reports/scripts/verify-sample-bucket-labels.sh` still fails with the
    known pre-existing appcompat/constraintlayout assertion.
- Remaining before final/commit: simplify-pass and adversarial review over the
  green proxy slice; fix real findings and rerun impacted gates.

### Simplify-pass and proxy altitude follow-up

- Simplify-pass ran four focused read-only agents: reuse, simplification,
  efficiency, and altitude.
- Fixed from the findings:
  - extracted neutral basic-auth repository URL injection to
    `com.grab.grazel.maven.mavenRepositoryUrlWithBasicCredentials` and reused it
    from both `MavenRules` and `LocalMavenProxyService`;
  - removed the task-local `LocalMavenResolutionPinContextAdapter` and its
    one-use provider/configurator interfaces; the task boundary now composes
    Gradle facts, proxy service configuration, and migrate-layer pin context in
    one place;
  - avoided building the Gradle module-cache artifact index for GAVs already
    backed by resolved artifact results;
  - made POM lookup cache-first, then Gradle `ArtifactResolutionQuery` fallback;
  - carried all proxy counters through the migrate-layer stats summary, including
    known POM failures, origin failures, checksum hits, and write-through cache
    hits;
  - replaced string-built `*_install.json` names in touched code/tests with
    `mavenInstallJsonName`;
  - renamed the task helper to `pinnableRepoResolutionGavs` and removed the
    unclear `values.flatten()` helper style.
- Altitude map after fixes:
  - extension/config: `experiments.localMavenResolution` only toggles the
    workflow;
  - Gradle dependency layer: `LocalMavenResolvedFactsBuilder`,
    `GradleModuleCacheFileResolver`, `GradlePomFileResolver`,
    `LocalMavenProxyService`, and `LocalMavenProxyServer` own Gradle/cache/proxy
    facts and serving;
  - task boundary: `PinMavenArtifactsTask` bridges file-backed task inputs,
    Gradle live configurations, proxy service setup, and migrate-layer pin
    context; JSON is parsed only inside task actions;
  - migrate/pinner layer: `ArtifactPinner`, `LocalMavenPinningWorkspace`,
    `MavenInstallWorkspaceRepositoryRewriter`, and
    `MavenInstallLockfileReconstructor` own temporary pinning workspace mutation,
    WORKSPACE repo rewriting, and lockfile reconstruction;
  - rendering layer remains unchanged; proxy URLs must not persist into
    generated output.
- Deferred consciously:
  - `LocalMavenPinningWorkspace` still uses bounded temporary text rewriting of
    the live `WORKSPACE` during the pinning window. This is a known pinner shim,
    not generated-output feedback; a future cleaner shape would render an
    isolated temporary pin WORKSPACE.
  - `MavenInstallLockfileReconstructor` remains algorithmically dense because it
    mirrors rules_jvm_external lockfile hash semantics. Do not simplify it
    without a focused parity test against those semantics.
  - Origin fallback still buffers successful origin responses before
    write-through caching. This is acceptable for the current verified path; a
    streaming rewrite should be separately tested against Ktor response and cache
    behavior.
- Focused verification after these simplify fixes:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.gradle.dependencies.LocalMavenResolvedFactsTest" --tests
  "com.grab.grazel.gradle.dependencies.LocalMavenProxyServiceTest" --tests
  "com.grab.grazel.migrate.dependencies.MavenInstallLockfileArtifactPathsTest"
  --tests "com.grab.grazel.migrate.dependencies.LocalMavenPinningWorkspaceTest"
  --tests "com.grab.grazel.tasks.internal.PinMavenArtifactsTaskTest" --tests
  "com.grab.grazel.bazel.rules.MavenRulesTest" --console=plain --no-daemon`
  passed in `21s`.

### Post-simplify altitude audit fix

- Read-only altitude audit found a real correctness gap in
  `MavenInstallLockfileReconstructor`: the baseline-lockfile branch restored
  baseline facts but skipped the existing POM-packaging artifact normalization.
  A new POM-packaging artifact introduced after an initial baseline could
  therefore survive outside `skipped`, making rules_jvm_external reject the
  reconstructed lockfile.
- Fix: `reconstruct` now applies `lockfileWithPomPackagingArtifactsSkipped`
  after either baseline merge or the no-baseline path, then uses the normalized
  lockfile for hash/render steps.
- Regression test added:
  `reconstruct marks new pom packaging artifacts skipped when baseline lockfile
  exists`.
- Verification:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.migrate.dependencies.MavenInstallLockfileReconstructorTest"
  --console=plain --no-daemon` passed in `18s`.
- `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed in
  `39s` before the POM-baseline fix; rerun still required after this fix before
  final.
- Forced sample proxy repin after simplify/altitude fixes:
  - temporary edits enabled `experiments.localMavenResolution` and perturbed
    root `maven_install.json` repository hash;
  - `./gradlew pinMavenArtifacts --console=plain --no-daemon --stacktrace >
    build/item38-debug/sample-forced-after-simplify-altitude.log 2>&1` passed in
    `33s`;
  - proxy summary: `156` artifacts from Gradle index, `159` POMs from Gradle
    index, `0` origin fallbacks, `141` origin failures, `22` lockfile artifact
    fallbacks, `15` metadata-only artifact fallbacks, `201` alternate artifact
    probes, `0` artifact misses, `0` known POM failures, `842` checksum hits,
    `318` write-through cache hits, `730992384` bytes served in `24207ms`;
  - temporary sample edits were removed and direct checks found no
    `localhost`/`127.0.0.1` in `WORKSPACE` or `*maven_install.json`.
- Broad local gates after the POM-baseline fix:
  - `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed
    in `38s`;
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed in `9s`;
  - `reports/scripts/verify-default-task-graph.sh` passed;
  - `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed with
    unchanged PAX baseline counts: bucket count `11`, pinfile count `11`, total
    artifact roots `1945`;
  - `git diff --check` and `git diff --check master...HEAD` passed;
  - `reports/scripts/verify-sample-bucket-labels.sh` still failed with the
    known pre-existing message:
    `WORKSPACE must not union one-sided appcompat exclude onto
    androidx.constraintlayout:constraintlayout`.

### PAX forced-proxy hook validation

- A forced PAX proxy run without customer-side repository-variable exclusion
  failed at `:pinMavenArtifacts` after `12m15s`:
  `build/item38-debug/pax-forced-proxy-after-bazel-restart-migrate.log`.
- Symptom: rules_jvm_external rejected the reconstructed root lockfile with
  `repositories: -1395933409 vs -2080637180`.
- Root cause: PAX post-generation logic removes `+ DAGGER_REPOSITORIES` from
  `WORKSPACE` after Grazel captures repository inputs. This workspace must use
  the existing customer-side hook
  `excludeExternalRepositoryVariables("maven", "DAGGER_REPOSITORIES")`; do not
  add PAX-specific proxy behavior.
- A stale-Bazel-server hypothesis was tested by temporarily shutting Bazel down
  before local proxy validation; the run still failed with the same signature
  mismatch, so that workaround was removed.
- Forced PAX proxy run with the hook passed:
  `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
  --rerun-tasks > build/item38-debug/pax-forced-proxy-final-with-hook-migrate.log
  2>&1` completed successfully in `11m10s`.
- Forced-run proxy summary: `787` artifacts from Gradle index, `788` POMs from
  Gradle index, `0` origin fallbacks, `30` origin failures, `45` lockfile
  artifact fallbacks, `52` metadata-only artifact fallbacks, `1713` alternate
  probes, `0` artifact misses, `0` known POM failures, `3716` checksum hits,
  `849` write-through cache hits, `1921502568` bytes served in `89746ms`.
- Temporary PAX edits for this forced run were removed; PAX status is clean.

### Final altitude notes

- The remaining active-lockfile facts path in `PinMavenArtifactsTask` is a
  bounded pinner-layer compatibility seam: during a repin it allows the proxy to
  serve artifacts already present in active lockfiles and to index those GAVs.
  It does not drive bucket ownership, tags, root dependency resolution, or final
  generated output. Keep for Item 38; future cleanup can replace it with an
  isolated temporary pin workspace if we want to eliminate all live lockfile
  reads from the proxy path.
- Style audit found no other verified source-shape issues after the simplify
  fixes. The task helper remains named by domain (`pinnableRepoResolutionGavs`)
  and the unclear receiver-extension/flattening style was removed.
- Final naming cleanup renamed the active-lockfile helper to
  `activeMavenInstallLockfileFallbackFacts` /
  `mavenInstallLockfileFallbackFacts` so the retained seam is explicit in code.
- Focused tests after the rename passed in `20s`:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.gradle.dependencies.LocalMavenResolvedFactsTest" --tests
  "com.grab.grazel.gradle.dependencies.LocalMavenProxyServiceTest" --tests
  "com.grab.grazel.migrate.dependencies.MavenInstallLockfileArtifactPathsTest"
  --tests
  "com.grab.grazel.migrate.dependencies.MavenInstallLockfileReconstructorTest"
  --tests "com.grab.grazel.migrate.dependencies.LocalMavenPinningWorkspaceTest"
  --tests "com.grab.grazel.tasks.internal.PinMavenArtifactsTaskTest" --tests
  "com.grab.grazel.bazel.rules.MavenRulesTest" --console=plain --no-daemon`.

### Final verification after altitude cleanup

- `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed in
  `41s`.
- `./gradlew migrateToBazel --console=plain --no-daemon` passed in `12s`.
- `reports/scripts/verify-default-task-graph.sh` passed.
- `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed with
  unchanged PAX counts: bucket count `11`, pinfile count `11`, total artifact
  roots `1945`.
- `git diff --check` and `git diff --check master...HEAD` passed.
- `reports/scripts/verify-sample-bucket-labels.sh` still fails with the known
  pre-existing appcompat/constraintlayout assertion.
- PAX normal final loop:
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace
    --rerun-tasks > build/item38-debug/pax-normal-final-migrate.log 2>&1`
    passed in `11m19s`; declared metadata fanout collected `2327` shards in
    `730ms`, resolved `496` deps across `2451` roots in `24193ms`, collected
    `17090` target tags in `15854ms`, analyzed variant compression for `2096`
    projects in `60080ms`, and collected target references across `2327`
    modules in `32839ms`.
  - `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk
    //app:app-gps-pax-debug-android-test.apk >
    build/item38-debug/pax-normal-final-apk-build.log 2>&1` passed in
    `214.992s`.
  - `./bazel.sh test --test_output=errors
    //app-utils:app-utils-gps-pax-debug-test
    //app-test:app-test-gps-pax-debug-test
    //application-initializer:application-initializer-gps-pax-debug-test >
    build/item38-debug/pax-normal-final-focused-tests.log 2>&1` passed in
    `17.313s`; Bazel reported `3 tests pass`.
  - PAX `git diff --check` passed and PAX status is clean.
