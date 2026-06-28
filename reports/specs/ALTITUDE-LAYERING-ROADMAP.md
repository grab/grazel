# Altitude Layering — Itemized Spec Roadmap (aligned end vision)

> **Status:** Aligned 2026-06-27 (full-layering scope). Cross-cutting index/roadmap for the
> foundational "altitude layering" pass. Start from `CURRENT-GOAL-ANCHOR.md`; this roadmap +
> item specs are the detailed source of truth. `2026-06-27-altitude-layering-refactor-plan.md`
> is superseded architectural input, not an execution contract.
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.

> **⚠️ Execution note — delegate to subagents; protect the main context.** Applies to every
> item: wide reads, audits, and PAX runs go to focused subagents returning distilled results.

---

## Locked decisions (this session)

1. **Scope = FULL layering, but not a single risky rewrite.** The pass improves the
   architecture in preserving slices, then makes only the scoped output change that has a
   reliable bloat win:
   - **Relocate (behaviour-preserving):** build the Layer-3 `BucketOwnershipPlanner` by
     moving the *existing* ownership logic as-is → **golden empty-diff**. Proves the new
     layer is sound before any behaviour changes.
   - **Improve (output-changing):** test/androidTest delta ownership. Lint delta ownership is
     deferred to a separate item with its own tested inheritance model. Main
     undeclared-transitive placement keeps the current set-math unless a separate future
     experiment proves a reduction.
2. **Transition safety for the improve step = baseline or dual-run diff classification.** If
   the old planner path still exists, keep it temporarily behind a flag and diff
   bucket-by-bucket on PAX. If the old path has already been removed by a prior preserving
   slice, use the frozen Item 10 PAX machine baseline as the parity source. Pass only when
   every difference is classified as intended scoped delta ownership, the size guard passes,
   and PAX builds pass.
3. **Size guard = freeze current PAX as the accepted baseline.** The accepted PAX generated
   state is recorded from branch `arun/grazel-refactor` as a machine-readable size baseline
   plus stable dirty-worktree diff/status hashes, giving future work a tight impact check
   without committing PAX generated files from this goal. Bucket count, pinfile count, and
   total artifact roots must **never increase** from the frozen baseline. Further reductions
   are a win and may monotonically lower the baseline after verification. Master is NOT the
   target (it over-bucketed). Correctness gates (PAX builds) remain primary; any
   correctness-required increase needs an explicit maintainer waiver, not a silent guard
   bypass.
4. **Complexity reduction is part of the goal.** Layering is not cosmetic. The verified PAX
   baseline gives the pass room to replace compensating machinery with better models: typed
   graph projections over SCC/fixpoint fallback, aggregated declared metadata over downstream
   inference, `BucketOwnershipPlanner` over scattered ownership predicates, mechanical
   `ComputeWorkspaceDependencies` value/index computation over policy, and renderers
   consuming plans over target/output feedback. If a new layer does not delete or simplify
   downstream special cases, document why the complexity is still required.

## Three amendments to Codex's plan (carry into every item)

1. **Success = altitude/clarity + real bucket reduction, NOT a code-size win.** Measured: the
   rewrite is ~net-neutral LOC (~6% deletes, comparable added). Judge it by "obvious which
   layer owns the bug" and by *reduced buckets on PAX* — never by LOC.
2. **`ComputeWorkspaceDependencies` is NOT "the brain."** It's a 328-line post-processor — the
   smallest core file and cleanest win. The real brain is `AggregatedDependencyResolver`
   (1,440) + `DependencyBucketPlacementEngine` (667) — which Item 13 rewrites. Don't optimize
   the wrong file.
3. **The HYBRID reconciliation is load-bearing and must be a NAMED, TESTED Layer-3
   responsibility.** "Declarations drive structure" is incomplete: undeclared transitives
   common to multiple leaves have no declared owner, and `compileOnly`/api-of-consumed-libs/
   conflict-resolved deps appear in the resolved closure declared elsewhere. Layer 3 owns the
   declared-vs-materialized rule. In this pass, Item 13 changes only the scoped
   test/androidTest delta behavior; lint and main undeclared-transitive placement remain
   unchanged and are documented as intentional retained hybrid rules, not hand-waved.

