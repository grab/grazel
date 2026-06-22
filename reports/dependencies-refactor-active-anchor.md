# Dependency Refactor Active Anchor

Last updated: 2026-06-22.

Read this file first after every context compaction/resume. `reports/dependencies-refactor-current-truth.md`
is now compacted; use it for a slightly fuller current-state memo, not as a transcript.

## Current Goal

Get the dependency refactor to merge-ready correctness and architecture:

- root app / `com.android.test` resolution is the expensive Gradle-resolved source of truth;
- variant APIs and cheap declared metadata drive ownership, excludes, and bucket shape;
- module extractors remain local and consume precomputed dependency/service data;
- PAX migration, debug APK, android-test APK, and unit/lint Bazel gates pass;
- shortcut fixes and wrong-altitude graph walks are removed before final review.

## Current Critical Decisions

- There are two known-compiling references:
  - Grazel master: old plugin behavior that supported PAX, but was slow and may be suboptimal.
  - PAX master: checked-in/generated PAX output that compiles, but may include legacy over-allowing.
- Use both as references, not blind targets. Prove dependency ownership before copying generated
  `tags` shape.
- Target `tags` stay local:
  - direct project deps become `@direct//...`;
  - a target's own direct Maven roots plus their Gradle-resolved closure become normalized
    `@maven//...` tags;
  - parent targets must not union Maven closures from child project targets.
- Maven `tags` are classpath-filter metadata and must use `@maven//:artifact_name`, even when actual
  `deps` are owned by repos such as `@debug_maven`, `@lint_maven`, or `@android_test_maven`.
- Gradle-resolved identity is the source of truth for versions. Declared metadata can guide
  ownership/excludes/buckets, but must not replace selected versions.
- Do not resurrect the rejected `MavenTagClosureCollector` / project-graph parent-tag union. It fixed
  one symptom but bloated PAX tags and lived at the wrong altitude.

## Debugging Seams

When a missing-class or bucket failure appears, inspect these seams in order:

1. PAX ownership seam: does the failing module directly use the artifact, and is it declared in the
   module's Gradle configuration? If not, a PAX `build.gradle` fix may be correct.
2. Variant metadata seam: did `VariantBuilder` / cheap declared metadata carry that configuration
   into the expected target variant or typed test/androidTest variant?
3. Root resolved graph seam: did `ResolveWorkspaceDependenciesTask` /
   `AggregatedDependencyResolver` / `ResolvedComponentsVisitor` observe the selected artifact and
   artifact-edge transitive closure from the root app/test graph?
4. Service seam: did `DependencyResolutionService` expose the right bucket label and closure for
   the target's own direct Maven root?
5. Module generation seam: did `Dependencies.collectTransitiveMavenDeps` and the relevant extractor
   emit local direct deps plus normalized tags without walking project dependencies?
6. Bazel/classpath seam: did generated `BUILD.bazel` tags/deps match the classpath-filter contract,
   and does the focused Bazel target compile?

Use focused diagnostics (`logger.quiet`, targeted generated files, small `jq`/script reads, or a
small purpose-built diagnostic Gradle task/file) before rerunning full PAX `migrateToBazel` or APK
gates.

## Context Maintenance Rules

- After each major command/fix, update this anchor with only current decisions, open blockers, and
  next gates.
- Keep this file short. If it grows beyond roughly 150 lines, compact it before continuing.
- Append detailed evidence to `reports/dependencies-refactor-current-status.md`.
- Use subagents for clean, bounded slices:
  - compare against Grazel master or PAX master;
  - audit generated `BUILD.bazel` shape;
  - extract focused data from large JSON/log files;
  - run read-only code review/simplify checks.
  Require exact file/line citations or command summaries; do not let subagents set priorities.
- When old sections conflict, prefer this anchor plus the latest dated status section. Then mark or
  remove the stale wording during routine maintenance.
- Do not delete evidence without preserving the current conclusion and command outcome in this
  anchor or the current-status report.

## Next Required Loop

- The PAX ownership fix for `deliveries-menu-items` has passed the main correctness loop:
  PAX `migrateToBazel`, focused `deliveries-menu-items-gps-pax-debug_kt`, and full
  `//app:app-gps-pax-debug.apk` + `//app:app-gps-pax-debug-android-test.apk` all passed.
- Keep the decision: this was a PAX direct-dependency fix, not Grazel tag broadening or
  databinding/annotation auto-injection.
- Databinding filter decision: do not auto-add databinding libraries. For databinding-enabled
  targets, filter artifacts provided by grab-bazel-common databinding macros from direct Bazel
  `deps`: `androidx.databinding:*`, `com.android.databinding:*`, and
  `androidx.annotation:annotation`. Non-databinding modules can still emit explicit annotation deps.
- If evidence shows Grazel dropped valid owned metadata, add a focused Grazel test first, then fix
  the lowest correct layer.
- PAX app-specific unit target discovery found no generated `gps-pax-debug` unit-test target under
  `//app:*`; the available app gate is `//app:app-gps-pax-debug.lint_test`.
- PAX lint audit: `//app:app-gps-pax-debug.lint_test` fails on `SerializedNameDefaultValue` errors
  in external Maven AARs, but focused baseline comparison shows the binary lint target already
  reached the named AARs through `HEAD` deps/lockfiles. Treat this as a documented PAX baseline lint
  exposure unless later evidence ties it to the refactor.
- Duplicate-annotation fix is verified through PAX:
  - fresh `./gradlew migrateToBazel --no-daemon --console=plain --stacktrace` passed in PAX;
  - generated `app-gps-pax-debug` no longer emits `androidx_annotation_annotation` as a direct
    app dep while databinding is enabled;
  - `./bazel.sh build //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk
    --verbose_failures` passed after the fix.
- PAX build still prints rules_jvm_external duplicate-version debug messages for annotation,
  databinding, Dagger, and Kotlin artifacts. They are not blocking the APK gate, but should be
  audited if Coursier warning cleanup is required before merge.
- Bounded audit says those duplicate-version warnings are plausibly preexisting `WORKSPACE`
  composition with `GRAB_BAZEL_COMMON_ARTIFACTS`/Dagger artifacts, not duplicate rows in generated
  Maven lock JSONs and not a clear current-refactor regression.
- Bounded PAX target/tag audit says `app-gps-pax-debug` deps count is unchanged vs `HEAD`, has no
  direct annotation dep while databinding is enabled, and android-test tags are unique/normalized
  (`@direct`, `@maven`, `@self`) with no obvious contract violation.
- Latest resource cleanup: PAX disk fell to about `14GiB` free after APK packaging; `bazelisk clean
  --expunge` recovered it to about `30GiB`; no Gradle/Bazel process was left running.
- Remaining loop: finish simplify pass, adversarial review, final focused local checks, and final
  status summary.
