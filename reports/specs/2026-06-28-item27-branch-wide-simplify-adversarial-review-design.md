# Item 27 - Branch-Wide Simplify + Adversarial Review Before Formatting (Design)

> **Status:** Proposed 2026-06-28.
> **Executor:** Codex.
> **Behaviour change:** none - golden EMPTY-diff.
> **Global Constraints + Verification Playbook + Code-quality stance:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `ALTITUDE-LAYERING-ROADMAP.md`. **Depends on:** Item 24.
> This review pass intentionally runs before Item 25 so the final generate/format task-graph
> cleanup happens after the branch-wide quality work.

> **Execution note - invoke the actual simplify-pass skill.** Run the available `simplify-pass`
> skill by name for maintainability, then run an adversarial review for correctness. Use scoped
> subagents for broad branch-diff coverage, but the parent agent owns reconciliation and fixes.

---

## Goal

Run one more ambitious quality pass over the entire branch diff before the formatting/task-graph
cleanup. This item is allowed to make broad source edits when they improve correctness,
maintainability, altitude, type safety, or test quality, but generated output and the PAX baseline
must not move.

This is not a paperwork review. Real findings must be fixed in this slice.

## Scope

Review the entire branch diff, not only files touched by Items 23/24/26. Include production code,
tests, task wiring, specs that guide execution, and any committed helper scripts.

The review must cover:

- correctness risks introduced by the refactor;
- altitude/layering violations;
- type-system escapes, reflection, unchecked casts, and stringly seams that have a typed
  alternative;
- dead code, duplicate models, accidental indirection, and stale compatibility wrappers;
- task boundary and Gradle provider/cacheability regressions;
- naming and model-shape issues that make future changes harder;
- comments that encode refactor history, AI/context artifacts, or migration diary notes instead
  of durable engineering facts;
- missing focused tests for changed behavior or reshaped seams.

## Required Review Passes

1. **Invoke `simplify-pass` over the branch diff.**
   - Explicitly invoke the available `simplify-pass` skill. Do not substitute a casual manual
     review for this step.
   - Review for reuse, simplification, efficiency, altitude, naming, model shape, and test
     maintainability.
   - Apply real fixes. Do not only list suggestions.

2. **Adversarial correctness review over the branch diff.**
   - Use high-skepticism reviewers/subagents.
   - Focus on resolved-vs-declared dependency mistakes, variant graph/root-input ownership,
     target-reference model collapse, task graph ordering, PAX baseline movement, generated-output
     drift, and missing regression tests.
   - Apply real fixes. A finding may be rejected only with concrete code evidence.

3. **Post-fix re-review.**
   - Re-run a scoped review after fixes to catch second-order issues.
   - The item cannot exit with unresolved confirmed findings.

## Hard Gates

- Generated Grazel output must be empty-diff.
- PAX migrate must leave the accepted generated baseline unchanged.
- PAX size guard must remain stable.
- No confirmed correctness or maintainability finding may be left unresolved.
- If a confirmed finding requires output-changing behavior, stop for maintainer direction. Do not
  silently defer it or hide it behind a future item.

## Verification

At minimum:

```text
./gradlew :grazel-gradle-plugin:test --console=plain --no-daemon
./gradlew migrateToBazel --console=plain --no-daemon
reports/scripts/verify-pax-size-guard.sh --mode preserving
git diff --check
git diff --check master...HEAD
```

PAX final guard:

```text
cd /Users/arun.sampathkumar/work/pax-android
./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks
git diff --check
```

Run additional focused tests for every fixed finding that has a reasonable local test seam.

## Acceptance Criteria

- Simplify pass ran across the whole branch diff and produced a reconciled finding list.
- The execution log records that the `simplify-pass` skill was invoked and summarizes its
  findings/fixes.
- Adversarial correctness review ran across the whole branch diff and produced a reconciled
  finding list.
- Confirmed findings are fixed in this slice or rejected with concrete code evidence.
- Post-fix re-review found no unresolved confirmed findings.
- Generated Grazel output is empty-diff.
- PAX migrate leaves the accepted baseline unchanged.
- Size guard, Gradle verification, and diff checks pass.
- Execution log records reviewers used, findings, fixes, rejected findings, commands, and results.

## Risks / Traps

- **Shallow review:** Do not finish after collecting comments. The item requires applying real
  fixes and re-reviewing.
- **Local-only confidence:** Passing local unit tests is not enough. PAX baseline stability is a
  hard gate.
- **Formatting bleed:** Do not start Item 25 during this item. The generate/format task merge is
  intentionally last.
