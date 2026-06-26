# Item 5 - Provenance and Exclude Correctness

This log tracks the output-changing correctness item. Keep entries short and
split by sub-step so new baselines can be traced to one cause.

## Step 5a - Exclude intersection

### Target

- Replace exclude union with exclude intersection at same-repo merge sites.
- Preserve Gradle-resolved coordinate/version; only the serialized exclude set
  changes when multiple owners for the same repo coordinate disagree.
- Add focused regression coverage before accepting generated-output drift.

### Status

- Started after Item 4 checkpoint `97d907c`.
- 5a read-only audits completed. Relevant findings:
  - True duplicate owners/paths for one repo coordinate must intersect excludes.
  - A resolved owner plus one declared metadata overlay is not a competing path;
    preserve the declared exclude metadata on that resolved owner.
  - Additional union sites existed in declared metadata collection and selected
    variant hierarchy rule lookup, not only in the three obvious merge sites.

### 2026-06-26 13:43 SGT

- Resource check before Gradle slice: `/Users/.../work/grazel` and `/private/var`
  had ~12 GiB free; memory was tight but no large `python3.12` process was present.
- Implemented 5a source changes:
  - `ResolvedDependency.merge` intersects excludes.
  - `mergeDependencyMetadataByMaxVersion` intersects duplicate real owners, but
    unions when exactly one side is declared metadata.
  - `ComputeWorkspaceDependencies.maxVersionReducer` now uses the central merge
    helper.
  - Declared exclude collectors and `ProjectExcludeRules.rulesFor` intersect
    duplicate rule sets instead of flattening/unioning them.
  - Placement explicit+inferred merging preserves explicit bucket owner metadata
    and only carries over closure/flags. This avoids stripping declared metadata
    after it has already been attached to the resolved owner.
- Added/updated focused tests for duplicate exclude intersection, declared
  metadata overlay preservation, collector duplicate declarations, selected
  variant hierarchy rule intersection, and the placement explicit/inferred
  distinction.
- Verification:
  - `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --tests "com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependenciesTest" --tests "com.grab.grazel.gradle.dependencies.DependencyBucketPlacementEngineTest" --tests "com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadataCollectorTest" --console=plain --no-daemon`
    passed.

### Next

- Run 5a local gates (`git diff --check`, task-graph and sample bucket label
  scripts, golden baseline command).
- If local gates pass or generated drift is classified/rebaselined, run the PAX
  migrate/build acceptance loop and classify output drift.

### 2026-06-26 13:55 SGT

- Local gates:
  - `git diff --check` passed.
  - `reports/scripts/verify-default-task-graph.sh` passed.
  - `reports/scripts/verify-sample-bucket-labels.sh` passed after updating the
    sample oracle for 5a.
  - `./gradlew verifyGrazelGoldenBaseline --console=plain --no-daemon` ran
    `migrateToBazel` and failed only because generated files now differ from the
    committed baseline, which is expected for Item 5a before rebaseline.
- Classified local generated drift:
  - `WORKSPACE`: removed one-sided
    `androidx.appcompat:appcompat` exclusion from
    `androidx.constraintlayout:constraintlayout`; kept `androidx.core:core`.
  - `maven_install.json`: `constraintlayout` hash changed and
    `androidx.appcompat:appcompat:aar` appears in the resolved dependency list
    because it is no longer over-excluded.
  - `WORKSPACE` jetify include list switched one entry from
    `com.android.support:support-fragment` to `androidx.fragment:fragment`,
    consistent with the newly retained appcompat closure.
  - No `additional_coursier_options` or `--force-version` is present in the
    generated `WORKSPACE`.
- Sample oracle changes:
  - Assert `constraintlayout` does not carry the one-sided appcompat exclusion.
  - Keep asserting the remaining generated `androidx.core:core` exclusion.
  - Remove stale `debugUnitTest` bucket expectation; regenerated sample output
    has no unit-test-only dependency bucket and no unit-test-only
    `constraintlayout` declaration.

### Next

- Run PAX 5a acceptance: resource check, `migrateToBazel`, generated diff
  classification, tag-prefix audit, and debug APK + android-test APK build.

### 2026-06-26 14:35 SGT

