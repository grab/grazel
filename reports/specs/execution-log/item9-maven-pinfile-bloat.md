# Item 9 - Maven Pin-File Bloat

## 2026-06-26 18:31 SGT - Starting Point

- Previous item status:
  - PAX `migrateToBazel` passed.
  - PAX `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk` passed.
  - Grazel `:grazel-gradle-plugin:test` passed.
  - `verify-default-task-graph.sh` and `verify-sample-bucket-labels.sh`
    passed.
- Current generated-shape concern:
  - `WORKSPACE` remains acceptable for now at 5,156 lines, but still larger
    than PAX `HEAD` 3,998 lines.
  - `android_test_maven_install.json`, `test_maven_install.json`, and
    `lint_maven_install.json` are much larger than PAX `HEAD`.
  - This can offset the inverted root-resolution performance gains by making
    Coursier pinning and generated repository setup heavier.
- Constraints:
  - Do not change or commit PAX files.
  - Keep PAX current worktree as generated verification output only.
  - Avoid shortcuts such as `additional_coursier_options` conflict forcing.
  - Preserve Gradle-resolved versions and transitive closure semantics.
  - Tags remain direct Maven dependency closure labels for build classpath
    filtering, not a reason to export/project-dep everything.
- Investigation plan:
  - Use read-only subagents for PAX generated diff and Grazel code-path audits.
  - In main context, inspect only targeted code/data needed to form a fix.
  - Add focused Grazel tests before changing production behavior.
  - Re-run the same verification gates if a production change is made.

## Pending Questions

- Are test/android-test/lint buckets carrying too many transitive artifacts as
  direct artifacts instead of override carriers?
- Are override targets being generated for direct deps when old Grazel only did
  so for non-direct/transitive duplicates?
- Is bucket placement spilling common/default artifacts into typed buckets when
  the hierarchy could keep them in `maven`?
- Is any growth expected/correct because strict package reachability now keeps
  only reachable packages but root-level values include a broader union?

## 2026-06-26 18:52 SGT - PAX Generated Diff Audit

- Read-only PAX audit completed against `HEAD` `05d2b4801530`; no PAX files
  were edited.
- Main bloat is input-coordinate growth, not only Coursier's own transitive
  expansion:
  - `android_test_maven_install.json`: deps `149 -> 468`, artifacts
    `168 -> 537`.
  - `test_maven_install.json`: deps `174 -> 469`, artifacts `216 -> 555`.
  - `lint_maven_install.json`: deps `13 -> 47`, artifacts `15 -> 76`.
  - Default `maven_install.json` shrank: deps `634 -> 601`, artifacts
    `764 -> 715`.
- `WORKSPACE` shows the same shape:
  - `android_test_maven` artifact strings `164 -> 506`, override targets
    `145 -> 448`.
  - `test_maven` artifact strings `160 -> 478`, override targets
    `146 -> 436`.
  - `lint_maven` artifact strings `4 -> 64`, override targets `1 -> 14`.
- For test buckets, almost all added dependency keys overlap root `@maven` and
  many are override-targeted back to `@maven`. This points at override-carrier
  entries being used as actual `maven_install.artifacts`, inflating pin inputs.
- Deleted old fine-grained unit-test buckets do not explain the growth:
  `0/296` new `test_maven` deps were covered by the union of deleted bucket
  deps.
- Lint is different: added deps are mostly Android lint/AGP toolchain artifacts
  outside root overlap, so it needs a separate correctness check before any
  pruning.
- Working hypothesis for the next TDD slice:
  - Placement roots and override-target sources are currently conflated.
  - A typed repo should be able to render `override_targets` for Gradle-resolved
    shared artifacts without always making those shared artifacts Coursier root
    inputs in that typed repo.
  - Any pruning must preserve Gradle-resolved version forcing for artifacts that
    still need to be rooted in the child repo; no `additional_coursier_options`
    shortcut.

## 2026-06-26 19:09 SGT - Red Tests