## End-vision altitude (from Codex's plan, amended)

```
Layer 0  Variant topology        VariantBuilder / Variant<*> / BucketHierarchyGraph
Layer 1  Cheap declared facts     DeclaredDependencyMetadataCollector + typed graph nodes/edges
Layer 2  Resolved value graph     AggregatedDependencyResolver (values+closure ONLY) + slim CWD
Layer 3  Bucket ownership plan     BucketOwnershipPlanner (declared placement + hybrid reconcile)
Layer 4  Workspace/render plans    WorkspacePlan / WorkspaceRenderPlan / TargetReferenceFacts
Layer 5  Rendering                 project/root gen + pinner (format only)
```
SCC is a **diagnostic fallback for proven genuine typed cycles only**. The known PAX
`deliveries-model-food ↔ food-testkit` case is treated as a false SCC caused by
project-level collapse of `testImplementation` and `implementation` edges until typed graph
projection proves otherwise. SCC must not drive bucket ownership or the normal render
workflow.

---

## Itemized specs (full layering)

Executed in order; each golden-checked + PAX-baseline-no-regress (except Item 13, the one
intended output change).

| # | Item | Status | Behaviour | Goal (one line) | Depends on |
|---|------|--------|-----------|-----------------|------------|
| **10** | Frozen PAX baseline + size guard | specced | preserving (tooling) | Freeze current PAX; automated guard: bucket/pinfile/total-roots never increase; reductions = win | — (first) |
| **9** | Typed graph nodes/edges + test→app | specced (`item9`) | preserving after baseline | Preserve source-set/variant graph identity; add `DependencyGraphEdge` and `AndroidTestTargetProjectEdge`; avoid false SCCs | 10 |
| **11** | Eliminate false SCCs; SCC diagnostic fallback | specced | preserving (verify+guard) | Prove known PAX false SCC disappears under typed projections; remove fallback or keep only with genuine typed-cycle proof; bucket ownership = DAG math only | 9 |
| **12** | Extract `BucketOwnershipPlanner` (Layer 3) — RELOCATE | specced | **preserving (empty-diff)** | Move existing ownership logic as-is into a named Layer-3 model; proves the layer before any behaviour change (Step A) | 10 |
| **13** | Test/androidTest delta ownership — IMPROVE | specced | **OUTPUT-CHANGING** | Make typed test buckets own only resolved-identity deltas; classify diffs; guarded totals may only stay flat or shrink | 12 |
| **14** | Slim `ComputeWorkspaceDependencies` to value-holder | specced | preserving | Move CWD's duplicate-collapse / override-synthesis into the planner/plan layer; CWD keeps flatten / max-version / transitive / KSP | 13 |
| **15** | Rendering purity + hygiene | specced | preserving | Wire-or-remove `commonAncestorsOf`/`closestCommonAncestorsOf`; delete dead-code residues; add `WorkspaceRenderPlanBuilder` test; confirm no generated-file parsing feedback | 14 |
| **16** | Simplify, adversarial review & final verification | specced | preserving | Run simplify pass, adversarial review, broad Grazel/PAX verification, docs/waiver cleanup; produce review-ready branch | 15 |
| **17** | Consolidate bucket set-math; remove duplication & dead code | draft final-review | **preserving (empty-diff)** | Extract duplicated set-math primitives to one `BucketSetMath.kt`; delete the resolver's dead `withoutDeclaredPlaceholdersCoveredByDefault` and orphaned ownership cluster; keep planner-private ownership helper | 10, 12 |
| **18** | Retire SCC ordering → typed DAG topo sort | draft final-review | **preserving (empty-diff)** | Replace Kosaraju/condensation in `ProjectReachabilityOrder` with direct typed Kahn ordering; keep fail-closed typed cycle diagnostic; delete vestigial `cyclic` flag + dead `check(!group.cyclic)`; drop redundant `normalized()` rebuild | 9, 11, 17 |
| **19** | Target reference facts; remove target-builder execution from reference collection | draft final-review | **preserving (empty-diff)** | Replace `BazelTarget`/`TargetBuilder`-based reference discovery with structured facts; keep consumer-first `WorkspacePlanService` mutation; target builders run only during generation | 17, 18 |
| **21** | Simplify pass — dead code, duplication & indirection | specced | **preserving (empty-diff)** | Whole-branch cleanup from the 4-agent simplify audit: delete zero-caller dead code (`tagsFor` ext, `rootArtifacts`/`variantArtifacts` fields, dead overloads/interface methods, unread input), de-dup `hasSameDefaultOwnerIdentityAs`, inline one-caller wrappers, filter-before-override in maven calc. Keeps `TasksManager` dependsOn + `@InputFiles` (deferred) | 10 |
| **22** | Set-math ownership reduction — EXPERIMENT | completed: Outcome B | proven-essential doc | Measured set-subtraction ownership on sample/PAX; active residual paths proved the current set-math problem-essential, so no Phase 2 reshape was attempted | 12, 13, 17 |
| **23** | Target reference model hygiene | proposed | **preserving (empty-diff)** | Remove dead `BazelTarget` compatibility collector and collapse duplicate target-reference data models; do not broaden into typed-label/regex cleanup | 19 |
| **26** | Variant-owned workspace dependency root inputs | proposed | **preserving (empty-diff)** | Move workspace dependency root input planning out of `WorkspaceDependencyInputsRegistrar`; prefer lazy `VariantBuilder.onVariants`; keep AGP configuration-name knowledge in `gradle.variant`; registrar wires task inputs only | 9, 10 |
| **29** | Declared metadata aggregation modes | proposed | **preserving (mode parity + empty-diff)** | Delete broad Gradle/TOML file tracing; add experiment switch between untracked bounded-coroutine single aggregation and cacheable per-project fanout + deterministic merge | 10, prefer after 26 |
| **24** | Branch-diff source shape hygiene | proposed | **preserving (empty-diff)** | Inventory Kotlin files changed by this branch; use scripts plus scoped subagents to remove policy-heavy generic extensions, clarify helper model naming/placement, remove test-only production seams/reflection escapes, and justify any retained complexity | 23, 26, 29 |
| **27** | Branch-wide simplify + adversarial review before formatting | proposed | **preserving (empty-diff)** | Explicitly invoke the `simplify-pass` skill, then run adversarial correctness review over the entire branch diff; ambitiously fix maintainability/correctness findings; PAX baseline must not move | 24 |
| **25** | Merge generate + format into one task per scope | specced | **preserving (empty-diff)** | Collapse the per-project and root generate/format task pairs into one `@UntrackedTask` each; extract a shared `formatWithBuildifier` helper; delete `FormatBazelFileTask`; rewire post/pin/migrate edges. Accepts losing format `@CacheableTask` (~6s/run on PAX; generate already untracked) | 27 |
| **28** | Hard source-shape inventory remediation | proposed | **preserving (empty-diff)** | Correct the under-enforced Item 24/27 source-shape pass with a committed per-file inventory, mandatory suspicious-pattern scan, row-level subagent reconciliation, and hard no-pending exit gate | 25 |