- PAX 5a acceptance passed:
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`
    passed in 14m10s.
  - PAX `git diff --check` passed.
  - Tag-prefix audit found `0` bucket Maven labels inside `tags` arrays.
  - `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures`
    passed in 659.350s.
- PAX generated drift remains large but build-correct for this checkpoint:
  - `WORKSPACE` + maven install JSON diff: 23,375 insertions and 7,666 deletions.
  - BUILD file diff shortstat: 2,208 files changed, 668,861 insertions
    and 761,851 deletions.
  - `debug_unit_test_maven_install.json` and related flavor unit-test repos are
    still deleted by the current bucket shape.
- Resource note: PAX build reduced free disk to ~8.5 GiB; clean Bazel output
  before continuing to 5b.

### Next

- Commit 5a after a final Grazel diff/status check.
- Clean PAX Bazel output to restore disk headroom.
- Start 5b provenance/variant-scoped artifact selection; do not mix it into the
  5a commit.

## Step 5b - Variant-scoped root artifact provenance

### Target

- Stop selecting non-default Maven install root artifacts through a global
  `shortId` winner.
- Rehydrate reachable transitive artifacts from the current variant's resolved
  artifact first, then default, then deterministic fallback for cross-bucket
  carriers.
- Preserve the existing global transitive-deps store for target tags; tag closure
  still needs the direct-Maven transitive fallback and is not the repo-root
  selection path.

### 2026-06-26 14:55 SGT

- Red test added in `WorkspacePlanBuilderTest`:
  - default has `com.example:shared:2.0.0`;
  - debug's resolved closure has `com.example:shared:1.0.0`;
  - `debug_maven` must pin `1.0.0`, not the global shortId winner.
- Red test failed as expected before production change.
- Implemented local green:
  - `MavenInstallRootArtifacts` now uses `VariantScopedArtifacts` instead of
    `selectedArtifactByShortId` / `mergeSelected`.
  - Removed version-based global shortId merge from repo root selection.
  - Renamed identity predicates to resolved/provenance terms.
- Verification:
  - `WorkspacePlanBuilderTest` passed.
  - `WorkspacePlanBuilderTest`, `MavenInstallArtifactsCalculatorTest`,
    `AggregatedDependencyResolverTest`, and `ComputeWorkspaceDependenciesTest`
    passed together.

### Next

- Run local generated-output gates for 5b.
- Classify sample generated drift.
- Run PAX migrate/build acceptance if local gates are clean or drift is
  explained.

### 2026-06-26 15:05 SGT

- Local 5b follow-up:
  - Found sample Bazel failure after first variant-scoped implementation:
    `debug_maven` carried default-owned AndroidX roots without redirects, so
    resource linking saw both `@maven` and `@debug_maven` copies.
  - Added/updated tests so default-owned artifacts carried as non-default
    Coursier roots must redirect Bazel labels back to `@maven`.
  - Generalized the old databinding-only default-owner redirect to all
    default-owned rehydrated roots. This preserves the artifact closure for
    Coursier pinning while avoiding duplicate Bazel labels/resources.
- Verification:
  - Focused tests passed:
    `WorkspacePlanBuilderTest`, `MavenInstallArtifactsCalculatorTest`,
    `AggregatedDependencyResolverTest`, `ComputeWorkspaceDependenciesTest`.
  - `git diff --check`, `reports/scripts/verify-default-task-graph.sh`, and
    `reports/scripts/verify-sample-bucket-labels.sh` passed.
  - `./gradlew verifyGrazelGoldenBaseline --console=plain --no-daemon`
    regenerated output and failed only on expected generated drift.
  - `bazelisk build //sample-android:sample-android-full-paid-debug` fails
    locally with a missing crashlytics symlinked manifest when Bazel does not
    download all outputs. Aquery shows the producer exists; running with
    `--remote_download_outputs=all` passes. Treat this as a local Bazel output
    materialization caveat, not a dependency graph failure.

### Next

- Run PAX 5b acceptance from the cleanly generated Grazel state:
  `migrateToBazel`, `git diff --check`, tag-prefix audit, and debug APK +
  android-test APK build.

### 2026-06-26 15:59 SGT

- PAX 5b acceptance passed after low-disk recovery.
- Commands/results:
  - `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`
    passed in PAX in 11m15s.
  - PAX `git diff --check` passed.
  - PAX tag-prefix audit over changed `*.bazel` files returned `0`; generated
    `tags` still use `@maven` labels, not bucket Maven repos.
  - First PAX Bazel build attempt reached app resource extraction but failed
    with `No space left on device`; this was an environment failure after disk
    dropped below 1 GiB, not a generated-script correctness failure.
  - Low-disk recovery: removed Gradle caches, removed PAX `bazel-cache`, then
    ran `bazelisk clean --expunge` in PAX.
  - Retry passed:
    `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk --verbose_failures`
    completed successfully in 2169.372s.
  - PAX post-build `git diff --check` passed.
  - PAX post-build tag-prefix audit returned `0`.
  - Grazel `git diff --check` passed.
- Generated-output shape:
  - PAX workspace/json drift is still large, but this slice removes the global
    shortId root-artifact winner and keeps default-owned transitive roots
    redirected to `@maven` while preserving Coursier pinning roots.
  - Remaining size/dedupe work should be treated as a later bucket/workspace
    optimization item, not mixed into this verified correctness slice.
- Resource note:
  - The successful retry left PAX disk at ~4.8 GiB free.
  - `bazelisk clean --expunge` restored disk to ~27 GiB free.
  - PAX `bazel-cache` is currently ~13 GiB and was left in place after expunge
    because disk was no longer critical and immediate reruns may benefit.

### Next

- Review and commit the verified 5b Grazel changes without pushing.
- Then continue with the next optimization item from the specs.