- Added focused failing tests before production changes:
  - `WorkspacePlanBuilderTest.repo plan does not pin default owned closure
    artifacts in child repos`
  - `MavenInstallArtifactsCalculatorTest.variant maven install redirects
    default closure artifacts without rooting them`
- Focused command:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.gradle.dependencies.WorkspacePlanBuilderTest" --tests
  "com.grab.grazel.migrate.dependencies.MavenInstallArtifactsCalculatorTest"
  --console=plain --no-daemon`
- Result: failed for the intended reason. Current code still includes
  `com.example:shared-transitive:2.0.0` in child repo artifacts/pin inputs
  instead of only using it as an override-target source.

## 2026-06-26 19:34 SGT - Focused Optimization Green

- Grazel master reference audit:
  - Old/master Grazel also rooted transitive override-target carriers in
    `maven_install.artifacts`; this optimization intentionally goes beyond
    master behavior.
  - Master avoided much test bucket bloat mostly upstream by filtering
    test/androidTest against parent direct deps during per-variant resolution.
  - Configured `overrideTargetLabels` applied to any artifact present in the
    resolved artifact closure, not only direct roots.
- Implemented a small model split:
  - `MavenInstallArtifactPlan.rootArtifacts`: actual `maven_install.artifacts`
    and pin inputs.
  - `MavenInstallArtifactPlan.overrideTargetArtifacts`: resolved closure
    artifacts used only to compute `override_targets`.
  - `CandidateMavenRepo.overrideTargetArtifacts` persists that distinction in
    the workspace plan.
- Current pruning rule is intentionally conservative:
  - Default and lint repos root all reachable artifacts as before.
  - Non-default repos keep non-default-owned reachable artifacts rooted, with
    owner override metadata when needed.
  - Non-default repos no longer root default-owned reachable artifacts; those
    become override-target sources only.
- Focused command passed:
  `./gradlew :grazel-gradle-plugin:test --tests
  "com.grab.grazel.gradle.dependencies.WorkspacePlanBuilderTest" --tests
  "com.grab.grazel.migrate.dependencies.MavenInstallArtifactsCalculatorTest"
  --console=plain --no-daemon`
- Next checks: full Grazel plugin unit tests, task-graph/sample verification,
  then PAX regenerate and compare pin-file counts before Bazel build gates.

## 2026-06-26 20:24 SGT - Compact-Root Optimization Backed Out

- PAX `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`
  reached `pinMavenArtifacts` and began repinning `android_test_maven`.
- The compact-root experiment shrank the visible `android_test_maven` Coursier
  root list, but Coursier then stayed CPU-bound with no output for roughly
  17 minutes and grew to about 3.5 GiB RSS.
- I interrupted the Gradle run to avoid system pressure. The Coursier child
  survived as an orphan, so I killed it separately.
- Interpretation:
  - Removing default-owned closure artifacts from `maven_install.artifacts`
    also removes rules_jvm_external/Coursier version-forcing roots.
  - That breaks the old load-bearing behavior: Gradle-resolved transitive
    versions are forced by making the closure part of the artifact inputs.
  - This is not an acceptable optimization without a different, proven way to
    pass Gradle-resolved version constraints into Coursier and the generated
    pinned repo.
- Action taken:
  - Backed out the compact-root production behavior and related test
    expectation changes.
  - Kept this log entry as the decision record: future pin-file reductions
    should target upstream bucket/test placement or a first-class version
    constraint mechanism, not simply dropping closure artifacts from
    `maven_install.artifacts`.

## 2026-06-26 19:45 SGT - Local Grazel Checks

- `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed
  in 46s.
- `reports/scripts/verify-default-task-graph.sh` passed.
- `reports/scripts/verify-sample-bucket-labels.sh` passed.
- `git diff --check` passed.
- Disk before PAX regenerate: about 21 GiB free on `/System/Volumes/Data`.

## 2026-06-26 20:38 SGT - PAX Restored After Backout

- Reran PAX after backing out the compact-root optimization:
  `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace`.
- Result: passed in 7m 56s. `pinMavenArtifacts` reported all repos
  up-to-date instead of entering the Coursier stall seen during the compact
  root experiment.
