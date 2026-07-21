# Verification Gates

Canonical gate sequence for the `arun/dependencies-refactor` branch. Run the
**local** gates for every change; run the **PAX** gates before merge or after
any change that could alter generated output.

Both build systems must stay green (Gradle + Bazel), and generated output must
stay byte-identical to the accepted baseline unless a change is explicitly
intended to move it.

## Build serialization

Only **one** Gradle build may run at a time. Never start a build while another
Gradle build (local or PAX) or a build-running worker is active. PAX
composite-builds the local plugin via `includeBuild("../grazel/grazel-gradle-plugin")`
(gated by `grazelLocalEnv=true` in PAX `local.properties`), so a PAX migrate
compiles the working-tree plugin directly — no `publishToMavenLocal` needed.

## Local gates

Run from the grazel repo root.

1. **Unit tests** — compiles main + test and runs the plugin unit suite:
   ```bash
   ./gradlew :grazel-gradle-plugin:test --console=plain
   ```
2. **Golden baseline (byte-identity)** — regenerates the sample projects and
   fails on any generated-file drift. This is the strongest local proxy for
   "PAX output unchanged":
   ```bash
   ./gradlew verifyGrazelGoldenBaseline --console=plain
   ```
   Success prints: `Grazel golden baseline verified: migrateToBazel, task
   graph, bucket labels, and generated-file diff are clean.`

   Internally this runs `migrateToBazel` + `verify-default-task-graph.sh` +
   `verify-sample-bucket-labels.sh` + a `git diff --exit-code` byte-identity
   check. Byte-identity is separate from the content assertions in those
   scripts.
3. **Bazel graph analysis (samples)** — the golden diff proves the *text* of the
   generated files matches; it does **not** prove the generated Bazel graph is
   *analyzable*. A dangling `project(...)`/label reference (a target referenced
   but never generated) passes the golden diff yet fails Bazel analysis. Analyze
   the samples:
   ```bash
   bazelisk build --nobuild //...
   ```
   Expect `Build completed successfully` with no `no such package` / `no such
   target`. Run this whenever a change can affect *which* targets get generated
   (reachability, migration criteria, target builders).

### Coverage limits (why a green local run can still miss a bug)

The golden baseline only exercises the shapes present in the sample modules
(`sample-android`, `sample-android-library`, `sample-kotlin-library`, `flavors/`,
`sample-android-test-util*`). A generation bug in a shape **no sample has** is
invisible locally. When you fix or discover a new generation shape (e.g. a
test-only-consumed library, a new plugin combo, a new variant topology), **add a
sample fixture that reproduces it** and commit its generated output into the
golden baseline — that is what turns "caught once in PAX" into "caught forever
locally". A behavioural fix that changes generated output is expected to move the
golden; commit the new output deliberately and confirm the diff contains **only**
the intended change.

### Known local waiver

`reports/scripts/verify-sample-bucket-labels.sh` may fail **only** on the
pre-existing appcompat/constraintlayout one-sided-exclude assertion. That is a
documented waiver, not a regression. Any other failure is real.

## PAX gates

PAX checkout: `/Users/arun.sampathkumar/work/pax-android`, branch
`arun/grazel-refactor`. The working tree carries the branch's generated output
as uncommitted modifications (the accepted baseline diff below).

> **Non-destructive rule (hard constraint).** Never run `git stash`
> (pop/apply/drop/push), `git checkout`, `git reset`, `git commit`, `git add`,
> `git clean`, `git restore`, `git switch`, `git branch -D`, or `git push` in
> the PAX repo. `migrateToBazel --rerun-tasks` overwriting generated files in
> place is fine; every verification step below is read-only.

Run in order:

1. **Migrate** (regenerate with the local patched plugin, ~11 min):
   ```bash
   cd /Users/arun.sampathkumar/work/pax-android
   ./gradlew migrateToBazel --no-daemon --console=plain --stacktrace --rerun-tasks
   ```
