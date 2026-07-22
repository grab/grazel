# Item 2: Reachability-channel evidence (walk vs. seed for MAIN roots)

Instrumentation: temporary `GRAZEL-ITEM2` logging added to
`AggregatedDependencyResolver.kt` (Task 1), applied for a samples-project migrate
(Task 1) and a full PAX migrate (Task 2), then reverted (Step 5 below). It logs,
per root, `walkOnlyPaths` (project paths discovered by the DFS walk fold that
were not in the seed) and `walkOnlyBuckets` (variant buckets discovered by the
walk fold not in the seed), plus `seedOnlyPaths` and a `BLANK` warning if a
bucket resolves empty.

## Samples evidence

(From Task 1, `grazel/.superpowers/sdd/task-1-report.md`, migrate run against
`sample-android` and `sample-android-tests`.)

| Project | Roots | MAIN walkOnlyPaths/Buckets | TEST walkOnlyPaths/Buckets | BLANK |
|---|---|---|---|---|
| `:sample-android` | 8 MAIN + 8 TEST (non-LINT) | all `0/0` | all `0/0` | 0 |
| `:sample-android-tests` | 8 MAIN + 8 TEST (non-LINT) | all `0/0` | all `0/0` | 0 |

Task 1 conclusion: for both sample projects, every MAIN and TEST root had
`walkOnlyPaths=0, walkOnlyBuckets=0` — the DFS-walk fold contributed zero novel
reachability facts beyond what scope-seeding already produced. Zero
`GRAZEL-ITEM2 BLANK` lines emitted.

## PAX evidence

Command: `./gradlew migrateToBazel --no-daemon --console=plain --rerun-tasks`
in `/Users/arun.sampathkumar/work/pax-android` (composite-builds this repo's
`grazel-gradle-plugin` via `grazelLocalEnv=true` in `local.properties`, so the
Task-1 instrumentation patch was live). Full log: `item2-pax.log` (607 KB).

**Result:** `BUILD SUCCESSFUL in 11m 12s`, exit code `0`. PAX working tree
(`git -C pax-android status --porcelain`) stayed empty — no writes made there.

**Counts:**

- Total `GRAZEL-ITEM2 MAIN` roots: **116**
- Total `GRAZEL-ITEM2 TEST` roots: **128**
- `GRAZEL-ITEM2 BLANK` occurrences: **0**

**MAIN roots where the walk fold adds paths (`walkOnlyPaths != 0`): 1 of 116**

```
GRAZEL-ITEM2 MAIN root=:apex-cfm:cfm-ui-tests bucket=default walkOnlyPaths=1:[:grab-test-recorder] walkOnlyBuckets=75:[:beta-enrollment=[debug], :payment:payments-partner-kit=[debug], :app-test=[debug], :auth=[debug], :enterprise:enterprise-kit-bridge=[debug], :geo:geo-driver-route-info=[debug], :geo:grab-location-bridge=[debug], :geo:route-api=[debug], :grab-api=[debug], :grab-coins:grab-coins-kit=[debug], :grab-feature=[debug], :grab-leanplum=[debug], :grab-rides-api=[debug], :grab-scribe-sdk=[debug], :merchant-experience:payments-oscar-models=[debug], :newface:newface-abtest=[debug], :newface:newface-router=[debug], :payment-bridge=[debug], :payment:data-models=[debug], :payment:fundsflow-elevate-prism-kit=[debug], :payment:payments-common-widgets=[debug], :payment:payments-core-kit=[debug], :payment:payments-gatekeeper-kit=[debug], :payment:payments-utils=[debug], :payments-checkout-sdk=[debug], :payments-sdk=[debug], :rewards-bridge=[debug], :tis:logout-verdict=[debug], :biometrics-kit=[debug], :cx-common=[debug], :grab-test-recorder=[debug, default], :grab-webview=[debug], :payment-campaigns-bridge=[debug], :payment:fundsflow-framework-kit=[debug], :payment:pay-sdk:pay-sdk-ui=[debug], :payment:payments-partner-error-handler-kit=[debug], :payment:payx-elevate-bridge=[debug], :payment:risk:3ds-bottomsheet-kit=[debug], :payment:splitpay-kit=[debug], :pin-kit=[debug], :tis-core=[debug], :compose-utils=[debug], :sdk:sdk-common=[debug], :transport:transport-utils=[debug], :payment:pay-sdk:pay-sdk-kit=[debug], :appstart=[debug], :grab-image-loader=[debug], :geo:base-data=[debug], :grab-api-bridge=[debug], :marketplace:offers-bridge=[debug], :payment:fundsflow-cashout-kit=[debug], :payment:fundsflow-common-data-models=[debug], :payment:pay-sdk:pay-sdk-common=[debug], :payment:transaction-history-kit=[debug], :shared-scheduler-provider=[debug], :socket:socket-bridge=[debug], :socket:socket-implementation=[debug], :app-test-bridge=[debug], :digitalfortress=[debug], :grab-secure-verify-apis=[debug], :grab-urls-di=[debug], :platform:grablets-platform-ext-api=[debug], :grab-analytics-bridge=[debug], :cfm-experimentation=[debug], :payment:payments-common-listeners=[debug], :geo:geo-analytics-bridge=[debug], :grab-rum-sdk=[debug], :grab-watcher=[debug], :guardian-kit-bridge=[debug], :payment:payments-internal-deeplink-kit=[debug], :payment:payments-utils-kit=[debug], :rtc-experimentation=[debug], :sdk:sdk-wrapper-base=[debug], :splash-bridge=[debug], :tis:identity-auth-kit=[debug]] seedOnlyPaths=2026
```