- Generated pin shape is back to the accepted expanded-root baseline:
  - `WORKSPACE`: 5156 lines.
  - `maven_install.json`: dependencies 601, artifacts 715.
  - `debug_maven_install.json`: dependencies 212, artifacts 246.
  - `android_test_maven_install.json`: dependencies 468, artifacts 537.
  - `test_maven_install.json`: dependencies 469, artifacts 555.
  - `lint_maven_install.json`: dependencies 47, artifacts 76.
- PAX `git diff --check` passed.
- Current decision remains:
  - Keep closure artifacts rooted for repos that materialize deps; this is
    how Grazel forces Coursier/rules_jvm_external to honor Gradle-resolved
    selected versions.
  - Pin-file bloat is real but should be attacked via upstream placement/test
    bucket filtering or a proven version-constraint mechanism, not by dropping
    closure artifacts from `maven_install.artifacts`.
- System state before Bazel build gate:
  - `/System/Volumes/Data` had about 16 GiB free.
  - `/private/var/tmp/_bazel_arun.sampathkumar` was about 79 GiB.
  - `pax-android/bazel-cache` was about 17 GiB.
  - Plan: run `bazelisk clean --expunge` before the next PAX Bazel gate to
    reduce disk-pressure-related false failures.

## 2026-06-26 20:53 SGT - PAX APK Build Gate Passed

- Ran `bazelisk clean --expunge` in PAX first. It only cleaned the current
  output base; many stale output bases remained under
  `/private/var/tmp/_bazel_arun.sampathkumar`.
- Removed stale private Bazel output-root content. The first `rm -rf` was
  interrupted because read-only Bazel output files produced huge permission
  noise, but it still reduced disk use enough:
  - Free disk improved from about 19 GiB to about 75 GiB.
  - Remaining private Bazel output-root size was about 24 GiB.
  - I did not delete `pax-android/bazel-cache`; preserving the disk cache kept
    the build fast.
- Ran the required PAX build gate:
  `./bazel.sh build //app:app-gps-pax-debug.apk
  //app:app-gps-pax-debug-android-test.apk`.
- Result: passed in 293.202s.
  - 54,549 total actions.
  - 37,656 disk-cache hits.
  - 6,165 remote-cache hits.
  - 10,728 internal actions.
- Observed warnings:
  - `rules_jvm_external` still reports duplicate artifact versions for a few
    coordinates. This is expected with the current expanded-root version
    forcing model and was not a build failure.
  - Large Kotlin/databinding warning noise is from PAX sources, not this
    Grazel change.
- Current status:
  - Compact-root pin-file optimization remains rejected.
  - Expanded-root baseline is verified for migrate + debug APK + android-test
    APK.
  - Remaining pin-file bloat is a follow-up architecture item, likely in
    upstream placement/test bucket filtering rather than artifact-root pruning.

## 2026-06-26 21:36 SGT - Focused PAX Unit-Test Gate Passed

- Ran a focused PAX Bazel unit-test gate after the APK build gate:
  `./bazel.sh test --test_output=errors
  //app-utils:app-utils-gps-pax-debug-test
  //app-test:app-test-gps-pax-debug-test
  //application-initializer:application-initializer-gps-pax-debug-test`.
- Result: passed.
  - Elapsed time: 369.117s.
  - 11,757 total actions.
  - Executed 3 out of 3 tests; 3 tests passed.
- Why this gate was chosen:
  - PAX has no obvious `//app:*debug-test` unit target from query.
  - These GPS PAX debug test targets are app-facing and exercise the generated
    reachable Maven/project classpath through heavy Kotlin/Kapt compilation.
- System notes:
  - Disk stayed safe during the run, with roughly 69 GiB free before the final
    stretch.
  - Memory was pressured but stable; large Java processes were active
    Bazel/Kotlin workers, and no high-memory `python3.12` process was present.
- Post-test checks:
  - Grazel `git diff --check` passed.
  - PAX `git diff --check` passed.
  - PAX still has a large generated-output worktree diff from
    `migrateToBazel`; do not commit those PAX changes.
