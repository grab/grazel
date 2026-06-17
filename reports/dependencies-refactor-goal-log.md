# Dependencies Refactor Goal Log

## Current Status / Next Action

Current status as of 2026-06-17 04:20:35 +08:
- Branch: `arun/dependencies-refactor`.
- Goal source: `/Users/arun.sampathkumar/.codex/attachments/a1ee8e1c-e9d0-478e-82a6-716df2a880ee/pasted-text-1.txt`.
- `aggregatedDependencyResolution` is now convention-default `true`; the root sample no longer opts in manually.
- Default task graph no longer schedules the old per-project/per-variant `*ResolveDependencies` fan-out for `computeWorkspaceDependencies` or `migrateToBazel`.
- Aggregated resolution now preserves Gradle `exclude {}` metadata. The focused verifier fails on the old regression and now confirms `androidx.constraintlayout:constraintlayout` is emitted as a detailed `maven.artifact(...)` with the app/flavor exclusions intact.
- `DefaultMavenInstallStore` now stores `overrideTarget` labels, so duplicate artifacts can resolve to the intended parent/default repository instead of the first variant bucket that mentions them.
- Main app sample bucket labels now pass the focused verifier:
  - `androidx.paging:paging-runtime` is routed through `@debug_maven`.
  - Common app deps such as activity, compose UI, and emoji are routed through `@maven` instead of leaf repos.
- Built-in `androidTest` now emits `@android_test_maven//:androidx_test_monitor`, matching master for the focused sample labels.
- Two graph ownership bugs were fixed in `ResolvedComponentsVisitor`:
  - A node first visited transitively and later seen as a root/project first-level dependency must still be emitted with direct ownership for the later edge.
  - Root-level dependency constraints must not count as direct dependencies.
- Raw aggregated buckets now include only `default`, `debug`, `androidTest`, and `lint` for the root sample. Debug-only paging no longer leaks into flavor buckets.
- Stale per-leaf, flavor, and now-unreferenced root `test_maven_install.json` files are deleted because the current WORKSPACE no longer references those repos.
- Core/lifecycle debug entries now route to `@maven`, intentionally: they are common implementation deps at higher versions in the default repo, while the debug repo only has older transitive versions from paging.
- Gradle unit tests and functional tests pass after fixture compatibility updates.
- `migrateToBazel` succeeds and the focused bucket/task-graph verifier scripts pass.
- `.bazel/.default.bazelrc` now forces `AndroidAapt2=standalone`. A plain `bazelisk build //...` passes after this, avoiding the persistent AAPT2 worker issue where a same-package crashlytics resource action opened the wrong sibling manifest.
- `bazelisk test //...` still fails only on 8 generated lint tests for existing sample lint/resource-baseline issues; 9 Bazel tests pass and the explicit non-lint subset passes.

Next action:
- Package/commit the working tree if desired. Do not mark broad `bazelisk test //...` green unless the generated lint baselines/resources are fixed or those lint targets are intentionally excluded.
- Pending for the next guided goal session: validate or explicitly accept the risk register at the end of this log, especially variant-specific KSP, coarse exclude bucketing, compileOnly placement, BOM detection, explicit opt-out coverage, and richer flavor/test bucket cases.

## Checkpoints

### 2026-06-17 01:58:52 +08 — Objective Intake and Current-State Audit

Hypothesis:
- The branch proves compile feasibility but not the original performance/semantic requirement.

