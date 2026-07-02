# Item 35 — User-Facing Progress Reporting for Dependency-Refactor Tasks (Design)

> **Status:** Approved 2026-07-01 (brainstormed + grounded by Opus exploration of the branch diff).
> **Executor:** Codex. **Behaviour change:** none for generated output — progress is a pure
> side-effect (status line + user-facing log). Golden EMPTY-diff; PAX size guard no-change.
> **Global Constraints + Verification Playbook + Code-quality stance:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`. **Depends on:** none hard; touches the new task layer
> + the pure-JVM collaborators it drives.

> **⚠️ Execution note — delegate to subagents; protect the main context.** Verification and PAX
> runs go to focused subagents returning distilled results.

---

## Goal

The dependency-refactor branch added a chain of long-running tasks (resolve, declared-metadata
collect, tag-plan collect, maven-ref collect, variant compression) that loop over all
subprojects/variants/roots but surface **no live feedback** to the user — a `migrateToBazel` run
goes quiet for the heavy middle of the pipeline. Add a **single-line transient progress ticker**
per heavy task ("processing `:module` (i/n)") plus a **permanent one-line summary** at each heavy
task's end, **without** importing the Gradle API into the strictly pure-JVM logic classes.

**Message style:** no `Grazel:` prefix on progress or summary text — keep messages plain.
Progress tickers may be lower-case imperative/status text
("resolving `:module` (i/n)"). Permanent summaries are capitalized, for example
`Resolved 123 deps across 4 roots in 812ms`.

Two channels, kept strictly separate:

- **Progress** — Gradle `ProgressLogger`, transient single status line. THE user-facing live
  channel. Threaded into pure-JVM code via a lambda, never the Gradle type.
- **Permanent summary** — `logger.quiet(...)` (this codebase's established user-facing log level,
  e.g. `GenerateRootBazelScriptsTask`, `ArtifactPinner`, `Bazel.kt`). Persists in the build log.
  **Never** `logger.lifecycle`/`info`/`warn` — those stay reserved for diagnostics, untouched.

## Grounded current state (verified by Opus exploration; Codex: re-confirm line refs)

**Existing infrastructure (reuse, do not reinvent):**
- `util/ProgressLogger.kt:22-24` — `ProgressLoggerFactory.startOperation(message): ProgressLogger`
  (returns a started operation). `:26-31` — nested `startOperation<T>(message, parent)`.
- `util/Progress.kt:21-31` — `object NoOpProgressLogger : ProgressLogger` (null object).
- `di/GradleServices.kt:42` — `progressLoggerFactory` is already bundled. Some target tasks do not
  currently inject `GradleServices`; use `GradleServices.from(project).progressLoggerFactory` or
  explicit injection deliberately.
- **Live precedent** for the transient ticker UX: `migrate/dependencies/BazelLogParsingOutputStream.kt`
  calls `progressLogger.progress("Downloading …")` to overwrite the status line during bazel runs.
- **Existing convention** for user-facing permanent lines: `logger.quiet("…".ansiGreen)` (agent
  confirmed `quiet` is the dominant user-facing channel; `info`/`warn` are diagnostic).

**Gradle log-level reality (the reason for the channel split):** default console shows
`QUIET`/`LIFECYCLE`/`WARN`/`ERROR`. `info` is hidden in normal runs. Summaries must therefore use
`quiet`, not `info`/`lifecycle`.

**Heavy N-of-M tasks and their pure-JVM seams (the ticker targets):**

| Task (file) | Loop / tick site | Pure-JVM collaborator | Gradle coupling of collaborator |
|---|---|---|---|
| `ResolveWorkspaceDependenciesTask.kt` | per config-root | `AggregatedDependencyResolver.kt:409` (`collectRootClosures` `forEach`) | `Logger` only (`:32`) — direct-instantiated at `ResolveWorkspaceDependenciesTask.kt:80` |
| `CollectDeclaredDependencyMetadataTask.kt:109` (SINGLE_TASK) | per project, **parallel coroutines** at `:287` | `DeclaredProjectMetadataSnapshotter` | live `Project` |
| `MergeDeclaredDependencyMetadataTask.kt:210` | per shard file | `DeclaredDependencyMetadataMerger` | pure JVM |
| `CollectKspProcessorDependenciesTask.kt:54` | per KSP root component | visitor | — |
| `CollectWorkspaceTargetTagPlanTask.kt:45` | per subproject at `WorkspaceTargetTagPlanCollector.kt:83` (**module granularity — NOT** the per-variant `:185`) | `DefaultWorkspaceTargetTagPlanCollector` (Dagger `@Singleton`) | live `Project` (method param) |
| `CollectTargetMavenRepoReferencesTask.kt:54` | per project (topological order) | `TargetReferenceFactsExtractor` | live `Project` |
| `AnalyzeVariantCompressionTask.kt:82` | per Android-lib project (already `info`-logs per project) | — | — |

**Existing summaries to standardize (move to `quiet`):** `CollectDeclaredDependencyMetadataTask.kt:131,235`
and `MergeDeclaredDependencyMetadataTask` currently emit summaries at `logger.lifecycle("Grazel: …")`.

**Explicitly NOT ticked (out of scope for the ticker):**
- `CollectProjectDeclaredDependencyMetadataTask.kt:158` — 1 project per task instance; the
  PROJECT_TASK_FANOUT *is* the Gradle-graph-level progress. No internal loop to tick.
- `GenerateBazelScriptsTask` / `GenerateRootBazelScriptsTask` — already emit permanent
  `logger.quiet("Generated …")` lines per project; adding a one-item ticker is noise.
- Monolithic compute/finalize/serialize tasks (`ComputeWorkspaceDependenciesTask`,
  `ComputeWorkspacePlanTask`, `FinalizeWorkspacePlanTask`, `CollectWorkspaceDependencyRootMetadataTask`,
  `PinMavenArtifactsTask`) — no internal collection loop; nothing meaningful to tick.

## Work

### Part 1 — The pure-JVM abstraction (S, the "lambda it out" seam)

Create `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/util/ProgressReporter.kt` with **zero
Gradle imports**:

```kotlin
package com.grab.grazel.util