**Current next-goal execution order:** 23 → 26 → 29 → 24 → 27 → 25 → final verification/review.
Item 23 removes the stale target-reference compatibility path first. Item 26 then fixes the
known workspace dependency root-input altitude leak and performs the broad changed-file altitude
scan. Item 29 then removes the declared-metadata build-file tracing cache proxy and adds the
single-vs-fanout aggregation experiment before this area is source-shape-polished. Item 24 then
runs as the branch-diff source-shape hygiene pass, cleaning the source shape after the
dependency-planning altitude fixes. Item 27 runs a branch-wide simplify/adversarial review over
the entire diff and fixes confirmed maintainability/correctness findings before formatting work
starts. Item 25 runs last so the generate/format task-graph reshape is the final preserving
task-graph cleanup.

**Post-Item-25 corrective pass:** Item 28 exists because Item 24/27 did not leave a hard
file-by-file inventory ledger and missed policy-heavy receiver extensions. Item 28 supersedes any
claim that source-shape cleanup is complete. It is preserving and starts only after the maintainer
commits the current PAX generated/local baseline, so PAX `git diff` becomes the regression signal.
If Item 29 has not yet run, run Item 29 before Item 28 so the inventory pass does not preserve or
polish the obsolete declared-metadata task shape.

**Current next-goal hard exit gate:** do not stop after any single item appears green. The goal is
complete only when Items 23, 26, 29, 24, 27, and 25 have all met their acceptance criteria; every changed
Kotlin file required by Items 24/26 has been inventoried, visited, and reconciled; confirmed
altitude violations are fixed in-slice; Item 27's simplify/adversarial findings are fixed or
rejected with concrete code evidence; generated output is empty-diff; PAX migrate leaves the
accepted baseline unchanged; required PAX APK builds pass where specified; task-graph checks pass;
comment-hygiene requirements are satisfied; execution logs record decisions, commands, failures,
and remaining risks.

