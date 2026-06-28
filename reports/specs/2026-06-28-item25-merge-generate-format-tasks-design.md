# Item 25 — Merge Generate + Format into One Task per Scope (Design)

> **Status:** Approved 2026-06-28 (grounded against the current task graph).
> **Executor:** Codex. **Behaviour change:** none to generated output — final in-tree
> `BUILD.bazel`/`WORKSPACE` must be byte-identical. Golden EMPTY-diff. **Task graph changes**
> (fewer tasks); format loses `@CacheableTask` — an accepted, documented tradeoff.
> **Global Constraints + Verification Playbook + Code-quality stance:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`. **Depends on:** Item 27; inherits Item 10 size guard.
> This task-graph cleanup intentionally runs after the branch-wide simplify/adversarial review.

> **⚠️ Execution note — delegate to subagents; protect the main context.** Task-graph
> verification and PAX runs go to focused subagents returning distilled results.

---

## Goal

Collapse the separate **generate** and **format** Gradle tasks into a **single task per scope**
(one per subproject + one root), removing task-graph complexity (halves the per-project task
count, removes the staged-file hand-off seam, and deletes a whole task class). This is
accidental complexity in the task graph — a clear win by the Item 1 code-quality yardstick.
Generated output is unchanged.

## Grounded current shape (verified)

```
per project:  generateBazelScripts(p)  ──output→input file──▶  formatBazelScripts(p)  ──▶ in-tree BUILD.bazel
                @UntrackedTask                                    @CacheableTask
root:         generateRootBazelScripts ──▶ formatWorkSpace + formatBuildBazel ──▶ WORKSPACE / root BUILD.bazel
                @UntrackedTask              @CacheableTask                  │
              generateBuildifierScript ──(buildifier launcher script)──▶ every format task
                                          formatWorkSpace.output ──▶ pinMavenArtifacts
```

- `GenerateBazelScriptsTask` (`tasks/internal/GenerateBazelScriptsTask.kt:55`, `@UntrackedTask`
  `:54`) is **per-subproject** (`TasksManager.kt:137-154`); action `:89-124` inits services,
  builds the project model (`bazelFileBuilder.create(project).targets()` `:105-106` →
  `projectBazelFileBuilder.build(targets)` `:116`), writes to the **staged** output
  `build/grazel/BUILD.bazelignore` (`buildBazel` `@OutputFile` `:84-87`), and runs
  `disableProjectBuildFile` rename logic (`:129-139`). Captures live `Project` at execution →
  reason for `@UntrackedTask`.
- `GenerateRootBazelScriptsTask` (`GenerateRootBazelScriptsTask.kt:54`, `@UntrackedTask` `:53`)
  is **root-only**; action `:86-121` traverses `rootProject.subprojects` at execution, writes
  `WORKSPACE` to its **final** location (`:73-75,106`) and root `BUILD.bazel` to the **staged**
  path (`:78-80,118`).
- `FormatBazelFileTask` (class in `FormatBazelBuildFileTask.kt:45`, `@CacheableTask` `:44`) —
  three instances (per-project `formatBazelScripts`, root `formatWorkSpace`, root
  `formatBuildBazel`). Action `:67-95`: copy staged `inputFile` → `build/grazel/<name>.tmp`
  (`:71-81`), `execOperations.exec [buildifierScript, tmp]` (`:82-87`), copy tmp → `outputFile`
  = the **final in-tree file** (`:88-93`). It is the step that *materializes* the formatted
  file. Format routine is **not extracted** — locked in this action.
- `GenerateBuildifierScriptTask` (`GenerateBuildifierScriptTask.kt:41`, `@UntrackedTask`) does
  `bazelisk run @grab-bazel-common//:buildifier --script_path=…` (`:63-70`) → launcher at
  `build/grazel/buildifier` (`:94`). **Stays** — the merged task still needs it as input.
- Edges: generate→format is **implicit** (output→input provider, no `dependsOn`);
  `postScriptGenerateTask.dependsOn(generateBazelScripts)` per project (`:151`);
  `pinMavenArtifacts.workspaceFile = formatWorkSpace.outputFile` (`:189`); `migrateToBazel`
  depends on post + all format tasks + pin (`:205-219`).