Files inspected:
- `reports/dependencies-refactor-HANDOFF.md`
- `reports/dependencies-refactor-worklog.md`
- `reports/dependencies-refactor-design-notes.md`
- `reports/dependency-resolution-to-workspace.md`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/ComputeWorkspaceDependenciesTask.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/ComputeWorkspaceDependencies.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/DependencyResolutionService.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/MavenInstallStore.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/variant/VariantBuilder.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/variant/VariantDataSource.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/variant/AndroidVariants.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/variant/ConfigurationParsingVariant.kt`
- `build.gradle`
- Generated `BUILD.bazel`, `WORKSPACE`, and `*_maven_install.json` files.

Commands and results:
- `git status --short --branch`: branch `arun/dependencies-refactor`; only `codedb.snapshot` untracked.
- `git merge-base master HEAD`: `1d6c91ed4ab3363a32020ea2204d1f53092ad335`, so `master...HEAD` is a direct branch diff.
- `git diff --name-status master...HEAD`: code delta is concentrated in resolver/task/dependency classes; generated Bazel outputs have large diffs.
- `./gradlew computeWorkspaceDependencies --dry-run --console=plain`: old `*ResolveDependencies` tasks appear before `:computeWorkspaceDependencies`.
- `./gradlew migrateToBazel --dry-run --console=plain`: old `*ResolveDependencies` tasks appear before generation tasks.
- `./gradlew computeWorkspaceDependencies --console=plain`: build succeeded, but executed 107 old dependency-resolution tasks.
- `jq` on regenerated `build/grazel/dependencies.json`: aggregated output currently contains leaf buckets (`demoFreeDebug`, `fullPaidDebug`, unit/androidTest leaf variants), plus `default`, `test`, `androidTest`, and `lint`.

Bucket/task-graph findings:
- Task graph does not satisfy the default no-fan-out requirement.
- Current bucket output does not preserve master semantics under debug-only filtering.
- `MavenInstallStore` likely needs to account for `overrideTarget` or avoid indexing override-only duplicates as first-class variant ownership.

Risks/open questions:
- Matching master semantics may require resolving/constructing synthetic bucket closures (`default`, `debug`, flavor, leaf) from binary roots rather than relying only on filtered leaf intersections.
- Existing functional fixtures may be too small to cover root sample behavior; a focused root-level golden loop may be needed.
- `com.android.test` root support must remain intact while fixing common/debug bucketing.

### 2026-06-17 02:06:05 +08 — Default Task Graph Green

Hypothesis:
- The old fan-out was caused by configuration-time wiring: `ResolveVariantDependenciesTask.register(...)` always populated `compileDependenciesJsons`, so Gradle scheduled those producer tasks even when the aggregated task action branch was selected.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/extension/ExperimentsExtension.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/ComputeWorkspaceDependenciesTask.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/ResolveVariantDependenciesTask.kt`
- `build.gradle`
- `reports/scripts/verify-default-task-graph.sh`

Commands and results:
- `reports/scripts/verify-default-task-graph.sh`: failed before the production change with many `*ResolveDependencies SKIPPED` tasks; passes after the change.
- `./gradlew computeWorkspaceDependencies --dry-run --console=plain`: build successful; task graph contains `:computeWorkspaceDependencies SKIPPED` and no `*ResolveDependencies` tasks.
- `./gradlew migrateToBazel --dry-run --console=plain`: build successful; generation graph contains `:computeWorkspaceDependencies SKIPPED` and no `*ResolveDependencies` tasks.

Task-graph findings:
- `aggregatedDependencyResolution` is now convention-default `true`.
- The root sample no longer sets `aggregatedDependencyResolution.set(true)`.
- Legacy per-variant task creation is skipped during `afterEvaluate` when aggregated resolution is enabled. This preserves the explicit opt-out shape because setting the flag false before project evaluation still allows the legacy task registration block to run.

Next step:
- Move to bucket correctness: reproduce the `@maven`/`@debug_maven`/leaf bucket regressions in focused tests or checked-in verification, then fix `DefaultMavenInstallStore` lookup and bucket reconstruction.

Risks/open questions:
- The explicit opt-out path still needs verification with a fixture or init-script run.
- Functional tests still have fixture compatibility failures unrelated to this task-graph change; root-level verification is currently stronger for this requirement.

### 2026-06-17 02:23:00 +08 — Bucket Lookup and AndroidTest Audit

Hypothesis:
- The first large generated-output regressions were two separate issues:
  1. Bucket reconstruction from only leaf intersections was insufficient under debug-only filtering.
  2. Maven repo lookup ignored `overrideTarget`, so duplicate leaf/default artifacts could choose the wrong repo.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/DependencyResolutionService.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/MavenInstallStore.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/ResolvedComponentsVisitor.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/DefaultDependencyResolutionServiceTest.kt`
- `reports/scripts/verify-sample-bucket-labels.sh`

Commands and results:
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionServiceTest" --console=plain`: passed.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.ResolvedComponentsVisitorTest" --console=plain`: passed.
- `./gradlew :grazel-gradle-plugin:compileKotlin --console=plain`: passed.
- `./gradlew migrateToBazel --console=plain`: passed.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `./gradlew :sample-android:dependencies --configuration demoFreeDebugAndroidTestCompileClasspath --console=plain`: passed; report shows `androidx.test:monitor:1.6.1` as a first-level androidTest dependency.