**Post-Item-19 cleanup:** Item 23 is a small preserving cleanup discovered after the Item 19
cutover. It removes the old `BazelTarget` reference collector from production source and collapses
the duplicate `TargetReferenceFacts`/`TargetMavenRepoReferences` model shape. It deliberately does
not attempt the larger typed-label rewrite needed to eliminate all regex/string-shaped reference
parsing.

**Post-Item-23 source-shape cleanup:** Item 24 is a branch-diff-scoped hygiene pass. Its inventory
starts from Kotlin files changed by this branch, but justified fan-out edits are allowed for
renames, interfaces, call-site cleanup, and type-boundary improvements. It uses deterministic
inventory plus scoped subagents because scripts catch shapes while agents catch altitude and naming
intent. Generated output and the PAX baseline must remain unchanged.

**Workspace dependency root-input cleanup:** Item 26 is split out from Item 24 because it is not
just source-shape hygiene. It fixes a concrete altitude leak where `WorkspaceDependencyInputsRegistrar`
reconstructs AGP configuration names and inspects backing variant types. The target shape is:
variant APIs expose typed configuration roles, a small planner maps those facts to dependency root
descriptors, and the registrar only wires task inputs. Item 26 also requires a broad agentic
altitude scan over every Kotlin file changed by the branch diff, so similar layer violations are
fixed in the same slice or rejected as non-violations with rationale before exit. It is preserving
and PAX-baseline guarded; output-changing redesign findings stop for maintainer direction instead
of being silently deferred.

**Post-16 follow-up pass (2026-06-28 audit).** Review agents ground-truthed the branch
after Item 16. Finding: the altitude work landed *more* completely than the executor's
self-assessment implied (typed graph is the ordering substrate, `BucketOwnershipPlanner` is
pure Layer-3, CWD is a 195-line value-holder, Item 15 hygiene all done, parity flags all
removed). Items 17–18 capture the only genuinely reducible debt: duplicated/dead set-math
(missed by the executor) and now-vestigial SCC. Both are **empty-diff**. Item 19 captures the
stronger altitude/performance smell found afterwards: reference collection executes target
builders, then generation executes them again. Item 19 removes that dual execution while
preserving the current consumer-first `WorkspacePlanService` mutation. Item 20 (cacheability)
is deliberately deferred.

**Behaviour property:** Items 10, 11, 12, 14, 15 and Item 9 Stage 1 are expected
**golden empty-diff**; Item 16 is final quality/verification only and must not introduce
behavior changes. Permitted classified diffs are Item 13 (the intended output change) and
Item 9 Stage 2 only if the new target edge exposes a real correctness fix. Any other output
diff is a stop-and-investigate event.

**Parity/diff mechanism:** Items 12 and 14 use the temporary parity/diff harness
`-Pgrazel.internal.parity=ownership|cwd` while their old paths exist. Item 13 may use
`-Pgrazel.internal.parity=delta` only if a pre-Item-13 path is still present; otherwise the
frozen Item 10 PAX size baseline plus generated diff classification is the parity source.
Each item removes any temporary parity mode and old path before completion.

## Cross-checks before Codex executes

- Each item inherits the Item 1 Global Constraints + Verification Playbook.
- **Item 13 has already been narrowed by adversarial review.** Main undeclared-transitive
  follow-the-roots and lint delta ownership are out of scope for this pass;
  test/androidTest deltas are in.
- Item 12 must come before 13 so the planner exists as a proven empty-diff layer before its
  algorithm changes.
- Item 10's size guard must exist before 9 and 11–16 so each step is regression-checked.
