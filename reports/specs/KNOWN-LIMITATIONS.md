# Dependency Refactor Known Limitations

This file records accepted limitations for the dependency-refactor branch. These are not
merge blockers for the current review slice unless one of them causes a verified PAX or
Grazel build failure.

## Root Classpath Scope

Grazel now uses app and `com.android.test` root classpath resolution as the source of
resolved Maven values. Library-only, JVM-only, and standalone library-test roots are not
first-class root nodes in this slice.

This is intentional for the current PAX acceptance target. Reintroducing old per-module
full resolution would undo the performance objective of the refactor.

## Declared Metadata Scope

Declared Gradle metadata is collected only as cheap metadata: direct declarations,
excludes, project edges, KSP ownership, and test ownership. Declared versions are not the
source of selected artifact values; Gradle-resolved values remain authoritative.

## Maven Pin Size

Some non-default pin files, especially `android_test_maven_install.json`,
`test_maven_install.json`, and `lint_maven_install.json`, are larger than desired. The
current accepted behavior keeps the Gradle-resolved closure in
`maven_install.artifacts` so rules_jvm_external/Coursier are constrained to the Gradle
selected versions.

The compact-root experiment was rejected because dropping closure artifacts removed that
version-forcing behavior and caused PAX pinning to stall. Future size work should happen
through better bucket placement, test/lint ownership, or a proven first-class version
constraint mechanism, not by masking Coursier conflicts with `--force-version`.

## Variant Compression

Variant compression remains downstream of extraction. This is a pre-existing shape and is
left alone because the dependency-refactor blockers are in dependency planning,
materialization, provenance, and reachability. Tags are excluded from compression
equivalence, so moving tag decisions into the plan does not change compression decisions.

## Cacheability Caveat

The refactor preserves the master-like approach of letting Gradle own resolution and
passing resolved values into cacheable task boundaries where feasible. A future cleanup
can revisit serialized resolved-component inputs versus live Gradle result objects, but
that is not required for the current PAX acceptance path.

## Verification Waivers

- PAX app-specific generated unit-test targets under `//app:*` are not expected; app
  query currently exposes lint-style tests there.
- PAX lint failures involving external Maven AARs, such as `SerializedNameDefaultValue`,
  are treated as pre-existing unless a current run proves this branch introduced them.
- rules_jvm_external duplicate-version diagnostic messages are tolerated when the build
  completes; the generated artifact closure is intentionally used to constrain Coursier
  to Gradle-selected versions.
- Root `./gradlew check` is waived for the current dependency-refactor review slice
  because it fails on sample-app lint that this branch did not modify:
  `sample-android/src/main/res/layout/activity_main.xml:73 MissingConstraints`.
- Root `bazelisk build //...` / `bazelisk test //...` are waived for this slice due
  local sample/rule hygiene issues that are not dependency-refactor regressions:
  - crashlytics generated manifest output is missing in Android configuration for
    sample-android lint/resource linking;
  - `flavors/sample-android-flavor` has duplicate generated `res_values` key
    `generated_value`.

  The generated crashlytics helper and duplicate `res_values` shapes already exist in
  `origin/master` BUILD files. Branch-vs-master sample diffs are dependency-label
  movement, not crashlytics or `res_values` structure changes. If local root Bazel
  green is required before merge, handle these as separate sample/Bazel hygiene fixes:
  crashlytics at the rule integration layer, and duplicate resources at sample resource
  ownership.