**MAIN roots where the walk fold adds buckets (`walkOnlyBuckets != 0`): 3 of 116**
(includes the line above, plus:)

```
GRAZEL-ITEM2 MAIN root=:comms-ui-tests:hedwig-ui-tests bucket=default walkOnlyPaths=0 walkOnlyBuckets=1:[:comms-ui-tests:common-ui-tests=[debug]] seedOnlyPaths=2022
GRAZEL-ITEM2 MAIN root=:cx-ui-tests:subscription-ui-tests bucket=default walkOnlyPaths=0 walkOnlyBuckets=1:[:subscriptions:subscription-test-common=[debug]] seedOnlyPaths=1898
```

All three non-zero MAIN roots are `*-ui-tests` support modules (`:apex-cfm:cfm-ui-tests`,
`:comms-ui-tests:hedwig-ui-tests`, `:cx-ui-tests:subscription-ui-tests`) whose
`default` bucket walk-fold surfaces sibling test-support project modules
(`:grab-test-recorder`, `:comms-ui-tests:common-ui-tests`,
`:subscriptions:subscription-test-common`) that are reachable transitively but
were not present in the seed set for that bucket.

**TEST-root novelty (converse check, recorded only): 1 of 128**

```
GRAZEL-ITEM2 TEST root=:app bucket=androidTest walkOnlyPaths=2:[:food-rating-ui-tests, :cx-ui-tests:common-ui-tests] walkOnlyBuckets=2:[:food-rating-ui-tests=[debug, default], :cx-ui-tests:common-ui-tests=[debug, default]] seedOnlyPaths=0
```

**BLANK-bucket occurrences: 0** — no `GRAZEL-ITEM2 BLANK` lines emitted anywhere
in the 607 KB log.

**Gate outputs:** No exceptions during resolution/generation; migrate task
graph completed and the whole build (51+ tasks across all PAX modules, plus the
composite `grazel-gradle-plugin` build) reported `BUILD SUCCESSFUL`.

## Static argument

**1. Does PAX use module→project `dependencySubstitution`?**

`grep -rn "dependencySubstitution\|substitute(\|useTarget" /Users/arun.sampathkumar/work/pax-android --include="*.gradle" --include="*.gradle.kts" --include="*.kt" -l | grep -v build/`
→ only `settings.gradle` matches. Three occurrences, all inside `includeBuild {}`
blocks, all gated behind local-dev flags read from `local.properties`:

