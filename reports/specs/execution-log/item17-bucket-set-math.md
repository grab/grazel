# Item 17 - Bucket Set Math Consolidation

## 2026-06-28

- Active spec: `reports/specs/2026-06-28-item17-consolidate-bucket-setmath-design.md`.
- Change made:
  - Added `BucketSetMath.kt` as the single home for general bucket ownership set-math helpers.
  - Removed duplicate general helpers from `AggregatedDependencyResolver.kt`,
    `BucketOwnershipPlanner.kt`, and `DependencyBucketPlacementEngine.kt`.
  - Left planner-specific test coverage helpers in `BucketOwnershipPlanner.kt`.
- Resource check before Gradle work:
  - `/System/Volumes/Data` had about `28GiB` free.
  - PAX `bazel-cache` was about `14G`.
  - No stale Gradle/Bazel/Coursier or high-RAM `python3.12` process was observed.
  - Full `du` over private Bazel roots and Gradle caches was stopped because it was too slow;
    no cleanup was triggered for this focused run.
- Verification:
  - `./gradlew :grazel-gradle-plugin:test --tests "com.grab.grazel.gradle.dependencies.AggregatedDependencyResolverTest" --tests "com.grab.grazel.gradle.dependencies.DependencyBucketPlacementEngineTest" --tests "com.grab.grazel.gradle.dependencies.BucketOwnershipPlannerTest" --console=plain --no-daemon`
    passed.
  - `./gradlew migrateToBazel --console=plain --no-daemon` passed.
  - `git status --short` after migrate showed no generated-output changes; only source/docs
    files from this item are dirty.

## 2026-06-28 Final Review Fix

- Final spec-compliance review found and removed the resolver's dead
  `withoutDeclaredPlaceholdersCoveredByDefault` copy. The only remaining helper with that name is
  the planner-private live path in `BucketOwnershipPlanner.kt`.
