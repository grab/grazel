# Item 6 — Simplify, Adversarial Review & Full Verification (Design)

> **Status:** Approved 2026-06-26. Sixth and final spec in the dependency-refactor spec set.
> **Executor:** Codex. **Behaviour change:** none (golden-checked against the Item 5
> re-baseline).
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Depends on:** Item 5 (correctness fixes landed; goldens re-baselined).

> **⚠️ Execution note — delegate to subagents; protect the main context.** The simplify
> and adversarial-review passes, and the broad verification runs, go to focused subagents
> returning distilled results. Keep the orchestrating context for triage and sign-off.

---

## Goal

Bring the branch to a clean, verified, **review-ready** state for the maintainer's final
review. Behaviour-preserving.

**Explicitly NOT in scope: merging, history rewrite, force-push.** The plan ends at
review-ready; the maintainer does the final review and merge.

## Sequence

1. **`resolve()` extraction (Tier-1 refactor).** Break the ~740-line
   `AggregatedDependencyResolver.resolve()` into named collaborators — root dispatch,
   declared-metadata enrichment, edge-scoping — so the orchestration, policy, and plumbing
   are separable. Pure mechanical lift; **golden empty-diff** against the Item 5 baseline.
2. **`/simplify` pass** over the branch diff (reuse, simplification, efficiency, altitude —
   quality only, not bug-hunting). Each applied fix golden-checked.
3. **Adversarial review pass** — Codex self-review / `/code-review` at high effort over the
   changed code, correctness focus. Findings triaged: fixed (golden-checked) or explicitly
   waived with rationale.
4. **Reports cleanup residue.** Confirm Item 1's deletion of the historical thrash landed.
   Surviving durable docs: `reports/specs/*` and `reports/specs/DO-NOT-REVISIT.md`.
   `reports/scripts/` retained.
5. **Deferred-follow-ups disposition.** Document the known limitations in
   `reports/specs/KNOWN-LIMITATIONS.md`:
   - binary-root requirement (app / `com.android.test` only);
   - library/JVM-only and standalone-library-test classpath roots not first-class;
   - pin-JSON size (test/android-test closures kept as Coursier constraints);
   - cacheability stance on live `ResolvedComponentResult` task inputs.
6. **Full verification (the done bar):**
   - `./gradlew check` green;
   - `bazelisk build //...` green;
   - PAX: `migrateToBazel` + `//app:app-gps-pax-debug.apk` +
     `//app:app-gps-pax-debug-android-test.apk` green;
   - `bazelisk test //...` green **except** the documented pre-existing lint targets
     (`SerializedNameDefaultValue` etc.);
   - `git diff --check master...HEAD` clean.

## Deliverable

- A clean, verified, **review-ready** branch.
- A short **review guide** (`reports/specs/REVIEW-GUIDE.md`): a one-page summary of what
  changed across the six items, the Item 5 documented diffs, and the active waivers — to
  orient the maintainer's final pass.

**The plan stops here.** Hand off to the maintainer for final review and merge.

## Acceptance criteria

- `resolve()` decomposed; `/simplify` and adversarial review applied; findings resolved or
  waived with rationale.
- Done bar fully met (see step 6); golden empty-diff against the Item 5 baseline for all
  behaviour-preserving steps.
- `KNOWN-LIMITATIONS.md` and `REVIEW-GUIDE.md` written; reports residue clean.

## Out of scope

- Merging, branch history rewrite, force-push (maintainer-owned).
- Variant compression refactor (see Item 2 non-goal).
- Library/JVM/test root support (documented limitation, not implemented).
