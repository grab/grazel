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

The current accepted PAX size baseline is 11 buckets, 11 materialized pinfiles, and 1945
input artifact roots. This is an improvement over the earlier Item 7 intermediate state,
but the non-default test/android-test/lint pin files are still considered size debt. The
remaining size is accepted only because PAX migrate, debug APK, android-test APK, selected
unit tests, and tag/reachability audits pass while preserving complete Gradle-resolved
closure.

The compact-root experiment was rejected because dropping closure artifacts removed that
version-forcing behavior and caused PAX pinning to stall. Future size work should happen
through better bucket placement, test/lint ownership, or a proven first-class version
constraint mechanism, not by masking Coursier conflicts with `--force-version`.

Old Grazel/PAX did not pass pinning because `override_targets` made Coursier ignore
conflicts. It passed because each split `maven_install` repo still carried the relevant
Gradle-resolved transitive closure in `artifacts`; with `version_conflict_policy =
"pinned"`, rules_jvm_external used those artifact coordinates as version-forcing inputs.
`override_targets` is a post-resolution Bazel-label redirection mechanism. It can point a
resolved target back to `@maven` or a patched label, but it is not a substitute for
including the Gradle-selected coordinate in the Coursier input set.

The remaining size problem is therefore upstream bucket ownership, not a license to drop
closure artifacts from materialized repos. The next optimization should reduce which
direct roots enter each bucket, then keep that bucket's Gradle-resolved closure complete
for Coursier. In particular:

- Direct declaration ownership should drive placement: `implementation` belongs in the
  default `maven` bucket, `debugImplementation` in `debug_maven`,
  `androidTestImplementation` in `android_test_maven`, etc. Declared versions may guide
  ownership only; selected versions still come from Gradle resolution.
- Test and android-test buckets should inherit main ownership instead of re-owning main
  dependencies. Their repos should own direct test deltas and carry only the closure
  needed for those deltas.
- Variant hierarchy/DAG placement should move shared direct dependencies to the closest
  common owner rather than duplicating them across leaf buckets.
- Lint and test buckets need tighter ownership. KSP is acceptable as a sidecar for this
  slice, but test/lint pinfiles are the main current bloat source.
- Candidate repos may exist for planning, but only repos with real direct-owned roots
  should materialize.

Correctness rule for the next goal: after a repo is materialized, every Gradle-resolved
coordinate needed to force that repo's selected dependency graph must remain in
`maven_install.artifacts`, even when `override_targets` redirects its generated Bazel
label elsewhere.

## Variant Compression

Variant compression remains downstream of extraction. This is a pre-existing shape and is
left alone because the dependency-refactor blockers are in dependency planning,
materialization, provenance, and reachability. Tags are excluded from compression
equivalence, so moving tag decisions into the plan does not change compression decisions.

## Target Reference Ordering

`collectTargetMavenRepoReferences` now uses typed dependency-graph reachability ordering so
acyclic project groups are processed once. SCC is no longer a normal target-reference
collection strategy: any genuine typed SCC fails in `ProjectReachabilityOrder` with typed
nodes and diagnostic edge labels. The previously suspected PAX
`deliveries-model-food:test -> food-testkit:main -> deliveries-model-food:main` shape is
covered as an acyclic typed graph.

The ordering graph is still not a dedicated target-reference graph. Non-configuration
references must be represented as typed dependency edges before ordering; otherwise the
graph layer should fail with diagnostics rather than reintroducing a task-local fixpoint.
PAX migrate, debug APK, android-test APK, focused unit tests, and the size guard pass with
this fail-closed shape.

Future work can make the altitude cleaner by modeling a first-class target-reference
reachability graph in the dependency/variant layer, augmenting Gradle project edges with
known non-configuration target edges before SCC ordering. The task should remain
orchestration only.

## Cacheability Boundary

The refactor preserves the master-like approach of letting Gradle own resolution and
passing resolved values into cacheable task boundaries where feasible.
`ResolveWorkspaceDependenciesTask` and `CollectKspProcessorDependenciesTask` remain
cacheable by design for this slice. The current cache key is not fully relocatable: it
includes live Gradle `ResolvedComponentResult` inputs, and KSP also carries absolute
artifact paths for processor-class extraction. This is accepted because the current goal
prioritizes preserving the verified task shape and PAX behavior. A future cacheability
hardening pass should serialize deterministic resolved-root models and use stable artifact
identity plus classpath inputs while keeping absolute paths execution-local.

## Renderer-Model References

`TargetMavenRepoReferencesCollector` still derives referenced Maven repos and project
targets from rendered target dependency/tag labels. This is not generated-file feedback and
does not read final BUILD output, but it keeps label formatting as part of planning
semantics. A future cleanup should expose typed Maven/project references from target
builders or dependency models so render-plan materialization no longer depends on label
string parsing.

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
