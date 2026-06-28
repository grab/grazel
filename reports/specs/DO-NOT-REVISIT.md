# Dependency Refactor Do Not Revisit

These are abandoned or explicitly constrained approaches for the dependency-refactor
goal. Keep this short so it remains useful after context compaction.

## Do Not Revert To Per-Module Full Resolution

The current architecture intentionally resolves app / `com.android.test` root classpaths
and fans resolved values down into buckets. The old master-style per-module/per-variant
resolution worked for PAX but was too slow and duplicated Gradle work. Fix missing seams
around planning, provenance, and reachability without returning to full module fanout
resolution.

## Do Not Use Coursier Force-Version Shortcuts

Coursier must be constrained through generated `maven_install.artifacts` that contain the
Gradle-resolved closure. Do not add `--force-version` or equivalent conflict masking to
make pinning pass.

## Do Not Scrape Rendered Output For Planning Decisions

Generated `BUILD.bazel` and `WORKSPACE` files are render outputs. They must not be parsed
as the source of truth for repo materialization, pin inputs, or compile-filter tag
decisions. Lift those decisions into `WorkspacePlan` / `WorkspaceRenderPlan`.

## Do Not Materialize Candidate Repos Blindly

`repoPlan.keys` are candidate repo definitions, not the emitted repo set. Materialized
repos must be limited to reachable generated deps/plugins/tags, override-target closure,
and always-materialized repos.

## Do Not Treat PAX As A Normal Commit Target

PAX is the verification workspace. Its local composite include build should pick up this
Grazel checkout. PAX build-logic can be temporarily adjusted for task-ordering
compatibility if necessary, but PAX changes are not committed from this goal unless the
maintainer explicitly asks for a local generated-output baseline commit. Do not push PAX.

## Do Not Execute Target Builders During Reference Collection

`CollectTargetMavenRepoReferencesTask` currently uses target builders as a planning input.
Item 19 exists to remove that round-trip. The intended end state is structured
`TargetReferenceFacts` feeding `WorkspaceRenderPlan`, then target builders running once during
BUILD generation.
