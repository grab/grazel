# Item 16 — Simplify, Adversarial Review & Final Verification (Design)

> **Status:** Approved 2026-06-27. Final item of the altitude-layering pass.
> **Executor:** Codex. **Behaviour change:** none. Golden-checked against the post-Item-15
> baseline.
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `reports/specs/ALTITUDE-LAYERING-ROADMAP.md`. **Depends on:** Items 10–15.

> **⚠️ Execution note — delegate to subagents; protect the main context.** The simplify
> pass, adversarial review, broad verification, and PAX audits go to focused subagents where
> possible. The main context owns sequencing, triage, and sign-off.

---

## Goal

Bring the altitude-layering branch to a clean, verified, **review-ready** state after Items
10–15 land. This item is quality and verification only; it must not introduce behavior
changes.

The PAX generated baseline is available on `/Users/arun.sampathkumar/work/pax-android`,
branch `arun/grazel-refactor`, commit `05d2b4801530726ab722133c2ba32cbba9afeb67`, and the
Grazel size guard from Item 10 owns the machine-readable baseline. PAX files are never
committed from this repo.

## Sequence

1. **Baseline sanity.** Confirm Item 10's `pax-size-baseline.json` exists, Item 13 reductions
   were monotonically re-baselined if any occurred, and PAX `git diff` is clean before final
   verification begins.
2. **Simplify pass.** Run the available simplify-pass skill over the branch diff, focused on
   reuse, simplification, efficiency, and altitude only. This is not a correctness hunt.
   Apply only behavior-preserving improvements and rerun impacted checks after each batch.
3. **Adversarial review pass.** Use focused subagents / code review to look for:
   - resolved-vs-declared version mistakes;
   - closure dropping or Coursier constraint regressions;
   - bucket bloat or size-guard blind spots;
   - false SCCs caused by project-level graph collapse;
   - SCC fallback still used on PAX without typed-node proof of a genuine cycle;
   - DAG/SCC ownership leakage;
   - target reachability under-collection;
   - cache/task boundary regressions;
   - stale parity flags or old paths;
   - generated-output feedback paths;
   - missing regression tests.
4. **Docs and residue cleanup.** Ensure:
   - `CURRENT-GOAL-ANCHOR.md` and `ALTITUDE-LAYERING-ROADMAP.md` are current;
   - superseded docs are marked as superseded, not execution contracts;
   - `KNOWN-LIMITATIONS.md` and `REVIEW-GUIDE.md` reflect final behavior;
   - `EXECUTION-LOG.md` records final commands, results, waivers, and risks.
5. **Full verification.** Run the final verification set below.

## Final Verification

Grazel:

```text
./gradlew check --console=plain --no-daemon
./gradlew migrateToBazel --console=plain --no-daemon
reports/scripts/verify-default-task-graph.sh
reports/scripts/verify-sample-bucket-labels.sh
bazelisk build //...
bazelisk test //...
git diff --check
git diff --check master...HEAD
```

PAX:

```text
cd /Users/arun.sampathkumar/work/pax-android
./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks
./bazel.sh build --verbose_failures //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk
./bazel.sh test --test_output=errors //app-utils:app-utils-gps-pax-debug-test //app-test:app-test-gps-pax-debug-test //application-initializer:application-initializer-gps-pax-debug-test
git diff --check
```

Then run the Item 10 PAX size guard from Grazel in preserving/final mode. If PAX has
generated diffs, classify them against the accepted baseline and do not commit them.

## Waiver Rules

- Behaviour-changing diffs are not accepted in Item 16.
- Failing checks require either a fix or a maintainer-quality documented waiver.
- Pre-existing failures must be proved pre-existing with a focused baseline command or prior
  durable log reference.
- Item 13 size increases cannot be waived here; they mean Item 13 was not complete.

## Acceptance Criteria

- Simplify-pass findings are applied or explicitly rejected with rationale.
- Adversarial review findings are fixed or waived with rationale.
- No stale parity flags or old comparison paths remain.
- SCC fallback is removed from the normal PAX path, or any remaining fallback is backed by
  typed-node proof of a genuine same-projection cycle and documented in durable docs.
- Final Grazel and PAX verification commands pass, or documented pre-existing waivers exist.
- `CURRENT-GOAL-ANCHOR.md`, `ALTITUDE-LAYERING-ROADMAP.md`, `KNOWN-LIMITATIONS.md`,
  `REVIEW-GUIDE.md`, and `EXECUTION-LOG.md` are current.
- Final response states local commits, architecture changes, verification results, accepted
  PAX diff/size shape, remaining risks, and merge-readiness.

## Out Of Scope

- Merging, branch history rewrite, force-push, or public push.
- Committing PAX generated files.
- New ownership algorithms beyond Items 10–15.
