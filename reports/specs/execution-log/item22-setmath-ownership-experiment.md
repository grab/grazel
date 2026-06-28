# Item 22 Execution Log - Set-Math Ownership Reduction Experiment

## Status

Outcome B: stop after Phase 1 measurement. Do not proceed to Phase 2 reshape.

The current bucket set-math is proven problem-essential for the measured PAX/sample shape. A
declaration-driven replacement is not justified under the empty-diff contract because active
placement paths still require inferred common-descendant ownership, leaf residual ownership,
and coverage/subtraction predicates.

## Commands And Inputs

- Sample measurement:
  `./gradlew resolveWorkspaceDependencies --console=plain --no-daemon --rerun-tasks -Pgrazel.internal.bucketPlacementReport=build/grazel/bucket-placement-measurement.json`
- PAX measurement:
  `cd /Users/arun.sampathkumar/work/pax-android && ./gradlew resolveWorkspaceDependencies --no-daemon --console=plain --stacktrace --rerun-tasks -Pgrazel.internal.bucketPlacementReport=build/grazel/bucket-placement-measurement.json`
- Measurement reports read:
  `build/grazel/bucket-placement-measurement.json` and
  `/Users/arun.sampathkumar/work/pax-android/build/grazel/bucket-placement-measurement.json`.
- Temporary instrumentation was removed before completion; no production code is retained for
  this measurement.

## PAX Measurement

Total placements: `48628`. Unknown placements: `0`.

Placement distribution:

| Role | Classification | Count | Notes |
|---|---:|---:|---|
| DEFAULT | DECLARED_EXPLICIT | 34882 | Declaration/direct explainable |
| DEFAULT | EXPLICIT_AND_INFERRED | 158 | Direct plus inferred closure |
| DEFAULT | EXPLICIT_RESOLVED | 804 | Resolved direct/default metadata |
| HIERARCHY | DECLARED_EXPLICIT | 637 | Declaration explainable |
| HIERARCHY | EXPLICIT_AND_INFERRED | 3 | Direct plus inferred closure |
| HIERARCHY | INFERRED_COMMON_DESCENDANT_LEAVES | 429 | Requires common-descendant set math |
| LEAF | LEAF_RESIDUAL | 6049 | Requires residual leaf set subtraction |
| LEAF | SELECTED_LEAF_HIERARCHY | 5666 | Selected leaf hierarchy placement |

Coverage predicates fired:

| Phase | Decision | Count |
|---|---:|---:|
| DEFAULT_NON_DEFAULT_HIERARCHY_SUPPRESSION | FALLBACK_COVERED | 432 |
| DEFAULT_NON_DEFAULT_HIERARCHY_SUPPRESSION | NOT_COVERED | 35844 |
| HIERARCHY_SELECTION | EXACT_ARTIFACT | 1053 |
| HIERARCHY_SELECTION | NOT_COVERED | 6735 |
| HIERARCHY_SELECTION | SUPERSET_CLOSURE | 2 |
| LEAF_RESIDUAL | EXACT_ARTIFACT | 2359 |
| LEAF_RESIDUAL | NOT_COVERED | 6049 |
| LEAF_RESIDUAL | SUPERSET_CLOSURE | 3 |

Concrete PAX examples:

- `:app` `gps` inferred common descendant deps:
  `androidx.activity:activity`, `androidx.activity:activity-ktx`,
  `androidx.browser:browser`, `androidx.camera:camera-view`,
  `androidx.cardview:cardview`.
- `:app` `gpsPaxDebug` leaf residual deps:
  `androidx.compose.ui:ui-geometry`, `com.bugsee:bugsee-android`,
  `com.grab.geo.indoor.nav:indoormapnav:0.0.76.pax-debug`.
- `:app` `gpsPaxRelease` leaf residual dep:
  `com.grab.geo.indoor.nav:indoormapnav:0.0.76.pax`.

## Sample Measurement

Total placements: `118`. Unknown placements: `0`.

Key counts:

- `INFERRED_COMMON_DESCENDANT_LEAVES`: `54`.
- `LEAF_RESIDUAL`: `4`.
- `DEFAULT_NON_DEFAULT_HIERARCHY_SUPPRESSION / FALLBACK_COVERED`: `55`.
- `HIERARCHY_SELECTION / SUPERSET_CLOSURE`: `28`.
- `LEAF_RESIDUAL / SUPERSET_CLOSURE`: `16`.

Examples include `:sample-android` `debugUnitTest` ownership for
`androidx.activity:activity` and `androidx.appcompat:appcompat`, plus
`:sample-android-tests` `androidTest` databinding dependencies.

## Decision

Phase 2 is forbidden under Item 22 because the rubric requires exact shadow parity and a
named simpler model. The measured residual paths are too active to justify a declaration-only
reshape, and no exact shadow planner was produced. This is a successful Outcome B: the current
set-math is no longer merely "left alone"; it is documented as problem-essential with PAX and
sample evidence.
