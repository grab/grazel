# Item 28 - Hard Source-Shape Inventory Remediation (Design)

> **Status:** Proposed 2026-06-29.
> **Executor:** Codex.
> **Behaviour change:** none - golden EMPTY-diff.
> **Global Constraints + Verification Playbook + Code-quality stance:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`.
> **Depends on:** Items 25, 30, 29, and 31 complete, plus the maintainer-created PAX local baseline
> commit.

---

## Why This Exists

Item 24 and Item 27 intended to clean source shape, but their execution did not leave a
machine-checkable file-by-file ledger. That allowed policy-heavy receiver extensions and scattered
helper models to survive even though the branch passed correctness gates.

This item is the corrective pass. Previous Item 24/27 logs are useful evidence, but they are not
completion evidence for this item. If those logs deferred a file to broader work, especially
declared-metadata/workspace-dependency files touched by Items 30 and 29, this item must re-examine
the file after those items land. Do not inherit old `no_issue` or deferral rationales.

Example smell this item must catch and rewrite:

```kotlin
private fun MutableMap<ProjectDependencyBucket, Map<String, ResolvedDependency>>.addToProjectBucket(...)
```

The receiver hides the role. Default rewrite:

```kotlin
private fun addDependenciesToProjectBucket(
    dependenciesByProjectBucket: MutableMap<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
    projectPath: String,
    bucketName: String,
    closure: Map<String, ResolvedDependency>,
    keepEmpty: Boolean = true,
)
```

## Goal

Perform a hard, branch-diff-scoped source-shape remediation pass over all changed Kotlin files.
This is not an advisory cleanup. It must produce a durable inventory, visit every file, fix the
confirmed readability/altitude issues, and prove no generated output moved.

The primary outcome is readable, reviewable Kotlin code with explicit roles instead of hidden
receiver state, predictable local model placement, and tests that use typed APIs rather than
reflection/string shortcuts where a typed seam exists.

## PAX Baseline

Before execution, record the maintainer-created PAX baseline:

```text
repo:   /Users/arun.sampathkumar/work/pax-android
branch: arun/grazel-refactor
sha:    <record actual local baseline sha before edits>
```

Do not commit PAX during this item. PAX `git diff` is the regression signal after the baseline.

## Scope

Initial scope is every Kotlin file changed by the current Grazel branch relative to master:

```text
git diff --name-only --diff-filter=ACMR master...HEAD -- '*.kt'
```

Include production, unit tests, functional tests, and any build logic Kotlin in the diff. Edits may
fan out to related Kotlin files when required for a correct rename, interface extraction, call-site
cleanup, or type-boundary fix. Fan-out files become inventory rows too.

Do not perform unrelated repo-wide aesthetic churn.

## Required Artifacts

This item cannot complete without these committed artifacts:

1. `reports/scripts/source-shape-inventory.sh` or an equivalent committed deterministic tool.
   - It must generate the changed-file list.
   - It must flag suspicious patterns.
   - If shell/`rg` is insufficient, add a Bun/TypeScript/tree-sitter Kotlin scanner under
     `reports/scripts`. Do not rely only on memory or subagent summaries.
2. `reports/specs/source-shape-inventory.tsv`.
   - One row per changed Kotlin file, including fan-out files.
   - The file must be updated after the final code edits.
3. Item-specific execution log:
   `reports/specs/execution-log/item28-hard-source-shape-inventory.md`.

Minimum TSV columns:

```text
file
area
owner_agent
review_status
generic_receiver_status
helper_model_status
test_escape_status
comment_status
action_taken
retained_rationale
verification
```

Allowed row statuses:

```text
fixed
no_issue
retained_problem_essential
deferred_requires_behavior_change
```

`pending`, blank, or vague statuses fail the item. `deferred_requires_behavior_change` requires a
specific future item or maintainer decision note. `no_issue` also requires a one-line
`retained_rationale`, for example "no mandatory source-shape pattern applies after scan"; blank
rationale fails the item.

## Mandatory Detection

The inventory tool and subagent prompts must check at least the patterns below. This list is a
floor, not a ceiling: if scripts, subagents, simplify-pass, adversarial review, or parent review
find additional readability/altitude/test-shape issues in a changed file, they must become
inventory rows and be reconciled before exit.

- generic collection/map receiver helpers:
  - `private fun Map<...>.`
  - `private fun MutableMap<...>.`
  - `private fun Set<...>.`
  - `private fun Collection<...>.`
  - nested generic receiver equivalents;
- mutating `Project.*` extensions introduced by this branch;
- policy-heavy extension functions where the receiver hides a role name;
- private data/helper classes declared mid-file without a locality reason;
- ambiguous helper names such as `Input`, `Result`, `State`, `Plan`, `Key`, `Edge`, `Node`,
  or `Summary` used inconsistently with Item 24 naming rules;
- production APIs kept only for tests;
- reflection, dynamic proxy, source-string assertions, unchecked casts, or other type-system
  escapes in touched tests;
- comments that encode migration diary, AI/context artifacts, stale TODOs, or historical
  explanation instead of durable behavior contracts.

Known rows that must be reconciled explicitly:

- `BucketOwnershipPlanner.kt` helper shapes such as `addDeclaredOutputMetadata`;
- `DeclaredDependencyMetadataCollector.kt` and declared-metadata task files deferred by Item 24;
- workspace dependency task files reshaped by Items 30 and 29.

## Rewrite Rules

### 1. Generic Receiver Extensions

Generic collection/map/mutable receiver extensions are banned by default in changed files.

Allowed only when all are true:

- the function is tiny, side-effect-free, and mathematically algebraic or DSL-like;
- the receiver role is obvious from the type alone;
- keeping it improves readability more than an explicit first parameter;
- the inventory row says `retained_problem_essential` or `no_issue` with a concrete rationale.

Otherwise convert to an explicit function with a role-named first parameter.

### 2. Helper Model Placement

Private helper models must be placed predictably:

```text
imports
public/internal boundary models
private file-local models/state
main class/object
private helper functions
```

Mid-file private data classes are allowed only when tightly local to a small algorithm and the
inventory row explains the locality reason.

### 3. Naming

Use role suffixes consistently:

- `Input` for data entering a planner/task boundary.
- `Plan` for durable planner output consumed by another layer.
- `Result` for one operation's returned output.
- `State` for mutable or accumulated algorithm-local state.
- `Key` for map/cache/grouping identity.
- `Edge` / `Node` for graph shape.
- `Summary` for diagnostics, logs, and reports.

Rename ambiguous types/functions even when call-site fanout is broad, provided generated output is
unchanged.

### 4. Tests

Tests are first-class scope. Prefer typed APIs and structural assertions.

Do not introduce:

- reflection to escape Gradle/Kotlin types;
- source-text assertions when a typed model can be asserted;
- dynamic proxies except for explicitly documented third-party API failure-path tests;
- unchecked casts where a typed fake or interface can carry the contract.

Existing escapes in changed files must be fixed or justified per row.

## Execution Model

1. Record Grazel status, current commit, PAX baseline SHA, and active item in the execution log.
   Confirm Items 25, 30, 29, and 31 are complete before starting remediation.
2. Generate the inventory with the committed script/tool.
3. Spawn scoped subagents by file clusters. They must return row-level findings mapped to the TSV,
   not broad prose.
4. Parent reconciles every row. Subagents do not decide final deferral.
5. Apply fixes in small preserving batches.
6. Rerun focused tests after each meaningful batch.
7. Regenerate the inventory after edits. Add rows for fan-out files.
8. Repeat until every row has an allowed terminal status and all suspicious patterns are either
   fixed or explicitly justified.
9. Run final Grazel and PAX gates.

## Hard Exit Gates

The item must not complete unless all are true:

- `reports/specs/source-shape-inventory.tsv` exists and has a row for every changed Kotlin file.
- The inventory has no `pending`, blank, or unreviewed rows.
- The inventory script/tool has been rerun after final edits.
- Every retained generic receiver extension has a concrete rationale.
- Every retained mid-file helper model has a concrete locality rationale.
- Every retained reflection/string/proxy test escape has a concrete necessity rationale.
- Every `no_issue` row has a concrete one-line rationale.
- Files deferred by Item 24/27 have been re-examined after Items 30 and 29, not inherited.
- Generated Grazel output is empty-diff.
- PAX generated output is unchanged from the new maintainer baseline.
- PAX size guard is unchanged.
- All verification commands are recorded with result and elapsed time.

## Verification

Local Grazel gates:

```text
reports/scripts/source-shape-inventory.sh
./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon
./gradlew migrateToBazel --console=plain --no-daemon
reports/scripts/verify-default-task-graph.sh
reports/scripts/verify-pax-size-guard.sh --mode preserving
git diff --check
git diff --check master...HEAD
```

PAX final gate:

```text
cd /Users/arun.sampathkumar/work/pax-android
./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks
git diff --check
```

If PAX generated output moves, stop and investigate. Do not classify generated drift as source
hygiene.

Run additional focused tests for each cluster that changes.

## Subagent Discipline

Use subagents aggressively for coverage, but keep them constrained:

- each subagent receives a file list and the TSV schema;
- each finding must name file, line/pattern, classification, and proposed fix;
- no subagent may mark a row terminal without parent reconciliation;
- parent must spot-check representative rows from every cluster.

## Risks / Traps

- **Inventory theater:** A list of files is not enough. The TSV must record actual decisions per
  file and per smell category.
- **Regex-only confidence:** Scripts flag candidates; they do not replace human/agent review.
- **Subagent drift:** Broad prose summaries are not accepted. Findings must map to inventory rows.
- **Aesthetic churn:** Edits must improve role clarity, layer contract, test contract, or local
  model shape.
- **Early exit:** Passing tests/PAX is not enough. The file-by-file ledger is a hard gate.

## Acceptance Criteria

- The source-shape inventory script/tool exists and is committed.
- `source-shape-inventory.tsv` is complete, terminal, and current after final edits.
- Confirmed source-shape findings are fixed, not merely logged.
- Retained complexity has concrete evidence, not assertion.
- Prior execution logs, including Item 22 set-math evidence, are consulted before assigning
  `retained_problem_essential`; evidence may be reused, but conclusions must be rechecked against
  the current file.
- PAX baseline remains unchanged after `migrateToBazel`.
- Grazel verification and PAX verification pass.
- Final response states the commit, inventory counts, fixed categories, retained rationales,
  verification results, and remaining risks.