2. **Clean-tree check** (read-only). PAX HEAD now carries the accepted
   generated output as **committed**, so a byte-identical migrate leaves the
   tree **clean**:
   ```bash
   git -C /Users/arun.sampathkumar/work/pax-android status --porcelain
   git -C /Users/arun.sampathkumar/work/pax-android diff --check
   ```
   Pass condition: `status --porcelain` prints **nothing** — no modified files,
   no untracked generated files. Any modified/untracked generated file is a
   **regression**. (Superseded baseline, from when output was uncommitted in the
   working tree: `1854 files changed, 68 insertions(+), 775167 deletions(-)`.)

   **Caveat for intended behavioural changes:** a fix that deliberately changes
   generated output (e.g. newly generating a previously-dropped module) will show
   exactly those intended files as new/modified — that is the *only* allowed diff.
   Verify every entry is expected and commit it as the new PAX baseline; any file
   outside the intended set is still a regression.
3. **Size guard** (run from the grazel repo root):
   ```bash
   reports/scripts/verify-pax-size-guard.sh --mode preserving
   ```
   Expect: `bucketCount=11`, `pinfileCount=11`, `totalArtifactRoots=1945`, and
   **no per-repo deltas**. Baseline lives at
   `reports/specs/pax-size-baseline.json`.
4. **APK build** (~3 min):
   ```bash
   cd /Users/arun.sampathkumar/work/pax-android
   ./bazel.sh build --verbose_failures \
     //app:app-gps-pax-debug.apk //app:app-gps-pax-debug-android-test.apk
   ```
   Expect `Build completed successfully` (warnings only).
5. **Focused test**:
   ```bash
   cd /Users/arun.sampathkumar/work/pax-android
   ./bazel.sh test --test_output=errors \
     //app-utils:app-utils-gps-pax-debug-test \
     //app-test:app-test-gps-pax-debug-test \
     //application-initializer:application-initializer-gps-pax-debug-test
   ```
   Expect `Executed 0 out of 3 tests: 3 tests pass`.
6. **Bazel graph analysis over the CI target set** (the gate that catches
   dangling labels — APK + 3 focused tests do **not** analyze the test graph, so
   this class slips past them). Use the pipeline's own target selection, not
   `//...`:
   ```bash
   # the exact set the bazel:impacted-targets job runs (kt_jvm_test, minus excluded flavors)
   QUERY_BAZEL_BIN=bazelisk BAZEL_ARGS="--config=ci" \
     scripts/bazel/diff/list_unit_test_targets > /tmp/ut_targets.txt
   # analyse (not execute) the whole set + transitive deps
   bazelisk build --nobuild --keep_going --config=ci --target_pattern_file=/tmp/ut_targets.txt
   ```
   Pass condition: `Analyzed N targets`, exit 0, **zero** `no such package` /
   `no such target`, no `Analysis of target ... failed`. This is the true
   "will the `bazel:impacted targets` job go green" signal — it reproduces the
   job's analysis phase without executing tests.

   **Do NOT use `bazelisk build //...` as the PAX pass/fail signal.** `//...`
   analyses the entire workspace, including pre-existing dangling clusters that
   the CI job never selects (it filters to `kt_jvm_test` targets). Judging PAX by
   `//...` produces false alarms on modules outside the CI universe. `//...` is a
   useful *exploration* tool (it surfaces every latent dangler); the CI target set
   is the *gate*.

Long builds (migrate, APK) should be launched in the background and chained as
each completes — do not block the session on them.

## Gotchas (lessons paid for; do not relearn)

- **Golden diff proves text, not analyzability.** A dangling label passes
  `git diff --exit-code` but fails Bazel analysis. Always run a graph-analysis
  gate (local samples §Local-3, PAX §PAX-6) for any change touching what gets
  generated.
- **`query` passing ≠ `analysis` passing.** `bazel query "kind(kt_jvm_test, ...)"`
  enumerates test targets without resolving their data/dep labels, so it happily
  lists a target whose dep is a missing package; the failure only appears at
  analysis. The graph gate must be `build --nobuild` (analysis), not a query.
- **`git status` clean ≠ no dangling.** A dangling reference lives in an
  *unchanged* consumer file pointing at a never-generated module; status shows
  only *changed* files, so it hides the dangler. Only analysis finds it.
- **Scope the check to the CI universe, not `//...`.** See §PAX-6.
- **Add a sample fixture for every new generation shape.** The golden only sees
  sample shapes; a bug in an unrepresented shape is invisible until PAX/CI. See
  §Coverage-limits.