**Why split today (the one real reason):** generate is `@UntrackedTask` (live `Project`),
format is `@CacheableTask`. The split + `build/grazel/*.bazelignore` staging let the
deterministic buildifier step be cached independently. **Decision (maintainer):** accept losing
that — generate already always runs, so format caching only ever saved the buildifier exec on
no-op reruns (~N execs ≈ ~6s on PAX vs an 8–18min migrate). Worth it for the simplification.
Buildifier is already invoked **per file** (not batched), so merging serializes nothing that is
currently parallel.

## Target shape

```
per project:  generateBazelScripts(p)   [generates AND formats its own BUILD.bazel]   @UntrackedTask
root:         generateRootBazelScripts   [generates AND formats WORKSPACE + root BUILD.bazel]  @UntrackedTask
              generateBuildifierScript ──(launcher)──▶ both merged tasks
              generateRootBazelScripts.WORKSPACE ──▶ pinMavenArtifacts
```

`FormatBazelFileTask` is **deleted**. The merged tasks keep their generate task IDs/names:
`generateBazelScripts` and `generateRootBazelScripts`. The intermediate `format*` task IDs
disappear because `migrateToBazel` is the user entry point and formatting becomes an internal step
of generation.

## Work

1. **Extract the buildifier format routine** out of `FormatBazelFileTask.action()` (`:67-95`)
   into a reusable `internal` helper — e.g. a top-level
   `fun formatWithBuildifier(buildifierScript: File, source: File, dest: File, exec:
   ExecOperations, fs: FileSystemOperations)` — that reproduces the **exact** copy→exec→copy
   behaviour, including the temp-file naming/extension and the precise buildifier command line.
   (buildifier's output can depend on the filename it sees — preserve it byte-for-byte.)
2. **Fold formatting into `GenerateBazelScriptsTask`** (per-project): inject `ExecOperations` +
   `FileSystemOperations`; after building+writing the generated content, call
   `formatWithBuildifier(...)` so the task emits the **final formatted in-tree `BUILD.bazel`**.
   The staged `build/grazel/BUILD.bazelignore` becomes an internal intermediate of this task
   (write generated content there or to a temp, format, materialize final). Preserve
   `disableProjectBuildFile` and any ignore/strict-reachability rename behaviour unchanged.
3. **Fold formatting into `GenerateRootBazelScriptsTask`** (root): same injection; after writing
   `WORKSPACE` and the staged root `BUILD.bazel`, format **both** to their final locations via
   the helper. Preserve the existing final-location conventions.
4. **Delete `FormatBazelFileTask`** (`FormatBazelBuildFileTask.kt`) and its three registrations
   in `TasksManager.kt` (`:177-186`, `:195-203`).
5. **Rewire `TasksManager`:**
   - merged tasks consume `generateBuildifierScript.buildifierScript` as an input (keep the
     dependency so they wait for the launcher); root merged task keeps the explicit provider/task
     edge to `generateBuildifierScript`. Verify the launcher generation ordering with a dry run
     and remove any stale reverse edge that no longer has a real input/output reason.
   - `postScriptGenerateTask.dependsOn(merged per-project tasks)` (unchanged intent).
   - `pinMavenArtifacts.workspaceFile` ← merged **root** task's `WORKSPACE` output (`:189`
     retargeted to the merged task's now-final WORKSPACE).
   - `migrateToBazel` (`:205-219`) depends on `postScriptGenerateTask` + merged per-project
     tasks + merged root task + `pinMavenArtifacts` (+ existing conditional tasks). This
     replaces the two format-task lists with the merged tasks — simpler.
6. **Document the accepted cacheability tradeoff** in `KNOWN-LIMITATIONS.md`: "generate+format
   merged into one `@UntrackedTask` per scope; buildifier formatting is no longer separately
   cacheable. Accepted: generate is already untracked-and-always-runs, so the cost is ~N
   buildifier execs per migrate (~6s on PAX). Reverting requires fixing generate's live-`Project`
   capture so the merged task can be `@CacheableTask`."
7. **Clean task/formatting comments while touching the code.** Remove comments that are artifacts
   of the refactor process, AI/context churn, or historical migration notes. Comments retained or
   added in this item must explain durable behavior that a future maintainer needs to know, such as
   Gradle task wiring, failure isolation, temp-file safety, or buildifier filename sensitivity.
   Do not leave comments that say the code exists because "old code did X", "this pass changed Y",
   "temporary migration", or other meta-process breadcrumbs unless they describe an active,
   user-visible compatibility constraint.

## Care points (must honor)

- **Final in-tree output byte-identical.** Same generated content + same buildifier + same
  invocation ⇒ identical `BUILD.bazel`/`WORKSPACE`. The golden empty-diff is the proof.
  Intermediate `build/grazel/*` staging files may change or disappear (not committed, not
  golden) — only the final tree files are gated.
- **Preserve the temp-copy isolation** — never run buildifier in a way that leaves a
  half-formatted file in the source tree on failure; format a temp, then materialize. (Same
  property the old format task had.)
- **Preserve the exact buildifier command line + temp filename** when extracting the helper —
  output can be filename-sensitive.
- **Buildifier-launcher dependency is retained** — the merged tasks cannot format without
  `generateBuildifierScript`'s output; keep that edge.
- **Error isolation is preserved by construction** — a generate failure fails the one task
  before its format step runs (same net effect as today's skip).
- **Comment hygiene is part of the slice** — comments in touched task/formatting code must encode
  durable engineering facts, not refactor history, LLM artifacts, context breadcrumbs, or migration
  diary entries.

## Safety mechanism

- **Sample golden EMPTY-diff** + **PAX generated diff stable** vs the frozen Item 10 baseline.
  Any final-file diff is stop-and-investigate.
- **Verify the task graph** with `reports/scripts/verify-default-task-graph.sh` (no orphaned
  `format*` tasks scheduled; `migrateToBazel` still reaches all final files) and a `--dry-run`
  of `migrateToBazel` before/after to confirm the merged tasks run in the right order and pin
  still runs after the (now-merged) WORKSPACE producer.
- **Size guard (Item 10):** no change expected.
- **Full final verification is mandatory.** Do not exit after a local task-graph check. The item
  requires Grazel migrate, PAX migrate, both PAX APK builds, task-graph verification, and comment
  hygiene before completion.

## Acceptance criteria

- `FormatBazelFileTask` deleted; per-project and root generate tasks each generate **and** format
  their own final files via the shared `formatWithBuildifier` helper.
- `generateBuildifierScript`, `postScriptGenerateTask`, `pinMavenArtifacts` rewired to the merged
  tasks; `migrateToBazel` reaches all final outputs with fewer tasks.
- Sample golden empty-diff; PAX generated diff stable; PAX migrate + both APKs green; size guard
  no-increase.
- `verify-default-task-graph.sh` green; `migrateToBazel --dry-run` shows the merged shape with
  pin after the root task.
- Cacheability tradeoff recorded in `KNOWN-LIMITATIONS.md`.
- Touched task/formatting code has no stale comments that describe the implementation journey
  rather than the current invariant.
- No `formatBazelScripts`, `formatWorkSpace`, or `formatBuildBazel` task registrations remain in
  the live task graph.
- No local-only success claim: completion requires the PAX final guard, not just sample/golden
  checks.

## Out of scope / Non-goal

- **Gradle Worker API / single-task internal fan-out — considered and explicitly dropped
  (2026-06-28).** Workers can't run generation: a `WorkAction`'s parameters must be serializable
  managed types and may not reference a live `Project`/`Configuration`/AGP variant, but
  generation is built from the live `Project` at execution (`bazelFileBuilder.create(project)`,
  `disableProjectBuildFile`, `project.file`). A single task fanning out over all projects is
  possible but only *sequentially* (losing the cross-project parallelism N tasks give), so it's
  a net slowdown on large repos. The worker-parallel single-task end state is gated on first
  making generation `Project`-free (rendering a serialized upstream target model — the deferred
  refactor below); it is **not** part of this item. Keep the N per-project tasks (Gradle gives
  the parallelism for free).
- Fixing generate's live-`Project` capture / making the merged task `@CacheableTask` (the large
  pre-existing config-cache refactor — explicitly deferred; it is also the prerequisite for any
  future worker-based fan-out).
- Removing the `build/grazel` staging entirely if it serves the ignore/strict-reachability
  mechanism — keep that behaviour; only the generate→format hand-off file becomes internal.
- Any change to generated content, bucketing, ownership, or `generateBuildifierScript` itself.
