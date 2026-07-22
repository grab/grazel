# Critic brief — attack the existing implementation for accidental complexity

Repo: `/Users/arun.sampathkumar/work/grazel`. Branch `arun/dependencies-refactor`.
Diff under review: `git diff master...HEAD` (the resolver refactor). The relevant source lives in:

- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/` — the resolver spine
  (`AggregatedDependencyResolver.kt`, `AggregatedDependencyRoot.kt`,
  `WorkspaceDependencyRootInputPlanner.kt`, `ResolvedComponentsVisitor.kt`, `TopologicalSorter.kt`,
  `resolution/` (RootContributionComputer, MainReachabilityTracker, DependencyBucketAccumulator,
  DeclaredMetadataMerger, …), `bucket/` (DependencyBucketPlacementEngine, Main/TestBucketPlanner,
  Coverage, BucketPlacementGraph, …), `WorkspaceRenderPlanService.kt`, `TargetReferenceFactsCollector.kt`).
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/migrate/target/` — target builders,
  `TargetVariantReachability.kt`, `TargetReferenceFactsExtractor.kt`.
- `grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/tasks/internal/` —
  `CollectTargetMavenRepoReferencesTask.kt` (the reference collector, with a fixpoint loop),
  `ComputeWorkspacePlanTask.kt`, `FinalizeWorkspacePlanTask.kt`, and the task graph
  (`TaskManager.kt`).

## What the branch does (context, not endorsement)

It replaced a **bottom-up, per-module-per-variant** Gradle resolution with a **top-down aggregated**
one: roots are seeded only from binaries (app + `com.android.test`), resolved once, and each
module's own per-variant dependency set + bucket/pin attribution is **reconstructed** from those
aggregated resolutions. This was done to cut execution time.

Three mechanisms carry the reconstruction — evaluate each on its merits:

1. **Buckets** (`bucket/`): how resolved dependencies are grouped/placed and reduced into the
   pinned Maven repository buckets, and how per-project ownership/coverage is computed.
2. **Reachability** (`resolution/MainReachabilityTracker.kt`, `migrate/target/TargetVariantReachability.kt`):
   which modules/variants are considered "reachable" and therefore generated, and the
   referenced-but-unreached fallback that generates a module referenced by an already-emitted target.
3. **Reference collector** (`tasks/internal/CollectTargetMavenRepoReferencesTask.kt`): a pass that
   walks projects consumers-first and iterates **to a fixed point** to gather the target/repo
   references each generated BUILD needs.

## Your angle (given to you at dispatch)

You will be told which ONE of these three to focus on. Read the actual code for your angle
(and enough of its neighbours to judge it). Then attack it for **accidental complexity** — you are
NOT hunting for correctness bugs, you are judging whether the mechanism is more complicated than the
problem requires.

For your angle, answer:
- **What is the essential complexity** the problem genuinely forces here, vs the **accidental
  complexity** this implementation adds on top?
- Concrete **simplifications**: name the specific construct/file/pass that could be removed,
  merged, or replaced with something simpler, and what it would become. Rough effort + risk each.
- **Altitude**: is this solved at the right depth, or is it special-cases piled on shared
  infrastructure (or the reverse — a heavy general mechanism where a narrow one would do)?
- **Big-O / wasted work** where relevant (extra passes, re-computation, re-visiting).
- If you think the mechanism is **essential and roughly right**, say so plainly and say why — do
  not manufacture criticism.

## Hard constraints you must respect in any simplification you propose

- Generated Bazel output must stay **byte-identical** (there is a golden-baseline gate and a
  real downstream monorepo baseline). A simplification that changes output is only interesting if
  you flag exactly what output moves and why it is acceptable.
- Do not propose reintroducing per-module-per-variant resolution (that is the slow thing this
  replaced) unless you can argue the perf cost is actually fine.

## Deliverable (write to the path you are given)

A focused critique for your one angle: essential-vs-accidental split, ranked concrete
simplifications (with effort/risk), altitude verdict, and a plain "keep as-is" on anything that is
genuinely right. Be specific — cite files and constructs. Return only the file path + a one-line
headline.