Findings:
- Synthetic hierarchy bucket resolution fixed the main app sample for the high-signal labels:
  - `@debug_maven//:androidx_paging_paging_runtime` is present.
  - `@maven//:androidx_activity_activity`, `@maven//:androidx_compose_ui_ui`, and `@maven//:androidx_emoji2_emoji2` are present.
  - Those common deps are no longer emitted from `@demo_free_debug_maven`.
- `DefaultMavenInstallStore` now caches the resolved `MavenDependency`, including `overrideTarget` labels. The targeted duplicate-artifact unit test passes.
- `ResolvedComponentsVisitor` now treats first-level external children of the resolved root as direct when traversing project nodes. This matches Gradle's dependency report shape, but it did not by itself create a final `androidTest` bucket.
- After regeneration, `build/grazel/dependencies.json` still has no `androidTest` entry in `result`. `sample-android/BUILD.bazel` still differs from master for `androidx_test_monitor`.

Open decision:
- Should standalone `com.android.test` root closures be allowed to populate `default` transitively, or should only their direct declarations feed `@maven` while app built-in `androidTest` owns androidTest-only first-level/transitive artifacts such as `androidx.test:monitor`?

### 2026-06-17 02:42:49 +08 — AndroidTest Bucket Restored

Hypothesis:
- The missing shared `androidTest` bucket was caused by graph traversal treating dependency constraints as direct root ownership, plus a cache hit losing directness when a repeated node was first visited transitively.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/ResolvedComponentsVisitor.kt`
- `grazel-gradle-plugin/src/test/kotlin/com/grab/grazel/gradle/dependencies/ResolvedComponentsVisitorTest.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/ComputeWorkspaceDependenciesTask.kt`
- Generated Bazel/Maven files.

Commands and results:
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.ResolvedComponentsVisitorTest" --console=plain`: failed before constraint handling; passed after the fix.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.ResolvedComponentsVisitorTest" --tests "com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionServiceTest" --console=plain`: passed.
- `./gradlew computeWorkspaceDependencies --console=plain`: passed.
- `./gradlew migrateToBazel --console=plain -q`: passed.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `git diff --check`: passed.

Findings:
- `sample-android/BUILD.bazel` now uses `@android_test_maven//:androidx_test_monitor` for all generated instrumentation targets.
- `build/grazel/dependencies.json` currently has buckets: `androidTest`, `debug`, `default`, `demo`, `free`, `full`, `lint`, `paid`.
- Raw aggregated output shows standalone `com.android.test` declared deps such as `espresso-core`, `ext:junit`, `rules`, and `runner` in `default`, while constraint-only `androidx.test:monitor` no longer becomes direct `default`.
- `android_test_maven_install.json` is back to the five-artifact shape from master: annotation, annotation-experimental, test annotation, monitor, tracing.

### 2026-06-17 02:48:07 +08 — Flavor Bucket Leakage Removed

Hypothesis:
- Flavor buckets were over-owning `androidx.paging:paging-runtime` because flavor reduction subtracted `default` but not already-owned build-type buckets.

Evidence before fix:
- `build/grazel/dependencies.json` had `androidx.paging:paging-runtime` as a direct dependency in `debug`, `demo`, `free`, `full`, and `paid`.
- The first run of `reports/scripts/verify-sample-bucket-labels.sh` after adding the check failed with: `Found debug-only paging dependency as direct dependency in demo bucket`.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`
- `reports/scripts/verify-sample-bucket-labels.sh`
- Generated Bazel/Maven files.

Commands and results:
- `./gradlew computeWorkspaceDependencies --console=plain`: passed.
- `./gradlew migrateToBazel --console=plain -q`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed after the reducer change.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `git diff --check`: passed.

Findings:
- `build/grazel/dependencies.json` now has buckets: `androidTest`, `debug`, `default`, `lint`.
- `debug` directly owns `androidx.paging:paging-runtime`; flavor buckets are absent because the sample currently has no external flavor-only dependencies.
- Current root maven install files are limited to repos referenced by WORKSPACE: `maven`, `debug_maven`, `android_test_maven`, `lint_maven`, and `ksp_maven`.
- Core/lifecycle labels are intentionally verified as `@maven`, not `@debug_maven`, because they are common implementation deps and the default repo owns newer selected versions.

### 2026-06-17 03:34:35 +08 — Verification Pass and Remaining Policy Decisions

Hypothesis:
- The refactor now satisfies the main performance/semantic requirement, and the remaining failures are either local Bazel worker behavior or sample lint expectations rather than dependency bucketing regressions.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/AndroidInstrumentationBinaryTargetBuilder.kt`
- Functional fixture build files and assertions under `grazel-gradle-plugin/src/functionalTest` and `grazel-gradle-plugin/src/test/projects`
- Generated root Bazel and Maven files.