```groovy
// settings.gradle:84-94 — gated on non-empty pluginsLocalDev (NOT set in this run)
if (!pluginsLocalDev.isEmpty()) {
    includeBuild('./pax-plugins') {
        pluginsLocalDev.findAll { !it.isEmpty() }.each { pluginName ->
            dependencySubstitution {
                substitute module("$grabPluginGroup:$pluginName") using project(":$pluginName")
            }
        }
    }
}

// settings.gradle:97-107 — gated on duxtonLocalEnv (NOT set in this run)
if (duxtonLocalEnv) {
    includeBuild(cleanedDuxtonDir) {
        dependencySubstitution {
            substitute module("com.grab:duxton") using project(":duxton-library")
        }
    }
}

// settings.gradle:109-116 — gated on grazelLocalEnv (SET to true for this run)
if (grazelLocalEnv) {
    includeBuild("../grazel/grazel-gradle-plugin") {
        dependencySubstitution {
            substitute module("com.grab.grazel:grazel-gradle-plugin") using project(":")
        }
    }
}
```

`local.properties` for this run contains only `grazelLocalEnv=true`;
`pluginsLocalDev` and `duxtonLocalDev` are absent/false (confirmed via
`grep -in "pluginsLocalDev\|duxtonLocalDev\|grazelLocalEnv" local.properties`
→ only the `grazelLocalEnv` line present). So the only active substitution in
this run replaces the **plugin-classpath coordinate**
`com.grab.grazel:grazel-gradle-plugin` with the local plugin project — this
affects `pluginManagement`/buildscript resolution, not any Android app/library
module's `implementation`/`api`/`testImplementation` dependency graph. It is
**classified as plugin-classpath substitution**, not app-module dependency
substitution. `com.grab:duxton` (`grep -rn "com.grab:duxton"` shows only a
Bazel `artifact_pinning` override table entry at `build.gradle:432` and the
inactive `settings.gradle:104` substitution) never resolves to a project edge
in this run, since `duxtonLocalEnv` is false — its module→project substitution
is present in the codebase but dormant.

**2. Any `includeBuild` besides the grazel plugin composite?**

