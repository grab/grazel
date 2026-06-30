# Item 33 - Variant-Layer Declared Config Roles Log

## 2026-06-29 +08

Active spec: `reports/specs/2026-06-29-item33-variant-layer-declared-config-roles-design.md`.

Goal: relocate declared-metadata configuration role classification from
`gradle.dependencies` to `gradle.variant` without changing generated output.

Implementation decisions:

- Added `VariantDependencyConfigurationRoles.kt` in `gradle.variant`.
- Moved the collector-owned declaration bucket predicate, excluded fragment
  list, declaration suffix list, suffix stripping helper, compile-only
  declaration predicate, `declarationBucketName()`, and `compileOnlyBucketName`
  out of `DeclaredDependencyMetadataCollector.kt`.
- Added typed accessors:
  - `Variant<*>.declaredDependencyConfigurations`
  - `Variant<*>.compileOnlyDeclaredDependencyConfigurations`
- Kept `Dependencies.kt`'s separate `isExternalDependencyDeclaration`
  classifier unchanged, as required by the spec. It now imports the single
  variant-layer `declarationBucketName()`.
- Replaced collector `BaseVariant` access with existing variant-layer
  `isWorkspaceAndroidLeaf`, `workspaceBuildTypeName`, and
  `workspaceProductFlavorNames`.
- Changed `Configuration.extractDeclaredExcludeRulesByShortId()` to only
  extract from the configuration it is handed. Filtering now happens before the
  call via `variant.declaredDependencyConfigurations`.

Red/green and debugging evidence:

- Added `VariantDependencyConfigurationRolesTest` before production changes.
  Red run failed to compile on missing accessors/functions.
- After moving the code, focused variant-role and collector tests passed.
- Broader dependency tests initially failed in
  `AggregatedDependencyResolverTest.declared exclude metadata ignores inherited
  classpath dependencies` because the old test called
  `debugRuntimeClasspath.extractDeclaredExcludeRulesByShortId()` directly and
  expected the extractor to self-filter. Root cause: Item 33 intentionally
  moved the declaration-role filter to the variant layer. The test was updated
  to assert the new caller contract: pass only already-classified declared
  dependency configurations.
- Added collector regression
  `declared exclude metadata ignores non declaration configurations` so a
  non-declaration tool config with excludes does not leak into metadata.

Commands/results:

- Red test:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.variant.VariantDependencyConfigurationRolesTest" --console=plain --no-daemon`
  failed on unresolved moved accessors/functions.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.variant.VariantDependencyConfigurationRolesTest" --tests "com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadataCollectorTest" --console=plain --no-daemon`
  passed.
- `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.DependencyBucketPlacementEngineTest" --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --console=plain --no-daemon`
  passed after updating the test contract.
- `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed.
- `./gradlew migrateToBazel --console=plain --no-daemon` passed.
  Sample metric stayed stable:
  `mode=PROJECT_TASK_FANOUT projects=10 shards=10 aggregateJsonBytes=145401 elapsedMs=16`.
- `reports/scripts/verify-default-task-graph.sh` passed.
- `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed:
  11 buckets, 11 pinfiles, 1945 total artifact roots, no per-repo deltas.
- `git diff --check` passed.
- `git diff --check master...HEAD` passed.
- `reports/scripts/verify-sample-bucket-labels.sh` failed on the known
  pre-existing appcompat/constraintlayout exclude-union assertion.
- PAX resource checkpoint before the expensive loop: storage stayed above the
  cleanup threshold after the earlier Bazel cleanup; `pax-android/bazel-cache`
  was preserved.
- PAX
  `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
  passed in `28m`. The first Maven pin no-build check was slow because Bazel
  rehydrated external repositories after cleanup, then every Maven repo was
  reported up-to-date. Declared metadata metric stayed output-equivalent:
  `mode=PROJECT_TASK_FANOUT projects=2327 shards=2327 aggregateJsonBytes=35247531 elapsedMs=442`.
- PAX `git diff --check` passed after migrate. PAX status remained exactly the
  accepted dirty baseline:
  `Constants.kt`, `Grazel.kt`, `ModuleLoggerTask.kt`,
  `generated/dependency_graph.json`, and untracked `Buildifier.kt`.
- `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed after
  PAX migrate: 11 buckets, 11 pinfiles, 1945 total artifact roots, no per-repo
  deltas.
