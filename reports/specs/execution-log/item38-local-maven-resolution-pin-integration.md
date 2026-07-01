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
