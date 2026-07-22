# Clean-room problem brief

You are designing an algorithm from scratch. **Do not read any existing implementation of a
solution** — not in this repository, not elsewhere. Design purely from the problem below and
your own knowledge of Gradle and Bazel. If you catch yourself wanting to inspect how some tool
"already does this", stop — that would defeat the experiment.

## Domain in one paragraph

A tool converts an existing **Gradle** multi-module Android/Kotlin project into **Bazel** build
files. The output is one `BUILD.bazel` per module (containing Bazel targets such as
`android_library`, `kt_jvm_library`, `android_binary`, `kt_jvm_test`, `android_unit_test`, plus
their dependency edges) and a set of **pinned external-artifact lists** (the exact resolved Maven
coordinates + versions the build will fetch, written into lockfile-style `*_install.json` files
and a `WORKSPACE`). The generated build must compile and link identically to the Gradle build.

## The inputs you are given

- A set of Gradle modules ("projects"). Each declares dependencies on other modules
  (project deps) and on external Maven artifacts, under various dependency **scopes**
  (`implementation`, `api`, `testImplementation`, `androidTestImplementation`, compile-only,
  annotation processors, etc.).
- Android modules have **variants** = (build-type × product-flavor) combinations. A dependency
  edge can be variant-specific. The same module can therefore resolve to different dependency
  sets per variant.
- A handful of modules are **binaries** (the app, and instrumentation-test apps); the rest are
  libraries. Libraries may be consumed only in `test`/`androidTest` scope by other modules.
- Gradle can, on request, fully resolve the dependency graph for a given module+configuration —
  but each such resolution is **expensive** (it forces Gradle configuration/resolution work).

## What you must produce (correctness, non-negotiable)

1. For every module that must exist in the Bazel build, a `BUILD.bazel` with the correct targets
   and the correct dependency edges (to other modules' targets and to external artifacts).
2. A correct, complete, **deduplicated** set of pinned external Maven artifacts, grouped into the
   repository buckets the build expects, byte-for-byte stable across runs.
3. No dangling references: every module referenced by some generated target must itself be
   generated.

## The hard problem (why the naive approach is unacceptable)

The naive approach resolves **every module × every variant independently** through Gradle. That is
O(modules × variants) expensive Gradle resolutions and does not finish in acceptable time on a
large real project (thousands of modules). **Your job is to design an algorithm that produces the
same correct output with dramatically fewer/cheaper resolutions.**

Think about: how few full Gradle resolutions can you get away with? What do you resolve, and at
what granularity? How do you reconstruct each individual module's correct per-variant dependency
set and its bucket/pin attribution **without** resolving each module individually? How do you
guarantee correctness (no missing edge, no dangling module, no missing pinned artifact) under
whatever shortcut you choose? What is the time complexity of your design, and what data structures
does it fundamentally require?

## Deliverable (write to the path you are given)

A self-contained design document:
- **Algorithm**: the passes/phases, in order, with what each consumes and produces.
- **Data structures**: what you fundamentally need to hold, and why.
- **Complexity**: number of expensive Gradle resolutions, and overall time/space, as a function
  of modules/variants/edges.
- **Correctness argument**: how you guarantee no missing edge, no dangling module, complete+stable
  pins — especially for a library reachable only through a `testImplementation` edge.
- **What you would NOT build**: mechanisms you considered and rejected as unnecessary, with why.

Be concrete and opinionated. Favour the simplest design that meets the correctness bar. State the
minimum set of mechanisms the problem *forces* you to have.