`grep -rn "includeBuild" /Users/arun.sampathkumar/work/pax-android/settings.gradle*`
→ four hits: `build-logic` (line 22, unconditional, no `dependencySubstitution`
— ordinary composite build for convention plugins), `./pax-plugins` (line 87,
inactive this run), `cleanedDuxtonDir` (line 102, inactive this run), and
`../grazel/grazel-gradle-plugin` (line 111, active this run). Only the last
is live, and (per #1) only affects the plugin classpath.

**3. General argument — enumerate the mechanisms**

`ResolvedComponentsVisitor` (grazel-gradle-plugin/src/main/kotlin/com/grab/grazel/gradle/dependencies/ResolvedComponentsVisitor.kt)
detects a project edge purely by **string inspection** of the resolved
component: `private val ResolvedComponentNode.isProject get() = toString().startsWith("project :")`
(line 52), used both to decide whether to emit a node (`!node.isProject`,
line 150) and, in `traverseProjectNodes` mode, to decide whether to descend
through it as an intermediate node (`child.isProject`, line 224). This means
**any** `ResolvedComponentResult` whose display form is a project coordinate
is treated as a project edge — the visitor has no way to distinguish "this was
a directly-declared `project(...)` dependency" from "this used to be a module
coordinate that Gradle's resolution engine substituted for a project."

Enumerating what can make a *resolved* graph contain a `project(...)` edge
with **no declared counterpart** in the source (i.e., no `project(...)` line
anywhere a human wrote it):

- **Module→project `dependencySubstitution`** (`substitute module(...) using project(...)`,
  as in PAX's own `settings.gradle:89-90`, `103-104`, `112-113`): a
  `build.gradle` can declare `implementation "com.grab:duxton:1.0"` (a normal
  module coordinate) and the *resolved* graph shows `project :duxton-library`
  instead — with zero `project(...)` syntax anywhere in that module's own
  build file. This is exactly the case Task 1/2's seed vs. walk split cannot
  see from declared-dependency inspection alone, because the seed is built
  from declared coordinates.
- **Composite-build automatic substitution** (`includeBuild(...)` with no
  explicit `dependencySubstitution` block): Gradle auto-substitutes any module
  coordinate matching a project's `group:name` inside the included build,
  without any `substitute(...)` line at all. `build-logic` (settings.gradle:22)
  is this shape, though it is a `pluginManagement`-only composite (buildscript
  classpath, not app dependencies) so it doesn't reach
  `AggregatedDependencyResolver`'s resolved configurations.
- **Nothing else found reading `ResolvedComponentsVisitor`**: the visitor only
  walks `ResolvedDependencyResult`s off `node.dependencies`; there is no other
  path (e.g. no special-casing of `capabilities`, `platform`, or `constraint`
  edges as anything other than filtered-out/normal nodes) that could inject an
  edge whose `toString()` starts with `"project :"` other than the component
  actually having been resolved to a project — which only happens via (a) a
  literal `project(...)` declaration or (b) substitution/composite-build
  auto-substitution.

Mechanisms that only **remove or re-version** edges (do not add undeclared
project edges): `exclude` (removes a transitive edge outright), version
`constraints`/`platform` alignment (changes which *module* version is
selected, never turns a module into a project unless combined with
substitution), and capability conflict resolution (picks among alternative
*module* candidates). None of these can turn a module coordinate into a
project result on their own.

**Conclusion:**

- **(a) For PAX, in this run:** "walk ⊆ DFS for MAIN roots" (i.e., every
  project edge the walk discovers is backed by an actual, findable transitive
  `project(...)` declaration somewhere in the graph, not manufactured by
  substitution) **holds**, empirically and by configuration: the only active
  `dependencySubstitution` this run is the plugin-classpath one
  (`grazelLocalEnv`), which never reaches `AggregatedDependencyResolver`'s
  app-module resolved configurations. The three non-zero MAIN roots found
  above are genuine transitive `project(...)` edges (test-support modules
  pulled in transitively by other project dependencies), not substitution
  artifacts.
- **(b) Universally:** does **not** hold. PAX's own `settings.gradle` contains
  two *dormant* module→project substitutions (`pluginsLocalDev`,
  `duxtonLocalEnv`) that — if a developer flipped them on locally — would
  make `com.grab:duxton` resolve to `project :duxton-library` inside an app
  module's dependency graph with **no `project(...)` syntax anywhere in that
  module's build file**. `ResolvedComponentsVisitor.isProject` cannot
  distinguish that from a hand-written `project(...)` dependency, and nothing
  in `AggregatedDependencyResolver`'s seed-vs-walk split accounts for
  substitution. So the general claim is false as stated.
- **(c) Holds only if module→project `dependencySubstitution` /
  composite-build substitution shapes are declared out of grazel's scope:**
  **yes** — this is the applicable case. The static argument ("everything the
  walk finds for a MAIN root is reachable via ordinary declared
  `project(...)` edges") is correct *conditioned on* grazel explicitly not
  supporting/handling projects that use module→project substitution for
  app-level dependencies (as opposed to plugin-classpath substitution, which
  is out of `AggregatedDependencyResolver`'s resolved-configuration scope by
  construction). This is the user-escalation case per the brief's
  §Decision-authority.

## Verdict inputs

- `mainWalkOnlyEmpty`: **false** — Task 1's two sample projects showed
  `walkOnlyPaths=0, walkOnlyBuckets=0` on every MAIN root, but PAX (a
  real-world, ~490-module composite build) shows 3 of 116 MAIN roots with
  non-zero `walkOnlyBuckets` (1 of those also non-zero `walkOnlyPaths`) — the
  fold is not a universal no-op; it does surface novel transitively-reachable
  project buckets for some `*-ui-tests` support modules.
- `holdsOnlyIfSubstitutionOutOfScope`: **true** (in place of
  `staticArgumentHolds`) — per the Static-argument section, "walk ⊆ DFS for
  MAIN roots" is true for PAX in this run and true in general *only* if
  module→project `dependencySubstitution`/composite-build auto-substitution
  are treated as outside grazel's declared-dependency model; PAX itself ships
  two dormant substitutions of exactly that shape, so the claim does not hold
  universally.
- `blanksNeverOccur`: **true** — zero `GRAZEL-ITEM2 BLANK` lines across both
  the samples run (Task 1) and the full PAX migrate (116 MAIN + 128 TEST
  roots, 607 KB log), and the PAX build completed with `BUILD SUCCESSFUL`,
  exit code 0, no resolution exceptions.