Commands and results:
- `./gradlew :grazel-gradle-plugin:functionalTest --console=plain`: passed.
- `./gradlew :grazel-gradle-plugin:test --console=plain`: passed.
- `./gradlew computeWorkspaceDependencies --dry-run --console=plain`: passed; no legacy `*ResolveDependencies` task fan-out.
- `./gradlew migrateToBazel --dry-run --console=plain`: passed; no legacy `*ResolveDependencies` task fan-out.
- `./gradlew migrateToBazel --console=plain`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `git diff --check`: passed.
- `bazelisk build //... --strategy=AndroidAapt2=sandboxed`: passed.
- `bazelisk test //flavors/sample-library-demo:sample-library-demo-test //flavors/sample-library-full:sample-library-full-test //sample-android-library:sample-android-library-debug-test //sample-kotlin-library:sample-kotlin-library-test --strategy=AndroidAapt2=sandboxed`: passed.
- `bazelisk test //... --strategy=AndroidAapt2=sandboxed`: failed only in generated lint targets for sample lint/baseline issues.

Findings:
- Build-type-only AGP variants needed to be treated as concrete leaves using the backing `BaseVariant`, otherwise hybrid fixtures with only debug/release leaves collapsed incorrectly.
- Source-less generated instrumentation test targets needed to be skipped to avoid Bazel `kt_jvm_library` deps-without-srcs failures.
- Root `WORKSPACE` no longer references `test_maven`; the stale tracked `test_maven_install.json` was removed to keep generated outputs consistent with the loaded repos.
- Default Bazel AndroidAapt2 worker strategy appears stateful across same-package crashlytics resource actions. The sandboxed strategy builds successfully and the failing action graph did not contain a dependency path to the incorrectly opened sibling manifest.

Open decisions:
- Should `--strategy=AndroidAapt2=sandboxed` be encoded in `.bazelrc` or kept as a local verification workaround?
- Should generated lint tests be made expected-green for this sample, excluded from broad `bazel test //...`, or left as known sample debt?
- Should untracked functional-test generated outputs (`MODULE.bazel`, lockfiles, fixture maven jsons, generated layout file, etc.) be committed as fixture updates or cleaned before finalizing?

### 2026-06-17 04:20:35 +08 — Excludes Preserved and Final Verification Refresh

Hypothesis:
- The remaining `WORKSPACE` drift was a real bug: the aggregated resolver emitted direct dependencies from binary classpath graph traversal, but never attached Gradle `ExternalDependency.excludeRules`.

Files changed:
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/AggregatedDependencyResolver.kt`
- `reports/scripts/verify-sample-bucket-labels.sh`
- `.bazel/.default.bazelrc`
- Regenerated Bazel/Maven files.

Commands and results:
- `reports/scripts/verify-sample-bucket-labels.sh`: failed before the resolver fix with `WORKSPACE must preserve Gradle exclude rules for androidx.constraintlayout:constraintlayout`; passed after regeneration.
- `./gradlew :grazel-gradle-plugin:compileKotlin --console=plain`: passed.
- `./gradlew migrateToBazel --console=plain`: passed and repinned the referenced repos.
- `./gradlew :grazel-gradle-plugin:test --console=plain`: passed.
- `./gradlew :grazel-gradle-plugin:functionalTest --console=plain`: passed.
- `./gradlew computeWorkspaceDependencies --dry-run --console=plain`: passed; only `:computeWorkspaceDependencies SKIPPED`, no legacy `*ResolveDependencies` tasks.
- `./gradlew migrateToBazel --dry-run --console=plain`: passed; no legacy `*ResolveDependencies` tasks.
- `reports/scripts/verify-default-task-graph.sh`: passed.
- `reports/scripts/verify-sample-bucket-labels.sh`: passed.
- `git diff --check`: passed.
- `bazelisk build //...`: first failed through the persistent AndroidAapt2 worker by opening the wrong sibling crashlytics manifest. `bazelisk build //... --strategy=AndroidAapt2=standalone` passed. After encoding `common --strategy=AndroidAapt2=standalone` in `.bazel/.default.bazelrc`, plain `bazelisk build //...` passed.
- `bazelisk test //...`: failed only the 8 generated lint targets under `//flavors/sample-android-flavor:*lint_test` and `//sample-android:*lint_test`; 9 tests passed.
- `bazelisk test //flavors/sample-library-demo:sample-library-demo-test //flavors/sample-library-full:sample-library-full-test //sample-android-library:sample-android-library-debug-test //sample-kotlin-library:sample-kotlin-library-test`: passed.

