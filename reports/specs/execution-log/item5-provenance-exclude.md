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
