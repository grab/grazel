# Item 10 — Frozen PAX Baseline + Automated Size Guard (Design)

> **Status:** Approved 2026-06-27. First executable item of the altitude-layering pass.
> **Executor:** Codex. **Behaviour change:** none (adds tooling + records a baseline; no
> grazel production code or generated-output change).
> **Global Constraints & Verification Playbook:** inherited from
> `reports/specs/2026-06-26-item1-baseline-and-safety-net-design.md`.
> **Index:** `reports/specs/ALTITUDE-LAYERING-ROADMAP.md`.

> **⚠️ Execution note — delegate to subagents; protect the main context.** PAX measurement
> goes to a focused subagent returning only the counts.

---

## Goal

Freeze the current verified PAX generated output as the accepted size baseline, and add an
automated guard so every later altitude-layering item (9, 11–15) is regression-checked: the
size totals must **never increase** from this baseline; reductions are a win. This is the
net that lets Item 13's output-changing rewrite proceed safely.

The PAX generated baseline is recorded from the PAX repo
`/Users/arun.sampathkumar/work/pax-android`, branch `arun/grazel-refactor`, commit
`05d2b4801530726ab722133c2ba32cbba9afeb67`. Future verification can run PAX migration and
use `git diff` in that repo as the tight impact loop. Grazel changes must never commit PAX
files; Grazel commits only the baseline metadata.

## Why first

Items 9 and 11–15 each need a regression check. In particular Item 13 (test/androidTest
delta ownership) is the one *intended* output change — it should reduce scoped buckets, and
this guard is how we prove it reduced rather than regressed.

## Metrics (TOTALS only — not per-bucket)

Guard three totals against the frozen baseline:
1. **Bucket count** — number of `maven_install(name = …)` repos in the PAX `WORKSPACE`.
2. **Pinfile count** — number of generated `*_install.json` pin files for PAX.
3. **Total artifact roots** — sum of `artifacts` entries across all materialized
   `maven_install` repos.

**Per-bucket counts are NOT globally gated** — but they are still recorded and compared.
Rationale: an output-changing item may deliberately redistribute roots. A universal
per-bucket ceiling would block legitimate consolidation. Instead:

- preserving items (9, 11, 12, 14, 15) must exact-match per-repo artifact identity;
- Item 13 may change only the scoped test/androidTest repo classes it explicitly declares;
  non-scoped repos, including lint repos, must exact-match;
- changed scoped repos must not increase. The guard framework may record an explicit
  maintainer-approved correctness waiver for non-Item-13 correctness fixes, but Item 13 has
  no internal increase waiver.

## Mechanism

1. **Record the baseline.** Refresh `reports/specs/PAX-BOUNDED-AUDIT-BASELINE.md` (currently
   stale) from the CURRENT PAX working-tree counts on `arun/grazel-refactor`: bucket count,
   pinfile count, total artifact roots, and per-repo artifact identity. Also write the same
   data in mandatory machine-readable file `reports/specs/pax-size-baseline.json` with this
   fixed schema:

   ```json
   {
     "paxRepoPath": "/Users/arun.sampathkumar/work/pax-android",
     "paxBranch": "arun/grazel-refactor",
     "paxCommit": "05d2b4801530726ab722133c2ba32cbba9afeb67",
     "bucketCount": 0,
     "pinfileCount": 0,
     "totalArtifactRoots": 0,
     "perRepo": {
       "maven": {
         "artifactRoots": 0,
         "artifactIds": []
       }
     }
   }
   ```

   `artifactIds` are the normalized strings from the active repo's `artifacts` list, sorted
   deterministically. Measured from the current verified PAX migrated output at
   `/Users/arun.sampathkumar/work/pax-android`. This committed snapshot is the frozen
   baseline. PAX generated files themselves are not committed to the Grazel branch.
2. **Fresh migrate requirement.** PAX verification must use `./gradlew migrateToBazel
   --no-daemon --console=plain --stacktrace --rerun-tasks`, or the guard must parse the
   Gradle output and fail unless the dependency planning/render tasks actually executed
   (`resolveWorkspaceDependencies`, `computeWorkspaceDependencies`, `computeWorkspacePlan`,
   `finalizeWorkspacePlan`). Do not accept an up-to-date false positive.
3. **Guard script.** Add `reports/scripts/verify-pax-size-guard.sh` that, after PAX
   `migrateToBazel`:
   - reads the machine-readable baseline and fails if required baseline fields are missing;
   - recomputes the three totals from the PAX generated `WORKSPACE` + `*_install.json`;
   - parses active repos from `WORKSPACE` and counts only the corresponding pin JSON files;
   - **hard-fails** if any total exceeds the baseline;
   - exact-matches per-repo artifact identity for preserving-item mode;
   - supports an Item-13 mode where only explicitly scoped test/androidTest repo classes may
     differ, with non-scoped repos exact-matching;
   - prints reductions explicitly as wins;
   - prints per-bucket deltas as informational (non-gating).
4. **Wiring.** Script lives in the grazel repo `reports/scripts/`, points at
   `~/work/pax-android`, and is run by Codex in the verification loop (consistent with the
   existing manual PAX gates). Not CI-wired (PAX is a separate repo; PAX builds are the
   primary correctness gate regardless).

## Validation

- The guard passes against the frozen baseline at creation (self-consistent: current == baseline).
- `git diff` in PAX is clean before the first measured run, then any generated diff after a
  later Grazel change is reviewed as the impact of that change.
- Grazel sample golden EMPTY-diff (Item 10 changes no grazel production code).
- PAX correctness gates (migrate + both APKs) remain primary and unchanged.

## Acceptance criteria

- `PAX-BOUNDED-AUDIT-BASELINE.md` and `reports/specs/pax-size-baseline.json` refreshed with current
  bucket/pinfile/total-root counts + per-repo artifact identity, committed in Grazel.
- `verify-pax-size-guard.sh` exists, recomputes the three totals, hard-fails on any increase,
  reports reductions and per-bucket deltas, and has preserving/item13 modes.
- Guard green on current PAX; sample golden empty-diff.

## Out of scope

- Per-bucket gating (reported only).
- Any grazel production code change.
- The reductions themselves (Item 13 produces them; Item 10 only measures/guards).

## Non-goal

Master as a target (it over-bucketed); `--force-version`; dropping closure.
