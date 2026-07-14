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

Long builds (migrate, APK) should be launched in the background and chained as
each completes — do not block the session on them.