- PAX
  `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
  passed in `751.827s`: 54,474 total actions.
- PAX
  `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test`
  passed in `23.001s`: 3 test targets passed.
- PAX final `git diff --check` passed and generated output stayed on the
  accepted baseline.

Item 33 checkpoint result:

- Local Grazel gates and PAX migrate/build/focused-test gates passed.
- Generated output is preserving/empty-diff against the accepted baselines.
- Initial Item 33 relocation landed in local commit `5868d22`
  (`refactor: move declared config roles to variant layer`). Post-review cleanup
  landed in local commit `2a780b5` (`refactor: tighten item33 cleanup`).

Post-commit simplify-pass checkpoint:

- Baseline commit before this pass: `5868d22`.
- Scope: committed branch diff through Item 33 only. Future Item34 docs were
  intentionally left out.
- Applied preserving cleanup from the simplify reviewers:
  - reused `VariantGraphKey.from(variant)` and `TEST_VARIANT` in
    `WorkspaceTargetTagPlanCollector`;
  - derived project reference facts from canonical `ProjectDependency.toString()`
    labels instead of a second target-name formula;
  - removed duplicate normalization in target Maven repo reference collection;
  - shared exclude-rule grouping while keeping all-dependency and
    declared-dependency caller contracts separate;
  - memoized selected descendant leaves inside one bucket placement plan and
    removed duplicate graph coverage helpers.
- Debugged and fixed one compile-only cleanup mistake: a private generic
  extension collided on JVM erasure with the public
  `extractExcludeRulesByShortId(Iterable<Configuration>)` helper, so it was
  replaced with explicit `excludeRulesByShortId(dependencies = ...)`.
- Focused verification passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.TargetReferenceFactsCollectorTest" --tests "com.grab.grazel.tasks.internal.WorkspacePlanTasksTest" --tests "com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadataCollectorTest" --tests "com.grab.grazel.gradle.dependencies.WorkspaceTargetTagPlanCollectorTest" --tests "com.grab.grazel.gradle.dependencies.DependencyBucketPlacementEngineTest" --console=plain --no-daemon`.
- `git diff --check` passed.
- Deferred larger simplify findings:
  root resolution sharding, moving target-reference collection/KSP extraction
  out of task actions, typed facts replacing regex/string parsing, owner facts
  replacing bucket-name string inference, and neutral-package Maven tag helper
  ownership.

Adversarial review checkpoint:

- Ran four read-only adversarial reviewers after simplify cleanup.
- Fixed the confirmed current-patch P1:
  `ProjectDependency` reference facts were briefly derived by parsing the
  rendered Bazel label, which could use a filesystem package path instead of
  Gradle `project.path`. `ProjectDependency` now exposes its canonical target
  name while `TargetReferenceFactsCollector` keeps the typed Gradle project path.
- Added regression coverage for a project whose Gradle path differs from its
  rendered label path:
  `TargetReferenceFactsCollectorTest.structured project references use Gradle path instead of rendered label path`.
- Explicitly rejected the `ResolvedComponentResult @Input` cacheability finding
  as a design disagreement: root components remain cacheable task inputs by
  maintainer decision and prior master behavior; no validation failure observed.
- Logged but did not change output-moving candidates in this preserving cleanup:
  KSP aggregated repo transitive closure, default override-carrier version
  promotion, structured declared project edges/root exclude metadata, and the
  Item34 target-reference service boundary.
- Verification after the fix passed:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.TargetReferenceFactsCollectorTest" --console=plain --no-daemon`
  and the broader touched-area focused test command.

Final verification after simplify/adversarial fixes:

- `./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon` passed.
- `./gradlew migrateToBazel --console=plain --no-daemon` passed with no local
  generated-output diff.
- `reports/scripts/verify-default-task-graph.sh` passed.
- `reports/scripts/verify-pax-size-guard.sh --mode preserving` passed: 11
  buckets, 11 pinfiles, 1945 total artifact roots, no per-repo deltas.
- `reports/scripts/verify-json-phase-inventory.sh` passed after refreshing the
  moved line numbers for `CollectDeclaredDependencyMetadataTask`.
- `git diff --check` and `git diff --check master...HEAD` passed.
- `reports/scripts/verify-sample-bucket-labels.sh` remains a known
  pre-existing/local waiver on the one-sided appcompat/constraintlayout exclude
  assertion.
- PAX branch `arun/grazel-refactor` at
  `cfa1057ed58ccb2a795a5f679f072a8f604ff48e` passed:
  `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks`
  in `11m21s`, with
  `mode=PROJECT_TASK_FANOUT projects=2327 shards=2327 aggregateJsonBytes=35247531 elapsedMs=481`;
  `./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk`
  in `215.433s`; and
  `./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test`
  in `16.790s`.
- PAX status stayed on the accepted dirty baseline:
  `Constants.kt`, `Grazel.kt`, `ModuleLoggerTask.kt`,
  `generated/dependency_graph.json`, and untracked `Buildifier.kt`.