fun interface ProgressReporter {
    fun report(message: String)

    companion object {
        val NoOp = ProgressReporter { }
    }
}
```

- This is the **only** type the pure-JVM logic classes see. It carries a pre-formatted string; the
  `(i/n)` formatting lives at the call site, which already holds `i` and `n`.
- `NoOp` exists **for tests to pass explicitly**. It is **NOT** a default parameter value on any
  production method (see Part 3) — a forgotten reporter must be a compile error, not a silent
  no-op.

### Part 2 — The adapter (the only new Gradle-touching code)

Add one helper to the existing Gradle-aware `util/ProgressLogger.kt`:

```kotlin
inline fun <T> ProgressLoggerFactory.withProgress(
    header: String,
    block: (ProgressReporter) -> T
): T {
    val op = startOperation(header)
    try {
        return block(ProgressReporter { op.progress(it) })
    } finally {
        op.completed()
    }
}
```

- `op.progress(it)` overwrites the single status line in place (single-line constraint honoured).
- The `ProgressReporter { op.progress(it) }` lambda is the Gradle↔pure-JVM boundary. Gradle's
  `ProgressLogger` never leaves the task layer.
- `completed()` in `finally` guarantees the operation closes even if the block throws.

### Part 3 — Thread the reporter into the pure-JVM collaborators (M)

For each heavy task, wrap the work in `withProgress(...)` and pass the reporter down. The reporter
is a **required parameter** (no default) on the production methods/constructors:

1. **`ResolveWorkspaceDependenciesTask`** — `AggregatedDependencyResolver` already takes `Logger`;
   add a required `reporter: ProgressReporter` (constructor or `resolve(...)` param, match its
   shape). Tick once per root inside `collectRootClosures` (`AggregatedDependencyResolver.kt:409`):
   `reporter.report("resolving ${root.path} (${index + 1}/$total)")`. Task wraps with
   `withProgress("resolving dependencies")`. Summary: `logger.quiet(...)` with dep count + root
   count.
2. **`CollectDeclaredDependencyMetadataTask` (SINGLE_TASK)** — pass a reporter into the parallel
   snapshot loop (`:287`). **Thread-safe:** wrap with an `AtomicInteger` completion counter and
   guard the `report(...)` call in a `synchronized` block (Gradle's `ProgressLogger` is not
   documented thread-safe). Tick on each project completion:
   `"snapshotting $path (${done.incrementAndGet()}/$total)"`. Keep the existing per-project
   work parallel — only the progress emission is serialized.
3. **`MergeDeclaredDependencyMetadataTask`** — tick per shard. Summary already present → move to
   `quiet`.
4. **`CollectKspProcessorDependenciesTask`** — tick per KSP root component. Add a `quiet` summary
   ("Scanned N processors across M roots"). If module labels are desired later, carry project paths
   alongside root components in a separate item; do not infer them stringly here.
5. **`CollectWorkspaceTargetTagPlanTask`** — add a required `reporter` param to
   `WorkspaceTargetTagPlanCollector.collect(rootProject, reporter)` (method param — the collector
   is a Dagger `@Singleton`, so NOT a constructor field). Tick per **subproject** at
   `WorkspaceTargetTagPlanCollector.kt:83` — **module granularity only; do NOT tick per variant at
   `:185`.** Summary: `quiet` ("tagged M modules").
6. **`CollectTargetMavenRepoReferencesTask`** — tick per project in topological order. Summary:
   `quiet` ("collected references across M modules").
7. **`AnalyzeVariantCompressionTask`** — tick per Android-lib project. Leave its existing
   per-project `logger.info` decisions as diagnostics; promote its end-of-run line to a `quiet`
   summary.

### Part 4 — Standardize summaries onto `quiet`

Move the existing `logger.lifecycle("Grazel: …")` summary lines
(`CollectDeclaredDependencyMetadataTask.kt:131,235`, the merge task) to `logger.quiet(...)` so every
**user-facing summary** sits at one deliberate level. **Drop the `Grazel:` prefix** while migrating
them (see Goal message-style note) — the migrated summaries read plainly, no prefix. **Do not
touch** any `logger.info` / `logger.warn` call — those remain diagnostics. `ProgressReporter` must never call any logger; the
logger must never drive the ticker.

## Hard constraints

- **No Gradle API in the pure-JVM classes' new code.** `ProgressReporter.kt` imports nothing from
  `org.gradle`. The lambda adapter is the boundary. Classes already importing `Logger`
  (`AggregatedDependencyResolver`, `RootBazelFileBuilder`) may keep it, but progress flows through
  `ProgressReporter`, not `Logger`.
- **`reporter` is a required parameter** on every production method/constructor it is added to.
  `ProgressReporter.NoOp` is used only by tests, passed explicitly. No silent default.
- **Channel separation is absolute.** Progress = `ProgressLogger` (transient). Summary =
  `logger.quiet` (permanent). Diagnostics = `logger.info`/`warn` (unchanged). No call site mixes
  them; `ProgressReporter` performs no logging.
- **Module granularity.** Per-subproject / per-root / per-shard ticks only. No per-variant ticking
  (the tag-plan collector loops variants at `:185` — leave that alone).
- **Thread-safety** for `CollectDeclaredDependencyMetadataTask`'s parallel path: `AtomicInteger`
  counter + `synchronized` `report(...)`.

## Safety mechanism

- Progress is a pure side-effect on the console/log; it touches **no** generated `BUILD`/`WORKSPACE`
  content. **Sample golden EMPTY-diff** and **PAX generated diff stable** vs the frozen Item 10
  baseline are expected with zero effort — any diff = a bug in the change (e.g. accidentally
  reordered a loop). **Size guard (Item 10): no change.**
- No parity flag (compile-time wiring; compiler + tests + golden are the net).

## Testing

- **Pure-JVM (the payoff):** pass a capturing fake `ProgressReporter` (collects messages into a
  list) into `AggregatedDependencyResolver` and each collector; assert the number of `report(...)`
  calls equals the module/root/shard count and the labels match the expected `(i/n)` shape. **No
  Gradle types required** in these tests.
- **Adapter:** one focused test that `ProgressLoggerFactory.withProgress` starts the operation,
  forwards `report` → `progress`, and calls `completed()` even when `block` throws (use a fake
  `ProgressLoggerFactory`/`ProgressLogger` capturing calls).
- **Thread-safety:** a test driving the declared-metadata reporter from multiple threads asserting
  the final count equals N and no lost/garbled updates (capturing reporter under contention).
- Existing task tests must still pass with the new required `reporter` param (update their call
  sites to pass `ProgressReporter.NoOp`).

## Acceptance criteria

- `util/ProgressReporter.kt` exists, pure-JVM (no `org.gradle` import), `fun interface` + `NoOp`.
- `ProgressLoggerFactory.withProgress` helper added to `util/ProgressLogger.kt`.
- The seven heavy tasks listed in Part 3 emit a transient per-module ticker via `withProgress` and
  a permanent `logger.quiet` summary. The explicitly-excluded tasks are untouched.
- `reporter` is a required parameter everywhere it was added; `ProgressReporter.NoOp` appears only
  in tests. Existing production `NoOpProgressLogger` is unrelated and may remain where already used.
- All user-facing summaries use `logger.quiet`; no summary uses `lifecycle`; no `info`/`warn` call
  was changed; `ProgressReporter` performs no logging.
- Pure-JVM progress unit tests, adapter test, and thread-safety test green; existing unit +
  functional suites green.
- Sample golden empty-diff; PAX generated diff stable; PAX `migrateToBazel` + both APKs green; size
  guard no-increase.

## Out of scope / Non-goal

- Per-variant ticking; ticking the monolithic compute/finalize/pin tasks or the already-chatty
  generate tasks; the PROJECT_TASK_FANOUT per-project task.
- Any nested/parent `ProgressLogger` operation threaded across tasks (single-line constraint; each
  task owns its own status line).
- Changing log levels of existing diagnostic `info`/`warn` calls, or altering generated output.
- Reshaping the task graph or the collectors' algorithms (Items 29–34 territory).
