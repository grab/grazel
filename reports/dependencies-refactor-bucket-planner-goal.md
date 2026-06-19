# Dependency Refactor Bucket Planner Goal

Status: ready to commit checkpoint
Started: 2026-06-19

## Objective

Introduce the first explicit MAIN dependency bucket planner so bucket placement
is separated from resolved-graph extraction.

## Scope

- Keep the current task shape from the previous architecture slice.
- Use `ResolvedComponentsVisitor` and binary-root classpath resolution only to
  extract resolved dependency facts.
- Use declared metadata variant topology to drive MAIN bucket placement.
- Model default, build-type, flavor, and leaf residual buckets in a pure
  planner that can be tested without Gradle task wiring.
- Preserve test/androidTest, KSP, library-only root behavior, and broad cleanup
  as separate follow-up work.

## Decisions

- Prefer a small explicit planner before committing to a larger DAG abstraction.
  A full DAG remains useful only if the placement rules become hard to reason
  about after this simpler extraction.
- Explicit hierarchy buckets from Gradle declarations win over inferred
  intersections. This preserves nearest-owner behavior such as
  `implementation "x:y:1.0"` plus `debugImplementation "x:y:2.0"` becoming
  `maven` for `1.0` and `debug_maven` for `2.0`.
- Inferred non-default hierarchy buckets require at least two descendant leaves.
  This avoids promoting a dependency from a single filtered leaf into a broad
  build-type or flavor bucket unless Gradle has an explicit hierarchy bucket.
- Identity remains bucket-owner aware: version, excludes, repository, Jetifier
  flags, and transitive ownership must not be collapsed just because two
  dependencies share `group:name`.

## Implementation Notes

- Added `MainDependencyBucketPlanner`, `MainDependencyBucketPlan`, and
  `MainBucketVariant`.
- `AggregatedDependencyResolver` now delegates MAIN default/build-type/flavor
  and leaf residual placement to the planner.
- `DeclaredDependencyMetadata.mainBucketVariants()` adapts serialized variant
  metadata into the planner input.
- The resolver still owns root snapshot visitation, dependency fact extraction,
  test/androidTest bucketing, lint bucketing, and KSP sidecar handling.

## Generated Output Notes

- Root `migrateToBazel` moved `androidx.fragment:fragment` into
  `free_maven_install.json` and the generated `free_maven` block in
  `WORKSPACE`.
- This is accepted as reasonable bucket movement for the first explicit planner
  checkpoint if verification remains green.

## Verification Log

- RED before implementation:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.MainDependencyBucketPlannerTest" --console=plain`
  failed because `MainDependencyBucketPlanner` and `MainBucketVariant` did not
  exist.
- GREEN during implementation:
  `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.MainDependencyBucketPlannerTest" --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --console=plain`
  passed.
- GREEN during implementation:
  `./gradlew :grazel-gradle-plugin:functionalTest --tests "com.grab.grazel.migrate.BuildVariantTest.migrateToBazelWithFlavorsWereUsed" --console=plain`
  passed.
- GREEN during implementation:
  `./gradlew migrateToBazel --console=plain` passed.

## Pending For Next Goal

- Decide whether this planner remains sufficient or whether to introduce a real
  bucket DAG abstraction for more complex placement rules.
- Add a focused fixture for partial leaf sets with flavor-only dependencies if
  generated output or a real repo shows a regression.
- Keep test/androidTest hierarchy precision low priority unless a failing case
  appears; the long-term direction is master-like hierarchy modeling.
- Keep KSP as the current cacheable sidecar/global bucket until bucketed KSP is
  explicitly prioritized.
- Library-only/JVM-only repositories remain out of scope for this slice; Grazel
  still expects an app or `com.android.test` edge root for aggregated
  resolution.
- Compatibility cleanup and old-code removal should happen only after the
  architecture and generated-output baseline are accepted.