Generated diff review:
- `WORKSPACE` now differs from master only by removing `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.7.3`; this is intentional because BOM/platform artifacts are filtered before rules_jvm_external pinning.
- `WORKSPACE` again preserves the master `maven.artifact(...)` shape for `androidx.constraintlayout:constraintlayout`, including exclusions for `androidx.appcompat:appcompat` and `androidx.core:core`.
- `sample-android/BUILD.bazel` and `flavors/sample-android-flavor/BUILD.bazel` move common core/lifecycle deps from `@debug_maven` to `@maven`; paging stays in `@debug_maven`. This matches the new bucket ownership: common implementation deps in default, debugImplementation deps in debug.
- `test_maven_install.json` and per-leaf maven install JSONs are deleted because the current root `WORKSPACE` no longer references those repos.
- `android_test_maven_install.json`, `debug_maven_install.json`, `lint_maven_install.json`, `ksp_maven_install.json`, and `maven_install.json` are repinned outputs. Their large diffs are mostly rules_jvm_external lockfile schema v3 hashes/services plus the changed bucket artifact sets.

Findings:
- Exclude metadata is collected from declared external dependencies across migratable project configurations and attached to direct emitted dependencies by `shortId`.
- The broader metadata-merge attempt in `unionDependencyMaps` caused unnecessary androidTest transitive repo growth and was reverted; the exclude fix does not need that merge because the exclude map is shared.
- `AndroidAapt2=standalone` is a narrower and lower-disk workaround than sandboxing. It removes the persistent worker state leak while preserving the rest of the worker configuration.

Remaining risk/open question:
- Broad `bazelisk test //...` is not green because generated lint test targets expose existing sample lint/resource issues: duplicate generated resources, `SetTextI18n`, missing constraints, missing/extra translations, and related sample lint findings. Treat this as sample lint debt unless the goal expands to fixing generated lint baselines/resources.

### 2026-06-17 10:57:42 +08 — Pending Risk Register for Next Guided Goal Session

Context:
- The current branch is a working refactor, not a finalized merge package. The root sample oracle and focused verifiers cover the high-signal behavior, but several edge-heavy paths are not yet proven deeply enough to call low-risk.
- The next session should be user-guided: decide which of these are release blockers, which need fixture coverage, and which are acceptable documented tradeoffs.

Least-confident areas:
- Variant-specific KSP handling. The current aggregated path still emits a broad `ksp_maven` bucket. It has not proven separate processors or processor versions per flavor/build type.
- Exclude metadata bucketing. Excludes are now preserved by `group:artifact`, but this is coarse if the same artifact is declared in multiple buckets with different exclude rules.
- Library-only and `compileOnly` placement. Non-app library compile classpaths are unioned into every leaf so unreachable deps land in `default`; this preserves availability but is broader than the old per-variant path.
- BOM/platform filtering. Current filtering is suffix-based (`-bom`/`.bom`), not Gradle attribute-based platform detection.
- Explicit opt-out path. The default no-fan-out path is verified, but the `aggregatedDependencyResolution = false` legacy path has not been refreshed as deeply in this run.
- Generated lint tests. `bazelisk test //...` remains red only for generated lint/sample issues; decide whether to fix baselines/resources or intentionally exclude those targets.

Edge-heavy scenarios to test before treating the refactor as broadly safe:
- Multiple app/test roots with overlapping but non-identical leaf sets.
- Real flavor-only external dependencies, not just debug-only plus common deps.
- Same `group:artifact` appearing in default/build-type/flavor/test buckets with different versions or excludes.
- `androidTest` and standalone `com.android.test` roots with dependencies that overlap main/runtime deps.
- Projects with no app binary root, or library-heavy repos where compile-only deps are important but not consumed by an app classpath.
- Variant-filtered projects where Gradle creates partial leaf sets and synthetic hierarchy buckets must still reconstruct the expected Maven repos.
- Non-`-bom` platform dependencies or POM-only artifacts that rules_jvm_external cannot pin.
